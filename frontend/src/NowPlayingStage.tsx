import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import {
  AlignCenter,
  AlignLeft,
  ArrowLeft,
  Check,
  Download,
  Heart,
  ListChecks,
  ListMusic,
  ListStart,
  LocateFixed,
  Moon,
  PanelRight,
  Pause,
  Play,
  Plus,
  Repeat,
  RotateCcw,
  Search,
  Shuffle,
  SkipBack,
  SkipForward,
  SlidersHorizontal,
  X,
} from "lucide-react";
import { Button, Cover } from "./components";
import { type LyricLine } from "./lyrics";
import { PlaybackTimeline, SyncedLyrics } from "./PlaybackWidgets";
import {
  DEFAULT_LYRICS_ALIGN,
  DEFAULT_LYRICS_FONT_SIZE,
  DEFAULT_LYRICS_INACTIVE_OPACITY,
  LARGE_LYRICS_FONT_SIZE,
  LYRICS_ACTIVE_COLOR_PRESETS,
  LYRICS_FONT_SIZE_MAX,
  LYRICS_FONT_SIZE_MIN,
  type LyricsAlign,
} from "./preferences";
import type { Track } from "./types";

export interface SleepTimerState {
  endsAtMs: number;
  stopAfterCurrent: boolean;
}

const SLEEP_OPTIONS = [
  { label: "关闭定时", minutes: 0, stopAfterCurrent: false },
  { label: "15 分钟", minutes: 15, stopAfterCurrent: false },
  { label: "30 分钟", minutes: 30, stopAfterCurrent: false },
  { label: "45 分钟", minutes: 45, stopAfterCurrent: false },
  { label: "60 分钟", minutes: 60, stopAfterCurrent: false },
  { label: "播完本首停止", minutes: 0, stopAfterCurrent: true },
] as const;

function formatSleepCountdown(msRemaining: number): string {
  const totalSec = Math.max(0, Math.ceil(msRemaining / 1000));
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${String(sec).padStart(2, "0")}`;
}

export function NowPlayingStage({
  current,
  playing,
  loadingPlay,
  duration,
  shuffle,
  repeat,
  isFavorite,
  inQueue,
  playbackLabel,
  sourceName,
  returnLabel,
  lines,
  lyricText,
  follow,
  largeLyrics,
  lyricsFontSize,
  lyricsActiveColor = "",
  lyricsInactiveOpacity = DEFAULT_LYRICS_INACTIVE_OPACITY,
  lyricsAlign = DEFAULT_LYRICS_ALIGN,
  lyricsOffsetMs = 0,
  sleepTimer = null,
  playlist,
  queueManaging,
  queueSaving,
  volumeControl,
  queueListContent,
  onBack,
  onTogglePlay,
  onSeek,
  onStep,
  onToggleShuffle,
  onToggleRepeat,
  onFavorite,
  onDownload,
  onAddToQueue,
  onAddToPlaylist,
  onToggleFollow,
  onToggleLargeLyrics,
  onChangeLyricsFontSize,
  onChangeLyricsActiveColor,
  onChangeLyricsInactiveOpacity,
  onChangeLyricsAlign,
  onResetLyricsStyle,
  onChangeLyricsOffsetMs,
  onSetSleepTimer,
  onToggleQueueManaging,
  onPlayQueueTrack,
  onPlayNextQueueTrack,
  onRemoveQueueTrack,
  onQuickSearch,
}: {
  current: Track | null;
  playing: boolean;
  loadingPlay: boolean;
  duration: number;
  shuffle: boolean;
  repeat: boolean;
  isFavorite: boolean;
  inQueue: boolean;
  playbackLabel: string;
  sourceName: string;
  returnLabel: string;
  lines: LyricLine[];
  lyricText: string;
  follow: boolean;
  largeLyrics: boolean;
  lyricsFontSize?: number;
  lyricsActiveColor?: string;
  lyricsInactiveOpacity?: number;
  lyricsAlign?: LyricsAlign;
  lyricsOffsetMs?: number;
  sleepTimer?: SleepTimerState | null;
  playlist: Track[];
  queueManaging: boolean;
  queueSaving: boolean;
  volumeControl: ReactNode;
  queueListContent: ReactNode;
  onBack: () => void;
  onTogglePlay: () => void;
  onSeek: (seconds: number) => void;
  onStep: (direction: number) => void;
  onToggleShuffle: () => void;
  onToggleRepeat: () => void;
  onFavorite: () => void;
  onDownload: () => void;
  onAddToQueue: () => void;
  onAddToPlaylist: () => void;
  onToggleFollow: () => void;
  onToggleLargeLyrics: () => void;
  onChangeLyricsFontSize?: (size: number) => void;
  onChangeLyricsActiveColor?: (color: string) => void;
  onChangeLyricsInactiveOpacity?: (opacity: number) => void;
  onChangeLyricsAlign?: (align: LyricsAlign) => void;
  onResetLyricsStyle?: () => void;
  onChangeLyricsOffsetMs?: (nextMs: number) => void;
  onSetSleepTimer?: (minutes: number, stopAfterCurrent: boolean) => void;
  onToggleQueueManaging: () => void;
  onPlayQueueTrack: (track: Track) => void;
  onPlayNextQueueTrack?: (track: Track) => void;
  onRemoveQueueTrack: (id: string) => void;
  onQuickSearch: (keyword: string) => void;
}) {
  const [queuePreference, setQueuePreference] = useState<boolean | null>(null);
  const [prevPlaylistLen, setPrevPlaylistLen] = useState(playlist.length);
  const [queueFilter, setQueueFilter] = useState("");
  const [sleepMenuOpen, setSleepMenuOpen] = useState(false);
  const [lyricsTuneOpen, setLyricsTuneOpen] = useState(false);
  const [lyricsStyleOpen, setLyricsStyleOpen] = useState(false);
  const [selectedSleepMinutes, setSelectedSleepMinutes] = useState<
    number | null
  >(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const sleepMenuRef = useRef<HTMLDivElement>(null);
  const lyricsTuneRef = useRef<HTMLDivElement>(null);
  const lyricsStyleRef = useRef<HTMLDivElement>(null);
  const queueWrapRef = useRef<HTMLElement>(null);

  useEffect(() => {
    if (!sleepTimer?.endsAtMs || sleepTimer.endsAtMs <= Date.now()) return;
    setNowMs(Date.now());
    const id = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [sleepTimer]);

  useEffect(() => {
    if (!sleepMenuOpen && !lyricsTuneOpen && !lyricsStyleOpen) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (
        sleepMenuOpen &&
        sleepMenuRef.current &&
        !sleepMenuRef.current.contains(target)
      ) {
        setSleepMenuOpen(false);
      }
      if (
        lyricsTuneOpen &&
        lyricsTuneRef.current &&
        !lyricsTuneRef.current.contains(target)
      ) {
        setLyricsTuneOpen(false);
      }
      if (
        lyricsStyleOpen &&
        lyricsStyleRef.current &&
        !lyricsStyleRef.current.contains(target)
      ) {
        setLyricsStyleOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setSleepMenuOpen(false);
        setLyricsTuneOpen(false);
        setLyricsStyleOpen(false);
      }
    };
    window.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [sleepMenuOpen, lyricsTuneOpen, lyricsStyleOpen]);

  if (playlist.length !== prevPlaylistLen) {
    setPrevPlaylistLen(playlist.length);
    if (prevPlaylistLen === 0 && playlist.length > 0) {
      setQueuePreference(true);
    } else if (playlist.length === 0) {
      setQueuePreference(null);
      setQueueFilter("");
    }
  }

  const adjustedLines = useMemo(() => {
    if (!lyricsOffsetMs) return lines;
    const shiftSec = lyricsOffsetMs / 1000;
    return lines.map((line) => ({
      ...line,
      time: Math.max(0, line.time + shiftSec),
    }));
  }, [lines, lyricsOffsetMs]);

  const isQueueShown =
    queuePreference !== null ? queuePreference : playlist.length > 0;
  const trimmedFilter = queueFilter.trim().toLowerCase();
  const filteredQueue = useMemo(() => {
    if (!trimmedFilter) return playlist;
    return playlist.filter((t) =>
      `${t.title} ${t.artist} ${t.album || ""}`
        .toLowerCase()
        .includes(trimmedFilter),
    );
  }, [playlist, trimmedFilter]);

  const locateCurrentInQueue = () => {
    const active = queueWrapRef.current?.querySelector<HTMLElement>(
      ".playlist-item.active",
    );
    active?.scrollIntoView({ block: "center", behavior: "smooth" });
  };

  const hasCurrentInQueue = Boolean(
    current && playlist.some((t) => t.id === current.id),
  );

  const sleepRemainingMs = sleepTimer?.endsAtMs
    ? Math.max(0, sleepTimer.endsAtMs - nowMs)
    : 0;
  const isSleepActive = Boolean(
    sleepTimer && (sleepTimer.stopAfterCurrent || sleepRemainingMs > 0),
  );
  const sleepLabel = sleepTimer?.stopAfterCurrent
    ? "播完停止"
    : sleepRemainingMs > 0
      ? formatSleepCountdown(sleepRemainingMs)
      : "睡眠定时";
  const offsetLabel =
    lyricsOffsetMs > 0 ? `+${lyricsOffsetMs}ms` : `${lyricsOffsetMs}ms`;
  const effectiveFontSize =
    lyricsFontSize ??
    (largeLyrics ? LARGE_LYRICS_FONT_SIZE : DEFAULT_LYRICS_FONT_SIZE);
  const opacityPct = Math.round(lyricsInactiveOpacity * 100);
  const fontRangePct =
    ((effectiveFontSize - LYRICS_FONT_SIZE_MIN) /
      (LYRICS_FONT_SIZE_MAX - LYRICS_FONT_SIZE_MIN)) *
    100;
  const opacityRangePct = ((opacityPct - 20) / (90 - 20)) * 100;
  const matchedColorPreset = LYRICS_ACTIVE_COLOR_PRESETS.find(
    (preset) =>
      preset.color.toLowerCase() === lyricsActiveColor.trim().toLowerCase(),
  );
  const activeColorLabel =
    lyricsActiveColor.trim() === ""
      ? "跟随主题"
      : matchedColorPreset
        ? matchedColorPreset.label
        : lyricsActiveColor.toUpperCase();
  const hasCustomLyricsStyle =
    effectiveFontSize !== DEFAULT_LYRICS_FONT_SIZE ||
    lyricsActiveColor.trim() !== "" ||
    Math.abs(lyricsInactiveOpacity - DEFAULT_LYRICS_INACTIVE_OPACITY) > 0.001 ||
    lyricsAlign !== DEFAULT_LYRICS_ALIGN;

  return (
    <section className="view full-player np-stage">
      {current?.cover && (
        <div
          className="player-atmosphere"
          aria-hidden="true"
          style={{
            backgroundImage: `url(${JSON.stringify(current.cover)})`,
          }}
        />
      )}
      <header className="playback-top">
        <button type="button" className="text-button" onClick={onBack}>
          <ArrowLeft />
          {returnLabel}
        </button>
        <div className="playback-top-tools">
          {current && (
            <>
              <div className="np-lyrics-style-menu" ref={lyricsStyleRef}>
                <button
                  type="button"
                  className={`np-tool-btn np-tool-icon np-lyrics-style-trigger ${lyricsStyleOpen ? "is-open" : ""} ${hasCustomLyricsStyle ? "active" : ""}`}
                  aria-label="歌词外观设置"
                  aria-expanded={lyricsStyleOpen}
                  aria-haspopup="dialog"
                  title="歌词外观设置"
                  onClick={() => {
                    setSleepMenuOpen(false);
                    setLyricsTuneOpen(false);
                    setLyricsStyleOpen((prev) => !prev);
                  }}
                >
                  Aa
                </button>
                {lyricsStyleOpen && (
                  <div
                    className="np-lyrics-popover np-lyrics-style-popover"
                    role="dialog"
                    aria-label="歌词外观设置"
                  >
                    <div className="np-lyrics-popover-head">
                      <span>歌词外观</span>
                      <button
                        type="button"
                        className="np-lyrics-popover-reset"
                        onClick={() => {
                          if (onResetLyricsStyle) {
                            onResetLyricsStyle();
                          } else if (largeLyrics) {
                            onToggleLargeLyrics();
                          }
                        }}
                      >
                        <RotateCcw aria-hidden="true" />
                        恢复默认
                      </button>
                    </div>

                    <label className="np-lyrics-style-field">
                      <span className="np-lyrics-style-row">
                        <span>字号</span>
                        <strong className="np-lyrics-style-val">
                          {effectiveFontSize}px
                        </strong>
                      </span>
                      <input
                        type="range"
                        aria-label="歌词字号"
                        min={LYRICS_FONT_SIZE_MIN}
                        max={LYRICS_FONT_SIZE_MAX}
                        step={1}
                        value={effectiveFontSize}
                        style={
                          { "--progress": `${fontRangePct}%` } as CSSProperties
                        }
                        onChange={(e) => {
                          const nextSize = Number(e.target.value);
                          if (onChangeLyricsFontSize) {
                            onChangeLyricsFontSize(nextSize);
                          } else if (
                            nextSize >= LARGE_LYRICS_FONT_SIZE !==
                            largeLyrics
                          ) {
                            onToggleLargeLyrics();
                          }
                        }}
                      />
                    </label>

                    <div className="np-lyrics-style-field">
                      <span className="np-lyrics-style-row">
                        <span>高亮歌词颜色</span>
                        <strong className="np-lyrics-style-val">
                          {activeColorLabel}
                        </strong>
                      </span>
                      <div
                        className="np-lyrics-color-row"
                        role="group"
                        aria-label="高亮歌词颜色预设"
                      >
                        {LYRICS_ACTIVE_COLOR_PRESETS.map((preset) => {
                          const selected =
                            preset.color === ""
                              ? lyricsActiveColor.trim() === ""
                              : lyricsActiveColor.trim().toLowerCase() ===
                                preset.color.toLowerCase();
                          if (preset.color === "") {
                            return (
                              <button
                                key="theme-default"
                                type="button"
                                className={`np-lyrics-color-chip ${selected ? "active" : ""}`}
                                aria-label={`高亮颜色：${preset.label}`}
                                aria-pressed={selected}
                                title={preset.label}
                                onClick={() => onChangeLyricsActiveColor?.("")}
                              >
                                {preset.label}
                              </button>
                            );
                          }
                          return (
                            <button
                              key={preset.color}
                              type="button"
                              className={`accent-dot np-lyrics-color-dot ${selected ? "active" : ""}`}
                              style={{ backgroundColor: preset.color }}
                              aria-label={`高亮颜色：${preset.label}`}
                              aria-pressed={selected}
                              title={`${preset.label} (${preset.color})`}
                              onClick={() =>
                                onChangeLyricsActiveColor?.(preset.color)
                              }
                            />
                          );
                        })}
                        <label
                          className="accent-custom-pick"
                          title="自定义高亮歌词颜色"
                        >
                          <input
                            type="color"
                            aria-label="自定义高亮歌词颜色"
                            value={lyricsActiveColor || "#ffffff"}
                            onChange={(e) =>
                              onChangeLyricsActiveColor?.(e.target.value)
                            }
                          />
                          <span>自定义</span>
                        </label>
                      </div>
                    </div>

                    <label className="np-lyrics-style-field">
                      <span className="np-lyrics-style-row">
                        <span>非高亮行透明度</span>
                        <strong className="np-lyrics-style-val">
                          {opacityPct}%
                        </strong>
                      </span>
                      <input
                        type="range"
                        aria-label="非高亮行透明度"
                        min={20}
                        max={90}
                        step={1}
                        value={opacityPct}
                        style={
                          {
                            "--progress": `${opacityRangePct}%`,
                          } as CSSProperties
                        }
                        onChange={(e) =>
                          onChangeLyricsInactiveOpacity?.(
                            Number(e.target.value) / 100,
                          )
                        }
                      />
                    </label>

                    <div className="np-lyrics-style-field">
                      <span className="np-lyrics-style-row">
                        <span>对齐方式</span>
                      </span>
                      <div
                        className="theme-density-switch np-lyrics-align-switch"
                        role="group"
                        aria-label="歌词对齐方式"
                      >
                        <button
                          type="button"
                          aria-pressed={lyricsAlign === "center"}
                          onClick={() => onChangeLyricsAlign?.("center")}
                        >
                          <AlignCenter aria-hidden="true" />
                          <span>居中对齐</span>
                        </button>
                        <button
                          type="button"
                          aria-pressed={lyricsAlign === "left"}
                          onClick={() => onChangeLyricsAlign?.("left")}
                        >
                          <AlignLeft aria-hidden="true" />
                          <span>左对齐</span>
                        </button>
                      </div>
                    </div>
                  </div>
                )}
              </div>
              {onChangeLyricsOffsetMs && (
                <div className="np-lyrics-tune-menu" ref={lyricsTuneRef}>
                  <button
                    type="button"
                    className={`np-tool-btn np-tool-icon np-ghost-tool np-tune-trigger ${lyricsTuneOpen ? "is-open" : ""} ${lyricsOffsetMs !== 0 ? "has-offset" : ""}`}
                    aria-label="歌词时间轴校准"
                    aria-expanded={lyricsTuneOpen}
                    aria-haspopup="dialog"
                    title={
                      lyricsOffsetMs !== 0
                        ? `歌词时间轴校准（当前 ${offsetLabel}）`
                        : "歌词时间轴校准"
                    }
                    onClick={() => {
                      setSleepMenuOpen(false);
                      setLyricsStyleOpen(false);
                      setLyricsTuneOpen((prev) => !prev);
                    }}
                  >
                    <SlidersHorizontal aria-hidden="true" />
                    {lyricsOffsetMs !== 0 && (
                      <span className="np-tune-dot" aria-hidden="true" />
                    )}
                  </button>
                  {lyricsTuneOpen && (
                    <div
                      className="np-lyrics-popover"
                      role="dialog"
                      aria-label="歌词时间轴校准"
                    >
                      <div className="np-lyrics-popover-head">
                        <span>歌词时间轴校准</span>
                        {lyricsOffsetMs !== 0 && (
                          <button
                            type="button"
                            className="np-lyrics-popover-reset"
                            onClick={() => onChangeLyricsOffsetMs(0)}
                          >
                            归零
                          </button>
                        )}
                      </div>
                      <div
                        className="np-offset-stepper"
                        role="group"
                        aria-label="歌词时间轴偏移校准"
                      >
                        <button
                          type="button"
                          className="np-stepper-btn"
                          aria-label="歌词提前 100 毫秒"
                          title="提前 100ms"
                          disabled={lyricsOffsetMs <= -2000}
                          onClick={() =>
                            onChangeLyricsOffsetMs(
                              Math.max(-2000, lyricsOffsetMs - 100),
                            )
                          }
                        >
                          −100ms
                        </button>
                        <button
                          type="button"
                          className={`np-stepper-btn np-stepper-value ${lyricsOffsetMs !== 0 ? "active" : ""}`}
                          aria-label="重置歌词时间偏移"
                          title={
                            lyricsOffsetMs === 0
                              ? "当前未偏移"
                              : "点击重置为 0ms"
                          }
                          disabled={lyricsOffsetMs === 0}
                          onClick={() => onChangeLyricsOffsetMs(0)}
                        >
                          {offsetLabel}
                        </button>
                        <button
                          type="button"
                          className="np-stepper-btn"
                          aria-label="歌词延后 100 毫秒"
                          title="延后 100ms"
                          disabled={lyricsOffsetMs >= 2000}
                          onClick={() =>
                            onChangeLyricsOffsetMs(
                              Math.min(2000, lyricsOffsetMs + 100),
                            )
                          }
                        >
                          +100ms
                        </button>
                      </div>
                      <p className="np-lyrics-popover-hint">
                        歌词慢了点 −100ms，快了点 +100ms
                      </p>
                    </div>
                  )}
                </div>
              )}
              {onSetSleepTimer && (
                <div className="np-sleep-menu" ref={sleepMenuRef}>
                  <button
                    type="button"
                    className={`np-tool-btn np-ghost-tool np-sleep-trigger ${isSleepActive ? "active" : ""}`}
                    aria-label="睡眠定时器"
                    aria-expanded={sleepMenuOpen}
                    aria-haspopup="menu"
                    title={
                      isSleepActive ? `睡眠定时：${sleepLabel}` : "设置睡眠定时"
                    }
                    onClick={() => {
                      setLyricsTuneOpen(false);
                      setLyricsStyleOpen(false);
                      setSleepMenuOpen((prev) => !prev);
                    }}
                  >
                    <Moon aria-hidden="true" />
                    <span
                      className={`np-sleep-label ${isSleepActive ? "is-active" : ""}`}
                    >
                      {sleepLabel}
                    </span>
                  </button>
                  {sleepMenuOpen && (
                    <div
                      className="np-sleep-dropdown"
                      role="menu"
                      aria-label="睡眠定时选项"
                    >
                      {SLEEP_OPTIONS.map((opt) => {
                        const selected =
                          opt.minutes === 0 && !opt.stopAfterCurrent
                            ? !isSleepActive
                            : opt.stopAfterCurrent
                              ? Boolean(sleepTimer?.stopAfterCurrent)
                              : isSleepActive &&
                                !sleepTimer?.stopAfterCurrent &&
                                selectedSleepMinutes === opt.minutes;
                        return (
                          <button
                            key={opt.label}
                            type="button"
                            role="menuitem"
                            className={selected ? "active" : undefined}
                            onClick={() => {
                              setSelectedSleepMinutes(
                                opt.minutes > 0 ? opt.minutes : null,
                              );
                              onSetSleepTimer(
                                opt.minutes,
                                opt.stopAfterCurrent,
                              );
                              setSleepMenuOpen(false);
                            }}
                          >
                            <span>{opt.label}</span>
                            {selected && <Check size={14} />}
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}
              <button
                type="button"
                className={`np-tool-btn lyric-follow ${follow && lines.length > 0 ? "active" : ""}`}
                aria-pressed={follow && lines.length > 0}
                disabled={!lines.length}
                title={
                  lines.length
                    ? "跟随当前播放位置"
                    : "歌词没有时间轴，无法跟随进度"
                }
                onClick={onToggleFollow}
              >
                跟随进度
              </button>
              <button
                type="button"
                className={`np-tool-btn np-queue-toggle ${isQueueShown ? "active" : ""}`}
                aria-label={isQueueShown ? "收起待播栏" : "展开待播栏"}
                aria-pressed={isQueueShown}
                title={isQueueShown ? "收起待播栏" : "展开待播栏"}
                onClick={() => setQueuePreference(!isQueueShown)}
              >
                <PanelRight aria-hidden="true" />
                <span>待播 {playlist.length}</span>
              </button>
            </>
          )}
        </div>
      </header>

      {current ? (
        <>
          <div
            className={`playback-grid ${isQueueShown ? "has-queue" : "immersive-dual"}`}
          >
            <div className="album-column">
              <div className="np-artwork-frame">
                <Cover track={current} large playing={playing} />
              </div>
              <div className="album-info">
                <h2>{current.title}</h2>
                <p className="np-subline">
                  <button
                    type="button"
                    className="np-meta-link"
                    title={`搜索歌手：${current.artist}`}
                    onClick={() => onQuickSearch(current.artist)}
                  >
                    {current.artist}
                  </button>
                  {current.album && (
                    <>
                      <span className="np-sub-sep" aria-hidden="true">
                        ·
                      </span>
                      <button
                        type="button"
                        className="np-meta-link"
                        title={`搜索专辑：${current.album}`}
                        onClick={() =>
                          onQuickSearch(`${current.artist} ${current.album}`)
                        }
                      >
                        {current.album}
                      </button>
                    </>
                  )}
                </p>
                {(sourceName || playbackLabel) && (
                  <div className="album-metadata np-tech-tags">
                    {sourceName && <span>{sourceName}</span>}
                    {playbackLabel && <span>{playbackLabel}</span>}
                  </div>
                )}
              </div>
            </div>

            <section className="lyrics-column" aria-label="歌词舞台">
              <SyncedLyrics
                lines={adjustedLines}
                text={lyricText}
                onSeek={onSeek}
                large
                follow={follow}
                size={largeLyrics}
                lyricsFontSize={effectiveFontSize}
                lyricsActiveColor={lyricsActiveColor}
                lyricsInactiveOpacity={lyricsInactiveOpacity}
                lyricsAlign={lyricsAlign}
              />
            </section>

            {isQueueShown && (
              <section className="album-queue" ref={queueWrapRef}>
                <div className="section-heading">
                  <h3>
                    待播列表 <span>{playlist.length}</span>
                  </h3>
                  <div className="np-queue-head-tools">
                    {hasCurrentInQueue &&
                      playlist.length > 3 &&
                      !trimmedFilter && (
                        <Button
                          label="定位当前曲目"
                          onClick={locateCurrentInQueue}
                        >
                          <LocateFixed />
                        </Button>
                      )}
                    {playlist.length > 0 && !trimmedFilter && (
                      <Button
                        label={queueManaging ? "完成" : "批量管理"}
                        active={queueManaging}
                        disabled={queueSaving}
                        onClick={onToggleQueueManaging}
                      >
                        {queueManaging ? <Check /> : <ListChecks />}
                      </Button>
                    )}
                  </div>
                </div>
                {playlist.length > 0 && (
                  <div className="np-queue-search">
                    <Search aria-hidden="true" />
                    <input
                      aria-label="筛选待播曲目"
                      placeholder="按歌名、歌手或专辑检索…"
                      value={queueFilter}
                      onChange={(e) => setQueueFilter(e.target.value)}
                    />
                    {queueFilter && (
                      <button
                        type="button"
                        aria-label="清空待播筛选"
                        onClick={() => setQueueFilter("")}
                      >
                        <X />
                      </button>
                    )}
                  </div>
                )}
                {trimmedFilter ? (
                  <div className="playback-list" data-filtering="true">
                    <div className="playlist-items">
                      {filteredQueue.length ? (
                        filteredQueue.map((track) => (
                          <div
                            className={`playlist-item ${current.id === track.id ? "active" : ""}`}
                            draggable={false}
                            key={track.id}
                          >
                            <button
                              type="button"
                              className="playlist-song"
                              onClick={() => onPlayQueueTrack(track)}
                            >
                              <Cover track={track} />
                              <span>
                                <strong>{track.title}</strong>
                                <small>
                                  {track.artist}
                                  {track.album ? ` · ${track.album}` : ""}
                                </small>
                              </span>
                            </button>
                            {onPlayNextQueueTrack && (
                              <Button
                                label={`下一首播放 ${track.title}`}
                                disabled={
                                  queueSaving || current.id === track.id
                                }
                                onClick={() => onPlayNextQueueTrack(track)}
                              >
                                <ListStart />
                              </Button>
                            )}
                            <Button
                              label={`移除 ${track.title}`}
                              disabled={queueSaving}
                              onClick={() => onRemoveQueueTrack(track.id)}
                            >
                              <X />
                            </Button>
                          </div>
                        ))
                      ) : (
                        <p className="empty">没有匹配的待播曲目</p>
                      )}
                    </div>
                  </div>
                ) : (
                  queueListContent
                )}
              </section>
            )}
          </div>

          <footer
            className="now-controls full-player-controls studio-deck"
            aria-label="播放控制台"
          >
            <PlaybackTimeline
              duration={duration}
              onSeek={onSeek}
              ariaLabel="播放进度"
              className="timeline np-deck-timeline"
            />
            <div className="np-deck-bar">
              <div className="album-actions">
                <button
                  type="button"
                  className={`secondary np-action-btn ${isFavorite ? "active-fav" : ""}`}
                  aria-label="收藏当前曲目"
                  aria-pressed={isFavorite}
                  title={isFavorite ? "取消收藏" : "收藏当前曲目"}
                  onClick={onFavorite}
                >
                  <Heart />
                  <span>{isFavorite ? "已收藏" : "收藏"}</span>
                </button>
                <button
                  type="button"
                  className="secondary np-action-btn"
                  aria-label="选择下载音质"
                  title="选择下载音质"
                  onClick={onDownload}
                >
                  <Download />
                  <span>下载音质</span>
                </button>
                <button
                  type="button"
                  className={`secondary np-action-btn ${inQueue ? "in-queue" : ""}`}
                  aria-label="加入播放列表"
                  title={
                    inQueue ? "已在待播列表中（点击重新定位）" : "加入待播"
                  }
                  onClick={onAddToQueue}
                >
                  {inQueue ? <Check /> : <Plus />}
                  <span>{inQueue ? "已在待播" : "加入待播"}</span>
                </button>
                <button
                  type="button"
                  className="secondary np-action-btn"
                  aria-label="加入歌单"
                  title="加入歌单"
                  onClick={onAddToPlaylist}
                >
                  <ListMusic />
                  <span>加入歌单</span>
                </button>
              </div>

              <div className="transport">
                <Button
                  label="切换随机播放"
                  active={shuffle}
                  onClick={onToggleShuffle}
                >
                  <Shuffle />
                </Button>
                <Button label="上一首" onClick={() => onStep(-1)}>
                  <SkipBack />
                </Button>
                <button
                  type="button"
                  className="pause"
                  aria-label={playing ? "暂停" : "播放"}
                  disabled={loadingPlay}
                  onClick={onTogglePlay}
                >
                  {loadingPlay ? (
                    <span className="loading-dot" />
                  ) : playing ? (
                    <Pause />
                  ) : (
                    <Play />
                  )}
                </button>
                <Button label="下一首" onClick={() => onStep(1)}>
                  <SkipForward />
                </Button>
                <Button
                  label="切换列表循环"
                  active={repeat}
                  onClick={onToggleRepeat}
                >
                  <Repeat />
                </Button>
              </div>

              <div className="np-deck-right">{volumeControl}</div>
            </div>
          </footer>
        </>
      ) : (
        <div className="empty">尚未播放曲目。返回搜索，选择一首音乐。</div>
      )}
    </section>
  );
}
