import { useEffect, useState } from "react";
import { Check, PlugZap } from "lucide-react";
import { api } from "./api";
import {
  buildMetadataSourcesSummary,
  DEFAULT_MIN_MATCH_SCORE,
  describeMinMatchScore,
  MIN_MATCH_SCORE_MAX,
  MIN_MATCH_SCORE_MIN,
  partitionMetadataSources,
  pickBuiltinMetadataSourceIds,
  pickRecommendedMetadataSourceIds,
  sanitizeMinMatchScore,
} from "./library-utils";
import { SourceDisclosure } from "./SourceDisclosure";
import type { LibraryConfig, MetadataSource } from "./types";
import "./library.css";

export const tagLabels: Record<string, string> = {
  title: "歌曲标题",
  artist: "歌手",
  album: "专辑",
  album_artist: "专辑歌手",
  date: "发行日期",
  track_number: "曲序",
  disc_number: "碟号",
  genre: "流派",
  composer: "作曲",
  comment: "备注",
  lyricist: "作词",
  language: "语言",
  copyright: "版权",
  publisher: "发行商",
  grouping: "分组",
  bpm: "BPM",
  isrc: "ISRC",
};
export const processingLabels: Record<string, string> = {
  queued: "等待处理",
  identifying: "识别与匹配",
  tagging: "写入与整理",
  organizing: "整理目录",
  indexing: "更新曲库",
  complete: "已完成",
  ready: "等待应用",
  needs_review: "待确认",
  failed: "处理失败",
  partial: "部分完成",
  paused: "已暂停",
  cancelled: "已取消",
  restoring: "正在恢复",
  restored: "已恢复",
};

function formatSourceFields(fields: string[]): string {
  return fields
    .map((field) =>
      field === "cover"
        ? "封面"
        : field === "lyrics"
          ? "歌词"
          : tagLabels[field] || field,
    )
    .join(" · ");
}

function MetadataSourceRow({
  source,
  checked,
  disabled,
  onToggle,
}: {
  source: MetadataSource;
  checked: boolean;
  disabled: boolean;
  onToggle: (checked: boolean) => void;
}) {
  const isPlugin = source.builtin === false;
  const fieldSummary = formatSourceFields(source.fields || []);
  const summaryCopy = source.recommend_reason || source.description;
  return (
    <div className="metadata-source">
      <div className="metadata-source-head">
        <label className="check-label">
          <input
            type="checkbox"
            disabled={disabled}
            checked={checked}
            onChange={(e) => onToggle(e.target.checked)}
          />
          <strong>{source.label}</strong>
        </label>
        <div className="metadata-source-badges">
          {source.recommended && (
            <span className="metadata-source-badge is-recommended">推荐</span>
          )}
          {isPlugin && (
            <span className="metadata-source-badge is-plugin">插件</span>
          )}
        </div>
      </div>
      <div className="metadata-source-body">
        <p className="metadata-source-desc">
          {summaryCopy}
          {fieldSummary && (
            <span className="metadata-source-fields"> · {fieldSummary}</span>
          )}
        </p>
        {(source.note ||
          source.url ||
          (source.recommend_reason && source.description)) && (
          <details className="metadata-source-more">
            <summary>详情与来源说明</summary>
            <div className="metadata-source-more-body">
              {source.recommend_reason && source.description && (
                <small>{source.description}</small>
              )}
              {(source.note || source.url) && (
                <small>
                  {source.note}
                  {source.url && (
                    <>
                      {" "}
                      <a href={source.url} target="_blank" rel="noreferrer">
                        来源网站
                      </a>
                    </>
                  )}
                </small>
              )}
            </div>
          </details>
        )}
      </div>
    </div>
  );
}

export function MetadataSources({
  config,
  change,
  disabled = false,
}: {
  config: LibraryConfig;
  change: (sources: string[]) => void;
  disabled?: boolean;
}) {
  const [sourceQuery, setSourceQuery] = useState("");
  const allSources = config.metadata_sources ?? [];
  const builtinIds = pickBuiltinMetadataSourceIds(allSources);
  const { builtin, plugins } = partitionMetadataSources(
    allSources,
    sourceQuery,
  );
  const summaryText = buildMetadataSourcesSummary(config.sources, allSources);

  const toggleSource = (id: string, nextChecked: boolean) => {
    if (nextChecked) {
      if (config.sources.includes(id)) return;
      change([...config.sources, id]);
    } else {
      change(config.sources.filter((item) => item !== id));
    }
  };

  return (
    <fieldset className="library-fieldset metadata-sources">
      <SourceDisclosure summary={summaryText}>
        <div className="metadata-sources-toolbar">
          <div className="metadata-sources-actions">
            <button
              type="button"
              className="secondary"
              disabled={disabled}
              onClick={() =>
                change(pickRecommendedMetadataSourceIds(allSources))
              }
            >
              一键勾选推荐组合
            </button>
            <button
              type="button"
              className="secondary"
              disabled={disabled || !builtinIds.length}
              onClick={() => change(builtinIds)}
            >
              全选内置
            </button>
            <button
              type="button"
              className="secondary"
              disabled={disabled || !config.sources.length}
              onClick={() => change([])}
            >
              清空
            </button>
          </div>
          {allSources.length > 5 && (
            <input
              type="search"
              className="metadata-sources-filter"
              aria-label="筛选刮削资料来源"
              placeholder="筛选来源名称或说明…"
              value={sourceQuery}
              onChange={(e) => setSourceQuery(e.target.value)}
            />
          )}
        </div>

        <div className="metadata-source-groups">
          {builtin.length > 0 && (
            <div className="metadata-source-group">
              <h3 className="metadata-source-group-title">推荐与内置源</h3>
              <div className="metadata-source-list">
                {builtin.map((source) => (
                  <MetadataSourceRow
                    key={source.id}
                    source={source}
                    checked={config.sources.includes(source.id)}
                    disabled={disabled}
                    onToggle={(next) => toggleSource(source.id, next)}
                  />
                ))}
              </div>
            </div>
          )}
          {plugins.length > 0 && (
            <div className="metadata-source-group">
              <h3 className="metadata-source-group-title">自定义插件资料源</h3>
              <div className="metadata-source-list">
                {plugins.map((source) => (
                  <MetadataSourceRow
                    key={source.id}
                    source={source}
                    checked={config.sources.includes(source.id)}
                    disabled={disabled}
                    onToggle={(next) => toggleSource(source.id, next)}
                  />
                ))}
              </div>
            </div>
          )}
          {builtin.length === 0 && plugins.length === 0 && (
            <p className="setting-help">
              {sourceQuery.trim()
                ? "没有匹配的刮削资料来源。"
                : "暂无可用的刮削资料来源。"}
            </p>
          )}
        </div>
      </SourceDisclosure>
    </fieldset>
  );
}

export function LibrarySettings({
  openSources,
  section,
}: {
  openSources?: () => void;
  section: "library" | "navidrome";
}) {
  const [config, setConfig] = useState<LibraryConfig | null>(null);
  const [password, setPassword] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [clearCredentials, setClearCredentials] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [navMessage, setNavMessage] = useState("");
  const [acoustidKey, setAcoustidKey] = useState("");
  const [watchStatus, setWatchStatus] = useState("");
  const load = () =>
    api<LibraryConfig>("/library/settings")
      .then(setConfig)
      .catch((e) => setError(e.message));
  useEffect(() => {
    void load();
  }, []);
  if (!config)
    return (
      <section className="settings-section">
        <h2>
          {section === "navidrome" ? "Navidrome 连接" : "刮削与归档默认规则"}
        </h2>
        <p role={error ? "alert" : "status"}>{error || "正在读取曲库设置…"}</p>
        {error && (
          <button className="secondary" onClick={() => void load()}>
            重新加载
          </button>
        )}
      </section>
    );
  const update = <K extends keyof LibraryConfig>(
    key: K,
    value: LibraryConfig[K],
  ) => setConfig({ ...config, [key]: value });
  async function saveLibrary(event: React.FormEvent) {
    event.preventDefault();
    if (!config) return;
    setBusy("library");
    setError("");
    setMessage("");
    try {
      const {
        navidrome: _nav,
        file_root: _root,
        metadata_sources: _sources,
        ...settings
      } = config;
      const saved = await api<LibraryConfig>("/library/settings", "PUT", {
        ...settings,
        min_match_score: sanitizeMinMatchScore(
          settings.min_match_score ?? DEFAULT_MIN_MATCH_SCORE,
        ),
      });
      setConfig(saved);
      setMessage("曲库设置已保存，后续新建任务将使用这些默认规则。");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy("");
    }
  }
  async function saveNav(test = false) {
    if (!config) return;
    setBusy(test ? "test" : "nav");
    setError("");
    setNavMessage("");
    try {
      const {
        has_password: _password,
        has_api_key: _key,
        ...nav
      } = config.navidrome;
      const saved = await api<LibraryConfig["navidrome"]>(
        "/library/navidrome",
        "PUT",
        {
          ...nav,
          password: password || (clearCredentials ? "" : null),
          api_key: apiKey || (clearCredentials ? "" : null),
          clear_password: clearCredentials && !password,
          clear_api_key: clearCredentials && !apiKey,
        },
      );
      setConfig({ ...config, navidrome: saved });
      setPassword("");
      setApiKey("");
      setClearCredentials(false);
      if (test) {
        const result = await api<{ version: string }>(
          "/navidrome/test",
          "POST",
        );
        setNavMessage(
          `连接成功${result.version ? `，服务器版本 ${result.version}` : ""}。可在音乐库的 Navidrome 页浏览。`,
        );
      } else setNavMessage("Navidrome 连接设置已保存。");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy("");
    }
  }
  return (
    <div className="engine-settings library-settings">
      {section === "library" && (
        <>
          <form onSubmit={saveLibrary} className="settings-section">
            <div className="library-section-title">
              <div>
                <h2>刮削与归档默认规则</h2>
              </div>
            </div>
            <div className="library-switches">
              <label className="library-switch">
                <input
                  type="checkbox"
                  checked={config.auto_scrape}
                  onChange={(e) => update("auto_scrape", e.target.checked)}
                />
                <span>
                  <strong>下载后自动刮削</strong>
                </span>
              </label>
              <label className="library-switch">
                <input
                  type="checkbox"
                  checked={config.auto_organize}
                  onChange={(e) => update("auto_organize", e.target.checked)}
                />
                <span>
                  <strong>下载后自动整理入库</strong>
                </span>
              </label>
            </div>
            <div className="field-row">
              <label className="field-sm">
                整理后的音乐库目录
                <input
                  required
                  value={config.library_dir}
                  onChange={(e) => update("library_dir", e.target.value)}
                  placeholder={`相对 ${config.file_root}`}
                  title={`相对目录位于 ${config.file_root} 内`}
                />
              </label>
              <label className="field-md">
                目录模板
                <input
                  required
                  aria-label="目录模板"
                  placeholder="{album_artist}/{album}"
                  value={config.folder_template}
                  onChange={(e) => update("folder_template", e.target.value)}
                />
              </label>
              <label className="field-md">
                文件名模板
                <input
                  required
                  aria-label="文件名模板"
                  placeholder="{track_number} - {title}"
                  value={config.filename_template}
                  onChange={(e) => update("filename_template", e.target.value)}
                />
              </label>
            </div>
            <div className="field-row">
              <label className="field-sm">
                歌词保存方式
                <select
                  value={config.lyrics_mode}
                  onChange={(e) => update("lyrics_mode", e.target.value)}
                >
                  <option value="both">内嵌标签和同名 LRC</option>
                  <option value="embed">仅内嵌标签</option>
                  <option value="lrc">仅同名 LRC</option>
                  <option value="none">不补全歌词</option>
                </select>
              </label>
              <label className="field-sm">
                封面保存方式
                <select
                  value={config.cover_mode}
                  onChange={(e) => update("cover_mode", e.target.value)}
                >
                  <option value="both">内嵌封面和同名图片</option>
                  <option value="embed">仅内嵌封面</option>
                  <option value="file">仅同名图片</option>
                  <option value="none">不补全封面</option>
                </select>
              </label>
              <label className="field-xs">
                原文件恢复期限
                <span className="field-unit" aria-hidden="true">
                  （天）
                </span>
                <input
                  type="number"
                  min="0"
                  max="365"
                  aria-label="原文件恢复期限"
                  placeholder="天，0 不备份"
                  title="单位：天，设为 0 不保存原文件备份"
                  value={config.backup_days}
                  onChange={(e) =>
                    update("backup_days", Number(e.target.value))
                  }
                />
              </label>
            </div>
            <label className="library-match-score-field">
              默认自动匹配分数
              <div className="library-match-score-controls">
                <input
                  type="range"
                  aria-label="默认自动匹配分数滑块"
                  min={MIN_MATCH_SCORE_MIN}
                  max={MIN_MATCH_SCORE_MAX}
                  step={1}
                  value={sanitizeMinMatchScore(config.min_match_score)}
                  onChange={(e) =>
                    update(
                      "min_match_score",
                      sanitizeMinMatchScore(e.target.value),
                    )
                  }
                />
                <input
                  type="number"
                  aria-label="默认自动匹配分数"
                  min={MIN_MATCH_SCORE_MIN}
                  max={MIN_MATCH_SCORE_MAX}
                  step={1}
                  value={config.min_match_score ?? DEFAULT_MIN_MATCH_SCORE}
                  onChange={(e) => {
                    const raw = Number(e.target.value);
                    if (Number.isFinite(raw)) {
                      update("min_match_score", raw);
                    }
                  }}
                  onBlur={() =>
                    update(
                      "min_match_score",
                      sanitizeMinMatchScore(config.min_match_score),
                    )
                  }
                />
              </div>
              <small>
                {describeMinMatchScore(
                  sanitizeMinMatchScore(config.min_match_score),
                )}
                （85–100 严格 / 75–84 推荐均衡 / 50–74 宽松自动整理）
              </small>
            </label>
            <details className="library-advanced">
              <summary>整理模板示例与可用变量</summary>
              <dl className="template-example">
                <dt>示例输出</dt>
                <dd>
                  <code>
                    /music/library/晨曦乐团/旷野回声/03 - 晴空序曲.flac
                  </code>
                </dd>
              </dl>
              <table className="template-variables">
                <thead>
                  <tr>
                    <th>变量</th>
                    <th>含义</th>
                    <th>示例</th>
                  </tr>
                </thead>
                <tbody>
                  {[
                    ["album_artist", "专辑歌手；未填写时使用歌手", "晨曦乐团"],
                    ["artist", "歌曲的歌手", "晨曦乐团"],
                    ["album", "专辑名称", "旷野回声"],
                    ["title", "歌曲标题", "晴空序曲"],
                    ["track_number", "曲序，自动补足至少两位", "03"],
                    ["disc_number", "碟号", "1"],
                    ["year", "发行日期的前四位", "2026"],
                    ["date", "完整发行日期", "2026-03-15"],
                    ["genre", "流派", "环境音乐"],
                    ["first_artist", "第一位歌手", "晨曦乐团"],
                    ["first_artist_letter", "第一位歌手首字母", "C"],
                    [
                      "file_song_name",
                      "原文件名（不含扩展名）",
                      "原始下载文件",
                    ],
                  ].map(([variable, meaning, example]) => (
                    <tr key={variable}>
                      <td>
                        <code>{`{${variable}}`}</code>
                      </td>
                      <td>{meaning}</td>
                      <td>{example}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </details>
            <MetadataSources
              config={config}
              change={(sources) => update("sources", sources)}
              disabled={!!busy}
            />
            <details className="library-advanced">
              <summary>目录监控与预处理</summary>
              <label className="check-label">
                <input
                  type="checkbox"
                  checked={config.watch_enabled || false}
                  onChange={(e) => update("watch_enabled", e.target.checked)}
                />
                启用目录监控
              </label>
              <label className="field-xl">
                监控目录
                <textarea
                  rows={3}
                  aria-label="监控目录（每行一个）"
                  placeholder="每行一个相对或绝对路径"
                  value={(config.watch_dirs || ["incoming"]).join("\n")}
                  onChange={(e) =>
                    update("watch_dirs", e.target.value.split("\n"))
                  }
                />
              </label>
              <div className="field-row">
                <label className="field-md">
                  失败文件目录
                  <input
                    value={config.watch_failure_dir || ""}
                    aria-label="未匹配/失败文件目录（可选）"
                    onChange={(e) =>
                      update("watch_failure_dir", e.target.value)
                    }
                    placeholder="留空保留原处，例如 review"
                  />
                </label>
                <label className="field-xs">
                  检查间隔
                  <span className="field-unit" aria-hidden="true">
                    （秒）
                  </span>
                  <input
                    type="number"
                    min={10}
                    max={86400}
                    aria-label="检查间隔"
                    placeholder="秒"
                    title="单位：秒"
                    value={config.watch_interval ?? 180}
                    onChange={(e) =>
                      update("watch_interval", Number(e.target.value))
                    }
                  />
                </label>
                <label className="field-xs">
                  文件稳定等待
                  <span className="field-unit" aria-hidden="true">
                    （秒）
                  </span>
                  <input
                    type="number"
                    min={2}
                    max={3600}
                    aria-label="文件稳定等待"
                    placeholder="秒"
                    title="单位：秒"
                    value={config.watch_stable_seconds ?? 30}
                    onChange={(e) =>
                      update("watch_stable_seconds", Number(e.target.value))
                    }
                  />
                </label>
              </div>
              <div className="field-row">
                <label className="field-sm">
                  监控处理方式
                  <select
                    value={config.watch_action || "both"}
                    onChange={(e) => update("watch_action", e.target.value)}
                  >
                    <option value="both">刮削并整理入库</option>
                    <option value="scrape">只刮削资料</option>
                    <option value="organize">只整理目录</option>
                  </select>
                </label>
                <label className="field-sm">
                  中文预处理
                  <select
                    value={config.chinese || "none"}
                    onChange={(e) => update("chinese", e.target.value)}
                  >
                    <option value="none">保留原文</option>
                    <option value="simplified">繁体转简体</option>
                    <option value="traditional">简体转繁体</option>
                  </select>
                </label>
              </div>
              <label className="check-label">
                <input
                  type="checkbox"
                  checked={config.repair_encoding || false}
                  onChange={(e) => update("repair_encoding", e.target.checked)}
                />
                修复 Latin1 乱码标签
              </label>
              <label className="check-label">
                <input
                  type="checkbox"
                  checked={config.watch_apply ?? true}
                  onChange={(e) => update("watch_apply", e.target.checked)}
                />
                自动应用可靠匹配
              </label>
              <div className="management-toolbar">
                <button
                  type="button"
                  className="secondary"
                  disabled={!!busy}
                  onClick={async () => {
                    setBusy("watch");
                    setError("");
                    try {
                      const result = await api<{ detail: string }>(
                        "/library/watch/scan",
                        "POST",
                      );
                      setWatchStatus(result.detail);
                    } catch (e) {
                      setError((e as Error).message);
                    } finally {
                      setBusy("");
                    }
                  }}
                >
                  检查已保存的监控目录
                </button>
                <button
                  type="button"
                  className="secondary"
                  onClick={async () => {
                    try {
                      const result = await api<{
                        detail?: string;
                        error?: string;
                        checked_at?: number;
                      }>("/library/watch");
                      setWatchStatus(
                        `${result.checked_at ? new Date(result.checked_at * 1000).toLocaleString("zh-CN") + " · " : ""}${result.error || result.detail || "尚未检查"}`,
                      );
                    } catch (e) {
                      setError((e as Error).message);
                    }
                  }}
                >
                  查看监控状态
                </button>
              </div>
              {watchStatus && <p role="status">{watchStatus}</p>}
            </details>
            {openSources && (
              <button className="secondary" type="button" onClick={openSources}>
                配置音源账号与代理
              </button>
            )}
            <details className="library-advanced">
              <summary>字段与覆盖规则</summary>
              <fieldset className="library-fieldset">
                <legend>允许自动补全的标签</legend>
                <div className="library-checks">
                  {Object.entries(tagLabels).map(([key, label]) => (
                    <label key={key} className="check-label">
                      <input
                        type="checkbox"
                        checked={config.fields.includes(key)}
                        onChange={(e) =>
                          update(
                            "fields",
                            e.target.checked
                              ? [...config.fields, key]
                              : config.fields.filter((v) => v !== key),
                          )
                        }
                      />
                      {label}
                    </label>
                  ))}
                </div>
              </fieldset>
              <label className="check-label">
                <input
                  type="checkbox"
                  checked={config.overwrite}
                  onChange={(e) => update("overwrite", e.target.checked)}
                />
                覆盖已有未锁定标签
              </label>
            </details>
            <div className="management-toolbar settings-sticky-bar">
              <button
                className="primary"
                disabled={
                  !!busy || (config.auto_scrape && !config.sources.length)
                }
              >
                <Check />
                {busy === "library" ? "正在保存…" : "保存曲库设置"}
              </button>
              <button
                type="button"
                className="secondary"
                disabled={!!busy}
                onClick={async () => {
                  setBusy("purge");
                  setError("");
                  setMessage("");
                  try {
                    const res = await api<{
                      removed_operations: number;
                      freed_bytes: number;
                    }>("/library/backups/purge", "POST");
                    const freed =
                      res.freed_bytes >= 1048576
                        ? `${(res.freed_bytes / 1048576).toFixed(1)} MB`
                        : `${(res.freed_bytes / 1024).toFixed(1)} KB`;
                    setMessage(
                      `已清理历史备份（${res.removed_operations} 条记录，释放 ${freed}）`,
                    );
                  } catch (e) {
                    setError((e as Error).message);
                  } finally {
                    setBusy("");
                  }
                }}
              >
                {busy === "purge" ? "正在清理…" : "清空历史备份"}
              </button>
              {config.auto_scrape && !config.sources.length && (
                <span role="status">请选择至少一个刮削来源。</span>
              )}
              {message && <span role="status">{message}</span>}
            </div>
          </form>
          <form
            className="settings-section"
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy("acoustid");
              setError("");
              try {
                const saved = await api<{ has_acoustid_key: boolean }>(
                  "/library/credentials",
                  "PUT",
                  { acoustid_key: acoustidKey },
                );
                setConfig({ ...config, ...saved });
                setAcoustidKey("");
                setMessage("AcoustID 配置已保存。");
              } catch (e) {
                setError((e as Error).message);
              } finally {
                setBusy("");
              }
            }}
          >
            <h2>音频指纹识别</h2>
            <label className="field-md">
              AcoustID 应用 Key
              <input
                type="password"
                autoComplete="new-password"
                value={acoustidKey}
                onChange={(e) => setAcoustidKey(e.target.value)}
                placeholder={
                  config.has_acoustid_key
                    ? "已配置；输入新 Key 可替换"
                    : "填写 acoustid.org 申请的应用 Key"
                }
              />
            </label>
            <p className="setting-help">
              需服务器安装 fpcalc ·{" "}
              <a
                href="https://acoustid.org/new-application"
                target="_blank"
                rel="noreferrer"
              >
                申请应用 Key
              </a>
            </p>
            <div className="management-toolbar">
              <button className="primary" disabled={!!busy || !acoustidKey}>
                保存指纹配置
              </button>
              <span>
                {config.has_acoustid_key ? "Key 已配置" : "Key 未配置"} ·{" "}
                {config.tools?.fpcalc ? "fpcalc 已找到" : "fpcalc 未找到"}
              </span>
            </div>
          </form>
        </>
      )}
      {section === "navidrome" && (
        <form
          className="settings-section"
          onSubmit={(e) => {
            e.preventDefault();
            void saveNav();
          }}
        >
          <h2>Navidrome 连接</h2>
          <label className="library-switch">
            <input
              type="checkbox"
              checked={config.navidrome.enabled}
              onChange={(e) =>
                update("navidrome", {
                  ...config.navidrome,
                  enabled: e.target.checked,
                })
              }
            />
            <span>
              <strong>启用 Navidrome</strong>
            </span>
          </label>
          <div className="field-row">
            <label className="field-lg">
              服务地址
              <input
                placeholder="http://服务器IP:4533"
                value={config.navidrome.url}
                onChange={(e) =>
                  update("navidrome", {
                    ...config.navidrome,
                    url: e.target.value,
                  })
                }
              />
            </label>
            <label className="field-sm">
              用户名
              <input
                autoComplete="off"
                value={config.navidrome.username}
                onChange={(e) =>
                  update("navidrome", {
                    ...config.navidrome,
                    username: e.target.value,
                  })
                }
              />
            </label>
          </div>
          <div className="field-row">
            <label className="field-md">
              密码
              <input
                type="password"
                autoComplete="new-password"
                placeholder={
                  config.navidrome.has_password
                    ? "已保存，留空保留"
                    : "Navidrome 密码"
                }
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            <label className="field-md">
              API Key
              <input
                type="password"
                autoComplete="new-password"
                placeholder={
                  config.navidrome.has_api_key
                    ? "已保存，留空保留"
                    : "可选，可代替用户名和密码"
                }
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
              />
            </label>
          </div>
          <label className="check-label">
            <input
              type="checkbox"
              checked={config.navidrome.auto_scan}
              onChange={(e) =>
                update("navidrome", {
                  ...config.navidrome,
                  auto_scan: e.target.checked,
                })
              }
            />
            本地整理完成后自动触发 Navidrome 扫描
          </label>
          {(config.navidrome.has_password || config.navidrome.has_api_key) && (
            <label className="check-label">
              <input
                type="checkbox"
                checked={clearCredentials}
                onChange={(e) => setClearCredentials(e.target.checked)}
              />
              清除原有认证信息
            </label>
          )}
          <label className="check-label">
            <input
              type="checkbox"
              checked={!!config.navidrome.delete_enabled}
              onChange={(e) =>
                update("navidrome", {
                  ...config.navidrome,
                  delete_enabled: e.target.checked,
                })
              }
            />
            允许删除 Navidrome 曲库原文件
          </label>
          {config.navidrome.delete_enabled && (
            <>
              <div className="field-row">
                <label className="field-md">
                  MeloKeep 中的共享目录
                  <input
                    value={config.navidrome.music_root || ""}
                    placeholder={`/music/library（位于 ${config.file_root} 内）`}
                    onChange={(e) =>
                      update("navidrome", {
                        ...config.navidrome,
                        music_root: e.target.value,
                      })
                    }
                  />
                </label>
                <label className="field-md">
                  Navidrome 中的同一目录
                  <input
                    value={config.navidrome.server_music_root || ""}
                    placeholder="/music"
                    onChange={(e) =>
                      update("navidrome", {
                        ...config.navidrome,
                        server_music_root: e.target.value,
                      })
                    }
                  />
                </label>
              </div>
              <p className="setting-help">
                需在 Navidrome 播放器中为 melokeep 开启 Report Real Path。
              </p>
            </>
          )}
          <div className="management-toolbar settings-sticky-bar">
            <button className="primary" disabled={!!busy}>
              {busy === "nav" ? "正在保存…" : "保存 Navidrome"}
            </button>
            <button
              className="secondary"
              type="button"
              disabled={!!busy || !config.navidrome.enabled}
              onClick={() => void saveNav(true)}
            >
              <PlugZap />
              {busy === "test" ? "正在连接…" : "保存并测试连接"}
            </button>
          </div>
          {navMessage && <p role="status">{navMessage}</p>}
        </form>
      )}
      {error && (
        <p className="library-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
