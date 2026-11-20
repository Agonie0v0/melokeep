import { useEffect, useState } from "react";
import { ArrowRight, WandSparkles } from "lucide-react";
import { api } from "./api";
import type { LibraryConfig } from "./types";

export function LibraryWorkbench({
  count,
  busy,
  start,
  rules,
  initialDirectoryMode,
  initialDirectory,
  initialDirectoryKey,
}: {
  count: number;
  busy: boolean;
  start: (
    action: "scrape" | "organize" | "both",
    options: Record<string, unknown>,
  ) => void;
  rules: () => void;
  initialDirectoryMode?: boolean;
  initialDirectory?: string;
  initialDirectoryKey?: number | string;
}) {
  const [config, setConfig] = useState<LibraryConfig | null>(null);
  const [error, setError] = useState("");
  const [action, setAction] = useState<"scrape" | "organize" | "both">("both");
  const [directoryMode, setDirectoryMode] = useState(
    Boolean(initialDirectoryMode),
  );
  const [directory, setDirectory] = useState(initialDirectory ?? "incoming");
  const [previewOnly, setPreviewOnly] = useState(false);
  useEffect(() => {
    if (initialDirectoryMode !== undefined) {
      setDirectoryMode(initialDirectoryMode);
    }
    if (initialDirectory !== undefined) {
      setDirectory(initialDirectory);
    }
  }, [initialDirectoryMode, initialDirectory, initialDirectoryKey]);
  const load = () =>
    api<LibraryConfig>("/library/settings")
      .then(setConfig)
      .catch((e) => setError(e.message));
  useEffect(() => {
    void load();
  }, []);
  const primaryLabel = previewOnly
    ? busy
      ? "正在生成预览…"
      : "生成修改预览"
    : busy
      ? "正在刮削与整理…"
      : action === "both"
        ? "开始刮削并整理"
        : action === "scrape"
          ? "开始刮削标签"
          : "开始整理目录";
  return (
    <section className="view library-workbench" aria-label="刮削整理工作台">
      <div className="library-heading">
        <div>
          <h2>处理音乐文件</h2>
        </div>
        <button className="secondary" onClick={rules}>
          前往规则设置 <ArrowRight />
        </button>
      </div>
      {!config && (
        <p role={error ? "alert" : "status"}>
          {error || "正在读取刮削规则…"}{" "}
          {error && (
            <button
              className="secondary"
              onClick={() => {
                setError("");
                void load();
              }}
            >
              重新加载
            </button>
          )}
        </p>
      )}
      {config && (
        <>
          <div className="workbench-options">
            <label>
              处理范围
              <select
                value={directoryMode ? "directory" : "selection"}
                aria-label="处理范围"
                disabled={busy}
                onChange={(e) =>
                  setDirectoryMode(e.target.value === "directory")
                }
              >
                <option value="selection">已选中的歌曲</option>
                <option value="directory">目录内全部歌曲（含子目录）</option>
              </select>
            </label>
            {directoryMode && (
              <label>
                目录路径
                <input
                  value={directory}
                  aria-label="目录路径"
                  onChange={(e) => setDirectory(e.target.value)}
                  placeholder="相对音乐根目录，例如 incoming"
                />
                <small>单次最多 1000 首；留空选择整个音乐根目录。</small>
              </label>
            )}
            <label>
              本次处理
              <select
                aria-label="本次处理"
                value={action}
                disabled={busy}
                onChange={(e) => setAction(e.target.value as typeof action)}
              >
                <option value="both">刮削资料并整理目录</option>
                <option value="scrape">只刮削标签、歌词与封面</option>
                <option value="organize">只按现有标签整理目录</option>
              </select>
            </label>
            <label className="check-label workbench-preview-toggle">
              <input
                type="checkbox"
                aria-label="执行前先生成预览（人工核对标签与路径后再写入文件）"
                checked={previewOnly}
                disabled={busy}
                onChange={(e) => setPreviewOnly(e.target.checked)}
              />
              <span>执行前先生成预览</span>
            </label>
          </div>
          <details className="workbench-rule-summary" aria-label="当前处理规则">
            <summary>查看当前处理规则</summary>
            {action !== "organize" && (
              <p>
                资料来源：
                {config.sources
                  .map(
                    (id) =>
                      config.metadata_sources?.find(
                        (source) => source.id === id,
                      )?.label || id,
                  )
                  .join("、") || "未选择"}{" "}
                · {config.overwrite ? "覆盖未锁定标签" : "仅补全空值"}
              </p>
            )}
            {action !== "scrape" && (
              <p className="library-path">
                目标目录：{config.library_dir} / {config.folder_template} /{" "}
                {config.filename_template}
                <small className="library-path-preview">
                  示例：{config.library_dir} /{" "}
                  {config.folder_template
                    .replace(/\{album_artist\}|\{artist\}/g, "晨曦乐团")
                    .replace(/\{album\}/g, "旷野回声")
                    .replace(/\{year\}|\{date\}/g, "2026")}{" "}
                  /{" "}
                  {config.filename_template
                    .replace(/\{track_number\}/g, "01")
                    .replace(/\{disc_number\}/g, "1")
                    .replace(/\{artist\}/g, "晨曦乐团")
                    .replace(/\{title\}/g, "晴空序曲")}
                  .flac
                </small>
              </p>
            )}
          </details>
          <div className="management-toolbar workbench-submit">
            <span aria-live="polite">
              {directoryMode
                ? `目录：${directory.trim() || "音乐根目录"}`
                : `已选 ${count} 首歌曲`}
            </span>
            <button
              className="primary"
              disabled={
                busy ||
                (!count && !directoryMode) ||
                (action !== "organize" && !config.sources.length)
              }
              onClick={() =>
                start(action, {
                  ...(directoryMode ? { scope_dir: directory.trim() } : {}),
                  auto_apply: !previewOnly,
                })
              }
            >
              <WandSparkles />
              {primaryLabel}
            </button>
            {!count && !directoryMode && (
              <small>先在歌曲列表中选择需要处理的歌曲</small>
            )}
            {action !== "organize" && !config.sources.length && (
              <small role="status">请选择至少一个刮削来源</small>
            )}
          </div>
        </>
      )}
    </section>
  );
}
