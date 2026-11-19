import { useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Music2, X, Play, Plus, ListMusic, MoreHorizontal } from "lucide-react";
import { activeLine, clock, type LyricLine } from "./lyrics";
import type { Track } from "./types";
export function Cover({
  track,
  large = false,
  playing = false,
  onPlay,
}: {
  track?: (Pick<Track, "cover"> & Partial<Pick<Track, "title">>) | null;
  large?: boolean;
  playing?: boolean;
  onPlay?: () => void;
}) {
  const Element = onPlay ? "button" : "span";
  return (
    <Element
      className={`${large ? "album-art" : "cover"}${onPlay ? " song-cover-play" : ""}${playing ? " is-playing" : ""}`}
      type={onPlay ? "button" : undefined}
      aria-label={onPlay ? `播放封面 ${track?.title || "歌曲"}` : undefined}
      title={onPlay ? `播放 ${track?.title || "歌曲"}` : undefined}
      onClick={onPlay}
    >
      <span className="record-fallback" aria-hidden="true">
        <span>
          <Music2 />
        </span>
      </span>
      {track?.cover && (
        <img
          key={track.cover}
          src={track.cover}
          alt=""
          loading="lazy"
          referrerPolicy="no-referrer"
          onLoad={(e) => e.currentTarget.classList.add("loaded")}
          onError={(e) => (e.currentTarget.style.display = "none")}
        />
      )}
      {playing && !large && (
        <span className="cover-playing-eq" aria-hidden="true">
          <span className="eq-bars">
            <span />
            <span />
            <span />
          </span>
        </span>
      )}
    </Element>
  );
}
export function SongTitle({
  track,
  onPlay,
  className = "",
}: {
  track: Pick<Track, "title">;
  onPlay: () => void;
  className?: string;
}) {
  return (
    <button
      type="button"
      className={`song-title-play ${className}`}
      aria-label={`播放歌名 ${track.title}`}
      title={`播放 ${track.title}`}
      onClick={onPlay}
    >
      {track.title}
    </button>
  );
}
export function Button({
  label,
  children,
  onClick,
  active,
  disabled = false,
  text,
}: {
  label: string;
  children: ReactNode;
  onClick: () => void;
  active?: boolean;
  disabled?: boolean;
  text?: string;
}) {
  return (
    <button
      className={`icon-button ${active ? "active" : ""}`}
      aria-label={label}
      aria-pressed={active}
      title={label}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
      {text && <span className="action-label">{text}</span>}
    </button>
  );
}
export function SongActions({
  track,
  play,
  queue,
  savePlaylist,
  children,
}: {
  track: Track;
  play: () => void;
  queue: () => void;
  savePlaylist?: () => void;
  children?: ReactNode;
}) {
  const id = useId();
  const anchor = `--song-${id.replace(/[^a-zA-Z0-9]/g, "")}`;
  return (
    <div className="library-actions song-actions">
      <Button label={`播放 ${track.title}`} text="播放" onClick={play}>
        <Play />
      </Button>
      <Button label={`加入待播 ${track.title}`} text="加入待播" onClick={queue}>
        <Plus />
      </Button>
      {savePlaylist && (
        <Button
          label={`加入歌单 ${track.title}`}
          text="加入歌单"
          onClick={savePlaylist}
        >
          <ListMusic />
        </Button>
      )}
      {children && (
        <>
          <button
            type="button"
            className="icon-button song-more-trigger"
            aria-label={`更多歌曲操作 ${track.title}`}
            popoverTarget={id}
            style={{ anchorName: anchor } as React.CSSProperties}
          >
            <MoreHorizontal />
            <span className="action-label">更多</span>
          </button>
          <div
            id={id}
            popover="auto"
            className="song-action-menu"
            style={{ positionAnchor: anchor } as React.CSSProperties}
            onClick={(event) => {
              if (
                (event.target as HTMLElement).closest(
                  "button:not(:disabled), a",
                )
              )
                event.currentTarget.hidePopover();
            }}
          >
            {children}
          </div>
        </>
      )}
    </div>
  );
}
export function Lyrics({
  lines,
  text,
  time,
  onSeek,
  large = false,
  follow = true,
  size = false,
}: {
  lines: LyricLine[];
  text: string;
  time: number;
  onSeek: (s: number) => void;
  large?: boolean;
  follow?: boolean;
  size?: boolean;
}) {
  const current = activeLine(lines, time);
  const container = useRef<HTMLDivElement>(null);
  const userPausedUntil = useRef(0);
  const lastCenteredIndex = useRef(-1);

  useEffect(() => {
    userPausedUntil.current = 0;
    lastCenteredIndex.current = -1;
    if (container.current) container.current.scrollTop = 0;
  }, [lines, text]);

  useEffect(() => {
    const center = (smooth: boolean) => {
      const box = container.current;
      if (!box) return;
      if (large) {
        box.style.setProperty(
          "--lyrics-center",
          `${Math.max(44, Math.round(box.clientHeight / 2 - 44))}px`,
        );
      }
      if (!follow || current < 0 || Date.now() < userPausedUntil.current) {
        return;
      }
      const element = box.querySelector<HTMLElement>('[data-active="true"]');
      if (!element) return;
      const targetTop =
        box.scrollTop +
        element.getBoundingClientRect().top -
        box.getBoundingClientRect().top -
        box.clientHeight / 2 +
        element.offsetHeight / 2;
      const reduceMotion =
        typeof matchMedia === "function" &&
        matchMedia("(prefers-reduced-motion: reduce)").matches;
      box.scrollTo({
        top: Math.max(0, targetTop),
        behavior: smooth && !reduceMotion ? "smooth" : "auto",
      });
    };

    const lineChanged = lastCenteredIndex.current !== current;
    lastCenteredIndex.current = current;
    center(lineChanged);

    const observer = new ResizeObserver(() => center(false));
    if (container.current) observer.observe(container.current);
    return () => observer.disconnect();
  }, [current, follow, size, lines, large]);

  return (
    <div
      ref={container}
      className={`${large ? "full-lyrics" : "compact-lyrics"} ${size ? "large" : ""} ${lines.length ? "" : "untimed"}`}
      onWheel={() => {
        userPausedUntil.current = Date.now() + 3500;
      }}
      onTouchStart={() => {
        userPausedUntil.current = Date.now() + 3500;
      }}
    >
      {lines.length ? (
        lines.map((line, i) => {
          const dist = current >= 0 ? Math.min(3, Math.abs(i - current)) : 3;
          return (
            <button
              key={`${line.time}-${i}`}
              className={i === current ? "active" : ""}
              data-active={i === current}
              data-distance={current >= 0 ? dist : undefined}
              onClick={() => {
                userPausedUntil.current = 0;
                onSeek(line.time);
              }}
            >
              <span>{line.text}</span>
              {large && (
                <small className="lyric-time">{clock(line.time)}</small>
              )}
            </button>
          );
        })
      ) : (
        <div className="lyric-empty">{text || "选择曲目后加载歌词"}</div>
      )}
    </div>
  );
}

const modalStack: symbol[] = [];

function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(
    container.querySelectorAll<HTMLElement>(
      'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])',
    ),
  ).filter(
    (element) =>
      element.getClientRects().length > 0 &&
      getComputedStyle(element).visibility !== "hidden",
  );
}

export function Modal({
  title,
  close,
  className,
  heading,
  children,
}: {
  title: string;
  close?: () => void;
  className?: string;
  heading?: ReactNode;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const closeRef = useRef(close);
  closeRef.current = close;

  useEffect(() => {
    const token = Symbol("modal");
    modalStack.push(token);
    const previousFocus = document.activeElement as HTMLElement | null;
    if (ref.current && !ref.current.contains(document.activeElement)) {
      ref.current.focus({ preventScroll: true });
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (modalStack[modalStack.length - 1] !== token) return;
      if (event.key === "Escape") {
        event.preventDefault();
        closeRef.current?.();
        return;
      }
      if (event.key !== "Tab" || !ref.current) return;
      const focusable = getFocusableElements(ref.current);
      if (!focusable.length) {
        event.preventDefault();
        ref.current.focus({ preventScroll: true });
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement as HTMLElement | null;
      if (!active || !ref.current.contains(active)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && active === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      const index = modalStack.lastIndexOf(token);
      if (index !== -1) modalStack.splice(index, 1);
      if (previousFocus && previousFocus.isConnected) {
        previousFocus.focus({ preventScroll: true });
      }
    };
  }, []);

  const backdropClass = className
    ? `modal-backdrop ${className}-backdrop`
    : "modal-backdrop";
  const dialogClass = className ? `modal-dialog ${className}` : "modal-dialog";

  return createPortal(
    <div className={backdropClass}>
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className={dialogClass}
      >
        <div className="dialog-heading">
          {heading || <h2>{title}</h2>}
          {close && (
            <Button label="关闭" onClick={close}>
              <X />
            </Button>
          )}
        </div>
        {children}
      </div>
    </div>,
    document.body,
  );
}
