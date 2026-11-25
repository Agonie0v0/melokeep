import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  ArrowUpCircle,
  Check,
  CircleAlert,
  Copy,
  ExternalLink,
  Eye,
  EyeOff,
  GitCommitHorizontal,
  RefreshCw,
} from "lucide-react";
import { AIMarkdown } from "./AIMarkdown";
import { Modal } from "./components";
import {
  applySystemUpdate,
  checkSystemHealth,
  fetchSystemUpdateStatus,
  saveSystemUpdateSettings,
} from "./api";
import type {
  SelfUpdateStatus,
  SystemUpdateInfo,
  SystemUpdateSettings,
} from "./types";
import {
  DOCKER_SOCK_COMPOSE_LINE,
  DOCKER_SOCK_RUN_FLAG,
  formatGitHubTokenStatus,
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

function DockerSockSnippets({
  prefix,
  copiedKey,
  onCopy,
}: {
  prefix: string;
  copiedKey: string;
  onCopy: (key: string, value: string) => void;
}) {
  const rows = [
    {
      key: `${prefix}-compose`,
      label: "docker-compose.yml（volumes）",
      value: DOCKER_SOCK_COMPOSE_LINE,
      buttonText: "复制挂载行",
    },
    {
      key: `${prefix}-run`,
      label: "docker run 参数",
      value: DOCKER_SOCK_RUN_FLAG,
      buttonText: "复制参数",
    },
  ];
  return (
    <div className="update-sock-rows">
      {rows.map((row) => (
        <div className="update-command-row" key={row.key}>
          <div className="update-sock-snippet">
            <span className="update-sock-label">{row.label}</span>
            <code>{row.value}</code>
          </div>
          <button
            type="button"
            className="secondary"
            onClick={() => onCopy(row.key, row.value)}
          >
            {copiedKey === row.key ? <Check /> : <Copy />}
            {copiedKey === row.key ? "已复制" : row.buttonText}
          </button>
        </div>
      ))}
    </div>
  );
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
  const [copiedKey, setCopiedKey] = useState("");
  const copyTimerRef = useRef<number | undefined>(undefined);
  const topCardRef = useRef<HTMLElement>(null);
  const [selfUpdate, setSelfUpdate] = useState<SelfUpdateStatus | null>(null);
  const [confirmMode, setConfirmMode] = useState<
    "update" | "force_pull" | null
  >(null);
  const [applyBusy, setApplyBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [healthPhase, setHealthPhase] = useState<
    "idle" | "waiting_restart" | "ready_reloading" | "timeout"
  >("idle");
  const [tokenInput, setTokenInput] = useState("");
  const [showToken, setShowToken] = useState(false);
  const [tokenBusy, setTokenBusy] = useState<"save" | "clear" | null>(null);
  const [tokenOverride, setTokenOverride] =
    useState<SystemUpdateSettings | null>(null);
  const [tokenNotice, setTokenNotice] = useState<{
    tone: "success" | "error";
    text: string;
  } | null>(null);
  const prevInfoRef = useRef<SystemUpdateInfo | null>(info);

  useEffect(() => {
    if (prevInfoRef.current === info) return;
    prevInfoRef.current = info;
    if (info && typeof info.github_token_configured === "boolean") {
      setTokenOverride(null);
    }
  }, [info]);

  useEffect(() => {
    return () => {
      if (copyTimerRef.current !== undefined) {
        window.clearTimeout(copyTimerRef.current);
      }
    };
  }, []);

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

  async function copyText(key: string, value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopiedKey(key);
      if (copyTimerRef.current !== undefined) {
        window.clearTimeout(copyTimerRef.current);
      }
      copyTimerRef.current = window.setTimeout(() => setCopiedKey(""), 2000);
    } catch {
      /* Clipboard may be unavailable in insecure HTTP contexts. */
    }
  }

  async function startSelfUpdate() {
    if (applyBusy || selfState.isRunning || healthPhase !== "idle") return;
    const fromBottom = confirmMode === "force_pull";
    setApplyBusy(true);
    setActionError("");
    setConfirmMode(null);
    if (fromBottom) {
      topCardRef.current?.scrollIntoView({
        behavior: "smooth",
        block: "start",
      });
    }
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

  const tokenStatus = formatGitHubTokenStatus(tokenOverride ?? info);

  async function submitTokenSettings(
    mode: "save" | "clear",
    payload: { github_token?: string; clear_github_token?: boolean },
    getLabel: (next: SystemUpdateSettings) => string,
  ) {
    if (tokenBusy !== null || updatingNow) return;
    setTokenBusy(mode);
    setTokenNotice(null);
    try {
      const next = await saveSystemUpdateSettings(payload);
      const label = getLabel(next);
      setTokenOverride(next);
      setTokenInput("");
      setShowToken(false);
      setTokenNotice({
        tone: "success",
        text: `${label}，正在重新检测更新…`,
      });
      try {
        await onCheck(true);
        setTokenNotice({
          tone: "success",
          text: `${label}，已完成更新检测。`,
        });
      } catch (checkErr) {
        setTokenNotice({
          tone: "error",
          text: `${label}，但重新检测失败：${
            checkErr instanceof Error ? checkErr.message : String(checkErr)
          }`,
        });
      }
    } catch (e) {
      setTokenNotice({
        tone: "error",
        text: e instanceof Error ? e.message : String(e),
      });
    } finally {
      setTokenBusy(null);
    }
  }

  function handleSaveToken(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const trimmed = tokenInput.trim();
    if (!trimmed) {
      setTokenNotice({
        tone: "error",
        text: "请输入有效的 GitHub Personal Access Token",
      });
      return;
    }
    void submitTokenSettings(
      "save",
      { github_token: trimmed },
      () => "GitHub Token 已保存",
    );
  }

  function handleClearToken() {
    void submitTokenSettings("clear", { clear_github_token: true }, (next) =>
      next.github_token_configured
        ? "已清空页面保存的 Token（当前回退使用环境变量）"
        : "已清空 GitHub Token",
    );
  }

  return (
    <div className="settings-form update-settings">
      <section ref={topCardRef} className="settings-section">
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
            {current?.in_container &&
              actionMode === "up_to_date" &&
              !updatingNow && (
                <button
                  type="button"
                  className="secondary"
                  disabled={checking || updatingNow}
                  title="强制重新拉取当前镜像并重启"
                  onClick={() => {
                    setActionError("");
                    setConfirmMode("force_pull");
                  }}
                >
                  <ArrowUpCircle aria-hidden="true" />
                  重新拉取并重启
                </button>
              )}
            <button
              type="button"
              className={info?.has_update ? "secondary" : "primary"}
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

        {current?.in_container && actionMode === "disabled_hint" && (
          <div className="update-sock-callout" role="note">
            <div className="update-sock-callout-head">
              <CircleAlert aria-hidden="true" />
              <strong>
                {effectiveSelfUpdate?.mode === "docker_socket"
                  ? "Web 一键更新暂不可用（docker.sock 访问异常）"
                  : "未挂载 /var/run/docker.sock，暂不支持 Web 一键更新"}
              </strong>
            </div>
            <p className="update-sock-callout-desc">
              {effectiveSelfUpdate?.mode === "docker_socket" &&
              effectiveSelfUpdate.reason
                ? effectiveSelfUpdate.reason
                : "当前容器未检测到宿主机 /var/run/docker.sock 挂载，因此无法通过网页直接拉取镜像并重启容器。请按下方任一方式添加挂载后重启容器："}
            </p>
            <DockerSockSnippets
              prefix="callout"
              copiedKey={copiedKey}
              onCopy={(key, value) => void copyText(key, value)}
            />
          </div>
        )}

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
                    setConfirmMode("update");
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

      <section className="settings-section update-token-section">
        <h2 className="update-token-title">
          GitHub 访问令牌
          <small
            className={`update-badge ${
              tokenStatus.badgeTone === "configured" ? "up-to-date" : ""
            }`.trim()}
          >
            {tokenStatus.badgeText}
          </small>
        </h2>
        <p className="update-token-desc">
          填写 GitHub Personal Access
          Token（公开仓库无需勾选私人权限）可将限额从 60 次/小时提升至 5,000
          次/小时；一处配置后 Web 端与已连接的手机 App
          检测更新均自动生效，令牌仅保存在服务端不回传明文。
        </p>
        <form
          className="update-token-form"
          onSubmit={(e) => handleSaveToken(e)}
        >
          <div className="update-token-controls">
            <div className="update-token-input-wrap">
              <input
                type={showToken ? "text" : "password"}
                className="update-token-input"
                aria-label="GitHub Personal Access Token"
                autoComplete="off"
                spellCheck={false}
                value={tokenInput}
                disabled={tokenBusy !== null || updatingNow}
                placeholder={
                  tokenStatus.saved
                    ? "已保存令牌（留空不变，输入新令牌可覆盖）"
                    : "输入 github_pat_... 或 ghp_..."
                }
                onChange={(e) => {
                  setTokenInput(e.target.value);
                  if (tokenNotice?.tone === "error") setTokenNotice(null);
                }}
              />
              <button
                type="button"
                className="update-token-reveal"
                aria-label={showToken ? "隐藏 Token" : "显示 Token"}
                aria-pressed={showToken}
                disabled={tokenBusy !== null || updatingNow}
                onClick={() => setShowToken((prev) => !prev)}
              >
                {showToken ? (
                  <EyeOff aria-hidden="true" />
                ) : (
                  <Eye aria-hidden="true" />
                )}
              </button>
            </div>
            <div className="update-token-actions">
              <button
                type="submit"
                className="primary"
                disabled={
                  tokenBusy !== null || updatingNow || !tokenInput.trim()
                }
              >
                {tokenBusy === "save" ? "正在保存…" : "保存并立即检测"}
              </button>
              {tokenStatus.saved && (
                <button
                  type="button"
                  className="secondary"
                  disabled={tokenBusy !== null || updatingNow}
                  onClick={() => handleClearToken()}
                >
                  {tokenBusy === "clear" ? "正在清空…" : "清空 Token"}
                </button>
              )}
            </div>
          </div>
          {tokenNotice && (
            <p
              className={`update-token-feedback ${
                tokenNotice.tone === "error" ? "is-error" : "is-success"
              }`}
              role={tokenNotice.tone === "error" ? "alert" : "status"}
            >
              {tokenNotice.text}
            </p>
          )}
        </form>
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

      {confirmMode && !updatingNow && (
        <Modal
          title={
            confirmMode === "force_pull"
              ? "确认重新拉取当前镜像并重启"
              : "确认更新系统"
          }
          close={applyBusy ? undefined : () => setConfirmMode(null)}
        >
          <p className="update-confirm-copy">
            {confirmMode === "force_pull"
              ? "将重新拉取当前镜像标签并重建容器。重启期间服务将短暂中断约 5–10 秒，挂载的 "
              : "更新期间服务将短暂重启约 5–10 秒，挂载的 "}
            <code>/music</code> 与 <code>/data</code> 数据会完整保留。
          </p>
          <div className="dialog-actions">
            <button
              type="button"
              className="secondary"
              disabled={applyBusy}
              onClick={() => setConfirmMode(null)}
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
              {applyBusy
                ? "正在提交…"
                : confirmMode === "force_pull"
                  ? "确认拉取并重启"
                  : "确认更新"}
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
