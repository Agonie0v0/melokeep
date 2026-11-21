import type { Quality, Track } from "./types";

const KIB = 1024;
const MIB = 1024 * KIB;
const GIB = 1024 * MIB;

const LOSSLESS_CODECS = new Set([
  "FLAC",
  "ALAC",
  "APE",
  "WAV",
  "AIFF",
  "AIF",
  "WV",
  "TTA",
  "TAK",
  "PCM",
  "DSF",
  "DFF",
]);

const CODEC_KEYWORD_RE =
  /\b(flac|alac|ape|wav|aiff|aif|wv|tta|tak|pcm|dsf|dff|mp3|aac|m4a|ogg|opus|wma)\b/i;

const INTERNAL_QUALITY_WORDS_RE =
  /\b(verified|candidate|resolved|full|probed_audio|declared|engine|probe|catalog_claim|unverified_extension|advertised|download_only|unavailable|ready|preview_only|login_required|preview|source|lossless|lossy)\b/gi;

export interface FriendlyQuality {
  title: string;
  bitrateText: string | null;
  sizeText: string | null;
  subtitle: string;
  badges: string[];
  isLossless: boolean;
  isPreviewOnly: boolean;
  needsLogin: boolean;
}

function positiveFinite(value: number | null | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0
    ? value
    : null;
}

function extractCodecUpper(q: Pick<Quality, "codec" | "label">): string {
  const rawCodec = (q.codec || "").trim().replace(/^\./, "").toUpperCase();
  if (rawCodec) return rawCodec;
  const match = (q.label || "").match(CODEC_KEYWORD_RE);
  return match ? match[1].toUpperCase() : "";
}

function isLosslessQuality(
  q: Pick<Quality, "lossless" | "codec" | "label">,
): boolean {
  if (q.lossless) return true;
  const codecUpper = extractCodecUpper(q);
  return Boolean(codecUpper && LOSSLESS_CODECS.has(codecUpper));
}

export function formatFileSizeBytes(sizeBytes: number): string {
  if (!Number.isFinite(sizeBytes) || sizeBytes <= 0) return "0 B";
  if (sizeBytes >= GIB) return `${(sizeBytes / GIB).toFixed(2)} GB`;
  if (sizeBytes >= MIB) return `${(sizeBytes / MIB).toFixed(1)} MB`;
  if (sizeBytes >= KIB) return `${Math.round(sizeBytes / KIB)} KB`;
  return `${Math.round(sizeBytes)} B`;
}

export function resolveQualitySizeText(
  q: Quality,
  track?: Pick<Track, "duration" | "size_bytes" | "size_label" | "qualities">,
): string | null {
  const qBytes = positiveFinite(q.size_bytes);
  if (qBytes !== null) return formatFileSizeBytes(qBytes);
  const qLabel = q.size_label?.trim();
  if (qLabel) return qLabel;

  if (track && (track.qualities?.length ?? 0) <= 1) {
    const tBytes = positiveFinite(track.size_bytes);
    if (tBytes !== null) return formatFileSizeBytes(tBytes);
    const tLabel = track.size_label?.trim();
    if (tLabel) return tLabel;
  }

  const duration = positiveFinite(track?.duration);
  if (duration !== null) {
    const kbps = positiveFinite(q.bitrate) ?? (isLosslessQuality(q) ? 950 : 0);
    if (kbps > 0) {
      const estimatedBytes = Math.round((duration * kbps * 1000) / 8);
      if (estimatedBytes > 0) {
        return `约 ${formatFileSizeBytes(estimatedBytes)}`;
      }
    }
  }
  return null;
}

export function resolveTrackDisplaySize(track: Track): string {
  const tBytes = positiveFinite(track.size_bytes);
  if (tBytes !== null) return formatFileSizeBytes(tBytes);
  const tLabel = track.size_label?.trim();
  if (tLabel) return tLabel;

  const primary = track.qualities?.[0];
  if (primary) {
    const pBytes = positiveFinite(primary.size_bytes);
    if (pBytes !== null) return formatFileSizeBytes(pBytes);
    const pLabel = primary.size_label?.trim();
    if (pLabel) return pLabel;
  }

  const duration = positiveFinite(track.duration);
  if (duration !== null) {
    let kbps = 0;
    if (track.qualities?.length) {
      for (const item of track.qualities) {
        const itemKbps =
          positiveFinite(item.bitrate) ?? (isLosslessQuality(item) ? 950 : 0);
        if (itemKbps > 0) {
          kbps = itemKbps;
          break;
        }
      }
    } else if (track.source === "navidrome" || track.source === "local") {
      const pbCodec = (track.playback?.codec || "").trim().toUpperCase();
      kbps =
        positiveFinite(track.playback?.bitrate) ??
        (pbCodec && LOSSLESS_CODECS.has(pbCodec) ? 950 : 0);
    }
    if (kbps > 0) {
      const estimatedBytes = Math.round((duration * kbps * 1000) / 8);
      if (estimatedBytes > 0) {
        return `约 ${formatFileSizeBytes(estimatedBytes)}`;
      }
    }
  }
  return "—";
}

export function formatFriendlyQuality(
  q: Quality,
  track?: Track,
): FriendlyQuality {
  const codecUpper = extractCodecUpper(q);
  const bitrate = positiveFinite(q.bitrate);
  const rawLabel = (q.label || "").trim();
  const isLossless = isLosslessQuality(q);

  let tier: string;
  if (/母带|master|spatial|空间|全景|dsd/i.test(rawLabel)) {
    tier = "超清母带";
  } else if (
    /hi-?res|hires|24\s*bit|高解析/i.test(rawLabel) ||
    (bitrate ?? 0) >= 1412
  ) {
    tier = "高解析无损";
  } else if (isLossless) {
    tier = "无损音质";
  } else if ((bitrate ?? 0) >= 300) {
    tier = "极高音质";
  } else if ((bitrate ?? 0) >= 192) {
    tier = "较高音质";
  } else if ((bitrate ?? 0) > 0) {
    tier = "标准音质";
  } else if (/\b(?:hq|high)\b|高品质|较高/i.test(rawLabel)) {
    tier = "较高音质";
  } else if (/\b(?:sq|standard|normal)\b|标准|普通/i.test(rawLabel)) {
    tier = "标准音质";
  } else {
    const cleaned = rawLabel
      .replace(INTERNAL_QUALITY_WORDS_RE, " ")
      .replace(CODEC_KEYWORD_RE, " ")
      .replace(/\b\d+\s*k(?:bps)?\b/gi, " ")
      .replace(/[\s·/|_-]+/g, " ")
      .trim();
    tier = cleaned || "标准音质";
  }

  const title = codecUpper ? `${tier} · ${codecUpper}` : tier;
  const bitrateText = bitrate !== null ? `${Math.round(bitrate)} kbps` : null;
  const sizeText = resolveQualitySizeText(q, track);
  const subtitle = [bitrateText, sizeText].filter(Boolean).join(" · ");

  const avail = (q.availability || "").trim().toLowerCase();
  const isPreviewOnly =
    avail === "preview_only" || avail === "preview" || /试听/.test(rawLabel);
  const needsLogin = avail === "login_required";
  const badges: string[] = [];
  if (isPreviewOnly) badges.push("仅试听片段");
  if (needsLogin) badges.push("需要账号权限");

  return {
    title,
    bitrateText,
    sizeText,
    subtitle,
    badges,
    isLossless,
    isPreviewOnly,
    needsLogin,
  };
}

export function trackSize(track: Track): number | null {
  if (
    typeof track.size_bytes === "number" &&
    Number.isFinite(track.size_bytes) &&
    track.size_bytes >= 0
  )
    return track.size_bytes;
  const match = track.size_label?.match(
    /^\s*([\d.]+)\s*(bytes?|[kmgt]i?b)\s*$/i,
  );
  if (!match) return null;
  const unit = match[2].toLowerCase();
  const power = { k: 1, m: 2, g: 3, t: 4 }[unit[0]] || 0;
  const size = Number(match[1]) * 1024 ** power;
  return Number.isFinite(size) ? size : null;
}

function qualityScore(track: Track): number | null {
  const known = trackQualities(track).filter(
    (q) => q.codec || q.lossless || q.bitrate,
  );
  if (!known.length) return null;
  return Math.max(
    ...known.map((q) =>
      q.lossless
        ? 1_000_000 + (q.bitrate || 0)
        : q.bitrate || (q.codec ? 0 : -1),
    ),
  );
}

function trackQualities(track: Track) {
  // Library streams are original files; search previews may be transcoded.
  if (track.source !== "navidrome" && track.source !== "local")
    return track.qualities;
  const codec = track.playback.codec?.trim().toLowerCase();
  if (!codec) return [];
  return [
    {
      codec,
      bitrate: track.playback.bitrate,
      lossless: LOSSLESS_CODECS.has(codec.toUpperCase()),
    },
  ];
}

function numeric(a: number | null, b: number | null, descending: boolean) {
  // Unknown values always follow known values, in either direction.
  if (a === null) return b === null ? 0 : 1;
  if (b === null) return -1;
  return descending ? b - a : a - b;
}

export function selectResults(
  tracks: Track[],
  source: string,
  quality: string,
  readyOnly: boolean,
  sort: string,
): Track[] {
  return tracks
    .filter((track) => {
      const qualities = trackQualities(track);
      return (
        (source === "all" || track.source === source) &&
        (!readyOnly || track.playback.state === "ready") &&
        (quality !== "lossless" || qualities.some((q) => q.lossless)) &&
        (quality !== "lossy" ||
          qualities.some((q) => !q.lossless && !!q.codec)) &&
        (quality !== "mp3" ||
          qualities.some((q) => q.codec.toLowerCase() === "mp3")) &&
        (quality !== "unknown" ||
          !qualities.length ||
          qualities.every((q) => !q.codec))
      );
    })
    .sort((a, b) => {
      if (sort === "name") return a.title.localeCompare(b.title, "zh");
      if (sort === "artist") return a.artist.localeCompare(b.artist, "zh");
      if (sort === "source") return a.source.localeCompare(b.source, "zh");
      if (sort.startsWith("duration"))
        return numeric(
          a.duration > 0 ? a.duration : null,
          b.duration > 0 ? b.duration : null,
          sort.endsWith("desc"),
        );
      if (sort.startsWith("size"))
        return numeric(trackSize(a), trackSize(b), sort.endsWith("desc"));
      if (sort.startsWith("quality"))
        return numeric(qualityScore(a), qualityScore(b), sort.endsWith("desc"));
      return 0;
    });
}
