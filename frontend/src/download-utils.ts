import type { DeleteDownloadResult, DownloadJob } from "./types";

export interface DownloadProcessingAction {
  jobId: string;
  label: string;
  emphasis: "review" | "apply" | "inspect";
}

export function getDownloadProcessingAction(
  processing?: DownloadJob["processing"] | null,
): DownloadProcessingAction | null {
  const jobId = processing?.id?.trim();
  if (!jobId) return null;
  switch (processing?.status?.trim()) {
    case "needs_review":
      return { jobId, label: "前往确认", emphasis: "review" };
    case "ready":
    case "preview_ready":
      return { jobId, label: "前往应用", emphasis: "apply" };
    case "needs_attention":
    case "failed":
    case "partial":
      return { jobId, label: "查看处理", emphasis: "inspect" };
    default:
      return null;
  }
}

export function hasDeletableDownloadFile(
  job: Pick<DownloadJob, "file">,
): boolean {
  return Boolean(job.file?.trim());
}

export function countDeletableDownloadFiles(
  targets: readonly Pick<DownloadJob, "file">[],
): number {
  return targets.filter(hasDeletableDownloadFile).length;
}

export function formatDownloadRemovalNotice(
  removedCount: number,
  deleteFile: boolean,
  deletedFileCount: number,
): string {
  if (!deleteFile) {
    return "下载记录已移除，已下载文件保留";
  }
  if (deletedFileCount > 0) {
    return `已移除 ${removedCount} 条下载记录，并同步删除 ${deletedFileCount} 个本地音频文件`;
  }
  return `已移除 ${removedCount} 条下载记录（未发现可删除的本地文件）`;
}

export function formatDownloadRemovalError(errors: readonly string[]): string {
  if (!errors.length) return "";
  if (errors.length === 1) return errors[0];
  return `${errors[0]}（共 ${errors.length} 项失败）`;
}

export interface RemoveDownloadJobsSummary {
  removedIds: string[];
  removedCount: number;
  deletedFileCount: number;
  deletedTrackIds: Set<string>;
  deletedFilePaths: Set<string>;
  errors: string[];
}

export async function removeDownloadJobsBatch(
  targets: readonly Pick<DownloadJob, "id" | "file">[],
  deleteFile: boolean,
  deleteJob: (
    job: Pick<DownloadJob, "id" | "file">,
    shouldDeleteFile: boolean,
  ) => Promise<DeleteDownloadResult | null | undefined>,
  onRemoved?: (jobId: string) => void,
): Promise<RemoveDownloadJobsSummary> {
  const removedIds: string[] = [];
  const deletedTrackIds = new Set<string>();
  const deletedFilePaths = new Set<string>();
  const errors: string[] = [];
  let deletedFileCount = 0;

  for (const job of targets) {
    const shouldDeleteFile = Boolean(
      deleteFile && hasDeletableDownloadFile(job),
    );
    try {
      const res = await deleteJob(job, shouldDeleteFile);
      removedIds.push(job.id);
      onRemoved?.(job.id);
      if (res?.file_deleted === true) {
        deletedFileCount += 1;
        const cleanPath = job.file?.trim();
        if (cleanPath) {
          deletedFilePaths.add(cleanPath);
        }
      }
      if (Array.isArray(res?.track_ids)) {
        for (const rawId of res.track_ids) {
          if (typeof rawId === "string" && rawId.trim()) {
            deletedTrackIds.add(rawId);
          }
        }
      }
    } catch (error) {
      errors.push(
        error instanceof Error ? error.message : String(error || "删除失败"),
      );
    }
  }

  return {
    removedIds,
    removedCount: removedIds.length,
    deletedFileCount,
    deletedTrackIds,
    deletedFilePaths,
    errors,
  };
}
