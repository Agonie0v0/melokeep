import {
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type CSSProperties,
} from "react";
import { Lyrics } from "./components";
import { clock, type LyricLine } from "./lyrics";
import { playbackClock } from "./playback-clock";
import {
  DEFAULT_PLAYBACK_RATE,
  PLAYBACK_RATE_MAX,
  PLAYBACK_RATE_MIN,
  PLAYBACK_RATE_STEP,
  formatPlaybackRate,
  sanitizePlaybackRate,
  type LyricsAlign,
} from "./preferences";

export function PlaybackRateControl({
  rate,
  onChange,
  disabled = false,
  compact = false,
}: {
  rate: number;
  onChange: (nextRate: number) => void;
  disabled?: boolean;
  compact?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const cleanRate = sanitizePlaybackRate(rate);
  const rateLabel = formatPlaybackRate(cleanRate);
  const isCustom = Math.abs(cleanRate - DEFAULT_PLAYBACK_RATE) > 0.001;
  const progressPct =
    ((cleanRate - PLAYBACK_RATE_MIN) /
      (PLAYBACK_RATE_MAX - PLAYBACK_RATE_MIN)) *
    100;

  useEffect(() => {
    if (disabled && open) {
      setOpen(false);
    }
  }, [disabled, open]);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    const onResize = () => setOpen(false);
    window.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("resize", onResize);
    };
  }, [open]);

  return (
    <div
      ref={rootRef}
      className={`playback-rate-control ${compact ? "compact" : ""} ${open ? "is-open" : ""}`}
    >
      <button
        type="button"
        className={`playback-rate-trigger ${compact ? "compact" : ""} ${isCustom ? "active" : ""} ${open ? "is-open" : ""}`}
        aria-label={`播放倍速 ${rateLabel}`}
        aria-expanded={open && !disabled}
        aria-haspopup="dialog"
        title={`播放倍速：${rateLabel}`}
        disabled={disabled}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span>{rateLabel}</span>
      </button>
      {open && !disabled && (
        <div
          className="playback-rate-popover"
          role="dialog"
          aria-label="播放倍速"
        >
          <div className="playback-rate-popover-head">
            <span className="playback-rate-popover-title">播放倍速</span>
            <div className="playback-rate-popover-meta">
              <strong className="playback-rate-current">{rateLabel}</strong>
              {isCustom && (
                <button
                  type="button"
                  className="playback-rate-reset"
                  onClick={() => onChange(DEFAULT_PLAYBACK_RATE)}
                >
                  恢复 1x
                </button>
              )}
            </div>
          </div>
          <div className="playback-rate-slider-wrap">
            <input
              type="range"
              min={PLAYBACK_RATE_MIN}
              max={PLAYBACK_RATE_MAX}
              step={PLAYBACK_RATE_STEP}
              value={cleanRate}
              style={{ "--progress": `${progressPct}%` } as CSSProperties}
              aria-label="播放倍速滑块"
              aria-valuetext={rateLabel}
              onChange={(e) =>
                onChange(sanitizePlaybackRate(Number(e.target.value)))
              }
            />
            <div className="playback-rate-ticks" aria-hidden="true">
              <span>0.5x</span>
              <span>1x</span>
              <span>2x</span>
              <span>3x</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export function usePlaybackTime(): number {
  return useSyncExternalStore(
    playbackClock.subscribe,
    playbackClock.get,
    playbackClock.get,
  );
}

export function PlaybackTimeline({
  duration,
  onSeek,
  disabled = false,
  ariaLabel = "播放进度",
  className = "timeline",
}: {
  duration: number;
  onSeek: (seconds: number) => void;
  disabled?: boolean;
  ariaLabel?: string;
  className?: string;
}) {
  const time = usePlaybackTime();
  const max = duration || 1;
  const clamped = Math.min(time, max);
  const pct = duration ? Math.min(100, (time / duration) * 100) : 0;

  return (
    <div className={className}>
      <span>{clock(time)}</span>
      <input
        aria-label={ariaLabel}
        type="range"
        min="0"
        max={max}
        step=".1"
        value={clamped}
        style={{ "--progress": `${pct}%` } as CSSProperties}
        onChange={(e) => onSeek(Number(e.target.value))}
        disabled={disabled}
      />
      <span>{clock(duration)}</span>
    </div>
  );
}

export function SyncedLyrics({
  lines,
  text,
  onSeek,
  large = false,
  follow = true,
  size = false,
  lyricsFontSize,
  lyricsActiveColor,
  lyricsInactiveOpacity,
  lyricsAlign,
}: {
  lines: LyricLine[];
  text: string;
  onSeek: (seconds: number) => void;
  large?: boolean;
  follow?: boolean;
  size?: boolean;
  lyricsFontSize?: number;
  lyricsActiveColor?: string;
  lyricsInactiveOpacity?: number;
  lyricsAlign?: LyricsAlign;
}) {
  const time = usePlaybackTime();
  return (
    <Lyrics
      lines={lines}
      text={text}
      time={time}
      onSeek={onSeek}
      large={large}
      follow={follow}
      size={size}
      lyricsFontSize={lyricsFontSize}
      lyricsActiveColor={lyricsActiveColor}
      lyricsInactiveOpacity={lyricsInactiveOpacity}
      lyricsAlign={lyricsAlign}
    />
  );
}
