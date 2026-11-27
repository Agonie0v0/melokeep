import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { flushSync } from "react-dom";
import {
  Check,
  Download,
  Expand,
  Heart,
  ListChecks,
  ListMusic,
  PanelRightClose,
  PanelRightOpen,
  Pause,
  Play,
  Repeat,
  Shuffle,
  SkipBack,
  SkipForward,
  Volume2,
  VolumeX,
  X,
} from "lucide-react";
import { Button, Cover } from "./components";
import {
  PlaybackRateControl,
  PlaybackTimeline,
  SyncedLyrics,
} from "./PlaybackWidgets";
import type { LyricLine } from "./lyrics";
import { DEFAULT_PLAYBACK_RATE, type LyricsAlign } from "./preferences";
import type { Track } from "./types";

function useMediaMatches(query: string): boolean {
  const [matches, setMatches] = useState<boolean>(() => {
    try {
      return typeof matchMedia === "function"
        ? matchMedia(query).matches
        : true;
    } catch {
      return true;
    }
  });
  useEffect(() => {
    if (typeof matchMedia !== "function") return;
    const mql = matchMedia(query);
    setMatches(mql.matches);
    const sync = () => {
      const next = mql.matches;
      flushSync(() => setMatches((prev) => (prev === next ? prev : next)));
    };
    mql.addEventListener("change", sync);
    window.addEventListener("resize", sync);
    return () => {
      mql.removeEventListener("change", sync);
      window.removeEventListener("resize", sync);
    };
  }, [query]);
  return matches;
}

export function PlayerDock({
  view,
  current,
  firstQueuedTrack = null,
  playing,
  loadingPlay,
  duration,
  shuffle,
  repeat,
  isFavorite,
  ambientGlow,
  volume,
  volumeControl,
  playbackRate = DEFAULT_PLAYBACK_RATE,
  onChangePlaybackRate,
  lines,
  lyricText,
  follow,
  largeLyrics,
  lyricsFontSize,
  lyricsActiveColor,
  lyricsInactiveOpacity,
  lyricsAlign,
  lyricsOffsetMs = 0,
  playbackLabel,
  drawerOpen,
  drawerTab,
  playlistCount,
  queueManaging,
  queueSaving,
  queueListContent,
  onSetViewPlayer,
  onPlayFirstQueued,
  onTogglePlay,
  onSeek,
  onStep,
  onToggleShuffle,
  onToggleRepeat,
  onFavorite,
  onDownload,
  onAddToPlaylist,
  onToggleFollow,
  onToggleLargeLyrics,
  onToggleDrawer,
  onSelectDrawerTab,
  onToggleQueueManaging,
  onOpenQueueModal,
}: {
  view: string;
  current: Track | null;
  firstQueuedTrack?: Track | null;
  playing: boolean;
  loadingPlay: boolean;
  duration: number;
  shuffle: boolean;
  repeat: boolean;
  isFavorite: boolean;
  ambientGlow: boolean;
  volume: number;
  volumeControl: ReactNode;
  playbackRate?: number;
  onChangePlaybackRate?: (nextRate: number) => void;
  lines: LyricLine[];
  lyricText: string;
  follow: boolean;
  largeLyrics: boolean;
  lyricsFontSize?: number;
  lyricsActiveColor?: string;
  lyricsInactiveOpacity?: number;
  lyricsAlign?: LyricsAlign;
  lyricsOffsetMs?: number;
  playbackLabel: string;
  drawerOpen: boolean;
  drawerTab: "lyrics" | "queue";
  playlistCount: number;
  queueManaging: boolean;
  queueSaving: boolean;
  queueListContent: ReactNode;
  onSetViewPlayer: () => void;
  onPlayFirstQueued?: () => void;
  onTogglePlay: () => void;
  onSeek: (seconds: number) => void;
  onStep: (direction: number) => void;
  onToggleShuffle: () => void;
  onToggleRepeat: () => void;
  onFavorite: () => void;
  onDownload: () => void;
  onAddToPlaylist: () => void;
  onToggleFollow: () => void;
  onToggleLargeLyrics: () => void;
  onToggleDrawer: (open?: boolean) => void;
  onSelectDrawerTab: (tab: "lyrics" | "queue") => void;
  onToggleQueueManaging: () => void;
  onOpenQueueModal: () => void;
}) {
  const isWideDock = useMediaMatches("(min-width: 1080px)");
  const isTinyDock = useMediaMatches("(max-width: 600px)");
  const [volOpen, setVolOpen] = useState(false);
  const volumeWrapRef = useRef<HTMLDetailsElement>(null);

  useEffect(() => {
    if (!volOpen) return;
    const onDocDown = (e: PointerEvent) => {
      if (
        volumeWrapRef.current &&
        !volumeWrapRef.current.contains(e.target as Node)
      ) {
        setVolOpen(false);
      }
    };
    const closeVol = () => setVolOpen(false);
    window.addEventListener("pointerdown", onDocDown);
    window.addEventListener("resize", closeVol);
    return () => {
      window.removeEventListener("pointerdown", onDocDown);
      window.removeEventListener("resize", closeVol);
    };
  }, [volOpen]);

  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (
        e.key === "Escape" &&
        !document.querySelector(
          'dialog[open], [role="dialog"][aria-modal="true"]',
        )
      ) {
        onToggleDrawer(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [drawerOpen, onToggleDrawer]);

  const adjustedLines = useMemo(() => {
    if (!lyricsOffsetMs) return lines;
    const shiftSec = lyricsOffsetMs / 1000;
    return lines.map((line) => ({
      ...line,
      time: Math.max(0, line.time + shiftSec),
    }));
  }, [lines, lyricsOffsetMs]);
  const activeTrack = current ?? firstQueuedTrack;
  const isStandby = !current && Boolean(firstQueuedTrack);
  const showDock = Boolean(activeTrack && view !== "player");

  return (
    <>
      {ambientGlow && current?.cover && (
        <div
          className="ambient-backdrop"
          aria-hidden="true"
          style={{
            backgroundImage: `url(${JSON.stringify(current.cover)})`,
          }}
        />
      )}

      {showDock && activeTrack && (
        <>
          <aside
            className="global-mini-player player-dock"
            aria-label="全局播放控制"
          >
            <div className="dock-track-zone">
              <button
                type="button"
                className="global-mini-track"
                aria-label={
                  isStandby
                    ? `播放待播列表：${activeTrack.title}`
                    : `进入正在播放：${activeTrack.title}`
                }
                title={
                  isStandby
                    ? `点击播放待播首曲（共 ${playlistCount} 首）`
                    : "点击进入全屏沉浸播放界面"
                }
                onClick={() =>
                  isStandby ? onPlayFirstQueued?.() : onSetViewPlayer()
                }
              >
                <Cover
                  track={activeTrack}
                  playing={Boolean(current && playing)}
                />
                <div className="global-mini-copy">
                  <div className="dock-title-row">
                    <strong>{activeTrack.title}</strong>
                  </div>
                  <span>
                    {isStandby
                      ? `${activeTrack.artist} · 待播 ${playlistCount} 首`
                      : playbackLabel && playbackLabel !== "尚未播放"
                        ? `${activeTrack.artist} · ${playbackLabel}`
                        : `${activeTrack.artist} · ${activeTrack.album || "未知专辑"}`}
                  </span>
                </div>
              </button>
              {current && isWideDock && (
                <div
                  className="dock-quick-actions"
                  role="group"
                  aria-label="当前曲目快捷操作"
                >
                  <button
                    type="button"
                    className={`icon-button favorite ${isFavorite ? "on" : ""}`}
                    aria-label="收藏当前曲目"
                    aria-pressed={isFavorite}
                    title={isFavorite ? "取消收藏" : "收藏当前曲目"}
                    onClick={onFavorite}
                  >
                    <Heart />
                  </button>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label="选择版本并下载"
                    title="选择版本并下载"
                    onClick={onDownload}
                  >
                    <Download />
                  </button>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label="加入歌单"
                    title="加入歌单"
                    onClick={onAddToPlaylist}
                  >
                    <ListMusic />
                  </button>
                </div>
              )}
            </div>

            <div className="dock-center-zone">
              <div className="global-mini-transport">
                {isWideDock && (
                  <Button
                    label="随机播放"
                    active={shuffle}
                    onClick={onToggleShuffle}
                  >
                    <Shuffle />
                  </Button>
                )}
                <Button
                  label="上一首全局曲目"
                  onClick={() =>
                    isStandby ? onPlayFirstQueued?.() : onStep(-1)
                  }
                >
                  <SkipBack />
                </Button>
                <button
                  type="button"
                  className="pause"
                  aria-label={playing ? "暂停全局播放" : "继续全局播放"}
                  disabled={loadingPlay}
                  onClick={() =>
                    isStandby ? onPlayFirstQueued?.() : onTogglePlay()
                  }
                >
                  {loadingPlay ? (
                    <span className="loading-dot" />
                  ) : playing ? (
                    <Pause />
                  ) : (
                    <Play />
                  )}
                </button>
                <Button
                  label="下一首全局曲目"
                  onClick={() =>
                    isStandby ? onPlayFirstQueued?.() : onStep(1)
                  }
                >
                  <SkipForward />
                </Button>
                {isWideDock && (
                  <Button
                    label="列表循环"
                    active={repeat}
                    onClick={onToggleRepeat}
                  >
                    <Repeat />
                  </Button>
                )}
              </div>
              <PlaybackTimeline
                className="timeline global-mini-timeline"
                ariaLabel="全局播放进度"
                duration={current ? duration : activeTrack.duration || 0}
                disabled={isStandby}
                onSeek={onSeek}
              />
            </div>

            <div className="global-mini-actions">
              <div className="global-mini-utilities">
                {current && !isTinyDock && (
                  <button
                    type="button"
                    className={`icon-button drawer-toggle-btn ${drawerOpen ? "active" : ""}`}
                    aria-label="歌词与待播抽屉"
                    aria-expanded={drawerOpen}
                    title={
                      drawerOpen ? "收起歌词与待播面板" : "展开歌词与待播面板"
                    }
                    onClick={() => onToggleDrawer()}
                  >
                    {drawerOpen ? <PanelRightClose /> : <PanelRightOpen />}
                  </button>
                )}
                <PlaybackRateControl
                  rate={playbackRate}
                  onChange={(r) => onChangePlaybackRate?.(r)}
                  compact
                />
                <details
                  ref={volumeWrapRef}
                  className="global-mini-volume"
                  open={volOpen}
                  onToggle={(e) => setVolOpen(e.currentTarget.open)}
                >
                  <summary aria-label="调节全局音量" title="调节音量">
                    {volume ? <Volume2 /> : <VolumeX />}
                  </summary>
                  {volOpen && (
                    <div className="global-mini-volume-panel">
                      {volumeControl}
                    </div>
                  )}
                </details>
                <Button label="播放列表" onClick={onOpenQueueModal}>
                  <ListMusic />
                </Button>
                {current && !isTinyDock && (
                  <Button label="展开全屏播放器" onClick={onSetViewPlayer}>
                    <Expand />
                  </Button>
                )}
              </div>
            </div>
          </aside>

          {current && drawerOpen && (
            <aside className="studio-drawer open" aria-label="歌词与待播列表">
              <div className="studio-drawer-head">
                <div
                  className="filter-group studio-drawer-tabs"
                  role="tablist"
                  aria-label="播放器抽屉切换"
                >
                  <button
                    type="button"
                    role="tab"
                    aria-selected={drawerTab === "lyrics"}
                    className={drawerTab === "lyrics" ? "active" : ""}
                    onClick={() => onSelectDrawerTab("lyrics")}
                  >
                    实时歌词
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={drawerTab === "queue"}
                    className={drawerTab === "queue" ? "active" : ""}
                    onClick={() => onSelectDrawerTab("queue")}
                  >
                    待播 ({playlistCount})
                  </button>
                </div>
                <div className="studio-drawer-head-tools">
                  {drawerTab === "lyrics" && (
                    <>
                      <button
                        type="button"
                        className={`np-tool-btn np-tool-icon ${largeLyrics ? "active" : ""}`}
                        aria-label="调整歌词字号"
                        aria-pressed={largeLyrics}
                        title="调整歌词字号"
                        onClick={onToggleLargeLyrics}
                      >
                        Aa
                      </button>
                      <button
                        type="button"
                        className={`np-tool-btn lyric-follow ${follow && lines.length > 0 ? "active" : ""}`}
                        aria-pressed={follow && lines.length > 0}
                        disabled={!lines.length}
                        onClick={onToggleFollow}
                      >
                        跟随
                      </button>
                    </>
                  )}
                  {drawerTab === "queue" && playlistCount > 0 && (
                    <Button
                      label={queueManaging ? "完成" : "批量管理"}
                      active={queueManaging}
                      disabled={queueSaving}
                      onClick={onToggleQueueManaging}
                    >
                      {queueManaging ? <Check /> : <ListChecks />}
                    </Button>
                  )}
                  <button
                    type="button"
                    className="icon-button"
                    aria-label="关闭歌词抽屉"
                    onClick={() => onToggleDrawer(false)}
                  >
                    <X />
                  </button>
                </div>
              </div>

              <div className="studio-drawer-track-strip">
                <Cover track={current} playing={playing} />
                <div className="studio-drawer-track-meta">
                  <strong>{current.title}</strong>
                  <span>
                    {current.artist} · {current.album || "未知专辑"}
                  </span>
                </div>
                <div className="studio-drawer-track-btns">
                  <button
                    type="button"
                    className={`icon-button favorite ${isFavorite ? "on" : ""}`}
                    aria-label="抽屉内收藏"
                    onClick={onFavorite}
                  >
                    <Heart />
                  </button>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label="抽屉内下载"
                    onClick={onDownload}
                  >
                    <Download />
                  </button>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label="抽屉内加入歌单"
                    onClick={onAddToPlaylist}
                  >
                    <ListMusic />
                  </button>
                </div>
              </div>

              <div className="studio-drawer-body">
                {drawerTab === "lyrics" ? (
                  <SyncedLyrics
                    lines={adjustedLines}
                    text={lyricText}
                    onSeek={onSeek}
                    follow={follow}
                    size={largeLyrics}
                    lyricsFontSize={lyricsFontSize}
                    lyricsActiveColor={lyricsActiveColor}
                    lyricsInactiveOpacity={lyricsInactiveOpacity}
                    lyricsAlign={lyricsAlign}
                  />
                ) : (
                  queueListContent
                )}
              </div>
            </aside>
          )}
        </>
      )}
    </>
  );
}
