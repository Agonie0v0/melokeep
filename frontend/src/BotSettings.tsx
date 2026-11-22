import { useEffect, useState } from "react";
import { api } from "./api";

type BotConfig = {
  public_url: string;
  link_ttl_seconds: number;
  bot_sources: string;
  tg_enabled: boolean;
  tg_ai_enabled: boolean;
  wecom_ai_enabled: boolean;
  bot_ai_allow_actions: boolean;
  tg_api_url: string;
  tg_local_enabled: boolean;
  tg_local_api_id: string;
  local_api_status: { available: boolean; running: boolean; detail: string };
  tg_proxy_mode: "direct" | "project" | "custom";
  wecom_enabled: boolean;
  tg_allowed_users: string;
  wecom_corp_id: string;
  wecom_agent_id: string;
  wecom_api_url: string;
  wecom_allowed_users: string;
  configured: Record<string, boolean>;
  status: { tg: boolean; wecom: boolean };
  callback_url: string;
  discord_enabled: boolean;
  feishu_enabled: boolean;
  dingtalk_enabled: boolean;
  feishu_bot_enabled: boolean;
  dingtalk_bot_enabled: boolean;
  discord_bot_enabled: boolean;
  feishu_ai_enabled: boolean;
  dingtalk_ai_enabled: boolean;
  discord_ai_enabled: boolean;
  feishu_app_id: string;
  feishu_region: string;
  feishu_allowed_users: string;
  dingtalk_client_id: string;
  dingtalk_robot_code: string;
  dingtalk_allowed_users: string;
  discord_application_id: string;
  discord_public_key: string;
  discord_allowed_users: string;
};
const secretFields = [
  "tg_token",
  "tg_proxy",
  "tg_local_api_hash",
  "wecom_secret",
  "wecom_token",
  "wecom_aes_key",
  "discord_webhook",
  "feishu_webhook",
  "feishu_sign_secret",
  "dingtalk_webhook",
  "dingtalk_sign_secret",
  "feishu_app_secret",
  "feishu_verification_token",
  "feishu_encrypt_key",
  "dingtalk_client_secret",
  "discord_bot_token",
];

export function BotSettings() {
  const [config, setConfig] = useState<BotConfig | null>(null);
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const [clear, setClear] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [dirty, setDirty] = useState(false);
  const [testResult, setTestResult] = useState<{
    channel: string;
    state: "loading" | "success" | "error";
    detail: string;
  } | null>(null);
  async function load() {
    try {
      const result = await api<BotConfig>("/bots/config");
      setConfig({
        ...result,
        tg_ai_enabled: result.tg_ai_enabled ?? false,
        wecom_ai_enabled: result.wecom_ai_enabled ?? false,
        wecom_api_url: result.wecom_api_url ?? "https://qyapi.weixin.qq.com",
        feishu_ai_enabled: result.feishu_ai_enabled ?? false,
        dingtalk_ai_enabled: result.dingtalk_ai_enabled ?? false,
        discord_ai_enabled: result.discord_ai_enabled ?? false,
        bot_ai_allow_actions: result.bot_ai_allow_actions ?? true,
      });
      setError("");
    } catch (e) {
      setError((e as Error).message);
    }
  }
  useEffect(() => {
    void load();
  }, []);
  if (!config)
    return (
      <section className="settings-section">
        <h2>通知与机器人</h2>
        <p role={error ? "alert" : "status"}>
          {error || "正在读取通知渠道配置…"}
        </p>
        {error && (
          <button className="secondary" onClick={() => void load()}>
            重新读取通知配置
          </button>
        )}
      </section>
    );
  function update(key: string, value: string | number | boolean) {
    setConfig((current) => current && { ...current, [key]: value });
    setDirty(true);
    setMessage("");
    setTestResult(null);
  }
  function field(
    key: keyof BotConfig,
    label: string,
    placeholder = "",
    sizeClass = "field-md",
  ) {
    return (
      <label key={key} className={sizeClass}>
        {label}
        <input
          value={String(config![key] ?? "")}
          placeholder={placeholder}
          onChange={(e) => update(key, e.target.value)}
        />
      </label>
    );
  }
  function secret(key: string, label: string, sizeClass = "field-md") {
    return (
      <div className={`bot-secret ${sizeClass}`} key={key}>
        <label>
          {label}
          <input
            type="password"
            autoComplete="new-password"
            value={secrets[key] || ""}
            placeholder={
              config!.configured[key]
                ? "已配置，留空保留；输入新值可替换"
                : "尚未配置"
            }
            disabled={clear.includes(key)}
            onChange={(e) => {
              setSecrets({ ...secrets, [key]: e.target.value });
              setDirty(true);
              setMessage("");
            }}
          />
        </label>
        {config!.configured[key] && (
          <label className="ready-filter">
            <input
              type="checkbox"
              checked={clear.includes(key)}
              onChange={(e) => {
                setClear(
                  e.target.checked
                    ? [...clear, key]
                    : clear.filter((k) => k !== key),
                );
                setDirty(true);
                setMessage("");
              }}
            />
            清除已保存的{label}
          </label>
        )}
      </div>
    );
  }
  function draftPayload() {
    const { configured, status, callback_url, local_api_status, ...values } =
      config!;
    return {
      ...values,
      ...Object.fromEntries(
        secretFields.map((key) => [key, secrets[key] || ""]),
      ),
      clear_secrets: clear,
    };
  }
  async function test(
    channel: "tg" | "wecom" | "discord" | "feishu" | "dingtalk",
  ) {
    setBusy(true);
    setError("");
    setMessage("");
    setTestResult({ channel, state: "loading", detail: "正在检查，请稍候…" });
    try {
      const result = await api<{ detail: string }>("/bots/test", "POST", {
        channel,
        config: draftPayload(),
      });
      setTestResult({ channel, state: "success", detail: result.detail });
    } catch (e) {
      setTestResult({ channel, state: "error", detail: (e as Error).message });
    } finally {
      setBusy(false);
    }
  }
  function feedback(channel: string) {
    return testResult?.channel === channel ? (
      <p
        className={testResult.state === "error" ? "log-error" : "test-result"}
        role={testResult.state === "error" ? "alert" : "status"}
      >
        {testResult.detail}
      </p>
    ) : null;
  }
  return (
    <form
      className="settings-form bot-settings"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError("");
        setMessage("");
        try {
          setConfig(
            await api<BotConfig>("/bots/config", "PUT", draftPayload()),
          );
          setSecrets({});
          setClear([]);
          setDirty(false);
          setMessage("通知渠道已保存并立即生效，无需重启服务");
        } catch (e) {
          setError((e as Error).message);
        } finally {
          setBusy(false);
        }
      }}
    >
      <section className="settings-section settings-basics">
        <h2>文件交付与下载链接</h2>
        <div className="field-row">
          {field(
            "public_url",
            "公网访问地址",
            "http://192.168.1.20:8765",
            "field-lg",
          )}
          <label className="field-xs">
            下载链接有效期
            <span className="field-unit" aria-hidden="true">
              （秒）
            </span>
            <input
              type="number"
              min="60"
              max="604800"
              required
              aria-label="下载链接有效期"
              placeholder="秒"
              title="单位：秒"
              value={config.link_ttl_seconds}
              onChange={(e) =>
                update("link_ttl_seconds", Number(e.target.value))
              }
            />
          </label>
          {field(
            "bot_sources",
            "机器人音源",
            "留空跟随 Web 设置，逗号分隔",
            "field-sm",
          )}
        </div>
      </section>
      <section className="settings-section settings-behavior">
        <h2>渠道 AI 对话</h2>
        <div className="bot-toggle-grid">
          <label className="ready-filter">
            <input
              type="checkbox"
              checked={config.tg_ai_enabled}
              onChange={(e) => update("tg_ai_enabled", e.target.checked)}
            />
            Telegram 使用 AI 对话
          </label>
          {(
            [
              ["feishu", "飞书 / Lark"],
              ["dingtalk", "钉钉"],
              ["discord", "Discord"],
            ] as const
          ).map(([channel, label]) => (
            <label className="ready-filter" key={channel}>
              <input
                type="checkbox"
                checked={Boolean(config[`${channel}_ai_enabled`])}
                onChange={(e) =>
                  update(`${channel}_ai_enabled`, e.target.checked)
                }
              />
              {label} 使用 AI 对话
            </label>
          ))}
          <label className="ready-filter">
            <input
              type="checkbox"
              checked={config.wecom_ai_enabled}
              onChange={(e) => update("wecom_ai_enabled", e.target.checked)}
            />
            企业微信使用 AI 对话
          </label>
          <label className="ready-filter">
            <input
              type="checkbox"
              checked={config.bot_ai_allow_actions}
              onChange={(e) => update("bot_ai_allow_actions", e.target.checked)}
            />
            允许渠道 AI 执行操作
          </label>
        </div>
      </section>
      <section className="settings-section settings-behavior">
        <h2>
          Telegram 机器人{" "}
          <small
            className={`channel-status status-dot-label ${config.status.tg ? "is-complete" : ""}`}
            data-enabled={config.status.tg}
          >
            <i className="status-dot" aria-hidden="true" />
            {config.status.tg ? "已启用" : "未启用"}
          </small>
        </h2>
        <label className="ready-filter">
          <input
            type="checkbox"
            checked={config.tg_enabled}
            onChange={(e) => update("tg_enabled", e.target.checked)}
          />
          启用 Telegram
        </label>
        <div className="field-row">
          {secret("tg_token", "Telegram Bot Token", "field-lg")}
          {field(
            "tg_allowed_users",
            "Telegram 用户白名单",
            "数字用户 ID，多个用逗号分隔",
            "field-md",
          )}
          {!config.tg_local_enabled &&
            field(
              "tg_api_url",
              "Telegram Bot API 地址",
              "https://api.telegram.org",
              "field-lg",
            )}
          <label className="field-sm">
            Telegram 代理
            <select
              aria-label="Telegram 代理"
              value={config.tg_proxy_mode}
              onChange={(e) => update("tg_proxy_mode", e.target.value)}
            >
              <option value="direct">不使用代理</option>
              <option value="project">使用项目代理</option>
              {config.tg_proxy_mode === "custom" && (
                <option value="custom">保留原有独立代理</option>
              )}
            </select>
          </label>
        </div>
        <label className="ready-filter">
          <input
            type="checkbox"
            checked={Boolean(config.tg_local_enabled)}
            onChange={(e) => update("tg_local_enabled", e.target.checked)}
          />
          使用内置 Telegram 本地服务
        </label>
        {config.tg_local_enabled && (
          <div className="local-api-settings">
            <p className="setting-help">
              内置服务：{config.local_api_status?.running ? "运行中" : "未启动"}{" "}
              ·{" "}
              {config.local_api_status?.available
                ? "程序已就绪"
                : "未找到 telegram-bot-api"}
              {config.local_api_status?.detail
                ? ` · ${config.local_api_status.detail}`
                : ""}
            </p>
            <div className="field-row">
              {field(
                "tg_local_api_id",
                "Telegram API ID",
                "my.telegram.org 的应用 API ID",
                "field-sm",
              )}
              {secret("tg_local_api_hash", "Telegram API Hash", "field-md")}
            </div>
          </div>
        )}
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={() => void test("tg")}
        >
          测试 Telegram 连接
        </button>
        {feedback("tg")}
      </section>
      <section className="settings-section settings-sources">
        <h2>
          企业微信自建应用{" "}
          <small
            className={`channel-status status-dot-label ${config.status.wecom ? "is-complete" : ""}`}
            data-enabled={config.status.wecom}
          >
            <i className="status-dot" aria-hidden="true" />
            {config.status.wecom ? "已启用" : "未启用"}
          </small>
        </h2>
        <label className="ready-filter">
          <input
            type="checkbox"
            checked={config.wecom_enabled}
            onChange={(e) => update("wecom_enabled", e.target.checked)}
          />
          启用企业微信
        </label>
        <div className="field-row">
          {field("wecom_corp_id", "企业微信 CorpID", "", "field-md")}
          {field("wecom_agent_id", "企业微信 AgentID", "", "field-sm")}
          {secret("wecom_secret", "企业微信 Secret", "field-lg")}
          {field(
            "wecom_api_url",
            "企业微信 API 转发地址",
            "https://qyapi.weixin.qq.com",
            "field-lg",
          )}
          {secret("wecom_token", "企业微信回调 Token", "field-md")}
          {secret("wecom_aes_key", "企业微信 EncodingAESKey", "field-lg")}
          {field(
            "wecom_allowed_users",
            "企业微信用户白名单",
            "留空允许全部成员；限制时填 UserID，逗号分隔",
            "field-md",
          )}
        </div>
        <label className="field-lg">
          企业微信回调地址
          <input
            readOnly
            value={
              config.public_url.trim()
                ? (config.public_url.includes("://") ? "" : "http://") +
                  config.public_url.trim().replace(/\/$/, "") +
                  "/bot/wecom"
                : "填写公网访问地址后生成"
            }
          />
        </label>
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={() => void test("wecom")}
        >
          测试企业微信连接
        </button>
        {feedback("wecom")}
      </section>
      <section className="settings-section settings-sources">
        <h2>更多下载通知渠道</h2>
        {(
          [
            ["discord", "Discord"],
            ["feishu", "飞书 / Lark"],
            ["dingtalk", "钉钉"],
          ] as const
        ).map(([channel, label]) => (
          <details className="source-setting" key={channel}>
            <summary>{label}</summary>
            <label className="ready-filter">
              <input
                type="checkbox"
                checked={Boolean(config[`${channel}_enabled`])}
                onChange={(e) => update(`${channel}_enabled`, e.target.checked)}
              />
              启用 {label} 下载通知
            </label>
            <div className="field-row">
              {secret(`${channel}_webhook`, `${label} Webhook`, "field-lg")}
              {channel !== "discord" &&
                secret(`${channel}_sign_secret`, "加签密钥", "field-md")}
            </div>
            <button
              type="button"
              className="secondary"
              disabled={busy}
              onClick={() => void test(channel)}
            >
              检查 {label} 配置
            </button>
            {feedback(channel)}
          </details>
        ))}
      </section>
      <section className="settings-section settings-sources">
        <h2>更多对话机器人</h2>
        {(
          [
            ["feishu", "飞书 / Lark"],
            ["dingtalk", "钉钉"],
            ["discord", "Discord"],
          ] as const
        ).map(([channel, label]) => (
          <details className="source-setting" key={channel}>
            <summary>{label} 对话机器人</summary>
            <label className="ready-filter">
              <input
                type="checkbox"
                checked={Boolean(config[`${channel}_bot_enabled`])}
                onChange={(e) =>
                  update(`${channel}_bot_enabled`, e.target.checked)
                }
              />
              启用 {label} 对话机器人
            </label>
            <div className="field-row">
              {channel === "feishu" && (
                <>
                  {field("feishu_app_id", "飞书 App ID", "", "field-md")}
                  {secret("feishu_app_secret", "飞书 App Secret", "field-lg")}
                  {secret(
                    "feishu_verification_token",
                    "飞书 Verification Token",
                    "field-lg",
                  )}
                  {secret("feishu_encrypt_key", "飞书 Encrypt Key", "field-lg")}
                  <label className="field-sm">
                    飞书区域
                    <select
                      value={config.feishu_region || "feishu"}
                      onChange={(e) => update("feishu_region", e.target.value)}
                    >
                      <option value="feishu">国内飞书</option>
                      <option value="lark">国际 Lark</option>
                    </select>
                  </label>
                </>
              )}
              {channel === "dingtalk" && (
                <>
                  {field(
                    "dingtalk_client_id",
                    "钉钉 Client ID / AppKey",
                    "",
                    "field-md",
                  )}
                  {secret(
                    "dingtalk_client_secret",
                    "钉钉 Client Secret / AppSecret",
                    "field-lg",
                  )}
                  {field(
                    "dingtalk_robot_code",
                    "钉钉 Robot Code",
                    "",
                    "field-md",
                  )}
                </>
              )}
              {channel === "discord" && (
                <>
                  {field(
                    "discord_application_id",
                    "Discord Application ID",
                    "",
                    "field-md",
                  )}
                  {field(
                    "discord_public_key",
                    "Discord Public Key",
                    "64 位十六进制公钥",
                    "field-lg",
                  )}
                  {secret("discord_bot_token", "Discord Bot Token", "field-lg")}
                </>
              )}
              {field(
                `${channel}_allowed_users`,
                `${label} 对话用户白名单（必填）`,
                channel === "feishu"
                  ? "Open ID，逗号分隔"
                  : channel === "dingtalk"
                    ? "企业 UserID / senderStaffId，逗号分隔"
                    : "数字用户 ID，逗号分隔",
                "field-md",
              )}
            </div>
            <label className="field-lg">
              {label} 消息回调地址
              <input
                readOnly
                value={
                  config.public_url.trim()
                    ? (config.public_url.includes("://") ? "" : "http://") +
                      config.public_url.trim().replace(/\/$/, "") +
                      `/bot/${channel}`
                    : "填写公网访问地址后生成"
                }
              />
            </label>
            {channel === "discord" && (
              <button
                type="button"
                className="secondary"
                disabled={busy || dirty || !config.discord_bot_enabled}
                onClick={async () => {
                  setBusy(true);
                  setError("");
                  setMessage("");
                  try {
                    const result = await api<{ detail: string }>(
                      "/bots/discord/commands",
                      "POST",
                      {},
                    );
                    setMessage(result.detail);
                  } catch (e) {
                    setError((e as Error).message);
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                注册 Discord /melokeep 命令
              </button>
            )}
          </details>
        ))}
      </section>
      <div className="settings-save settings-sticky-bar">
        <button className="primary" disabled={busy} type="submit">
          {busy ? "正在处理…" : "保存通知配置"}
        </button>
        {error && (
          <p role="alert" className="log-error">
            {error}
          </p>
        )}
        {message && <p role="status">{message}</p>}
        {dirty && (
          <p className="muted">有未保存的修改，可先测试连接，保存后生效。</p>
        )}
      </div>
    </form>
  );
}
