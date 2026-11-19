import { useEffect, useState } from "react";
import {
  Check,
  Copy,
  ExternalLink,
  GitCommitHorizontal,
  RefreshCw,
} from "lucide-react";
import { AIMarkdown } from "./AIMarkdown";
import type { SystemUpdateInfo } from "./types";

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

  useEffect(() => {
    if (!info && !checking) {
      void onCheck(false);
    }
  }, [info, checking, onCheck]);

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

  async function copyCommand() {
    try {
      await navigator.clipboard.writeText(dockerCommand);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* Clipboard may be unavailable in insecure HTTP contexts. */
    }
  }

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
          <button
            type="button"
            className="primary"
            disabled={checking}
            onClick={() => void onCheck(true)}
          >
            <RefreshCw className={checking ? "spin" : undefined} />
            {checking ? "正在检查…" : "立即检查更新"}
          </button>
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
                onClick={() => void copyCommand()}
              >
                {copied ? <Check /> : <Copy />}
                {copied ? "已复制" : "复制命令"}
              </button>
            </div>
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
