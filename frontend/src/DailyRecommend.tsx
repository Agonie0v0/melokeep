import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertCircle,
  Check,
  Download,
  Heart,
  ListMusic,
  Play,
  Plus,
  RefreshCw,
  Sparkles,
  Square,
} from "lucide-react";
import {
  cancelDailyRecommendationJob,
  dailyRecommendationEventsUrl,
  fetchDailyRecommendation,
  navidromeSingleDownloadUrl,
  startDailyRecommendation,
} from "./api";
import { Cover, SongTitle } from "./components";
import {
  buildDailySeedsFromFavorites,
  isDailyItemEnqueueable,
  mergeDailyItem,
  parseDailyCalendar,
} from "./daily-utils";
import { clock } from "./lyrics";
import { formatFriendlyQuality } from "./results";
import type { SettingsTab } from "./SettingsWorkspace";
import type {
  DailyRecommendDoneEvent,
  DailyRecommendErrorEvent,
  DailyRecommendItem,
  DailyRecommendItemEvent,
  DailyRecommendMetaEvent,
  DailyRecommendation,
  DailyRecommendStatusEvent,
  DownloadJob,
  Track,
} from "./types";
import "./daily.css";

type CategoryFilter = "all" | "discover" | "familiar";

export function DailyRecommend({
  authenticated,
  current,
  playing,
  favIds,
  favorites,
  jobs,
  sourceName,
  onPlay,
  onPlayAll,
  onQueueAll,
  onPlayNext,
  onFavorite,
  onAddToPlaylist,
  onDownload,
  onOpenSettings,
  onNotice,
}: {
  authenticated: boolean;
  current: Track | null;
  playing: boolean;
  favIds: Set<string>;
  favorites: Track[];
  jobs: DownloadJob[];
  sourceName: (id: string) => string;
  onPlay: (track: Track, context: Track[]) => void;
  onPlayAll: (tracks: Track[]) => void;
  onQueueAll: (tracks: Track[]) => void;
  onPlayNext: (track: Track) => void;
  onFavorite: (track: Track) => void;
  onAddToPlaylist: (tracks: Track[]) => void;
  onDownload: (tracks: Track[]) => void;
  onOpenSettings: (tab?: SettingsTab) => void;
  onNotice: (message: string) => void;
}) {
  const [date, setDate] = useState("");
  const [refreshSeq, setRefreshSeq] = useState(0);
  const [targetCount, setTargetCount] = useState(30);
  const [mode, setMode] = useState("");
  const [curatorNote, setCuratorNote] = useState("");
  const [styleTags, setStyleTags] = useState<string[]>([]);
  const [items, setItems] = useState<DailyRecommendItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [streaming, setStreaming] = useState(false);
  const [stageMessage, setStageMessage] = useState("");
  const [matchedCount, setMatchedCount] = useState(0);
  const [error, setError] = useState("");
  const [categoryFilter, setCategoryFilter] = useState<CategoryFilter>("all");

  const generationRef = useRef(0);
  const eventSourceRef = useRef<EventSource | null>(null);
  const activeJobIdRef = useRef<string | null>(null);
  const favoritesRef = useRef(favorites);
  favoritesRef.current = favorites;
  const onNoticeRef = useRef(onNotice);
  onNoticeRef.current = onNotice;

  const closeStream = useCallback(() => {
    eventSourceRef.current?.close();
    eventSourceRef.current = null;
  }, []);

  const applySnapshot = useCallback((snapshot: DailyRecommendation) => {
    setDate(snapshot.date || "");
    setRefreshSeq(snapshot.refresh_seq || 0);
    setTargetCount(snapshot.target_count > 0 ? snapshot.target_count : 30);
    setMode(snapshot.mode || "");
    setCuratorNote(snapshot.curator_note || "");
    setStyleTags(Array.isArray(snapshot.style_tags) ? snapshot.style_tags : []);
    const nextItems = Array.isArray(snapshot.items) ? snapshot.items : [];
    setItems(nextItems);
    setMatchedCount(nextItems.length);
  }, []);

  const subscribeJob = useCallback(
    (jobId: string, gen: number) => {
      closeStream();
      activeJobIdRef.current = jobId;
      setStreaming(true);
      setLoading(false);
      setError("");

      let terminalHandled = false;
      const events = new EventSource(dailyRecommendationEventsUrl(jobId), {
        withCredentials: true,
      });
      eventSourceRef.current = events;

      events.addEventListener("status", (event) => {
        if (generationRef.current !== gen) return;
        try {
          const payload = JSON.parse(
            (event as MessageEvent).data,
          ) as DailyRecommendStatusEvent;
          if (payload.message) setStageMessage(payload.message);
          if (typeof payload.matched === "number") {
            setMatchedCount(payload.matched);
          }
          if (typeof payload.target === "number" && payload.target > 0) {
            setTargetCount(payload.target);
          }
        } catch {
          /* Ignore malformed SSE status frame. */
        }
      });

      events.addEventListener("meta", (event) => {
        if (generationRef.current !== gen) return;
        try {
          const payload = JSON.parse(
            (event as MessageEvent).data,
          ) as DailyRecommendMetaEvent;
          if (payload.date) setDate(payload.date);
          if (payload.refresh_seq) setRefreshSeq(payload.refresh_seq);
          if (payload.mode) setMode(payload.mode);
          if (typeof payload.curator_note === "string") {
            setCuratorNote(payload.curator_note);
          }
          if (Array.isArray(payload.style_tags)) {
            setStyleTags(payload.style_tags);
          }
          if (payload.target_count > 0) {
            setTargetCount(payload.target_count);
          }
        } catch {
          /* Ignore malformed SSE meta frame. */
        }
      });

      events.addEventListener("item", (event) => {
        if (generationRef.current !== gen) return;
        try {
          const payload = JSON.parse(
            (event as MessageEvent).data,
          ) as DailyRecommendItemEvent;
          if (payload?.item?.track?.id) {
            setItems((prev) => {
              const next = mergeDailyItem(prev, payload.item);
              setMatchedCount(next.length);
              return next;
            });
          }
        } catch {
          /* Ignore malformed SSE item frame. */
        }
      });

      events.addEventListener("done", (event) => {
        terminalHandled = true;
        events.close();
        if (eventSourceRef.current === events) eventSourceRef.current = null;
        if (activeJobIdRef.current === jobId) activeJobIdRef.current = null;
        if (generationRef.current !== gen) return;

        setStreaming(false);
        setLoading(false);
        setStageMessage("");

        try {
          const payload = JSON.parse(
            (event as MessageEvent).data,
          ) as DailyRecommendDoneEvent;
          const daily = payload?.daily;
          if (daily) {
            setDate((prev) => daily.date || prev);
            setRefreshSeq((prev) => daily.refresh_seq || prev);
            setTargetCount((prev) =>
              daily.target_count > 0 ? daily.target_count : prev,
            );
            setMode((prev) => daily.mode || prev);
            setCuratorNote((prev) => daily.curator_note || prev);
            if (Array.isArray(daily.style_tags) && daily.style_tags.length) {
              setStyleTags(daily.style_tags);
            }
            const nextItems = Array.isArray(daily.items) ? daily.items : [];
            if (nextItems.length > 0) {
              setItems(nextItems);
              setMatchedCount(nextItems.length);
            } else if (!payload.cancelled) {
              setItems([]);
              setMatchedCount(0);
            }
            if (daily.error && !payload.cancelled) {
              setError(daily.error);
            }
          }
          if (payload?.cancelled) {
            onNoticeRef.current("已停止本次推荐生成");
          }
        } catch {
          /* Keep already streamed items if done payload failed to decode. */
        }
      });

      events.addEventListener("error", (event) => {
        if (terminalHandled || generationRef.current !== gen) return;
        terminalHandled = true;
        events.close();
        if (eventSourceRef.current === events) eventSourceRef.current = null;
        if (activeJobIdRef.current === jobId) activeJobIdRef.current = null;

        const rawData =
          "data" in event
            ? String((event as MessageEvent).data || "").trim()
            : "";
        if (rawData) {
          try {
            const payload = JSON.parse(rawData) as DailyRecommendErrorEvent;
            setStreaming(false);
            setLoading(false);
            setStageMessage("");
            setError(payload.detail || "每日推荐生成失败，请稍后重试");
            return;
          } catch {
            /* Fall through to snapshot recovery. */
          }
        }

        void fetchDailyRecommendation()
          .then((snapshot) => {
            if (generationRef.current !== gen) return;
            if (snapshot.status === "ready" && snapshot.items?.length) {
              applySnapshot(snapshot);
              setStreaming(false);
              setLoading(false);
              setStageMessage("");
              return;
            }
            setStreaming(false);
            setLoading(false);
            setStageMessage("");
            setError(snapshot.error || "推荐流连接已断开，请重试");
          })
          .catch(() => {
            if (generationRef.current !== gen) return;
            setStreaming(false);
            setLoading(false);
            setStageMessage("");
            setError("推荐流连接已断开，请点击「换一批」重试");
          });
      });
    },
    [applySnapshot, closeStream],
  );

  const loadOrStart = useCallback(
    async (force: boolean) => {
      if (!authenticated) return;
      const gen = ++generationRef.current;
      closeStream();
      const prevJobId = activeJobIdRef.current;
      activeJobIdRef.current = null;
      if (force && prevJobId) {
        void cancelDailyRecommendationJob(prevJobId).catch(() => {});
      }

      setError("");
      setLoading(true);
      setStreaming(false);
      if (force) {
        setItems([]);
        setMatchedCount(0);
        setCategoryFilter("all");
        setStageMessage("正在分析最近听歌口味，换一批全网好歌…");
      } else {
        setStageMessage("正在加载今日推荐歌单…");
      }

      const seeds = buildDailySeedsFromFavorites(favoritesRef.current);

      try {
        if (!force) {
          const cached = await fetchDailyRecommendation();
          if (generationRef.current !== gen) return;
          if (cached.status === "ready" && cached.items?.length) {
            applySnapshot(cached);
            setLoading(false);
            setStageMessage("");
            return;
          }
          if (cached.status === "generating" && cached.job_id) {
            applySnapshot(cached);
            setStageMessage("正在全网搜寻匹配曲目…");
            subscribeJob(cached.job_id, gen);
            return;
          }
        }

        const started = await startDailyRecommendation({
          force,
          seeds: seeds.length ? seeds : undefined,
        });
        if (generationRef.current !== gen) return;

        if (started.status === "ready" && started.items?.length) {
          applySnapshot(started);
          setLoading(false);
          setStageMessage("");
          return;
        }
        if (started.job_id) {
          if (started.date) setDate(started.date);
          if (started.refresh_seq) setRefreshSeq(started.refresh_seq);
          if (started.target_count > 0) setTargetCount(started.target_count);
          if (started.mode) setMode(started.mode);
          if (started.curator_note) setCuratorNote(started.curator_note);
          if (Array.isArray(started.style_tags) && started.style_tags.length) {
            setStyleTags(started.style_tags);
          }
          if (Array.isArray(started.items) && started.items.length) {
            setItems(started.items);
            setMatchedCount(started.items.length);
          }
          setStageMessage("正在结合听歌偏好全网筛选 30 首曲目…");
          subscribeJob(started.job_id, gen);
          return;
        }
        applySnapshot(started);
        setLoading(false);
        setStageMessage("");
        if (started.error) setError(started.error);
      } catch (err) {
        if (generationRef.current !== gen) return;
        setLoading(false);
        setStreaming(false);
        setStageMessage("");
        setError(err instanceof Error ? err.message : "加载每日推荐失败");
      }
    },
    [applySnapshot, authenticated, closeStream, subscribeJob],
  );

  const handleStop = useCallback(() => {
    const jobId = activeJobIdRef.current;
    generationRef.current += 1;
    closeStream();
    activeJobIdRef.current = null;
    setStreaming(false);
    setLoading(false);
    setStageMessage("");
    if (jobId) {
      void cancelDailyRecommendationJob(jobId).catch(() => {});
    }
    onNotice("已停止本次推荐生成");
  }, [closeStream, onNotice]);

  useEffect(() => {
    if (!authenticated) return;
    void loadOrStart(false);
    return () => {
      // Only close SSE on view switch; keep server-side job running so returning resumes seamlessly.
      generationRef.current += 1;
      closeStream();
    };
  }, [authenticated, closeStream, loadOrStart]);

  const calendar = useMemo(() => parseDailyCalendar(date), [date]);

  const jobByTrackId = useMemo(() => {
    const map = new Map<string, DownloadJob>();
    for (const job of jobs) {
      const id = job.track?.id;
      if (!id) continue;
      const existing = map.get(id);
      if (!existing) {
        map.set(id, job);
      } else if (existing.status !== "complete" && job.status === "complete") {
        map.set(id, job);
      }
    }
    return map;
  }, [jobs]);

  const discoverCount = useMemo(
    () => items.filter((item) => item.category !== "familiar").length,
    [items],
  );
  const familiarCount = useMemo(
    () => items.filter((item) => item.category === "familiar").length,
    [items],
  );

  const visibleItems = useMemo(() => {
    if (categoryFilter === "discover") {
      return items.filter((item) => item.category !== "familiar");
    }
    if (categoryFilter === "familiar") {
      return items.filter((item) => item.category === "familiar");
    }
    return items;
  }, [categoryFilter, items]);

  const visibleTracks = useMemo(
    () => visibleItems.map((item) => item.track),
    [visibleItems],
  );
  const allTracks = useMemo(() => items.map((item) => item.track), [items]);

  const enqueueableTracks = useMemo(
    () =>
      items
        .filter((item) => {
          if (!isDailyItemEnqueueable(item)) return false;
          const job = jobByTrackId.get(item.track.id);
          return ![
            "queued",
            "resolving",
            "downloading",
            "enriching",
            "complete",
          ].includes(job?.status || "");
        })
        .map((item) => item.track),
    [items, jobByTrackId],
  );

  const isAiMode = mode.toLowerCase() === "ai";
  const modeLabel = isAiMode ? "AI 推荐" : mode ? "规则推荐" : "";
  const safeTarget = targetCount > 0 ? targetCount : 30;
  const effectiveMatched = Math.max(matchedCount, items.length);
  const progressPct = Math.min(
    100,
    Math.round((effectiveMatched / safeTarget) * 100),
  );

  return (
    <section className="view daily-view" aria-label="每日推荐">
      <header className="daily-hero">
        <div className="daily-hero-main">
          <div
            className="daily-calendar"
            aria-label={`推荐日期 ${calendar.fullDate}`}
          >
            <span className="daily-calendar-head">
              {calendar.monthLabel} · {calendar.weekdayLabel}
            </span>
            <strong className="daily-calendar-day">{calendar.day}</strong>
          </div>

          <div className="daily-hero-copy">
            <div className="daily-title-row">
              <h2>每日推荐</h2>
              {modeLabel && (
                <span
                  className={`daily-mode-badge ${isAiMode ? "is-ai" : "is-rule"}`}
                >
                  {isAiMode && <Sparkles aria-hidden="true" />}
                  {modeLabel}
                </span>
              )}
              {refreshSeq > 1 && (
                <span className="daily-batch-badge">第 {refreshSeq} 批</span>
              )}
              {!isAiMode && mode && !loading && !streaming && (
                <button
                  type="button"
                  className="text-button"
                  onClick={() => onOpenSettings("ai")}
                >
                  配置 AI 推荐
                </button>
              )}
            </div>

            <p className="daily-subtitle">
              根据你的最近听歌记录与收藏品味，全网精选 {safeTarget} 首
              {items.length > 0 && items.length < safeTarget
                ? `（已加载 ${items.length} 首）`
                : ""}
            </p>

            {curatorNote && <p className="daily-curator-note">{curatorNote}</p>}

            {styleTags.length > 0 && (
              <div className="daily-style-tags" aria-label="今日推荐风格标签">
                {styleTags.slice(0, 6).map((raw) => {
                  const tag = raw.trim().replace(/^#+/, "");
                  if (!tag) return null;
                  return (
                    <span key={tag} className="daily-tag-chip">
                      #{tag}
                    </span>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        <div className="daily-actions">
          <button
            type="button"
            className="primary"
            disabled={!visibleTracks.length}
            onClick={() => onPlayAll(visibleTracks)}
          >
            <Play aria-hidden="true" />
            播放全部
          </button>

          <button
            type="button"
            className="secondary"
            disabled={loading}
            onClick={() => void loadOrStart(true)}
          >
            <RefreshCw
              className={loading || streaming ? "is-spinning" : ""}
              aria-hidden="true"
            />
            换一批
          </button>

          {streaming && (
            <button
              type="button"
              className="secondary daily-stop-btn"
              onClick={handleStop}
            >
              <Square aria-hidden="true" />
              停止生成
            </button>
          )}

          <button
            type="button"
            className="secondary"
            disabled={!visibleTracks.length}
            onClick={() => onQueueAll(visibleTracks)}
          >
            <Plus aria-hidden="true" />
            加入待播
          </button>

          <button
            type="button"
            className="secondary"
            disabled={!allTracks.length}
            onClick={() => onAddToPlaylist(allTracks)}
          >
            <ListMusic aria-hidden="true" />
            存为歌单
          </button>

          <button
            type="button"
            className="secondary"
            disabled={!enqueueableTracks.length}
            onClick={() => onDownload(enqueueableTracks)}
          >
            {enqueueableTracks.length > 0 ? (
              <Download aria-hidden="true" />
            ) : (
              <Check aria-hidden="true" />
            )}
            {enqueueableTracks.length > 0
              ? `批量入库 (${enqueueableTracks.length})`
              : items.length > 0
                ? "已全入库"
                : "批量入库"}
          </button>
        </div>

        {(loading || streaming) && (
          <div className="daily-progress" role="status" aria-live="polite">
            <div className="daily-progress-head">
              <span>{stageMessage || "正在为你挑选今日全网推荐曲目…"}</span>
              <small>
                {effectiveMatched} / {safeTarget}
              </small>
            </div>
            <div
              className={`daily-progress-bar ${effectiveMatched === 0 ? "is-indeterminate" : ""}`}
              aria-hidden="true"
            >
              <span
                style={
                  effectiveMatched > 0
                    ? { width: `${progressPct}%` }
                    : undefined
                }
              />
            </div>
          </div>
        )}
      </header>

      {error && (
        <div className="daily-error-banner" role="alert">
          <AlertCircle aria-hidden="true" />
          <span>{error}</span>
          <button
            type="button"
            className="secondary"
            onClick={() => void loadOrStart(true)}
          >
            重试
          </button>
        </div>
      )}

      {items.length > 0 && (
        <div
          className="daily-filter-bar"
          role="group"
          aria-label="筛选每日推荐曲目"
        >
          <button
            type="button"
            className={`plugin-archive-chip ${categoryFilter === "all" ? "is-active" : ""}`}
            aria-pressed={categoryFilter === "all"}
            onClick={() => setCategoryFilter("all")}
          >
            全部 ({items.length})
          </button>
          <button
            type="button"
            className={`plugin-archive-chip ${categoryFilter === "discover" ? "is-active" : ""}`}
            aria-pressed={categoryFilter === "discover"}
            onClick={() => setCategoryFilter("discover")}
          >
            全网发现 ({discoverCount})
          </button>
          <button
            type="button"
            className={`plugin-archive-chip ${categoryFilter === "familiar" ? "is-active" : ""}`}
            aria-pressed={categoryFilter === "familiar"}
            onClick={() => setCategoryFilter("familiar")}
          >
            曲库延伸 ({familiarCount})
          </button>
        </div>
      )}

      {(loading || streaming) && items.length === 0 ? (
        <div className="daily-skeleton-card">
          <div className="shimmer-list" aria-hidden="true">
            <div className="shimmer-row" />
            <div className="shimmer-row" />
            <div className="shimmer-row" />
            <div className="shimmer-row" />
            <div className="shimmer-row" />
          </div>
        </div>
      ) : items.length === 0 ? (
        <div className="daily-empty">
          <Sparkles aria-hidden="true" />
          <h3>暂无今日推荐曲目</h3>
          <p className="muted">
            可先尝试收藏或播放几首歌曲，或直接点击下方按钮全网生成 30 首推荐。
          </p>
          <button
            type="button"
            className="primary"
            onClick={() => void loadOrStart(true)}
          >
            <RefreshCw aria-hidden="true" />
            生成今日推荐
          </button>
        </div>
      ) : visibleItems.length === 0 ? (
        <div className="empty">当前分类下暂无曲目，可切换「全部」查看。</div>
      ) : (
        <div className="daily-list" role="list" aria-label="每日推荐曲目列表">
          {visibleItems.map((item, idx) => {
            const track = item.track;
            const isCurrent = current?.id === track.id;
            const isFavorite = favIds.has(track.id);
            const job = jobByTrackId.get(track.id);
            const jobStatus = job?.status || "";
            const isActiveDownload = [
              "queued",
              "resolving",
              "downloading",
              "enriching",
            ].includes(jobStatus);
            const inLibraryOrDone = item.in_library || jobStatus === "complete";
            const primaryQuality = track.qualities?.length
              ? formatFriendlyQuality(track.qualities[0], track)
              : null;
            const subtitleParts = [
              track.artist || "未知歌手",
              track.album || "",
              sourceName(track.source),
              primaryQuality?.title || "",
            ].filter(Boolean);

            return (
              <div
                key={track.id}
                role="listitem"
                className={`daily-row ${isCurrent ? "is-playing" : ""}`}
              >
                <span className="daily-rank" aria-hidden="true">
                  {String(idx + 1).padStart(2, "0")}
                </span>

                <Cover
                  track={track}
                  playing={isCurrent && playing}
                  onPlay={() => onPlay(track, visibleTracks)}
                />

                <div className="daily-row-main">
                  <div className="daily-row-titleline">
                    <SongTitle
                      track={track}
                      onPlay={() => onPlay(track, visibleTracks)}
                    />
                    <span
                      className={`daily-source-badge ${
                        inLibraryOrDone
                          ? "is-library"
                          : item.category === "familiar"
                            ? "is-familiar"
                            : "is-discover"
                      }`}
                    >
                      {inLibraryOrDone
                        ? "已在曲库"
                        : item.category === "familiar"
                          ? "口味延伸"
                          : "全网发现"}
                    </span>
                    {primaryQuality?.isLossless && (
                      <span className="daily-quality-pill">无损</span>
                    )}
                  </div>

                  <div className="daily-row-meta">
                    {subtitleParts.join(" · ")}
                  </div>

                  {item.reason && (
                    <div className="daily-row-reason" title={item.reason}>
                      {item.reason}
                    </div>
                  )}
                </div>

                <span className="daily-row-duration">
                  {track.duration ? clock(track.duration) : "—"}
                </span>

                <div className="daily-row-actions">
                  <button
                    type="button"
                    className="row-play"
                    aria-label={`播放 ${track.title}`}
                    title="播放"
                    onClick={() => onPlay(track, visibleTracks)}
                  >
                    <Play aria-hidden="true" />
                    <span>播放</span>
                  </button>

                  <button
                    type="button"
                    className={`icon-button favorite ${isFavorite ? "on" : ""}`}
                    aria-label={
                      isFavorite
                        ? `取消收藏 ${track.title}`
                        : `收藏 ${track.title}`
                    }
                    aria-pressed={isFavorite}
                    title={isFavorite ? "取消收藏" : "收藏"}
                    onClick={() => onFavorite(track)}
                  >
                    <Heart aria-hidden="true" />
                  </button>

                  {track.source === "navidrome" ? (
                    <a
                      className="icon-button"
                      aria-label={`下载 ${track.title}`}
                      title="下载 Navidrome 原文件"
                      href={navidromeSingleDownloadUrl(track.source_id)}
                      download={`${track.title}.${track.playback?.codec || "audio"}`}
                    >
                      <Download aria-hidden="true" />
                    </a>
                  ) : isDailyItemEnqueueable(item) && !inLibraryOrDone ? (
                    <button
                      type="button"
                      className={`icon-button ${isActiveDownload ? "active" : ""}`}
                      disabled={isActiveDownload}
                      aria-label={
                        isActiveDownload
                          ? `${track.title} 正在入库`
                          : `下载 ${track.title} 到曲库`
                      }
                      title={isActiveDownload ? "下载入库中" : "下载到曲库"}
                      onClick={() => onDownload([track])}
                    >
                      <Download aria-hidden="true" />
                    </button>
                  ) : (
                    <span
                      className="daily-inlib-check"
                      title={inLibraryOrDone ? "已在曲库" : "本地音源"}
                      aria-label={inLibraryOrDone ? "已在曲库" : "本地音源"}
                    >
                      <Check aria-hidden="true" />
                    </span>
                  )}

                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`下一首播放 ${track.title}`}
                    title="下一首播放 / 加入待播"
                    onClick={() => onPlayNext(track)}
                  >
                    <Plus aria-hidden="true" />
                  </button>

                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`加入歌单 ${track.title}`}
                    title="加入歌单"
                    onClick={() => onAddToPlaylist([track])}
                  >
                    <ListMusic aria-hidden="true" />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
