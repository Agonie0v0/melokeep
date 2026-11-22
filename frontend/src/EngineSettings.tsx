import { useEffect, useMemo, useState } from "react";
import { Check, Search } from "lucide-react";
import { api } from "./api";
import { PluginSettings } from "./PluginSettings";
import {
  SOURCE_FILTER_ALL,
  SOURCE_FILTER_SELECTED,
  availableSourceCategory,
  filterAvailableSources,
  isPluginSource,
  selectOnlySourceKeys,
  sourceEngineKey,
} from "./plugin-utils";
import { SourceDisclosure } from "./SourceDisclosure";
import { SourceFilterBar } from "./SourceFilterBar";
import type { AvailableSource } from "./types";

type JsonObject = Record<string, unknown>;
interface EngineConfig {
  music_sources: string[];
  global_client_cfg: JsonObject;
  proxy_url: string;
  proxy_scope: string;
  proxy_sources: string[];
  request_timeout: number;
  default_threads: number;
  download_layout: string;
  lyrics_mode: string;
  cookies: Record<string, string | JsonObject>;
}

export function EngineSettings({
  sources,
  saved,
}: {
  sources: AvailableSource[];
  saved: () => Promise<void>;
}) {
  const [config, setConfig] = useState<EngineConfig | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [proxyQuery, setProxyQuery] = useState("");
  const [proxyCategory, setProxyCategory] = useState(SOURCE_FILTER_ALL);
  const [sourceQuery, setSourceQuery] = useState("");
  const [sourceCategory, setSourceCategory] = useState(SOURCE_FILTER_ALL);
  const globalKeys = ["work_dir", "search_size_per_source"];

  const availableCategories = useMemo(
    () => new Set(sources.map((source) => availableSourceCategory(source))),
    [sources],
  );
  const effectiveProxyCategory =
    proxyCategory === SOURCE_FILTER_ALL ||
    proxyCategory === SOURCE_FILTER_SELECTED ||
    availableCategories.has(proxyCategory)
      ? proxyCategory
      : SOURCE_FILTER_ALL;
  const effectiveSourceCategory =
    sourceCategory === SOURCE_FILTER_ALL ||
    sourceCategory === SOURCE_FILTER_SELECTED ||
    availableCategories.has(sourceCategory)
      ? sourceCategory
      : SOURCE_FILTER_ALL;

  useEffect(() => {
    if (effectiveProxyCategory !== proxyCategory) {
      setProxyCategory(SOURCE_FILTER_ALL);
    }
  }, [effectiveProxyCategory, proxyCategory]);

  useEffect(() => {
    if (effectiveSourceCategory !== sourceCategory) {
      setSourceCategory(SOURCE_FILTER_ALL);
    }
  }, [effectiveSourceCategory, sourceCategory]);

  const proxySources = config?.proxy_sources ?? [];
  const musicSources = config?.music_sources ?? [];

  const filteredProxySources = useMemo(
    () =>
      filterAvailableSources(sources, {
        query: proxyQuery,
        category: effectiveProxyCategory,
        selectedKeys: proxySources,
      }),
    [sources, proxyQuery, effectiveProxyCategory, proxySources],
  );

  const filteredMusicSources = useMemo(
    () =>
      filterAvailableSources(sources, {
        query: sourceQuery,
        category: effectiveSourceCategory,
        selectedKeys: musicSources,
      }),
    [sources, sourceQuery, effectiveSourceCategory, musicSources],
  );
  function hydrate(raw: EngineConfig) {
    const globalCfg =
      raw?.global_client_cfg && typeof raw.global_client_cfg === "object"
        ? raw.global_client_cfg
        : {};
    const value: EngineConfig = {
      music_sources: Array.isArray(raw?.music_sources) ? raw.music_sources : [],
      global_client_cfg: globalCfg,
      proxy_url: raw?.proxy_url ?? "",
      proxy_scope: raw?.proxy_scope ?? "none",
      proxy_sources: Array.isArray(raw?.proxy_sources) ? raw.proxy_sources : [],
      request_timeout: Number(raw?.request_timeout ?? 30),
      default_threads: Number(raw?.default_threads ?? 4),
      download_layout: raw?.download_layout ?? "source",
      lyrics_mode: raw?.lyrics_mode ?? "both",
      cookies:
        raw?.cookies && typeof raw.cookies === "object" ? raw.cookies : {},
    };
    setConfig(value);
  }
  useEffect(() => {
    let active = true;
    void api<EngineConfig>("/engine/config")
      .then((value) => {
        if (active) hydrate(value);
      })
      .catch((e: Error) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, []);
  function updateGlobal(key: string, value: unknown) {
    setConfig(
      (current) =>
        current && {
          ...current,
          global_client_cfg: { ...current.global_client_cfg, [key]: value },
        },
    );
  }
  const cookieText = (value: string | JsonObject | undefined) =>
    typeof value === "string"
      ? value
      : Object.entries(value || {})
          .map(([key, val]) => `${key}=${val}`)
          .join("; ");
  if (!config)
    return (
      <div className="engine-settings-stack engine-settings">
        <section className="settings-section">
          <p role={error ? "alert" : "status"}>
            {error || "正在读取完整设置…"}
          </p>
          {error && (
            <button
              type="button"
              className="secondary"
              onClick={() => {
                setError("");
                void api<EngineConfig>("/engine/config")
                  .then(hydrate)
                  .catch((e: Error) => setError(e.message));
              }}
            >
              重新读取设置
            </button>
          )}
        </section>
        <PluginSettings onChanged={saved} />
      </div>
    );
  return (
    <div className="engine-settings-stack engine-settings">
      <form
        className="settings-form"
        onChange={() => setMessage("")}
        onSubmit={async (event) => {
          event.preventDefault();
          setError("");
          setMessage("");
          setBusy(true);
          try {
            const payload = {
              ...config,
              global_client_cfg: Object.fromEntries(
                Object.entries(config.global_client_cfg).filter(([key]) =>
                  globalKeys.includes(key),
                ),
              ),
            };
            const result = await api<{
              source_errors?: Record<string, string>;
            }>("/engine/config", "PUT", payload);
            hydrate(await api<EngineConfig>("/engine/config"));
            await saved();
            const unavailable = Object.entries(result.source_errors || {});
            setMessage(
              unavailable.length
                ? `设置已保存，${unavailable.length} 个音源暂不可用，其他音源已生效。${unavailable.map(([name, hint]) => `${sources.find((s) => sourceEngineKey(s) === name)?.label || name}：${hint}`).join("；")}`
                : "全部设置已保存并生效",
            );
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <section className="settings-section settings-basics">
          <h2>基础设置</h2>
          <div className="field-row">
            <label className="field-sm">
              下载目录
              <input
                value={String(config.global_client_cfg.work_dir || "")}
                onChange={(e) => updateGlobal("work_dir", e.target.value)}
                required
              />
            </label>
            <label className="field-sm">
              目录组织
              <select
                aria-label="目录组织"
                value={config.download_layout}
                onChange={(e) =>
                  setConfig({ ...config, download_layout: e.target.value })
                }
              >
                {[
                  ["source", "按音源"],
                  ["artist", "按歌手"],
                  ["artist_album", "歌手 / 专辑"],
                  ["flat", "全部平铺"],
                ].map(([id, label]) => (
                  <option key={id} value={id}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label className="field-sm">
              歌词保存
              <select
                aria-label="歌词保存"
                value={config.lyrics_mode}
                onChange={(e) =>
                  setConfig({ ...config, lyrics_mode: e.target.value })
                }
              >
                {[
                  ["both", "LRC 文件与嵌入"],
                  ["lrc", "LRC 文件"],
                  ["embed", "嵌入音频"],
                  ["none", "不保存"],
                ].map(([id, label]) => (
                  <option key={id} value={id}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className="field-row">
            {[["search_size_per_source", "每个音源结果数", 1, 50]].map(
              ([key, label, min, max]) => (
                <label key={key} className="field-xs">
                  {label}
                  <input
                    type="number"
                    min={min}
                    max={max}
                    required
                    value={Number(config.global_client_cfg[key] ?? min)}
                    onChange={(e) =>
                      updateGlobal(String(key), Number(e.target.value))
                    }
                  />
                </label>
              ),
            )}
            <label className="field-xs">
              默认下载并发数
              <input
                type="number"
                min="1"
                max="4"
                required
                value={config.default_threads}
                onChange={(e) =>
                  setConfig({
                    ...config,
                    default_threads: Number(e.target.value),
                  })
                }
              />
            </label>
            <label className="field-xs">
              请求超时
              <span className="field-unit" aria-hidden="true">
                （秒）
              </span>
              <input
                type="number"
                min="0"
                max="300"
                required
                aria-label="请求超时"
                placeholder="0 不限"
                title="单位：秒，0 表示不限"
                value={config.request_timeout}
                onChange={(e) =>
                  setConfig({
                    ...config,
                    request_timeout: Number(e.target.value),
                  })
                }
              />
            </label>
          </div>
        </section>
        <section className="settings-section settings-proxy">
          <h2>项目代理</h2>
          <div className="field-row">
            <label className="field-lg">
              项目 HTTP / HTTPS 代理
              <input
                value={config.proxy_url}
                onChange={(e) =>
                  setConfig({ ...config, proxy_url: e.target.value })
                }
                placeholder="留空不使用代理"
              />
            </label>
            <label className="field-sm">
              音源代理作用范围
              <select
                aria-label="音源代理作用范围"
                value={config.proxy_scope}
                onChange={(e) =>
                  setConfig({ ...config, proxy_scope: e.target.value })
                }
              >
                <option value="none">音源不使用代理</option>
                <option value="all">全部音源</option>
                <option value="selected">仅指定音源</option>
              </select>
            </label>
          </div>
          {config.proxy_scope === "selected" && (
            <SourceDisclosure
              summary={`代理音源 · 已选 ${config.proxy_sources.length} 个`}
            >
              <SourceFilterBar
                sources={sources}
                selectedKeys={config.proxy_sources}
                query={proxyQuery}
                onQueryChange={setProxyQuery}
                category={effectiveProxyCategory}
                onCategoryChange={setProxyCategory}
                filteredSources={filteredProxySources}
                onSelectKeys={(next) =>
                  setConfig({ ...config, proxy_sources: next })
                }
                selectedLabel="已选代理"
                clearLabel="清空代理选择"
                disabled={busy}
                extraActions={
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() =>
                      setConfig({
                        ...config,
                        proxy_sources: selectOnlySourceKeys(
                          sources
                            .filter((source) => source.proxy_recommended)
                            .map((source) => sourceEngineKey(source)),
                        ),
                      })
                    }
                  >
                    选择推荐音源
                  </button>
                }
              />
              {filteredProxySources.length ? (
                <fieldset className="source-picker">
                  <legend>使用全局代理的音源</legend>
                  {filteredProxySources.map((source) => {
                    const name = sourceEngineKey(source);
                    const pluginOrigin = isPluginSource(source);
                    return (
                      <label className="check-label" key={source.id}>
                        <input
                          type="checkbox"
                          checked={config.proxy_sources.includes(name)}
                          onChange={(e) =>
                            setConfig({
                              ...config,
                              proxy_sources: e.target.checked
                                ? selectOnlySourceKeys([
                                    ...config.proxy_sources,
                                    name,
                                  ])
                                : config.proxy_sources.filter(
                                    (v) => v !== name,
                                  ),
                            })
                          }
                        />
                        <span>{source.label}</span>
                        <small
                          className={`origin-badge ${pluginOrigin ? "is-plugin" : "is-builtin"}`}
                        >
                          {pluginOrigin ? "自定义插件" : "内置开放源"}
                        </small>
                        {source.proxy_recommended && <small>推荐代理</small>}
                      </label>
                    );
                  })}
                </fieldset>
              ) : (
                <div className="source-filter-empty" role="status">
                  <Search aria-hidden="true" />
                  <div>
                    <strong>没有符合筛选条件的代理音源</strong>
                    <p>可以清空搜索词或切换分类，查看全部可用音源。</p>
                  </div>
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => {
                      setProxyQuery("");
                      setProxyCategory(SOURCE_FILTER_ALL);
                    }}
                  >
                    重置筛选
                  </button>
                </div>
              )}
            </SourceDisclosure>
          )}
        </section>
        <section className="settings-section settings-sources">
          <h2>全部音源与账号设置</h2>
          <SourceDisclosure
            summary={`搜索音源与账号 · 已选 ${config.music_sources.length} / ${sources.length} 个`}
          >
            <SourceFilterBar
              sources={sources}
              selectedKeys={config.music_sources}
              query={sourceQuery}
              onQueryChange={setSourceQuery}
              category={effectiveSourceCategory}
              onCategoryChange={setSourceCategory}
              filteredSources={filteredMusicSources}
              onSelectKeys={(next) =>
                setConfig({ ...config, music_sources: next })
              }
              selectedLabel="已启用"
              disabled={busy}
            />
            {filteredMusicSources.length ? (
              <div className="source-settings-grid">
                {filteredMusicSources.map((source) => {
                  const name = sourceEngineKey(source);
                  const pluginOrigin = isPluginSource(source);
                  return (
                    <details key={source.id} className="source-setting">
                      <summary>
                        <span className="source-setting-heading">
                          <span>{source.label}</span>
                          <span
                            className={`origin-badge ${pluginOrigin ? "is-plugin" : "is-builtin"}`}
                          >
                            {pluginOrigin ? "自定义插件" : "内置开放源"}
                          </span>
                          {source.category && (
                            <span className="origin-badge">
                              {source.category}
                            </span>
                          )}
                          {pluginOrigin && source.version && (
                            <span className="origin-badge">
                              v{source.version}
                            </span>
                          )}
                        </span>
                        <label
                          className="check-label"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <input
                            type="checkbox"
                            aria-label={`启用 ${source.label}`}
                            checked={config.music_sources.includes(name)}
                            onChange={(e) =>
                              setConfig({
                                ...config,
                                music_sources: e.target.checked
                                  ? selectOnlySourceKeys([
                                      ...config.music_sources,
                                      name,
                                    ])
                                  : config.music_sources.filter(
                                      (v) => v !== name,
                                    ),
                              })
                            }
                          />
                        </label>
                      </summary>
                      {source.description && (
                        <p className="setting-help">{source.description}</p>
                      )}
                      {source.capability?.note && (
                        <p className="setting-help" role="status">
                          {source.capability.note}
                        </p>
                      )}
                      {source.init_error && (
                        <p className="setting-help" role="status">
                          暂不可用：{source.init_error}。补全配置后再次保存。
                        </p>
                      )}
                      {pluginOrigin && (
                        <p className="setting-help">
                          此音源由自定义 JS
                          插件提供，插件专属参数与内网白名单请在下方「自定义 JS
                          音源插件」面板配置。
                        </p>
                      )}
                      <label className="field-xl">
                        Cookie / API Key
                        <textarea
                          aria-label="Cookie"
                          spellCheck={false}
                          autoComplete="off"
                          value={cookieText(config.cookies[name])}
                          onChange={(e) =>
                            setConfig({
                              ...config,
                              cookies: {
                                ...config.cookies,
                                [name]: e.target.value,
                              },
                            })
                          }
                          placeholder="未配置；如 Jamendo 可填写 client_id=你的应用ID"
                        />
                      </label>
                      <div className="field-row">
                        <label className="field-sm">
                          音源代理
                          <select
                            aria-label="音源代理"
                            value={
                              config.proxy_scope === "all" ||
                              (config.proxy_scope === "selected" &&
                                config.proxy_sources.includes(name))
                                ? "project"
                                : "direct"
                            }
                            onChange={(e) => {
                              const selected =
                                config.proxy_scope === "all"
                                  ? sources.map((s) => sourceEngineKey(s))
                                  : config.proxy_scope === "selected"
                                    ? config.proxy_sources
                                    : [];
                              setConfig({
                                ...config,
                                proxy_scope: "selected",
                                proxy_sources:
                                  e.target.value === "project"
                                    ? [...new Set([...selected, name])]
                                    : selected.filter((v) => v !== name),
                              });
                            }}
                          >
                            <option value="direct">不使用代理</option>
                            <option value="project">使用项目代理</option>
                          </select>
                        </label>
                      </div>
                    </details>
                  );
                })}
              </div>
            ) : (
              <div className="source-filter-empty" role="status">
                <Search aria-hidden="true" />
                <div>
                  <strong>没有符合筛选条件的音源</strong>
                  <p>可以清空搜索词或切换分类，查看全部可用音源。</p>
                </div>
                <button
                  type="button"
                  className="secondary"
                  onClick={() => {
                    setSourceQuery("");
                    setSourceCategory(SOURCE_FILTER_ALL);
                  }}
                >
                  重置筛选
                </button>
              </div>
            )}
          </SourceDisclosure>
        </section>
        <div className="settings-save settings-sticky-bar">
          <button
            className="primary"
            disabled={busy || !config.music_sources.length}
          >
            <Check />
            {busy ? "正在保存…" : "保存设置"}
          </button>
          {error && <p role="alert">{error}</p>}
          {message && <p role="status">{message}</p>}
          {!config.music_sources.length && (
            <p role="status">请选择至少一个音源后保存。</p>
          )}
        </div>
      </form>
      <PluginSettings onChanged={saved} />
    </div>
  );
}
