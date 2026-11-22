import { useEffect, useRef, useState } from "react";
import { api } from "./api";

type Settings = {
  enabled: boolean;
  base_url: string;
  model: string;
  api_type: "chat_completions" | "responses";
  proxy_mode: "direct" | "project";
  key_configured: boolean;
  project_proxy_configured: boolean;
  stream: boolean;
  preferences: string;
};
type Draft = Pick<
  Settings,
  | "enabled"
  | "base_url"
  | "model"
  | "api_type"
  | "proxy_mode"
  | "stream"
  | "preferences"
> & {
  api_key: string;
  clear_key: boolean;
};

export function AISettings() {
  const [config, setConfig] = useState<Settings | null>(null);
  const [key, setKey] = useState("");
  const [clearKey, setClearKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [models, setModels] = useState<string[]>([]);
  const [modelsBusy, setModelsBusy] = useState(false);
  const [modelsNotice, setModelsNotice] = useState("");
  const form = useRef<HTMLFormElement>(null);
  const modelRequest = useRef<AbortController | null>(null);
  const modelTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );

  async function load() {
    try {
      const value = await api<Settings>("/agent/settings");
      setConfig({
        ...value,
        api_type: value.api_type || "chat_completions",
        proxy_mode: value.proxy_mode || "direct",
      });
      setError("");
    } catch (e) {
      setError((e as Error).message);
    }
  }
  useEffect(() => {
    void load();
  }, []);

  function draft(): Draft {
    // Read the form before disabling it, including browser autofill that did not fire onChange.
    const data = new FormData(form.current!);
    return {
      enabled: data.has("enabled"),
      base_url: String(data.get("base_url") || "").trim(),
      model: String(data.get("model") || "").trim(),
      api_type: data.get("api_type") as Settings["api_type"],
      proxy_mode: data.get("proxy_mode") as Settings["proxy_mode"],
      stream: data.has("stream"),
      preferences: String(data.get("preferences") || "").trim(),
      api_key: String(data.get("api_key") || "").trim(),
      clear_key: data.has("clear_key"),
    };
  }
  function update<K extends keyof Settings>(name: K, value: Settings[K]) {
    setConfig((current) => current && { ...current, [name]: value });
    setError("");
    setNotice("");
  }
  async function fetchModels(value: Draft) {
    clearTimeout(modelTimer.current);
    modelRequest.current?.abort();
    const controller = new AbortController();
    modelRequest.current = controller;
    if (!value.base_url) {
      setModelsBusy(false);
      setModelsNotice("请先填写 API 地址，无需保存或选择模型。");
      return;
    }
    setModelsBusy(true);
    setModelsNotice("");
    try {
      const result = await api<{ models: string[]; detail: string }>(
        "/agent/settings/models",
        "POST",
        value,
        controller.signal,
      );
      if (!controller.signal.aborted) {
        setModels(result.models);
        setModelsNotice(
          result.models.length
            ? result.detail
            : "服务商返回的模型列表为空，可以手动填写模型 ID。",
        );
      }
    } catch (e) {
      if (!controller.signal.aborted)
        setModelsNotice(`${(e as Error).message}；也可手动填写模型 ID。`);
    } finally {
      if (modelRequest.current === controller) setModelsBusy(false);
    }
  }
  useEffect(
    () => () => {
      clearTimeout(modelTimer.current);
      modelRequest.current?.abort();
    },
    [],
  );

  return (
    <section className="settings-section ai-settings">
      <h2>AI 助手</h2>
      {error && (
        <p role="alert" className="ai-error">
          {error}
        </p>
      )}
      {!config ? (
        <button className="secondary" onClick={() => void load()}>
          重新读取配置
        </button>
      ) : (
        <form
          ref={form}
          onChange={(e) => {
            const target = e.target;
            if (
              !(target instanceof HTMLInputElement) &&
              !(target instanceof HTMLSelectElement)
            )
              return;
            const { name } = target;
            if (
              ![
                "base_url",
                "api_type",
                "proxy_mode",
                "api_key",
                "clear_key",
              ].includes(name)
            )
              return;
            clearTimeout(modelTimer.current);
            modelRequest.current?.abort();
            setModelsBusy(false);
            setModels([]);
            setModelsNotice("");
            const value = draft();
            if (!value.base_url || value.clear_key) return;
            try {
              new URL(value.base_url);
            } catch {
              return;
            }
            modelTimer.current = setTimeout(() => void fetchModels(value), 800);
          }}
          onSubmit={async (e) => {
            e.preventDefault();
            const value = draft();
            setBusy(true);
            setError("");
            setNotice("");
            try {
              const saved = await api<Settings>(
                "/agent/settings",
                "PUT",
                value,
              );
              setConfig(saved);
              setKey("");
              setClearKey(false);
              setNotice(
                saved.base_url && saved.model
                  ? "AI 配置已保存"
                  : "AI 配置已保存；补全 API 地址和模型后即可使用。",
              );
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <fieldset disabled={busy}>
            <div className="ai-toggle-row">
              <label className="ready-filter">
                <input
                  name="enabled"
                  type="checkbox"
                  checked={config.enabled}
                  onChange={(e) => update("enabled", e.target.checked)}
                />
                启用 AI 助手
              </label>
              <label className="ready-filter">
                <input
                  name="stream"
                  type="checkbox"
                  checked={!!config.stream}
                  onChange={(e) => update("stream", e.target.checked)}
                />{" "}
                流式显示回复
              </label>
            </div>
            <div className="field-row">
              <label className="field-lg">
                API 基础地址
                <input
                  name="base_url"
                  type="url"
                  placeholder="https://api.example.com/v1"
                  value={config.base_url}
                  onChange={(e) => update("base_url", e.target.value)}
                />
              </label>
              <label className="field-sm">
                接口类型
                <select
                  name="api_type"
                  aria-label="接口类型"
                  value={config.api_type}
                  onChange={(e) =>
                    update("api_type", e.target.value as Settings["api_type"])
                  }
                >
                  <option value="chat_completions">Chat Completions</option>
                  <option value="responses">Responses</option>
                </select>
              </label>
              <label className="field-sm">
                AI 请求代理
                <select
                  name="proxy_mode"
                  aria-label="AI 请求代理"
                  value={config.proxy_mode}
                  onChange={(e) =>
                    update(
                      "proxy_mode",
                      e.target.value as Settings["proxy_mode"],
                    )
                  }
                >
                  <option value="direct">直连</option>
                  <option value="project">使用项目代理</option>
                </select>
              </label>
            </div>
            {config.proxy_mode === "project" &&
              !config.project_proxy_configured && (
                <small>项目代理尚未配置，请先在「音源与下载」填写。</small>
              )}
            <div className="field-row">
              <div className="bot-secret field-lg">
                <label>
                  API Key
                  <input
                    name="api_key"
                    type="password"
                    autoComplete="new-password"
                    value={key}
                    disabled={clearKey}
                    placeholder={
                      config.key_configured
                        ? "已配置，留空保留"
                        : "本地无鉴权服务可留空"
                    }
                    onChange={(e) => {
                      setKey(e.target.value);
                      setError("");
                      setNotice("");
                    }}
                  />
                </label>
                {config.key_configured && (
                  <label className="ready-filter">
                    <input
                      name="clear_key"
                      type="checkbox"
                      checked={clearKey}
                      onChange={(e) => setClearKey(e.target.checked)}
                    />
                    清除已保存的密钥
                  </label>
                )}
              </div>
              {models.length > 0 && (
                <label className="field-md">
                  选择可用模型
                  <select
                    aria-label="选择可用模型"
                    value={models.includes(config.model) ? config.model : ""}
                    onChange={(e) => {
                      if (e.target.value) update("model", e.target.value);
                    }}
                  >
                    <option value="">请选择模型，或在下方手动填写</option>
                    {models.map((model) => (
                      <option key={model} value={model}>
                        {model}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              <div className="ai-model-group">
                <label className="field-md">
                  模型名称
                  <input
                    name="model"
                    placeholder="选择模型或手动填写模型 ID"
                    value={config.model}
                    maxLength={200}
                    onChange={(e) => update("model", e.target.value)}
                  />
                </label>
                <button
                  type="button"
                  className="secondary ai-fetch-btn"
                  disabled={modelsBusy}
                  onClick={() => void fetchModels(draft())}
                >
                  {modelsBusy ? "正在获取模型…" : "获取模型列表"}
                </button>
              </div>
            </div>
            {modelsNotice && (
              <p className="muted" aria-live="polite">
                {modelsNotice}
              </p>
            )}
            <label className="field-xl">
              音乐偏好
              <textarea
                name="preferences"
                maxLength={1000}
                value={config.preferences || ""}
                onChange={(e) => update("preferences", e.target.value)}
                placeholder="例如：优先原唱和录音室版，下载时优先无损；已下载的歌曲跳过"
              />
            </label>
            <div className="ai-actions settings-sticky-bar">
              <button className="primary" type="submit">
                {busy ? "正在处理…" : "保存配置"}
              </button>
              <button
                type="button"
                className="secondary"
                onClick={async () => {
                  const value = draft();
                  if (!value.base_url || !value.model) {
                    setError("请填写 API 地址并选择或填写模型，无需先保存。");
                    return;
                  }
                  setBusy(true);
                  setError("");
                  setNotice("");
                  try {
                    setNotice(
                      (
                        await api<{ detail: string }>(
                          "/agent/settings/test",
                          "POST",
                          value,
                        )
                      ).detail,
                    );
                  } catch (e) {
                    setError((e as Error).message);
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                测试当前配置
              </button>
              {(
                [
                  ["tools", "测试工具调用"],
                  ["image", "测试图片输入"],
                ] as const
              ).map(([capability, label]) => (
                <button
                  key={capability}
                  type="button"
                  className="secondary"
                  onClick={async () => {
                    const value = draft();
                    if (!value.base_url || !value.model) {
                      setError("请填写 API 地址并选择或填写模型，无需先保存。");
                      return;
                    }
                    setBusy(true);
                    setError("");
                    setNotice("");
                    try {
                      setNotice(
                        (
                          await api<{ detail: string }>(
                            `/agent/settings/test-capability/${capability}`,
                            "POST",
                            value,
                          )
                        ).detail,
                      );
                    } catch (e) {
                      setError((e as Error).message);
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  {label}
                </button>
              ))}
            </div>
          </fieldset>
        </form>
      )}
      {notice && <p role="status">{notice}</p>}
    </section>
  );
}
