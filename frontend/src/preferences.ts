import { api } from "./api.ts";
import {
  DEFAULT_APPEARANCE,
  HEX_ACCENT_COLOR,
  THEME_PRESETS,
} from "./theme-presets.ts";
import type { AppearanceState } from "./theme-presets.ts";

export interface AppearancePreferences {
  mode?: "system" | "light" | "dark";
  light_preset?: string;
  dark_preset?: string;
  custom_accent?: string;
  ambient_glow?: boolean;
  density?: "modern" | "compact";
}

export type LyricsAlign = "center" | "left";

export const LYRICS_FONT_SIZE_MIN = 14;
export const LYRICS_FONT_SIZE_MAX = 32;
export const DEFAULT_LYRICS_FONT_SIZE = 24;
export const LARGE_LYRICS_FONT_SIZE = 28;
export const LYRICS_INACTIVE_OPACITY_MIN = 0.2;
export const LYRICS_INACTIVE_OPACITY_MAX = 0.9;
export const DEFAULT_LYRICS_INACTIVE_OPACITY = 0.45;
export const DEFAULT_LYRICS_ALIGN: LyricsAlign = "center";

export const PLAYBACK_RATE_MIN = 0.5;
export const PLAYBACK_RATE_MAX = 3;
export const DEFAULT_PLAYBACK_RATE = 1;
export const PLAYBACK_RATE_STEP = 0.05;
export const PLAYBACK_RATE_PRESETS = [0.75, 1, 1.25, 1.5, 2, 2.5, 3] as const;

export const LYRICS_ACTIVE_COLOR_PRESETS = [
  { label: "跟随主题", color: "" },
  { label: "纯净白", color: "#FFFFFF" },
  { label: "流金琥珀", color: "#F59E0B" },
  { label: "晴空蓝", color: "#38BDF8" },
  { label: "薄荷绿", color: "#34D399" },
  { label: "玫瑰粉", color: "#FB7185" },
] as const;

export interface PlayerPreferences {
  volume?: number;
  shuffle?: boolean;
  repeat?: boolean;
  playback_rate?: number;
  follow_lyrics?: boolean;
  large_lyrics?: boolean;
  lyrics_font_size?: number;
  lyrics_active_color?: string;
  lyrics_inactive_opacity?: number;
  lyrics_align?: LyricsAlign;
}

export interface NavidromeViewPreferences {
  mode?: string;
  layout?: string;
}

export interface UIPreferences {
  appearance?: AppearancePreferences;
  navigation_order?: string[];
  player?: PlayerPreferences;
  library_tree_width?: number;
  navidrome_view?: NavidromeViewPreferences;
  dismissed_update?: string;
}

export type PreferenceKey = keyof UIPreferences;

export interface LocalPlayerState {
  volume: number;
  shuffle: boolean;
  repeat: boolean;
  playbackRate: number;
  follow: boolean;
  largeLyrics: boolean;
  lyricsFontSize: number;
  lyricsActiveColor: string;
  lyricsInactiveOpacity: number;
  lyricsAlign: LyricsAlign;
}

const PLAYER_STORAGE_KEY = "melokeep-player";
const DIRTY_STORAGE_KEY = "melokeep-prefs-dirty";
export const DEFAULT_PLAYER_PREFS: LocalPlayerState = {
  volume: 0.65,
  shuffle: false,
  repeat: true,
  playbackRate: DEFAULT_PLAYBACK_RATE,
  follow: true,
  largeLyrics: false,
  lyricsFontSize: DEFAULT_LYRICS_FONT_SIZE,
  lyricsActiveColor: "",
  lyricsInactiveOpacity: DEFAULT_LYRICS_INACTIVE_OPACITY,
  lyricsAlign: DEFAULT_LYRICS_ALIGN,
};

export function sanitizeLyricsFontSize(
  value: unknown,
  fallback: number = DEFAULT_LYRICS_FONT_SIZE,
): number {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "boolean") return fallback;
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(
    LYRICS_FONT_SIZE_MAX,
    Math.max(LYRICS_FONT_SIZE_MIN, Math.round(n)),
  );
}

export function sanitizeLyricsActiveColor(
  value: unknown,
  fallback = "",
): string {
  if (typeof value !== "string") return fallback;
  const trimmed = value.trim();
  if (trimmed === "") return "";
  return HEX_ACCENT_COLOR.test(trimmed) ? trimmed : fallback;
}

export function sanitizeLyricsInactiveOpacity(
  value: unknown,
  fallback: number = DEFAULT_LYRICS_INACTIVE_OPACITY,
): number {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "boolean") return fallback;
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  const clamped = Math.min(
    LYRICS_INACTIVE_OPACITY_MAX,
    Math.max(LYRICS_INACTIVE_OPACITY_MIN, n),
  );
  return Math.round(clamped * 100) / 100;
}

export function sanitizeLyricsAlign(
  value: unknown,
  fallback: LyricsAlign = DEFAULT_LYRICS_ALIGN,
): LyricsAlign {
  return value === "center" || value === "left" ? value : fallback;
}

export function sanitizePlaybackRate(
  value: unknown,
  fallback: number = DEFAULT_PLAYBACK_RATE,
): number {
  if (
    value === null ||
    value === undefined ||
    typeof value === "boolean" ||
    typeof value === "object"
  ) {
    return fallback;
  }
  if (typeof value === "string" && value.trim() === "") {
    return fallback;
  }
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  const clamped = Math.min(PLAYBACK_RATE_MAX, Math.max(PLAYBACK_RATE_MIN, n));
  return Math.round(clamped * 100) / 100;
}

export function formatPlaybackRate(rate: number): string {
  const clean = sanitizePlaybackRate(rate);
  return `${clean.toFixed(2).replace(/\.?0+$/, "")}x`;
}

export function normalizePlayerPrefs(
  raw: Partial<LocalPlayerState> | null | undefined,
  fallback: LocalPlayerState = DEFAULT_PLAYER_PREFS,
): LocalPlayerState {
  if (!raw || typeof raw !== "object") {
    return { ...fallback };
  }
  const vol = Number(raw.volume);
  const rawFontSize: unknown = raw.lyricsFontSize;
  const hasFontSize =
    rawFontSize !== undefined &&
    rawFontSize !== null &&
    rawFontSize !== "" &&
    typeof rawFontSize !== "boolean" &&
    Number.isFinite(Number(rawFontSize));
  const rawLargeLyrics =
    typeof raw.largeLyrics === "boolean" ? raw.largeLyrics : undefined;

  const lyricsFontSize = hasFontSize
    ? sanitizeLyricsFontSize(raw.lyricsFontSize, fallback.lyricsFontSize)
    : rawLargeLyrics !== undefined
      ? rawLargeLyrics
        ? LARGE_LYRICS_FONT_SIZE
        : DEFAULT_LYRICS_FONT_SIZE
      : fallback.lyricsFontSize;

  const largeLyrics = hasFontSize
    ? lyricsFontSize >= LARGE_LYRICS_FONT_SIZE
    : (rawLargeLyrics ?? fallback.largeLyrics);

  return {
    volume:
      typeof raw.volume !== "boolean" &&
      raw.volume !== null &&
      raw.volume !== undefined &&
      Number.isFinite(vol) &&
      vol >= 0 &&
      vol <= 1
        ? vol
        : fallback.volume,
    shuffle: typeof raw.shuffle === "boolean" ? raw.shuffle : fallback.shuffle,
    repeat: typeof raw.repeat === "boolean" ? raw.repeat : fallback.repeat,
    playbackRate: sanitizePlaybackRate(raw.playbackRate, fallback.playbackRate),
    follow: typeof raw.follow === "boolean" ? raw.follow : fallback.follow,
    largeLyrics,
    lyricsFontSize,
    lyricsActiveColor: sanitizeLyricsActiveColor(
      raw.lyricsActiveColor,
      fallback.lyricsActiveColor,
    ),
    lyricsInactiveOpacity: sanitizeLyricsInactiveOpacity(
      raw.lyricsInactiveOpacity,
      fallback.lyricsInactiveOpacity,
    ),
    lyricsAlign: sanitizeLyricsAlign(raw.lyricsAlign, fallback.lyricsAlign),
  };
}

export function parsePlayerPreferences(
  dto?: PlayerPreferences | null,
  fallback: LocalPlayerState = DEFAULT_PLAYER_PREFS,
): LocalPlayerState {
  if (!dto || typeof dto !== "object") return { ...fallback };
  return normalizePlayerPrefs(
    {
      volume: dto.volume,
      shuffle: dto.shuffle,
      repeat: dto.repeat,
      playbackRate: dto.playback_rate,
      follow: dto.follow_lyrics,
      largeLyrics: dto.large_lyrics,
      lyricsFontSize: dto.lyrics_font_size,
      lyricsActiveColor: dto.lyrics_active_color,
      lyricsInactiveOpacity: dto.lyrics_inactive_opacity,
      lyricsAlign: dto.lyrics_align,
    },
    fallback,
  );
}

export function toPlayerPreferences(
  state: LocalPlayerState,
): PlayerPreferences {
  const clean = normalizePlayerPrefs(state);
  return {
    volume: clean.volume,
    shuffle: clean.shuffle,
    repeat: clean.repeat,
    playback_rate: clean.playbackRate,
    follow_lyrics: clean.follow,
    large_lyrics: clean.largeLyrics,
    lyrics_font_size: clean.lyricsFontSize,
    lyrics_active_color: clean.lyricsActiveColor,
    lyrics_inactive_opacity: clean.lyricsInactiveOpacity,
    lyrics_align: clean.lyricsAlign,
  };
}

export function loadInitialPlayerPrefs(): LocalPlayerState {
  try {
    const raw = JSON.parse(
      globalThis.localStorage?.getItem(PLAYER_STORAGE_KEY) || "null",
    );
    if (raw && typeof raw === "object") {
      return normalizePlayerPrefs(raw, DEFAULT_PLAYER_PREFS);
    }
  } catch {
    /* Optional local preferences. */
  }
  return { ...DEFAULT_PLAYER_PREFS };
}

export function saveLocalPlayerPrefs(next: LocalPlayerState) {
  try {
    globalThis.localStorage?.setItem(
      PLAYER_STORAGE_KEY,
      JSON.stringify(normalizePlayerPrefs(next)),
    );
  } catch {
    /* Storage may be unavailable. */
  }
}

export function toAppearancePreferences(
  state: AppearanceState,
): AppearancePreferences {
  return {
    mode: state.mode,
    light_preset: state.lightPreset,
    dark_preset: state.darkPreset,
    custom_accent: state.customAccent,
    ambient_glow: state.ambientGlow,
    density: state.density,
  };
}

export function parseAppearancePreferences(
  dto?: AppearancePreferences,
  fallback: AppearanceState = DEFAULT_APPEARANCE,
): AppearanceState {
  if (!dto) return fallback;
  const lightValid = new Set(
    THEME_PRESETS.filter((p) => p.scheme === "light").map((p) => p.id),
  );
  const darkValid = new Set(
    THEME_PRESETS.filter((p) => p.scheme === "dark").map((p) => p.id),
  );
  return {
    mode:
      dto.mode === "light" || dto.mode === "dark" || dto.mode === "system"
        ? dto.mode
        : fallback.mode,
    lightPreset:
      dto.light_preset && lightValid.has(dto.light_preset as never)
        ? (dto.light_preset as AppearanceState["lightPreset"])
        : fallback.lightPreset,
    darkPreset:
      dto.dark_preset && darkValid.has(dto.dark_preset as never)
        ? (dto.dark_preset as AppearanceState["darkPreset"])
        : fallback.darkPreset,
    customAccent:
      typeof dto.custom_accent === "string"
        ? HEX_ACCENT_COLOR.test(dto.custom_accent)
          ? dto.custom_accent
          : dto.custom_accent === ""
            ? ""
            : fallback.customAccent
        : fallback.customAccent,
    ambientGlow:
      typeof dto.ambient_glow === "boolean"
        ? dto.ambient_glow
        : fallback.ambientGlow,
    density:
      dto.density === "compact" || dto.density === "modern"
        ? dto.density
        : fallback.density,
  };
}

function readDirtyKeys(): Partial<Record<PreferenceKey, boolean>> {
  try {
    const raw = JSON.parse(
      globalThis.localStorage?.getItem(DIRTY_STORAGE_KEY) || "null",
    );
    return raw && typeof raw === "object" ? raw : {};
  } catch {
    return {};
  }
}

function writeDirtyKeys(next: Partial<Record<PreferenceKey, boolean>>) {
  try {
    const clean = Object.fromEntries(
      Object.entries(next).filter(([, value]) => value === true),
    );
    if (Object.keys(clean).length === 0) {
      globalThis.localStorage?.removeItem(DIRTY_STORAGE_KEY);
    } else {
      globalThis.localStorage?.setItem(
        DIRTY_STORAGE_KEY,
        JSON.stringify(clean),
      );
    }
  } catch {
    /* Storage may be unavailable. */
  }
}

export function getDirtyPreferenceKeys(): Set<PreferenceKey> {
  return new Set(
    Object.entries(readDirtyKeys())
      .filter(([, value]) => value === true)
      .map(([key]) => key as PreferenceKey),
  );
}

let mutationSeq = 0;
const keySeq: Partial<Record<PreferenceKey, number>> = {};
let pendingPatch: UIPreferences | null = null;
let flushTimer: ReturnType<typeof setTimeout> | null = null;
let unloadBound = false;

export function getPreferencesMutationSeq(): number {
  return mutationSeq;
}

export function mergePreferencesPatch(
  base: UIPreferences,
  patch: UIPreferences,
): UIPreferences {
  return {
    ...base,
    ...patch,
    ...(base.appearance || patch.appearance
      ? { appearance: { ...base.appearance, ...patch.appearance } }
      : {}),
    ...(base.player || patch.player
      ? { player: { ...base.player, ...patch.player } }
      : {}),
    ...(base.navidrome_view || patch.navidrome_view
      ? { navidrome_view: { ...base.navidrome_view, ...patch.navidrome_view } }
      : {}),
  };
}

function takePendingPayload(): {
  payload: UIPreferences;
  snapshotSeq: Partial<Record<PreferenceKey, number>>;
} | null {
  if (flushTimer !== null) {
    clearTimeout(flushTimer);
    flushTimer = null;
  }
  const payload = pendingPatch;
  pendingPatch = null;
  if (!payload) return null;
  const snapshotSeq: Partial<Record<PreferenceKey, number>> = {};
  for (const key of Object.keys(payload) as PreferenceKey[]) {
    snapshotSeq[key] = keySeq[key] ?? 0;
  }
  return { payload, snapshotSeq };
}

function markClearedAfterSave(
  snapshotSeq: Partial<Record<PreferenceKey, number>>,
) {
  const dirty = readDirtyKeys();
  let changed = false;
  for (const [key, sent] of Object.entries(snapshotSeq)) {
    const k = key as PreferenceKey;
    if ((keySeq[k] ?? 0) === sent && dirty[k]) {
      delete dirty[k];
      changed = true;
    }
  }
  if (changed) writeDirtyKeys(dirty);
}

function sendPreferencesNow() {
  const taken = takePendingPayload();
  if (!taken) return;
  void api<UIPreferences>("/preferences", "PUT", taken.payload)
    .then(() => markClearedAfterSave(taken.snapshotSeq))
    .catch(() => {});
}

function flushWithKeepalive() {
  const taken = takePendingPayload();
  if (!taken) return;
  try {
    void fetch("/api/preferences", {
      method: "PUT",
      keepalive: true,
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-MeloKeep-Request": "1",
      },
      body: JSON.stringify(taken.payload),
    })
      .then((res) => {
        if (res.ok) markClearedAfterSave(taken.snapshotSeq);
      })
      .catch(() => {});
  } catch {
    /* Ignore unload errors. */
  }
}

function ensureUnloadListeners() {
  if (unloadBound || typeof window === "undefined") return;
  unloadBound = true;
  window.addEventListener("pagehide", flushWithKeepalive);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") flushWithKeepalive();
  });
}

type PreferencesPatchListener = (patch: UIPreferences) => void;
const patchListeners = new Set<PreferencesPatchListener>();

export function subscribePreferencesPatch(
  listener: PreferencesPatchListener,
): () => void {
  patchListeners.add(listener);
  return () => {
    patchListeners.delete(listener);
  };
}

export function scheduleSavePreferences(
  patch: UIPreferences,
  options: { debounceMs?: number } = {},
) {
  mutationSeq += 1;
  for (const listener of patchListeners) {
    listener(patch);
  }
  const dirty = readDirtyKeys();
  for (const key of Object.keys(patch) as PreferenceKey[]) {
    keySeq[key] = (keySeq[key] ?? 0) + 1;
    dirty[key] = true;
  }
  writeDirtyKeys(dirty);
  pendingPatch = pendingPatch
    ? mergePreferencesPatch(pendingPatch, patch)
    : patch;
  ensureUnloadListeners();
  if (flushTimer !== null) clearTimeout(flushTimer);
  const delay = options.debounceMs ?? 0;
  flushTimer = setTimeout(sendPreferencesNow, delay);
}

export function fetchServerPreferences(): Promise<UIPreferences> {
  return api<UIPreferences>("/preferences");
}
