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

export interface PlayerPreferences {
  volume?: number;
  shuffle?: boolean;
  repeat?: boolean;
  follow_lyrics?: boolean;
  large_lyrics?: boolean;
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
  follow: boolean;
  largeLyrics: boolean;
}

const PLAYER_STORAGE_KEY = "melokeep-player";
const DIRTY_STORAGE_KEY = "melokeep-prefs-dirty";
export const DEFAULT_PLAYER_PREFS: LocalPlayerState = {
  volume: 0.65,
  shuffle: false,
  repeat: true,
  follow: true,
  largeLyrics: false,
};

export function loadInitialPlayerPrefs(): LocalPlayerState {
  try {
    const raw = JSON.parse(
      globalThis.localStorage?.getItem(PLAYER_STORAGE_KEY) || "null",
    );
    if (raw && typeof raw === "object") {
      const vol = Number(raw.volume);
      return {
        volume:
          Number.isFinite(vol) && vol >= 0 && vol <= 1
            ? vol
            : DEFAULT_PLAYER_PREFS.volume,
        shuffle:
          typeof raw.shuffle === "boolean"
            ? raw.shuffle
            : DEFAULT_PLAYER_PREFS.shuffle,
        repeat:
          typeof raw.repeat === "boolean"
            ? raw.repeat
            : DEFAULT_PLAYER_PREFS.repeat,
        follow:
          typeof raw.follow === "boolean"
            ? raw.follow
            : DEFAULT_PLAYER_PREFS.follow,
        largeLyrics:
          typeof raw.largeLyrics === "boolean"
            ? raw.largeLyrics
            : DEFAULT_PLAYER_PREFS.largeLyrics,
      };
    }
  } catch {
    /* Optional local preferences. */
  }
  return { ...DEFAULT_PLAYER_PREFS };
}

export function saveLocalPlayerPrefs(next: LocalPlayerState) {
  try {
    globalThis.localStorage?.setItem(PLAYER_STORAGE_KEY, JSON.stringify(next));
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
