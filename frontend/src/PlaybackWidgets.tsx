import { useSyncExternalStore, type CSSProperties } from "react";
import { Lyrics } from "./components";
import { clock, type LyricLine } from "./lyrics";
import { playbackClock } from "./playback-clock";

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
}: {
  lines: LyricLine[];
  text: string;
  onSeek: (seconds: number) => void;
  large?: boolean;
  follow?: boolean;
  size?: boolean;
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
    />
  );
}
