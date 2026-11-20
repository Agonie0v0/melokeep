import type { LibraryJob, LibraryJobItem, LibraryRefreshResult } from "./types";

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
