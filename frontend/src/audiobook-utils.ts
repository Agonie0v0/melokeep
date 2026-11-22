import type { BatchQualityPolicy, Track } from "./types";

export interface BatchDownloadPayloadOptions {
  preferredQuality: BatchQualityPolicy;
  audiobookLayout: boolean;
  skipAutoOrganize: boolean;
  bookTitle?: string;
  totalEpisodes?: number;
}

export interface BatchDownloadPayloadItem {
  track_id: string;
  quality_id: string;
  preferred_quality: BatchQualityPolicy;
  skip_auto_organize: boolean;
  layout?: "audiobook";
  book_title?: string;
  chapter_index?: number;
  chapter_total?: number;
}

export function isCollectionTrack(track: Pick<Track, "episodes">): boolean {
  return (
    typeof track.episodes === "number" &&
    Number.isFinite(track.episodes) &&
    (track.episodes > 0 || track.episodes === -1)
  );
}

export function formatEpisodeBadge(episodes?: number): string {
  return episodes && episodes > 0 ? `整部 · ${episodes} 集` : "整部有声书";
}

export function getEpisodeNumber(
  track: Pick<Track, "playlist_index">,
  fallbackIndex: number,
): number {
  return track.playlist_index && track.playlist_index > 0
    ? track.playlist_index
    : fallbackIndex + 1;
}

export function selectEpisodeRange<T extends Pick<Track, "playlist_index">>(
  episodes: T[],
  start: number,
  end: number,
): T[] {
  if (!episodes.length) return [];
  const safeStart = Number.isFinite(start) ? Math.trunc(start) : 1;
  const safeEnd = Number.isFinite(end) ? Math.trunc(end) : episodes.length;
  const low = Math.min(safeStart, safeEnd);
  const high = Math.max(safeStart, safeEnd);
  return episodes.filter((item, idx) => {
    const epNum = getEpisodeNumber(item, idx);
    return epNum >= low && epNum <= high;
  });
}

export function shouldUseBatchDownloadModal(
  items: Track[],
  isAudiobook?: boolean,
): boolean {
  return Boolean(isAudiobook || items.length > 10);
}

export function chunkArray<T>(items: T[], size = 200): T[][] {
  if (!items.length) return [];
  const chunkSize = Number.isFinite(size) && size > 0 ? Math.trunc(size) : 200;
  const result: T[][] = [];
  for (let offset = 0; offset < items.length; offset += chunkSize) {
    result.push(items.slice(offset, offset + chunkSize));
  }
  return result;
}

export function buildBatchDownloadPayloads(
  items: Track[],
  opts: BatchDownloadPayloadOptions,
): BatchDownloadPayloadItem[] {
  const totalEpisodes =
    typeof opts.totalEpisodes === "number" &&
    Number.isFinite(opts.totalEpisodes) &&
    opts.totalEpisodes > 0
      ? Math.trunc(opts.totalEpisodes)
      : items.length;
  const trimmedBookTitle = opts.bookTitle?.trim() || "";

  return items.map((item, idx) => {
    const base: BatchDownloadPayloadItem = {
      track_id: item.id,
      quality_id: "",
      preferred_quality: opts.preferredQuality,
      skip_auto_organize: Boolean(opts.skipAutoOrganize),
    };
    if (opts.audiobookLayout) {
      base.layout = "audiobook";
      base.book_title =
        trimmedBookTitle || item.album?.trim() || item.title?.trim() || "";
      base.chapter_index = getEpisodeNumber(item, idx);
      base.chapter_total = totalEpisodes;
    }
    return base;
  });
}
