import type { SelfUpdateStatus, SystemUpdateInfo } from "./types";

export const DOCKER_SOCK_COMPOSE_LINE =
  "- /var/run/docker.sock:/var/run/docker.sock";

export const SELF_UPDATE_STAGE_LABELS: Record<string, string> = {
  idle: "准备更新",
  pulling: "正在拉取最新镜像",
  recreating: "正在重建容器",
  restarting: "正在重启服务",
  up_to_date: "已是最新版本",
  failed: "更新失败",
};

export type SelfUpdateActionMode =
  "hidden" | "primary" | "secondary" | "disabled_hint";

export function getSelfUpdateActionMode(
  info: SystemUpdateInfo | null,
): SelfUpdateActionMode {
  const selfUpdate = info?.self_update;
  if (!selfUpdate) return "hidden";
  if (!selfUpdate.supported) return "disabled_hint";
  return info?.has_update ? "primary" : "secondary";
}

export function formatSelfUpdateState(
  status: SelfUpdateStatus | null | undefined,
): {
  stage: string;
  label: string;
  detail: string;
  progress: number;
  showProgress: boolean;
  isRunning: boolean;
  isRestarting: boolean;
  isFailed: boolean;
  isUpToDate: boolean;
} {
  const stage = (status?.stage || (status?.in_progress ? "pulling" : "idle"))
    .trim()
    .toLowerCase();
  const label = SELF_UPDATE_STAGE_LABELS[stage] || stage || "更新中";
  const rawProgress = Number(status?.progress);
  const clampedProgress = Number.isFinite(rawProgress)
    ? Math.min(100, Math.max(0, Math.round(rawProgress)))
    : stage === "recreating"
      ? 92
      : stage === "restarting"
        ? 98
        : stage === "up_to_date"
          ? 100
          : 0;
  const isRestarting = stage === "restarting";
  const isFailed = stage === "failed";
  const isUpToDate = stage === "up_to_date";
  const isRunning = Boolean(
    status?.in_progress ||
    stage === "pulling" ||
    stage === "recreating" ||
    stage === "restarting",
  );
  const showProgress = isRunning || isUpToDate;
  const detail =
    status?.error ||
    status?.message ||
    (stage === "restarting"
      ? "容器正在平滑重启，请稍候…"
      : stage === "recreating"
        ? "正在应用新镜像并重建容器…"
        : stage === "pulling"
          ? "正在从镜像仓库下载最新层…"
          : "");

  return {
    stage,
    label,
    detail,
    progress: clampedProgress,
    showProgress,
    isRunning,
    isRestarting,
    isFailed,
    isUpToDate,
  };
}
