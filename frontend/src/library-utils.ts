import type {
  LibraryJob,
  LibraryJobItem,
  LibraryRefreshResult,
  MetadataSource,
} from "./types";

export const RUNNING_JOB_STATUSES = new Set([
  "queued",
  "scanning",
  "identifying",
  "tagging",
  "organizing",
  "indexing",
  "applying",
  "restoring",
  "running",
]);

export const FINISHED_JOB_STATUSES = new Set([
  "complete",
  "completed",
  "failed",
  "partial",
  "cancelled",
  "restored",
]);

export function isRunningJobStatus(status: string): boolean {
  return RUNNING_JOB_STATUSES.has((status || "").trim().toLowerCase());
}

export function isFinishedJobStatus(status: string): boolean {
  return FINISHED_JOB_STATUSES.has((status || "").trim().toLowerCase());
}

function normalizeRelFolder(raw: string): string {
  return raw.replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
}

function joinRootAndRel(root: string, rel: string): string {
  const cleanRoot = root.replace(/[\\/]+$/, "");
  if (!cleanRoot) return rel;
  const sep = cleanRoot.includes("\\") ? "\\" : "/";
  const normalizedRel = sep === "\\" ? rel.replace(/\//g, "\\") : rel;
  return `${cleanRoot}${sep}${normalizedRel}`;
}

/**
 * Extracts a backend `scope_dir` string from the MusicLibrary active folder key
 * (supports JSON tuple `["root","relFolder"]`, `"all"`, and `"root::relFolder"`).
 */
export function extractScopeDirFromFolderKey(
  folderKey: string,
  options?: {
    root?: string;
    folder?: string;
    hasMultipleRoots?: boolean;
  },
): string {
  const trimmedKey = (folderKey || "").trim();
  if (!trimmedKey || trimmedKey === "all") {
    return "";
  }

  let root = (options?.root || "").trim();
  let rel = options?.folder !== undefined ? options.folder : "";

  if (options?.folder === undefined) {
    if (trimmedKey.startsWith("[")) {
      try {
        const parsed: unknown = JSON.parse(trimmedKey);
        if (Array.isArray(parsed) && parsed.length >= 2) {
          if (!root && typeof parsed[0] === "string") {
            root = parsed[0].trim();
          }
          rel = typeof parsed[1] === "string" ? parsed[1] : "";
        }
      } catch {
        rel = trimmedKey;
      }
    } else if (trimmedKey.includes("::")) {
      const idx = trimmedKey.indexOf("::");
      if (!root) root = trimmedKey.slice(0, idx).trim();
      rel = trimmedKey.slice(idx + 2);
    } else {
      rel = trimmedKey;
    }
  }

  const cleanRel = normalizeRelFolder(rel);
  if (cleanRel === "" || cleanRel === ".") {
    return options?.hasMultipleRoots && root ? root : "";
  }
  if (options?.hasMultipleRoots && root) {
    return joinRootAndRel(root, cleanRel);
  }
  return cleanRel;
}

export interface JobProgressInfo {
  running: boolean;
  processed: number;
  total: number;
  percent: number;
  indeterminate: boolean;
}

export function getJobProgress(
  job: Pick<LibraryJob, "status" | "processed" | "total">,
): JobProgressInfo {
  const status = (job.status || "").trim().toLowerCase();
  const running = isRunningJobStatus(status);
  const rawTotal = Number(job.total);
  const total =
    Number.isFinite(rawTotal) && rawTotal > 0 ? Math.floor(rawTotal) : 0;
  const rawProcessed = Number(job.processed);
  const safeProcessed =
    Number.isFinite(rawProcessed) && rawProcessed > 0
      ? Math.floor(rawProcessed)
      : 0;
  const processed = total > 0 ? Math.min(total, safeProcessed) : safeProcessed;
  const percent = total > 0 ? Math.round((processed / total) * 100) : 0;
  const indeterminate =
    running &&
    (total === 0 ||
      (processed === 0 &&
        ["queued", "scanning", "identifying"].includes(status)));

  return {
    running,
    processed,
    total,
    percent,
    indeterminate,
  };
}

export function buildKeepExistingChoices(
  items?: Pick<LibraryJobItem, "id" | "status">[],
): Record<string, string> {
  const choices: Record<string, string> = {};
  if (!items?.length) return choices;
  for (const item of items) {
    if (item.status === "needs_review" && item.id) {
      choices[item.id] = "existing";
    }
  }
  return choices;
}

export function countPendingReviewItems(
  items?: Pick<LibraryJobItem, "status">[],
): number {
  if (!items?.length) return 0;
  let count = 0;
  for (const item of items) {
    if (item.status === "needs_review") count += 1;
  }
  return count;
}

export function hasReadyJobItems(
  items?: Pick<LibraryJobItem, "status">[],
): boolean {
  if (!items?.length) return false;
  return items.some(
    (item) => item.status === "ready" || item.status === "preview_ready",
  );
}

export function formatLibraryRefreshNotice(
  res: Partial<LibraryRefreshResult> | null | undefined,
): string {
  if (!res) return "已刷新音乐库";
  const scanned = Number(res.scanned_files) || 0;
  const updated = Number(res.updated_or_new) || 0;
  const missing = Number(res.marked_missing) || 0;
  const base = `已刷新音乐库：共扫描 ${scanned} 首，新增/更新 ${updated} 首，移除 ${missing} 条失效记录`;
  const unhealthy = Array.isArray(res.unhealthy_roots)
    ? res.unhealthy_roots.filter((r) => typeof r === "string" && r.trim())
    : [];
  return unhealthy.length
    ? `${base}（异常目录：${unhealthy.join("、")}）`
    : base;
}

export const MIN_MATCH_SCORE_MIN = 50;
export const MIN_MATCH_SCORE_MAX = 100;
export const DEFAULT_MIN_MATCH_SCORE = 85;
export const RECOMMENDED_FALLBACK_SOURCE_IDS = [
  "itunes",
  "musicbrainz",
  "lrclib",
] as const;

export function sanitizeMinMatchScore(
  value: unknown,
  fallback = DEFAULT_MIN_MATCH_SCORE,
): number {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "boolean") return fallback;
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(
    MIN_MATCH_SCORE_MAX,
    Math.max(MIN_MATCH_SCORE_MIN, Math.round(n)),
  );
}

export function describeMinMatchScore(score: number): string {
  const clean = sanitizeMinMatchScore(score);
  if (clean >= 85) {
    return "严格匹配：仅高置信度自动通过，降低误匹配风险";
  }
  if (clean >= 75) {
    return "推荐均衡：兼顾自动整理覆盖率与准确度";
  }
  return "宽松匹配：更多曲目自动整理，建议留意结果复核";
}

export type ToastTone = "success" | "warning" | "error" | "info";

const TOAST_ERROR_RE = /失败|错误|断开|无效|超时|无法|异常(?!目录)/;
const TOAST_WARNING_RE =
  /异常目录|跳过|付费|需登录|请先|请输入|正在|暂不可|警告/;
const TOAST_SUCCESS_RE =
  /已加入|已删除|已移除|已刷新|已完成|已保存|已更新|已关闭|已开启|已设置|已勾选|已全选|已安排|成功/;

export function inferToastTone(message: string): ToastTone {
  const text = (message || "").trim();
  if (!text) return "info";
  if (TOAST_ERROR_RE.test(text)) return "error";
  if (TOAST_WARNING_RE.test(text)) return "warning";
  if (TOAST_SUCCESS_RE.test(text)) return "success";
  return "info";
}

export function pickRecommendedMetadataSourceIds(
  metadataSources?: MetadataSource[],
  maxPluginSources = 3,
): string[] {
  if (!metadataSources?.length) {
    return [...RECOMMENDED_FALLBACK_SOURCE_IDS];
  }
  const explicitPlugins = metadataSources.filter(
    (source) => source.builtin === false && source.recommended === true,
  );
  const explicitBuiltins = metadataSources.filter(
    (source) => source.builtin !== false && source.recommended === true,
  );
  const pluginLimit = Math.max(0, Math.floor(maxPluginSources));
  const pickedPlugins = explicitPlugins.slice(0, pluginLimit);
  if (pickedPlugins.length > 0) {
    const hasStructuredPlugin = pickedPlugins.some((source) => {
      const fields = new Set(source.fields || []);
      return fields.has("title") && fields.has("album");
    });
    const pickedPluginIds = pickedPlugins.map((source) => source.id);
    const fallbackBuiltinIds = explicitBuiltins
      .filter((source) => !(hasStructuredPlugin && source.id === "musicbrainz"))
      .map((source) => source.id);
    return [...pickedPluginIds, ...fallbackBuiltinIds];
  }
  if (explicitBuiltins.length > 0) {
    return explicitBuiltins.map((source) => source.id);
  }
  const fallbackSet = new Set<string>(RECOMMENDED_FALLBACK_SOURCE_IDS);
  const matched = metadataSources
    .filter((source) => source.builtin !== false && fallbackSet.has(source.id))
    .map((source) => source.id);
  return matched.length ? matched : [...RECOMMENDED_FALLBACK_SOURCE_IDS];
}

export function pickBuiltinRecommendedMetadataSourceIds(
  metadataSources?: MetadataSource[],
): string[] {
  if (!metadataSources?.length) {
    return [...RECOMMENDED_FALLBACK_SOURCE_IDS];
  }
  const explicitBuiltins = metadataSources
    .filter((source) => source.builtin !== false && source.recommended === true)
    .map((source) => source.id);
  if (explicitBuiltins.length > 0) {
    return explicitBuiltins;
  }
  const builtinIdSet = new Set(
    metadataSources
      .filter((source) => source.builtin !== false)
      .map((source) => source.id),
  );
  const matched = RECOMMENDED_FALLBACK_SOURCE_IDS.filter((id) =>
    builtinIdSet.has(id),
  );
  return matched.length ? [...matched] : [...RECOMMENDED_FALLBACK_SOURCE_IDS];
}

export function pickBuiltinMetadataSourceIds(
  metadataSources?: MetadataSource[],
): string[] {
  if (!metadataSources?.length) return [];
  return metadataSources
    .filter((source) => source.builtin !== false)
    .map((source) => source.id);
}

export function partitionMetadataSources(
  metadataSources: MetadataSource[] = [],
  query = "",
): {
  builtin: MetadataSource[];
  plugins: MetadataSource[];
} {
  const needle = query.trim().toLowerCase();
  const filtered = needle
    ? metadataSources.filter((source) =>
        [
          source.id,
          source.label,
          source.description,
          source.recommend_reason,
          ...(source.fields || []),
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase()
          .includes(needle),
      )
    : metadataSources;

  return {
    builtin: filtered.filter((source) => source.builtin !== false),
    plugins: filtered.filter((source) => source.builtin === false),
  };
}

export function buildMetadataSourcesSummary(
  selectedSources: string[],
  metadataSources?: MetadataSource[],
): string {
  const catalog = metadataSources ?? [];
  const selectedSet = new Set(selectedSources);
  const totalCount = catalog.length;
  const selectedItems = catalog.length
    ? catalog.filter((source) => selectedSet.has(source.id))
    : selectedSources.map((id) => ({ id, label: id }));
  const selectedCount = selectedItems.length;
  const previewLabels = selectedItems.slice(0, 3).map((item) => item.label);
  const extraCount = selectedItems.length - previewLabels.length;
  const preview = previewLabels.length
    ? `${previewLabels.join(" · ")}${extraCount > 0 ? ` 等 ${selectedCount} 个` : ""}`
    : "未选择";
  return `刮削资料来源 · 已选 ${selectedCount} / ${totalCount} 个（${preview}）`;
}
