import { useCallback, useEffect, useId, useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  Code2,
  FileCode,
  Link as LinkIcon,
  Play,
  RefreshCw,
  Settings2,
  ShieldAlert,
  Trash2,
  Upload,
} from "lucide-react";
import { api } from "./api";
import { Modal } from "./components";
import {
  buildPluginSettingsPayload,
  formatAllowedOrigins,
  isMaskedSecretValue,
  isSecretFieldType,
  parseAllowedOrigins,
  pluginCapabilityBadges,
} from "./plugin-utils";
import type {
  PluginInspectResult,
  PluginTestAction,
  PluginTestResult,
  ProviderPlugin,
} from "./types";

type ImportMode = "file" | "code" | "url";

const MAX_PLUGIN_BYTES = 512 * 1024;

const TEST_ACTIONS: { value: PluginTestAction; label: string }[] = [
  { value: "search", label: "search（搜索曲目）" },
  { value: "resolve", label: "resolve（解析音质与播放地址）" },
  { value: "playlist", label: "playlist（导入歌单）" },
  { value: "lyrics", label: "lyrics（获取歌词）" },
  { value: "metadata", label: "metadata（曲库刮削元数据）" },
  { value: "episodes", label: "episodes（获取分集列表）" },
];

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(1)} KiB`;
}

function extractFirstTrack(result: unknown): Record<string, unknown> | null {
  let list: unknown[] = [];
  if (Array.isArray(result)) {
    list = result;
  } else if (result && typeof result === "object") {
    const obj = result as Record<string, unknown>;
    if (Array.isArray(obj.tracks)) list = obj.tracks;
    else if (Array.isArray(obj.items)) list = obj.items;
  }
  const first = list.find((item) => item && typeof item === "object");
  return (first as Record<string, unknown>) || null;
}

export function PluginSettings({
  onChanged,
}: {
  onChanged: () => Promise<void>;
}) {
  const headingId = useId();
  const [plugins, setPlugins] = useState<ProviderPlugin[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [busyId, setBusyId] = useState("");
  const [notice, setNotice] = useState("");
  const [actionError, setActionError] = useState("");

  // Import state
  const [importMode, setImportMode] = useState<ImportMode>("file");
  const [fileName, setFileName] = useState("");
  const [fileCode, setFileCode] = useState("");
  const [pastedCode, setPastedCode] = useState("");
  const [sourceUrl, setSourceUrl] = useState("");
  const [inspecting, setInspecting] = useState(false);
  const [inspectDraft, setInspectDraft] = useState<{
    code?: string;
    source_url?: string;
    result: PluginInspectResult;
  } | null>(null);
  const [installEnabled, setInstallEnabled] = useState(true);
  const [installing, setInstalling] = useState(false);

  // Config modal state
  const [configPlugin, setConfigPlugin] = useState<ProviderPlugin | null>(null);
  const [settingDrafts, setSettingDrafts] = useState<Record<string, string>>(
    {},
  );
  const [clearedSecrets, setClearedSecrets] = useState<string[]>([]);
  const [originsText, setOriginsText] = useState("");
  const [savingConfig, setSavingConfig] = useState(false);
  const [configError, setConfigError] = useState("");

  // Test modal state
  const [testPlugin, setTestPlugin] = useState<ProviderPlugin | null>(null);
  const [testAction, setTestAction] = useState<PluginTestAction>("search");
  const [testKeyword, setTestKeyword] = useState("ambient");
  const [testLocator, setTestLocator] = useState("");
  const [testTrackSourceId, setTestTrackSourceId] = useState("demo-001");
  const [testTrackTitle, setTestTrackTitle] = useState("Solar Wind");
  const [testTrackArtist, setTestTrackArtist] = useState("Open Horizon");
  const [testTrackAlbum, setTestTrackAlbum] = useState("");
  const [testTrackJson, setTestTrackJson] = useState("");
  const [testing, setTesting] = useState(false);
  const [testError, setTestError] = useState("");
  const [testResult, setTestResult] = useState<PluginTestResult | null>(null);

  // Uninstall confirm modal state
  const [uninstallTarget, setUninstallTarget] = useState<ProviderPlugin | null>(
    null,
  );
  const [uninstalling, setUninstalling] = useState(false);

  const loadPlugins = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const res = await api<{ plugins: ProviderPlugin[] }>("/plugins");
      setPlugins(Array.isArray(res?.plugins) ? res.plugins : []);
    } catch (e) {
      setLoadError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadPlugins();
  }, [loadPlugins]);

  const activeCount = plugins.filter((p) => p.enabled).length;

  function activeImportBody(): { code?: string; source_url?: string } | null {
    if (importMode === "file") {
      const code = fileCode.trim();
      return code ? { code: fileCode } : null;
    }
    if (importMode === "code") {
      const code = pastedCode.trim();
      return code ? { code: pastedCode } : null;
    }
    const url = sourceUrl.trim();
    return url ? { source_url: url } : null;
  }

  async function handleFileSelect(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    setActionError("");
    setNotice("");
    if (!file) {
      setFileName("");
      setFileCode("");
      return;
    }
    if (file.size > MAX_PLUGIN_BYTES) {
      setActionError(
        `脚本文件过大（${formatBytes(file.size)}），上限为 512 KiB。`,
      );
      event.target.value = "";
      return;
    }
    try {
      const text = await file.text();
      setFileName(file.name);
      setFileCode(text);
    } catch {
      setActionError("读取本地脚本文件失败，请确认文件为 UTF-8 编码的文本。");
    }
  }

  async function handleInspect() {
    const body = activeImportBody();
    if (!body) return;
    if (
      body.source_url &&
      !/^https:\/\//i.test(body.source_url) &&
      !/^http:\/\//i.test(body.source_url)
    ) {
      setActionError("请输入有效的 HTTPS 插件脚本地址。");
      return;
    }
    setInspecting(true);
    setActionError("");
    setNotice("");
    try {
      const result = await api<PluginInspectResult>(
        "/plugins/inspect",
        "POST",
        body,
      );
      setInstallEnabled(true);
      setInspectDraft({ ...body, result });
    } catch (e) {
      setActionError((e as Error).message);
    } finally {
      setInspecting(false);
    }
  }

  async function handleConfirmInstall() {
    if (!inspectDraft) return;
    setInstalling(true);
    setActionError("");
    try {
      const payload: {
        code?: string;
        source_url?: string;
        enabled: boolean;
      } = { enabled: installEnabled };
      if (inspectDraft.code !== undefined) payload.code = inspectDraft.code;
      if (inspectDraft.source_url !== undefined)
        payload.source_url = inspectDraft.source_url;
      const res = await api<{ plugin: ProviderPlugin }>(
        "/plugins",
        "POST",
        payload,
      );
      setInspectDraft(null);
      setFileName("");
      setFileCode("");
      setPastedCode("");
      setSourceUrl("");
      setNotice(
        `已安装插件「${res.plugin.manifest.name}」（${res.plugin.id} v${res.plugin.manifest.version}）。`,
      );
      await loadPlugins();
      await onChanged();
    } catch (e) {
      setActionError((e as Error).message);
    } finally {
      setInstalling(false);
    }
  }

  async function handleToggle(plugin: ProviderPlugin) {
    const nextEnabled = !plugin.enabled;
    setBusyId(plugin.id);
    setActionError("");
    setNotice("");
    setPlugins((current) =>
      current.map((item) =>
        item.id === plugin.id ? { ...item, enabled: nextEnabled } : item,
      ),
    );
    try {
      const res = await api<{ plugin: ProviderPlugin }>(
        `/plugins/${encodeURIComponent(plugin.id)}`,
        "PATCH",
        { enabled: nextEnabled },
      );
      setPlugins((current) =>
        current.map((item) => (item.id === plugin.id ? res.plugin : item)),
      );
      setNotice(
        `插件「${res.plugin.manifest.name}」已${res.plugin.enabled ? "启用" : "停用"}。`,
      );
      await onChanged();
    } catch (e) {
      setPlugins((current) =>
        current.map((item) =>
          item.id === plugin.id ? { ...item, enabled: plugin.enabled } : item,
        ),
      );
      setActionError((e as Error).message);
    } finally {
      setBusyId("");
    }
  }

  function openConfigModal(plugin: ProviderPlugin) {
    const initial: Record<string, string> = {};
    for (const field of plugin.manifest.settings || []) {
      const existing = plugin.settings?.[field.key];
      if (isSecretFieldType(field.type)) {
        initial[field.key] = "";
      } else if (existing !== undefined) {
        initial[field.key] = existing;
      } else {
        initial[field.key] = field.default_value ?? "";
      }
    }
    setSettingDrafts(initial);
    setClearedSecrets([]);
    setOriginsText(formatAllowedOrigins(plugin.allowed_origins));
    setConfigError("");
    setConfigPlugin(plugin);
  }

  async function handleSaveConfig(event: React.FormEvent) {
    event.preventDefault();
    if (!configPlugin) return;
    setSavingConfig(true);
    setConfigError("");
    try {
      const settings = buildPluginSettingsPayload(
        configPlugin.manifest.settings,
        settingDrafts,
        configPlugin.settings || {},
        clearedSecrets,
      );
      const allowed_origins = parseAllowedOrigins(originsText);
      const res = await api<{ plugin: ProviderPlugin }>(
        `/plugins/${encodeURIComponent(configPlugin.id)}`,
        "PATCH",
        { settings, allowed_origins },
      );
      setPlugins((current) =>
        current.map((item) =>
          item.id === configPlugin.id ? res.plugin : item,
        ),
      );
      setConfigPlugin(null);
      setNotice(`已保存插件「${res.plugin.manifest.name}」配置。`);
      await onChanged();
    } catch (e) {
      setConfigError((e as Error).message);
    } finally {
      setSavingConfig(false);
    }
  }

  function openTestModal(plugin: ProviderPlugin) {
    const caps = plugin.manifest.capabilities;
    const defaultAction: PluginTestAction = caps?.search
      ? "search"
      : caps?.playlist
        ? "playlist"
        : caps?.metadata
          ? "metadata"
          : caps?.lyrics
            ? "lyrics"
            : "resolve";
    setTestPlugin(plugin);
    setTestAction(defaultAction);
    setTestResult(null);
    setTestError("");
    setTestTrackJson("");
  }

  async function handleRunTest(event: React.FormEvent) {
    event.preventDefault();
    if (!testPlugin) return;
    setTesting(true);
    setTestError("");
    setTestResult(null);
    try {
      const body: Record<string, unknown> = { action: testAction };
      if (testAction === "search" || testAction === "metadata") {
        body.keyword = testKeyword;
      } else if (testAction === "playlist") {
        body.locator = testLocator;
      } else {
        if (testTrackJson.trim()) {
          let parsed: unknown;
          try {
            parsed = JSON.parse(testTrackJson);
          } catch {
            throw new Error("Track JSON 格式无效，请检查语法。");
          }
          if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
            throw new Error("Track JSON 必须是一个对象。");
          }
          body.track = parsed;
        } else {
          body.track = {
            source_id: testTrackSourceId.trim(),
            title: testTrackTitle.trim(),
            artist: testTrackArtist.trim(),
            album: testTrackAlbum.trim(),
          };
        }
      }
      const res = await api<PluginTestResult>(
        `/plugins/${encodeURIComponent(testPlugin.id)}/test`,
        "POST",
        body,
      );
      setTestResult(res);
    } catch (e) {
      setTestError((e as Error).message);
    } finally {
      setTesting(false);
    }
  }

  async function handleConfirmUninstall() {
    if (!uninstallTarget) return;
    setUninstalling(true);
    setActionError("");
    try {
      await api<{ ok: boolean; id: string }>(
        `/plugins/${encodeURIComponent(uninstallTarget.id)}`,
        "DELETE",
      );
      const removedName = uninstallTarget.manifest.name;
      setUninstallTarget(null);
      setNotice(`已卸载插件「${removedName}」。`);
      await loadPlugins();
      await onChanged();
    } catch (e) {
      setActionError((e as Error).message);
    } finally {
      setUninstalling(false);
    }
  }

  const canInspect = Boolean(activeImportBody());
  const firstSearchTrack =
    testResult?.ok && testResult.action === "search"
      ? extractFirstTrack(testResult.result)
      : null;

  return (
    <section
      className="settings-section plugin-settings"
      aria-labelledby={headingId}
    >
      <div className="plugin-section-header">
        <div>
          <h2 id={headingId}>
            自定义 JS 音源插件
            <small>
              已安装 {plugins.length} 个 · 启用 {activeCount} 个
            </small>
          </h2>
          <p className="setting-help">
            MeloKeep 使用纯 Go 隔离沙箱（goja）运行符合 Provider Plugin API v1
            的单文件 JS 插件，可用于接入开放许可库、公开播客或自托管媒体服务。
          </p>
        </div>
        <button
          type="button"
          className="secondary"
          disabled={loading}
          onClick={() => void loadPlugins()}
        >
          <RefreshCw />
          刷新列表
        </button>
      </div>

      {notice && (
        <p className="plugin-status-msg" role="status">
          <CheckCircle2 aria-hidden="true" />
          {notice}
        </p>
      )}
      {actionError && (
        <p className="plugin-error-banner" role="alert">
          <AlertTriangle aria-hidden="true" />
          {actionError}
        </p>
      )}

      {loading ? (
        <p role="status">正在加载自定义插件…</p>
      ) : loadError ? (
        <div className="plugin-load-error">
          <p role="alert">{loadError}</p>
          <button
            type="button"
            className="secondary"
            onClick={() => void loadPlugins()}
          >
            重试加载
          </button>
        </div>
      ) : !plugins.length ? (
        <div className="plugin-empty">
          <Code2 aria-hidden="true" />
          <div>
            <strong>暂未安装自定义 JS 插件</strong>
            <p>
              可通过下方任意方式导入合规脚本，经静态检查确认后即可立即生效，无需重启服务。
            </p>
          </div>
        </div>
      ) : (
        <div className="plugin-list">
          {plugins.map((plugin) => {
            const caps = pluginCapabilityBadges(plugin.manifest.capabilities);
            const busy = busyId === plugin.id;
            return (
              <article
                className={`plugin-card ${plugin.enabled ? "is-enabled" : "is-disabled"}`}
                key={plugin.id}
              >
                <div className="plugin-card-head">
                  <div className="plugin-card-title">
                    <div className="plugin-card-name-row">
                      <strong>{plugin.manifest.name}</strong>
                      <code className="plugin-id-code">{plugin.id}</code>
                      <span className="origin-badge is-plugin">
                        v{plugin.manifest.version}
                      </span>
                      {plugin.manifest.category && (
                        <span className="origin-badge">
                          {plugin.manifest.category}
                        </span>
                      )}
                    </div>
                    {plugin.manifest.description && (
                      <p className="plugin-card-desc">
                        {plugin.manifest.description}
                      </p>
                    )}
                  </div>
                  <label className="plugin-switch">
                    <input
                      type="checkbox"
                      aria-label={`启用插件 ${plugin.manifest.name}`}
                      checked={plugin.enabled}
                      disabled={busy}
                      onChange={() => void handleToggle(plugin)}
                    />
                    <span>{plugin.enabled ? "已启用" : "已停用"}</span>
                  </label>
                </div>

                <div className="plugin-card-meta">
                  <div className="plugin-cap-list" aria-label="插件能力">
                    {caps.map((cap) => (
                      <span className="plugin-cap-badge" key={cap.key}>
                        {cap.label}
                      </span>
                    ))}
                  </div>
                  {plugin.sha256 && (
                    <span
                      className="plugin-sha"
                      title={`SHA-256: ${plugin.sha256}`}
                    >
                      SHA-256: <code>{plugin.sha256.slice(0, 12)}…</code>
                    </span>
                  )}
                  {plugin.source_url && (
                    <span
                      className="plugin-source-url"
                      title={plugin.source_url}
                    >
                      来源: {plugin.source_url}
                    </span>
                  )}
                </div>

                {plugin.last_error && (
                  <p className="plugin-error-banner" role="alert">
                    <AlertTriangle aria-hidden="true" />
                    最近错误：{plugin.last_error}
                  </p>
                )}

                <div className="plugin-card-actions">
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() => openTestModal(plugin)}
                  >
                    <Play />
                    测试
                  </button>
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() => openConfigModal(plugin)}
                  >
                    <Settings2 />
                    配置
                  </button>
                  <button
                    type="button"
                    className="secondary danger-text"
                    disabled={busy}
                    onClick={() => {
                      setActionError("");
                      setUninstallTarget(plugin);
                    }}
                  >
                    <Trash2 />
                    卸载
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}

      <div className="plugin-import-box">
        <h3>安装自定义插件</h3>
        <div
          className="plugin-import-tabs"
          role="tablist"
          aria-label="插件导入方式"
        >
          <button
            type="button"
            role="tab"
            aria-selected={importMode === "file"}
            className={importMode === "file" ? "primary" : "secondary"}
            onClick={() => setImportMode("file")}
          >
            <Upload />
            选择本地 .js 文件
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={importMode === "code"}
            className={importMode === "code" ? "primary" : "secondary"}
            onClick={() => setImportMode("code")}
          >
            <FileCode />
            直接粘贴 JS 源码
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={importMode === "url"}
            className={importMode === "url" ? "primary" : "secondary"}
            onClick={() => setImportMode("url")}
          >
            <LinkIcon />从 HTTPS URL 拉取
          </button>
        </div>

        {importMode === "file" && (
          <div className="plugin-import-panel">
            <label>
              选择插件脚本（.js，最大 512 KiB）
              <input
                type="file"
                accept=".js,text/javascript,application/javascript"
                onChange={(e) => void handleFileSelect(e)}
              />
            </label>
            {fileName && (
              <p className="setting-help">
                已读取：<strong>{fileName}</strong>（
                {formatBytes(new Blob([fileCode]).size)}）
              </p>
            )}
          </div>
        )}

        {importMode === "code" && (
          <div className="plugin-import-panel">
            <label>
              插件 JavaScript 源码（CommonJS）
              <textarea
                rows={7}
                spellCheck={false}
                value={pastedCode}
                onChange={(e) => setPastedCode(e.target.value)}
                placeholder={
                  "// 遵循 MeloKeep Provider Plugin API v1\nconst manifest = { apiVersion: 1, id: 'custom-open-source', name: '示例音源', version: '1.0.0', capabilities: { search: true, playlist: false } };\nmodule.exports = { manifest, search(ctx, opts) {}, resolve(ctx, track) { return []; } };"
                }
              />
            </label>
          </div>
        )}

        {importMode === "url" && (
          <div className="plugin-import-panel">
            <label>
              插件脚本 HTTPS URL
              <input
                type="url"
                value={sourceUrl}
                onChange={(e) => setSourceUrl(e.target.value)}
                placeholder="https://example.org/plugins/open-provider.js"
              />
            </label>
            <p className="setting-help">
              仅支持公开公网地址；将先由服务端拉取并静态检查 manifest
              与权限，确认后才写入本地。
            </p>
          </div>
        )}

        <div className="plugin-import-footer">
          <button
            type="button"
            className="primary"
            disabled={inspecting || !canInspect}
            onClick={() => void handleInspect()}
          >
            {inspecting ? "正在静态检查…" : "检查并安装"}
          </button>
        </div>
      </div>

      {inspectDraft && (
        <Modal
          title="确认安装自定义插件"
          className="plugin-modal"
          close={
            installing
              ? undefined
              : () => {
                  setActionError("");
                  setInspectDraft(null);
                }
          }
        >
          <div className="plugin-security-notice" role="alert">
            <ShieldAlert aria-hidden="true" />
            <div>
              <strong>安全风险提醒</strong>
              <p>
                插件在受控沙箱中执行网络请求，默认拦截内网地址，请仅安装来自可信来源的脚本。
              </p>
            </div>
          </div>

          <dl className="plugin-inspect-meta">
            <div>
              <dt>插件名称</dt>
              <dd>{inspectDraft.result.manifest.name}</dd>
            </div>
            <div>
              <dt>插件 ID</dt>
              <dd>
                <code>{inspectDraft.result.manifest.id}</code>
              </dd>
            </div>
            <div>
              <dt>版本</dt>
              <dd>v{inspectDraft.result.manifest.version}</dd>
            </div>
            {inspectDraft.result.manifest.category && (
              <div>
                <dt>分类</dt>
                <dd>{inspectDraft.result.manifest.category}</dd>
              </div>
            )}
            <div className="plugin-inspect-full">
              <dt>SHA-256 摘要</dt>
              <dd>
                <code className="plugin-sha-full">
                  {inspectDraft.result.sha256}
                </code>
              </dd>
            </div>
            <div className="plugin-inspect-full">
              <dt>声明能力</dt>
              <dd className="plugin-cap-list">
                {pluginCapabilityBadges(
                  inspectDraft.result.manifest.capabilities,
                ).map((cap) => (
                  <span className="plugin-cap-badge" key={cap.key}>
                    {cap.label}
                  </span>
                ))}
              </dd>
            </div>
          </dl>

          {inspectDraft.result.manifest.description && (
            <p className="setting-help">
              {inspectDraft.result.manifest.description}
            </p>
          )}

          {!!inspectDraft.result.permissions?.length && (
            <div className="plugin-inspect-block">
              <h4>权限摘要</h4>
              <ul>
                {inspectDraft.result.permissions.map((perm, idx) => (
                  <li key={idx}>{perm}</li>
                ))}
              </ul>
            </div>
          )}

          {!!inspectDraft.result.warnings?.length && (
            <div className="plugin-inspect-warnings" role="status">
              <h4>检查警告</h4>
              <ul>
                {inspectDraft.result.warnings.map((warn, idx) => (
                  <li key={idx}>{warn}</li>
                ))}
              </ul>
            </div>
          )}

          <label className="check-label">
            <input
              type="checkbox"
              checked={installEnabled}
              onChange={(e) => setInstallEnabled(e.target.checked)}
            />
            安装后立即启用此插件
          </label>

          {actionError && (
            <p className="plugin-error-banner" role="alert">
              <AlertTriangle aria-hidden="true" />
              {actionError}
            </p>
          )}

          <div className="dialog-actions">
            <button
              type="button"
              className="secondary"
              disabled={installing}
              onClick={() => {
                setActionError("");
                setInspectDraft(null);
              }}
            >
              取消
            </button>
            <button
              type="button"
              className="primary"
              disabled={installing}
              onClick={() => void handleConfirmInstall()}
            >
              {installing ? "正在安装…" : "确认安装"}
            </button>
          </div>
        </Modal>
      )}

      {configPlugin && (
        <Modal
          title={`配置插件 · ${configPlugin.manifest.name}`}
          className="plugin-modal"
          close={savingConfig ? undefined : () => setConfigPlugin(null)}
        >
          <form
            className="plugin-modal-form"
            onSubmit={(e) => void handleSaveConfig(e)}
          >
            {configPlugin.manifest.settings?.length ? (
              <div className="plugin-dynamic-fields">
                {configPlugin.manifest.settings.map((field) => {
                  const secretField = isSecretFieldType(field.type);
                  const savedMasked = isMaskedSecretValue(
                    configPlugin.settings?.[field.key],
                  );
                  const cleared = clearedSecrets.includes(field.key);
                  if (field.type === "boolean") {
                    const checked =
                      (settingDrafts[field.key] ??
                        configPlugin.settings?.[field.key] ??
                        field.default_value) === "true";
                    return (
                      <label key={field.key} className="check-label">
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={(e) =>
                            setSettingDrafts({
                              ...settingDrafts,
                              [field.key]: e.target.checked ? "true" : "false",
                            })
                          }
                        />
                        {field.label}
                      </label>
                    );
                  }
                  if (secretField) {
                    return (
                      <div className="bot-secret" key={field.key}>
                        <label>
                          {field.label}
                          {field.required && " *"}
                          <input
                            type="password"
                            autoComplete="new-password"
                            disabled={cleared}
                            required={Boolean(field.required && !savedMasked)}
                            value={settingDrafts[field.key] ?? ""}
                            placeholder={
                              savedMasked
                                ? "已配置密钥，留空保持不变；输入新值可覆盖"
                                : field.placeholder || "请输入密钥"
                            }
                            onChange={(e) =>
                              setSettingDrafts({
                                ...settingDrafts,
                                [field.key]: e.target.value,
                              })
                            }
                          />
                        </label>
                        {savedMasked && (
                          <label className="ready-filter">
                            <input
                              type="checkbox"
                              checked={cleared}
                              onChange={(e) =>
                                setClearedSecrets(
                                  e.target.checked
                                    ? [...clearedSecrets, field.key]
                                    : clearedSecrets.filter(
                                        (k) => k !== field.key,
                                      ),
                                )
                              }
                            />
                            清除已保存的{field.label}
                          </label>
                        )}
                      </div>
                    );
                  }
                  return (
                    <label key={field.key} className="form-label">
                      {field.label}
                      {field.required && " *"}
                      <input
                        type={
                          field.type === "number"
                            ? "number"
                            : field.type === "url"
                              ? "url"
                              : "text"
                        }
                        required={field.required}
                        value={settingDrafts[field.key] ?? ""}
                        placeholder={
                          field.placeholder ||
                          (field.default_value
                            ? `默认：${field.default_value}`
                            : "")
                        }
                        onChange={(e) =>
                          setSettingDrafts({
                            ...settingDrafts,
                            [field.key]: e.target.value,
                          })
                        }
                      />
                    </label>
                  );
                })}
              </div>
            ) : (
              <p className="setting-help">该插件未声明额外自定义参数。</p>
            )}

            <div className="plugin-origins-box">
              <label className="form-label">
                允许访问的内网 Origin 白名单（allowed_origins）
                <textarea
                  rows={3}
                  spellCheck={false}
                  value={originsText}
                  onChange={(e) => setOriginsText(e.target.value)}
                  placeholder={
                    "http://192.168.1.50:8080\nhttp://nas.local:9000"
                  }
                />
              </label>
              <p className="setting-help">
                SSRF 安全提示：每行一个或使用逗号分隔（格式{" "}
                <code>scheme://host[:port]</code>
                ）。默认策略严格拦截所有内网、局域网与环回地址；仅在接入自托管私有媒体服务时才授权对应内网
                Origin。
              </p>
            </div>

            {configError && (
              <p className="plugin-error-banner" role="alert">
                <AlertTriangle aria-hidden="true" />
                {configError}
              </p>
            )}

            <div className="dialog-actions">
              <button
                type="button"
                className="secondary"
                disabled={savingConfig}
                onClick={() => setConfigPlugin(null)}
              >
                取消
              </button>
              <button type="submit" className="primary" disabled={savingConfig}>
                {savingConfig ? "正在保存…" : "保存配置"}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {testPlugin && (
        <Modal
          title={`沙箱测试 · ${testPlugin.manifest.name}`}
          className="plugin-modal plugin-test-modal"
          close={testing ? undefined : () => setTestPlugin(null)}
        >
          <form
            className="plugin-modal-form"
            onSubmit={(e) => void handleRunTest(e)}
          >
            <label className="form-label">
              测试方法（action）
              <select
                value={testAction}
                onChange={(e) => {
                  setTestAction(e.target.value as PluginTestAction);
                  setTestResult(null);
                  setTestError("");
                }}
              >
                {TEST_ACTIONS.map((item) => {
                  const caps = testPlugin.manifest.capabilities;
                  const supported =
                    item.value === "resolve" ||
                    Boolean(caps?.[item.value as keyof typeof caps]);
                  return (
                    <option key={item.value} value={item.value}>
                      {item.label}
                      {supported ? "" : "（插件未声明该能力）"}
                    </option>
                  );
                })}
              </select>
            </label>

            {(testAction === "search" || testAction === "metadata") && (
              <label className="form-label">
                关键词（keyword）
                <input
                  value={testKeyword}
                  onChange={(e) => setTestKeyword(e.target.value)}
                  placeholder="例如：ambient"
                />
              </label>
            )}

            {testAction === "playlist" && (
              <label className="form-label">
                歌单链接或 ID（locator）
                <input
                  value={testLocator}
                  onChange={(e) => setTestLocator(e.target.value)}
                  placeholder="输入公开歌单链接或 ID"
                />
              </label>
            )}

            {(testAction === "resolve" ||
              testAction === "lyrics" ||
              testAction === "episodes") && (
              <div className="plugin-test-track-fields">
                <div className="settings-grid">
                  <label className="form-label">
                    source_id
                    <input
                      value={testTrackSourceId}
                      onChange={(e) => setTestTrackSourceId(e.target.value)}
                      placeholder="曲目唯一 ID"
                    />
                  </label>
                  <label className="form-label">
                    title
                    <input
                      value={testTrackTitle}
                      onChange={(e) => setTestTrackTitle(e.target.value)}
                      placeholder="歌曲标题"
                    />
                  </label>
                  <label className="form-label">
                    artist
                    <input
                      value={testTrackArtist}
                      onChange={(e) => setTestTrackArtist(e.target.value)}
                      placeholder="歌手 / 创作者"
                    />
                  </label>
                  <label className="form-label">
                    album
                    <input
                      value={testTrackAlbum}
                      onChange={(e) => setTestTrackAlbum(e.target.value)}
                      placeholder="专辑名称（可选）"
                    />
                  </label>
                </div>
                <details className="plugin-advanced-json">
                  <summary>高级：直接输入完整 Track JSON（可选）</summary>
                  <textarea
                    rows={4}
                    spellCheck={false}
                    value={testTrackJson}
                    onChange={(e) => setTestTrackJson(e.target.value)}
                    placeholder='{"source_id":"demo-001","title":"Solar Wind","artist":"Open Horizon"}'
                  />
                </details>
              </div>
            )}

            {testError && (
              <p className="plugin-error-banner" role="alert">
                <AlertTriangle aria-hidden="true" />
                {testError}
              </p>
            )}

            <div className="dialog-actions">
              <button
                type="button"
                className="secondary"
                disabled={testing}
                onClick={() => setTestPlugin(null)}
              >
                关闭
              </button>
              <button type="submit" className="primary" disabled={testing}>
                <Play />
                {testing ? "沙箱运行中…" : "运行测试"}
              </button>
            </div>
          </form>

          {testResult && (
            <div className="plugin-test-output">
              <div className="plugin-test-summary">
                <span
                  className={`plugin-test-badge ${testResult.ok ? "ok" : "fail"}`}
                >
                  {testResult.ok ? "测试通过" : "测试失败"}
                </span>
                <span>耗时 {testResult.duration_ms} ms</span>
                {firstSearchTrack && (
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => {
                      setTestTrackJson(
                        JSON.stringify(firstSearchTrack, null, 2),
                      );
                      setTestTrackSourceId(
                        String(
                          firstSearchTrack.source_id ||
                            firstSearchTrack.sourceId ||
                            "",
                        ),
                      );
                      setTestTrackTitle(String(firstSearchTrack.title || ""));
                      setTestTrackArtist(String(firstSearchTrack.artist || ""));
                      setTestTrackAlbum(String(firstSearchTrack.album || ""));
                      setTestAction("resolve");
                    }}
                  >
                    用首条曲目测试 resolve
                  </button>
                )}
              </div>

              {testResult.error && (
                <p className="plugin-error-banner" role="alert">
                  {testResult.error}
                </p>
              )}

              {testResult.result !== undefined && (
                <div className="plugin-test-section">
                  <h4>返回结果 JSON</h4>
                  <pre className="plugin-test-json">
                    {JSON.stringify(testResult.result, null, 2)}
                  </pre>
                </div>
              )}

              <div className="plugin-test-section">
                <h4>ctx.log 日志（{testResult.logs?.length || 0}）</h4>
                {testResult.logs?.length ? (
                  <ul className="plugin-log-list">
                    {testResult.logs.map((entry, idx) => (
                      <li key={idx}>
                        <span className={`plugin-log-level is-${entry.level}`}>
                          {entry.level.toUpperCase()}
                        </span>
                        <span className="plugin-log-time">{entry.time}</span>
                        <span className="plugin-log-msg">{entry.message}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="setting-help">本次调用未输出 ctx.log 日志。</p>
                )}
              </div>
            </div>
          )}
        </Modal>
      )}

      {uninstallTarget && (
        <Modal
          title="确认卸载插件"
          close={uninstalling ? undefined : () => setUninstallTarget(null)}
        >
          <p>
            确定要卸载插件「<strong>{uninstallTarget.manifest.name}</strong>」（
            <code>{uninstallTarget.id}</code>）吗？
          </p>
          <p className="setting-help">
            卸载后将从磁盘与配置库中移除该插件脚本、已保存设置与白名单，并立即从可用音源中注销。
          </p>
          <div className="dialog-actions">
            <button
              type="button"
              className="secondary"
              disabled={uninstalling}
              onClick={() => setUninstallTarget(null)}
            >
              取消
            </button>
            <button
              type="button"
              className="primary danger"
              disabled={uninstalling}
              onClick={() => void handleConfirmUninstall()}
            >
              {uninstalling ? "正在卸载…" : "确认卸载"}
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
