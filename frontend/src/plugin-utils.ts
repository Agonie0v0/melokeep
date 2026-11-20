import type {
  AvailableSource,
  MetadataSource,
  PluginArchiveEntryInspect,
  PluginSettingField,
  ProviderPlugin,
  SourceCapability,
} from "./types";

export const MAX_PLUGIN_BYTES = 512 * 1024;
export const MAX_PLUGIN_ARCHIVE_BYTES = 16 * 1024 * 1024;
export const ARCHIVE_UNCATEGORIZED = "未分类";
export const ARCHIVE_FILTER_ALL = "__all__";
export const ARCHIVE_FILTER_INVALID = "__invalid__";

export const PLUGIN_STATUS_ALL = "all" as const;
export const PLUGIN_STATUS_ENABLED = "enabled" as const;
export const PLUGIN_STATUS_DISABLED = "disabled" as const;
export type PluginStatusFilter =
  | typeof PLUGIN_STATUS_ALL
  | typeof PLUGIN_STATUS_ENABLED
  | typeof PLUGIN_STATUS_DISABLED;

export interface ArchiveCategoryBucket {
  category: string;
  count: number;
  ids: string[];
}

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

export function isPluginArchiveFile(
  file: Pick<File, "name" | "type">,
): boolean {
  const name = (file.name || "").trim().toLowerCase();
  if (name.endsWith(".zip")) return true;
  const type = (file.type || "").trim().toLowerCase();
  return (
    type === "application/zip" ||
    type === "application/x-zip-compressed" ||
    type === "multipart/x-zip"
  );
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
}

export function archiveEntryCategory(
  entry: Pick<PluginArchiveEntryInspect, "ok" | "manifest">,
): string {
  if (!entry.ok || !entry.manifest) return "";
  const category = (entry.manifest.category || "").trim();
  return category || ARCHIVE_UNCATEGORIZED;
}

export function getValidArchivePluginIds(
  entries: PluginArchiveEntryInspect[],
): string[] {
  const seen = new Set<string>();
  const ids: string[] = [];
  for (const entry of entries) {
    const id = entry.ok ? (entry.manifest?.id || "").trim() : "";
    if (!id || seen.has(id)) continue;
    seen.add(id);
    ids.push(id);
  }
  return ids;
}

export function getArchiveCategoryBuckets(
  entries: PluginArchiveEntryInspect[],
): ArchiveCategoryBucket[] {
  const buckets = new Map<string, ArchiveCategoryBucket>();
  for (const entry of entries) {
    if (!entry.ok || !entry.manifest?.id) continue;
    const id = entry.manifest.id.trim();
    if (!id) continue;
    const category = archiveEntryCategory(entry);
    let bucket = buckets.get(category);
    if (!bucket) {
      bucket = { category, count: 0, ids: [] };
      buckets.set(category, bucket);
    }
    if (!bucket.ids.includes(id)) {
      bucket.ids.push(id);
      bucket.count = bucket.ids.length;
    }
  }
  const list = Array.from(buckets.values());
  list.sort((a, b) => {
    if (a.category === ARCHIVE_UNCATEGORIZED) return 1;
    if (b.category === ARCHIVE_UNCATEGORIZED) return -1;
    return 0;
  });
  return list;
}

export function filterArchiveEntries(
  entries: PluginArchiveEntryInspect[],
  filter: string,
): PluginArchiveEntryInspect[] {
  if (!filter || filter === ARCHIVE_FILTER_ALL) return entries;
  if (filter === ARCHIVE_FILTER_INVALID) {
    return entries.filter((entry) => !entry.ok);
  }
  return entries.filter(
    (entry) => entry.ok && archiveEntryCategory(entry) === filter,
  );
}

export function toggleArchiveIdSelection(
  selectedIds: string[],
  id: string,
): string[] {
  if (!id) return selectedIds;
  return selectedIds.includes(id)
    ? selectedIds.filter((item) => item !== id)
    : [...selectedIds, id];
}

export function toggleArchiveCategorySelection(
  selectedIds: string[],
  categoryIds: string[],
): string[] {
  if (!categoryIds.length) return selectedIds;
  const selectedSet = new Set(selectedIds);
  const allSelected = categoryIds.every((id) => selectedSet.has(id));
  if (allSelected) {
    const removeSet = new Set(categoryIds);
    return selectedIds.filter((id) => !removeSet.has(id));
  }
  const next = [...selectedIds];
  for (const id of categoryIds) {
    if (!selectedSet.has(id)) {
      selectedSet.add(id);
      next.push(id);
    }
  }
  return next;
}

export function getInstalledPluginCategories(
  plugins: ProviderPlugin[],
): string[] {
  const seen = new Set<string>();
  const categories: string[] = [];
  let hasUncategorized = false;

  for (const plugin of plugins) {
    const category = (plugin.manifest?.category || "").trim();
    if (!category) {
      hasUncategorized = true;
      continue;
    }
    if (!seen.has(category)) {
      seen.add(category);
      categories.push(category);
    }
  }

  if (hasUncategorized) {
    categories.push(ARCHIVE_UNCATEGORIZED);
  }
  return categories;
}

export function filterInstalledPlugins(
  plugins: ProviderPlugin[],
  query: string,
  status: PluginStatusFilter,
  category: string,
): ProviderPlugin[] {
  const needle = query.trim().toLowerCase();
  return plugins.filter((plugin) => {
    if (status === PLUGIN_STATUS_ENABLED && !plugin.enabled) return false;
    if (status === PLUGIN_STATUS_DISABLED && plugin.enabled) return false;

    if (category && category !== ARCHIVE_FILTER_ALL) {
      const cat =
        (plugin.manifest?.category || "").trim() || ARCHIVE_UNCATEGORIZED;
      if (cat !== category) return false;
    }

    if (needle) {
      const haystack = [
        plugin.id,
        plugin.manifest?.name || "",
        plugin.manifest?.description || "",
        plugin.manifest?.category || "",
      ]
        .join("\n")
        .toLowerCase();
      if (!haystack.includes(needle)) return false;
    }

    return true;
  });
}
