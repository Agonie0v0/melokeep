import { useEffect, useState } from "react";
import {
  ArrowUpCircle,
  Check,
  Copy,
  ExternalLink,
  GitCommitHorizontal,
  RefreshCw,
} from "lucide-react";
import { AIMarkdown } from "./AIMarkdown";
import {
  applySystemUpdate,
  checkSystemHealth,
  fetchSystemUpdateStatus,
} from "./api";
import type { SelfUpdateStatus, SystemUpdateInfo } from "./types";
import {
  DOCKER_SOCK_COMPOSE_LINE,
  formatSelfUpdateState,
  getSelfUpdateActionMode,
} from "./update-utils";

function formatTimestamp(raw?: string): string {
  if (!raw) return "—";
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) return raw;
  return date.toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function updateTargetKey(info: SystemUpdateInfo | null): string {
  if (!info?.has_update) return "";
  return (
    info.latest?.commit ||
    info.latest?.tag ||
    info.latest?.version ||
    info.latest?.short_commit ||
    ""
  );
}

export function updateTargetLabel(info: SystemUpdateInfo | null): string {
  if (!info?.latest) return "";
  return (
    info.latest.version ||
    info.latest.tag ||
    info.latest.short_commit ||
    info.latest.title ||
    ""
  );
}

const SAFE_PROTOCOLS = new Set(["http:", "https:"]);
function safeExternalHref(raw?: string | null): string | null {
  if (!raw) return null;
  try {
    const parsed = new URL(raw.trim());
    return SAFE_PROTOCOLS.has(parsed.protocol) ? parsed.href : null;
  } catch {
    return null;
  }
}

export function UpdateSettings({
  info,
  checking,
  error,
  onCheck,
}: {
  info: SystemUpdateInfo | null;
  checking: boolean;
  error: string;
  onCheck: (force: boolean) => Promise<void>;
}) {
  const [copied, setCopied] = useState(false);
  const [sockCopied, setSockCopied] = useState(false);
  const [selfUpdate, setSelfUpdate] = useState<SelfUpdateStatus | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [applyBusy, setApplyBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [healthPhase, setHealthPhase] = useState<
    "idle" | "waiting_restart" | "ready_reloading" | "timeout"
  >("idle");

  useEffect(() => {
    if (!info && !checking) {
      void onCheck(false);
    }
  }, [info, checking, onCheck]);

  useEffect(() => {
    if (!info?.self_update) return;
    setSelfUpdate((prev) => {
      if (prev?.in_progress || healthPhase !== "idle") return prev;
      return info.self_update ?? null;
    });
  }, [info?.self_update, healthPhase]);

  const effectiveSelfUpdate = selfUpdate ?? info?.self_update ?? null;
  const selfState = formatSelfUpdateState(effectiveSelfUpdate);
  const actionMode = getSelfUpdateActionMode(info);

  useEffect(() => {
    if (healthPhase !== "idle") return;
    if (!selfState.isRunning) return;
    if (selfState.isRestarting) {
      setHealthPhase("waiting_restart");
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void fetchSystemUpdateStatus()
        .then((next) => {
          if (cancelled) return;
          setSelfUpdate(next);
          if (next.stage === "restarting") {
            setHealthPhase("waiting_restart");
          } else if (next.stage === "up_to_date") {
            void onCheck(true);
          }
        })
        .catch(() => {
          if (!cancelled) {
            setHealthPhase("waiting_restart");
          }
        });
    }, 1000);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [
    effectiveSelfUpdate,
    selfState.isRunning,
    selfState.isRestarting,
    healthPhase,
    onCheck,
  ]);

  useEffect(() => {
    if (healthPhase !== "waiting_restart") return;
    let cancelled = false;
    let timer: number | undefined;
    const startedAt = Date.now();

    const probe = async () => {
      if (cancelled) return;
      const ok = await checkSystemHealth();
      if (cancelled) return;
      if (ok) {
        setHealthPhase("ready_reloading");
        await onCheck(true).catch(() => {});
        if (cancelled) return;
        timer = window.setTimeout(() => {
          window.location.reload();
        }, 900);
        return;
      }
      if (Date.now() - startedAt >= 120_000) {
        setHealthPhase("timeout");
        return;
      }
      timer = window.setTimeout(() => {
        void probe();
      }, 2000);
    };

    timer = window.setTimeout(() => {
      void probe();
    }, 2500);

    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [healthPhase, onCheck]);

  const current = info?.current;
  const issue = error || info?.error || "";
  const statusLabel = checking
    ? "正在检查…"
    : issue
      ? "检测异常"
      : info?.has_update
        ? info.ahead_by
          ? `发现新版本 · 落后 ${info.ahead_by} 个提交`
          : "发现新版本"
        : info?.comparable
          ? "已是最新版本"
          : info
            ? "参考最新提交"
            : "等待检查";

  const dockerCommand = "docker compose pull && docker compose up -d";

  async function copyText(value: string, onSuccess: (state: boolean) => void) {
    try {
      await navigator.clipboard.writeText(value);
      onSuccess(true);
      window.setTimeout(() => onSuccess(false), 2000);
    } catch {
      /* Clipboard may be unavailable in insecure HTTP contexts. */
    }
  }

  async function startSelfUpdate() {
    if (applyBusy || selfState.isRunning || healthPhase !== "idle") return;
    setApplyBusy(true);
    setActionError("");
    setConfirmOpen(false);
    try {
      const next = await applySystemUpdate();
      setSelfUpdate(next);
      if (next.stage === "restarting") {
        setHealthPhase("waiting_restart");
      }
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setApplyBusy(false);
    }
  }

  const updatingNow =
    applyBusy ||
    selfState.isRunning ||
    healthPhase === "waiting_restart" ||
    healthPhase === "ready_reloading";

  return (
    <div className="settings-form update-settings">
      <section className="settings-section">
        <div className="update-header">
          <div>
            <h2>
              版本与更新
              <small
                className={`update-badge ${
                  info?.has_update
                    ? "has-update"
                    : info?.comparable && !issue
                      ? "up-to-date"
                      : ""
                }`}
              >
                {statusLabel}
              </small>
            </h2>
          </div>
          <div className="update-header-actions">
            {actionMode === "primary" && !updatingNow && (
              <button
                type="button"
                className="primary"
                disabled={checking || applyBusy}
                onClick={() => {
                  setActionError("");
                  setConfirmOpen(true);
                }}
              >
                <ArrowUpCircle aria-hidden="true" />
                立即更新
              </button>
            )}
            {actionMode === "secondary" && !updatingNow && (
              <button
                type="button"
                className="secondary"
                disabled={checking || applyBusy}
                onClick={() => {
                  setActionError("");
                  setConfirmOpen(true);
                }}
              >
                <ArrowUpCircle aria-hidden="true" />
                拉取最新镜像并重启
              </button>
            )}
            <button
              type="button"
              className={actionMode === "primary" ? "secondary" : "primary"}
              disabled={checking || updatingNow}
              onClick={() => void onCheck(true)}
            >
              <RefreshCw className={checking ? "spin" : undefined} />
              {checking ? "正在检查…" : "立即检查更新"}
            </button>
          </div>
        </div>

        <dl className="update-meta-grid">
          <div>
            <dt>当前版本</dt>
            <dd>{current?.version || "—"}</dd>
          </div>
          <div>
            <dt>代码提交</dt>
            <dd>
              {current?.short_commit
                ? `${current.short_commit}${current.dirty ? " (含本地修改)" : ""}`
                : "未注入"}
            </dd>
          </div>
          <div>
            <dt>分支 / 标签</dt>
            <dd>{current?.ref || "—"}</dd>
          </div>
          <div>
            <dt>构建时间</dt>
            <dd>{formatTimestamp(current?.build_time)}</dd>
          </div>
          <div>
            <dt>运行环境</dt>
            <dd>{current?.in_container ? "Docker 容器" : "本地 / 源码运行"}</dd>
          </div>
          <div>
            <dt>上次检测</dt>
            <dd>
              {info
                ? `${formatTimestamp(info.checked_at)}${info.cached ? "（缓存）" : ""}`
                : "—"}
            </dd>
          </div>
        </dl>

        {issue && (
          <p className="update-alert" role="alert">
            {issue}
          </p>
        )}

        {actionError && (
          <p className="update-alert" role="alert">
            {actionError}
          </p>
        )}

        {selfState.isFailed && (
          <p className="update-alert" role="alert">
            {selfState.detail ||
              "容器自更新失败，请查看运行日志或使用命令行更新。"}
          </p>
        )}

        {healthPhase === "timeout" && (
          <p className="update-alert" role="alert">
            等待新版本就绪超时（超过 120 秒），请手动刷新页面或检查容器日志。
          </p>
        )}

        {confirmOpen && !updatingNow && (
          <div
            className="update-confirm-box"
            role="region"
            aria-label="确认更新"
          >
            <p>
              更新期间服务将短暂重启约 5–10 秒，挂载的 <code>/music</code> 与{" "}
              <code>/data</code> 数据会完整保留。
            </p>
            <div className="update-confirm-actions">
              <button
                type="button"
                className="secondary"
                disabled={applyBusy}
                onClick={() => setConfirmOpen(false)}
              >
                取消
              </button>
              <button
                type="button"
                className="primary"
                disabled={applyBusy}
                onClick={() => void startSelfUpdate()}
              >
                <ArrowUpCircle aria-hidden="true" />
                {applyBusy ? "正在提交…" : "确认更新"}
              </button>
            </div>
          </div>
        )}

        {(selfState.showProgress ||
          healthPhase === "waiting_restart" ||
          healthPhase === "ready_reloading") && (
          <div
            className="update-progress-card"
            role="status"
            aria-live="polite"
          >
            <div className="update-progress-meta">
              <strong>
                {healthPhase === "ready_reloading"
                  ? "新版本已启动，正在刷新页面…"
                  : healthPhase === "waiting_restart"
                    ? "容器重启中，正在等待新版本就绪…"
                    : selfState.label}
              </strong>
              <span>
                {healthPhase === "ready_reloading"
                  ? "100%"
                  : `${selfState.progress}%`}
              </span>
            </div>
            <div
              className="processing-progress-track"
              role="progressbar"
              aria-label="系统更新进度"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={
                healthPhase === "ready_reloading" ? 100 : selfState.progress
              }
            >
              <span
                className="processing-progress-fill"
                style={{
                  width: `${
                    healthPhase === "ready_reloading"
                      ? 100
                      : Math.max(8, selfState.progress)
                  }%`,
                }}
              />
            </div>
            {selfState.detail && healthPhase === "idle" && (
              <p className="update-progress-detail">{selfState.detail}</p>
            )}
          </div>
        )}

        {actionMode === "disabled_hint" && effectiveSelfUpdate?.reason && (
          <p className="update-hint" role="note">
            Web 一键更新未启用：{effectiveSelfUpdate.reason}
          </p>
        )}

        {info && !issue && info.has_update && info.latest && (
          <div className="update-summary" role="status">
            <div>
              <strong>发现可用更新：{updateTargetLabel(info)}</strong>
              <p>
                {info.mode === "release"
                  ? `发布于 ${formatTimestamp(info.latest.published_at)}`
                  : info.ahead_by
                    ? `上游 main 分支领先当前版本 ${info.ahead_by} 个提交`
                    : "上游仓库已有新提交"}
              </p>
            </div>
            <div className="update-summary-actions">
              {actionMode === "primary" && !updatingNow && (
                <button
                  type="button"
                  className="primary"
                  disabled={checking || applyBusy}
                  onClick={() => {
                    setActionError("");
                    setConfirmOpen(true);
                  }}
                >
                  <ArrowUpCircle aria-hidden="true" />
                  立即更新
                </button>
              )}
              {safeExternalHref(info.latest.url) && (
                <a
                  className="secondary update-ext-link"
                  href={safeExternalHref(info.latest.url)!}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  查看 GitHub 对比
                  <ExternalLink aria-hidden="true" />
                </a>
              )}
            </div>
          </div>
        )}

        {info && !issue && !info.has_update && !info.comparable && (
          <p className="update-hint">
            未关联远程提交基线，下方仅显示仓库最近提交。
          </p>
        )}
      </section>

      <section className="settings-section">
        <h2>更新日志</h2>
        {info?.release_notes ? (
          <div className="update-notes">
            <AIMarkdown content={info.release_notes} />
          </div>
        ) : null}

        {info?.commits?.length ? (
          <ul className="update-commits">
            {info.commits.map((item) => (
              <li key={item.sha}>
                <div className="update-commit-main">
                  <GitCommitHorizontal aria-hidden="true" />
                  <span className="update-commit-title">{item.title}</span>
                </div>
                <div className="update-commit-meta">
                  {item.author && <span>{item.author}</span>}
                  {item.date && <span>{formatTimestamp(item.date)}</span>}
                  {safeExternalHref(item.url) ? (
                    <a
                      href={safeExternalHref(item.url)!}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="update-sha"
                    >
                      {item.short}
                    </a>
                  ) : (
                    <code className="update-sha">{item.short}</code>
                  )}
                </div>
              </li>
            ))}
          </ul>
        ) : !info?.release_notes ? (
          <p>
            {checking
              ? "正在拉取更新日志…"
              : info?.has_update
                ? "上游暂无可展示的文字日志，请点击上方链接前往 GitHub 查看。"
                : "当前已是最新版本，暂无待更新日志。"}
          </p>
        ) : null}
      </section>

      <section className="settings-section">
        <h2>如何更新</h2>
        {current?.in_container ? (
          <>
            <p>
              当前运行在 Docker 容器中，请在宿主机{" "}
              <code>docker-compose.yml</code>{" "}
              所在目录执行以下命令拉取最新镜像并重建容器（<code>/data</code> 与{" "}
              <code>/music</code> 挂载数据会自动保留）：
            </p>
            <div className="update-command-row">
              <code>{dockerCommand}</code>
              <button
                type="button"
                className="secondary"
                onClick={() => void copyText(dockerCommand, setCopied)}
              >
                {copied ? <Check /> : <Copy />}
                {copied ? "已复制" : "复制命令"}
              </button>
            </div>
            {actionMode === "disabled_hint" && (
              <details className="library-advanced update-sock-guide">
                <summary>如何开启 Web 单容器一键更新？</summary>
                <p>
                  只需在宿主机 <code>docker-compose.yml</code> 的{" "}
                  <code>volumes:</code> 下增加一行挂载并重启容器，即可在 Web
                  端一键拉取最新镜像并平滑更新：
                </p>
                <div className="update-command-row">
                  <code>{DOCKER_SOCK_COMPOSE_LINE}</code>
                  <button
                    type="button"
                    className="secondary"
                    onClick={() =>
                      void copyText(DOCKER_SOCK_COMPOSE_LINE, setSockCopied)
                    }
                  >
                    {sockCopied ? <Check /> : <Copy />}
                    {sockCopied ? "已复制" : "复制挂载配置"}
                  </button>
                </div>
              </details>
            )}
          </>
        ) : (
          <p>
            当前为本地 / 源码模式运行：请拉取最新源码（<code>git pull</code>
            ）并重新构建前端（<code>cd frontend && npm run build</code>）与 Go
            后端后重启服务。
          </p>
        )}
      </section>
    </div>
  );
}
