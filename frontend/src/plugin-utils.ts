import type {
  AvailableSource,
  MetadataSource,
  PluginSettingField,
  SourceCapability,
} from "./types";

const MASKED_SECRET_VALUES = new Set([
  "[已隐藏]",
  "__MELOKEEP_HIDDEN__",
  "[hidden]",
  "***",
  "••••••••",
]);

export const FALLBACK_METADATA_SOURCE_LABELS: Record<string, string> = {
  musicbrainz: "MusicBrainz",
  itunes: "iTunes",
  acoustid: "AcoustID",
  lrclib: "LRCLIB",
};

export function isSecretFieldType(type?: string): boolean {
  const normalized = (type || "").trim().toLowerCase();
  return normalized === "secret" || normalized === "password";
}

export function isMaskedSecretValue(value?: string): boolean {
  if (!value) return false;
  const trimmed = value.trim();
  return MASKED_SECRET_VALUES.has(trimmed) || trimmed.startsWith("[已隐藏]");
}

export function parseAllowedOrigins(raw: string): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const part of raw.split(/[\n,]+/)) {
    const item = part.trim();
    if (!item || seen.has(item)) continue;
    seen.add(item);
    result.push(item);
  }
  return result;
}

export function formatAllowedOrigins(origins?: string[]): string {
  return Array.isArray(origins) ? origins.join("\n") : "";
}

export function buildPluginSettingsPayload(
  fields: PluginSettingField[] | undefined,
  drafts: Record<string, string>,
  currentSettings: Record<string, string>,
  clearedKeys: string[],
): Record<string, string> {
  const payload: Record<string, string> = {};
  const clearedSet = new Set(clearedKeys);

  for (const field of fields || []) {
    const key = field.key;
    if (isSecretFieldType(field.type)) {
      if (clearedSet.has(key)) {
        payload[key] = "";
        continue;
      }
      const draft = drafts[key] ?? "";
      if (draft.trim()) {
        payload[key] = draft;
        continue;
      }
      const existing = currentSettings[key] ?? "";
      if (isMaskedSecretValue(existing)) {
        // Keep the backend's masked placeholder intact so untouched secrets are preserved.
        payload[key] = existing;
      } else {
        payload[key] = "";
      }
      continue;
    }

    if (field.type === "boolean") {
      const value = drafts[key] ?? currentSettings[key] ?? field.default_value;
      payload[key] = value === "true" ? "true" : "false";
      continue;
    }

    payload[key] = drafts[key] ?? currentSettings[key] ?? "";
  }

  return payload;
}

export function pluginCapabilityBadges(
  cap?: Partial<SourceCapability> | null,
): { key: string; label: string }[] {
  if (!cap) return [];
  const items: { key: string; label: string }[] = [];
  if (cap.search) items.push({ key: "search", label: "搜索" });
  if (cap.playlist) items.push({ key: "playlist", label: "歌单" });
  if (cap.episodes) items.push({ key: "episodes", label: "分集" });
  if (cap.lyrics) items.push({ key: "lyrics", label: "歌词" });
  if (cap.metadata) items.push({ key: "metadata", label: "刮削" });
  return items;
}

export function sourceEngineKey(
  source: Pick<AvailableSource, "id" | "engine_name">,
): string {
  return source.engine_name || source.id;
}

export function isPluginSource(
  source: Pick<AvailableSource, "origin" | "plugin_id">,
): boolean {
  return source.origin === "plugin" || Boolean(source.plugin_id);
}

export function pickPlaylistImportSource(
  sources: AvailableSource[],
  current: string,
): string {
  const playlistSources = sources.filter((s) =>
    Boolean(s.capability?.playlist),
  );
  if (!playlistSources.length) return "";
  if (current && playlistSources.some((s) => s.id === current)) return current;
  return playlistSources[0].id;
}

export function formatMetadataSourceLabel(
  id: string,
  metadataSources?: Pick<MetadataSource, "id" | "label">[],
): string {
  if (!id) return "";
  const dynamic = metadataSources?.find((item) => item.id === id)?.label;
  if (dynamic) return dynamic;
  return FALLBACK_METADATA_SOURCE_LABELS[id] || id;
}
