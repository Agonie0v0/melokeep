import type { DailyRecommendItem, DailyRecommendSeed, Track } from "./types";

const WEEKDAY_LABELS = [
  "周日",
  "周一",
  "周二",
  "周三",
  "周四",
  "周五",
  "周六",
] as const;

export interface DailyCalendarInfo {
  day: string;
  monthLabel: string;
  weekdayLabel: string;
  fullDate: string;
}

/** Parse YYYY-MM-DD in local time to avoid UTC midnight day-shift issues. */
export function parseDailyCalendar(
  dateText?: string,
  fallbackDate: Date = new Date(),
): DailyCalendarInfo {
  const clean = (dateText || "").trim();
  const match = clean.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  let target = fallbackDate;
  if (match) {
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    const candidate = new Date(year, month - 1, day);
    if (
      candidate.getFullYear() === year &&
      candidate.getMonth() === month - 1 &&
      candidate.getDate() === day
    ) {
      target = candidate;
    }
  }
  const yyyy = target.getFullYear();
  const mm = target.getMonth() + 1;
  const dd = target.getDate();
  const dayPadded = String(dd).padStart(2, "0");
  const monthPadded = String(mm).padStart(2, "0");
  return {
    day: dayPadded,
    monthLabel: `${mm}月`,
    weekdayLabel: WEEKDAY_LABELS[target.getDay()] || "",
    fullDate: `${yyyy}-${monthPadded}-${dayPadded}`,
  };
}

/** Return true if a daily recommended track can be downloaded into the server library. */
export function isDailyItemEnqueueable(item: DailyRecommendItem): boolean {
  if (!item || item.in_library || !item.track) return false;
  const id = item.track.id?.trim() || "";
  if (!id || item.track.pay_required) return false;
  const source = (item.track.source || "").trim().toLowerCase();
  return source !== "local" && source !== "navidrome" && source !== "device";
}

/** Append or update one streamed item by track.id without duplicates. */
export function mergeDailyItem(
  items: DailyRecommendItem[],
  nextItem: DailyRecommendItem,
): DailyRecommendItem[] {
  const id = nextItem.track?.id?.trim();
  if (!id) return items;
  const idx = items.findIndex((entry) => entry.track.id === id);
  if (idx === -1) return [...items, nextItem];
  const next = [...items];
  next[idx] = nextItem;
  return next;
}

/** Build deduplicated cold-start taste seeds from saved favorites. */
export function buildDailySeedsFromFavorites(
  favorites: Track[],
  maxCount = 20,
): DailyRecommendSeed[] {
  const seen = new Set<string>();
  const seeds: DailyRecommendSeed[] = [];
  for (const track of favorites) {
    const title = (track.title || "").trim();
    if (!title) continue;
    const artist = (track.artist || "").trim();
    const key = `${title.toLowerCase()}|${artist.toLowerCase()}`;
    if (seen.has(key)) continue;
    seen.add(key);
    seeds.push({
      title,
      artist,
      album: (track.album || "").trim() || undefined,
      weight: 1.8,
    });
    if (seeds.length >= maxCount) break;
  }
  return seeds;
}

/** Check whether browser playback crossed the meaningful listening threshold. */
export function shouldReportWebListeningHistory(
  playedSeconds: number,
  durationSeconds: number,
): boolean {
  if (!Number.isFinite(playedSeconds) || playedSeconds <= 0) return false;
  if (playedSeconds >= 30) return true;
  if (
    Number.isFinite(durationSeconds) &&
    durationSeconds > 10 &&
    playedSeconds / durationSeconds >= 0.5
  ) {
    return true;
  }
  return false;
}
