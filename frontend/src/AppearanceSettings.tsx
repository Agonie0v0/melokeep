import { Check, Monitor, Moon, RotateCcw, Sparkles, Sun } from "lucide-react";
import {
  ACCENT_SWATCHES,
  THEME_PRESETS,
  type AppearanceController,
} from "./theme";

export function AppearanceSettings({
  controller,
}: {
  controller: AppearanceController;
}) {
  const {
    appearance,
    activePreset,
    setMode,
    selectPreset,
    bindSchemePreset,
    setCustomAccent,
    setAmbientGlow,
    setDensity,
    resetAppearance,
  } = controller;

  const lightPresets = THEME_PRESETS.filter((p) => p.scheme === "light");
  const darkPresets = THEME_PRESETS.filter((p) => p.scheme === "dark");

  return (
    <div className="appearance-settings">
      <section className="settings-section">
        <div className="appearance-header">
          <div>
            <h2>明暗模式</h2>
          </div>
          <button type="button" className="secondary" onClick={resetAppearance}>
            <RotateCcw aria-hidden="true" />
            恢复默认外观
          </button>
        </div>

        <div
          className="appearance-mode-cards"
          role="group"
          aria-label="明暗模式"
        >
          {(
            [
              ["system", "跟随系统", Monitor],
              ["light", "浅色", Sun],
              ["dark", "深色", Moon],
            ] as const
          ).map(([id, title, Icon]) => (
            <button
              key={id}
              type="button"
              className={`appearance-mode-card ${appearance.mode === id ? "active" : ""}`}
              aria-pressed={appearance.mode === id}
              onClick={() => setMode(id)}
            >
              <Icon aria-hidden="true" />
              <div>
                <strong>{title}</strong>
              </div>
              {appearance.mode === id && (
                <span className="appearance-mode-check">
                  <Check aria-hidden="true" />
                </span>
              )}
            </button>
          ))}
        </div>
      </section>

      <section className="settings-section">
        <h2>浅色主题</h2>
        <div className="appearance-gallery">
          {lightPresets.map((preset) => {
            const isCurrent = activePreset === preset.id;
            const isDayDefault = appearance.lightPreset === preset.id;
            return (
              <div
                key={preset.id}
                className={`appearance-theme-card ${isCurrent ? "active" : ""}`}
              >
                <button
                  type="button"
                  className="appearance-card-trigger"
                  onClick={() => selectPreset(preset.id)}
                >
                  <div
                    className="appearance-card-canvas"
                    style={{
                      background: preset.preview.bg,
                      color: preset.preview.ink,
                    }}
                  >
                    <div
                      className="mock-nav"
                      style={{ background: preset.preview.surface }}
                    >
                      <span
                        className="mock-dot"
                        style={{ background: preset.preview.accent }}
                      />
                      <span className="mock-line short" />
                    </div>
                    <div
                      className="mock-card"
                      style={{ background: preset.preview.surface }}
                    >
                      <span
                        className="mock-btn"
                        style={{ background: preset.preview.accent }}
                      />
                      <span className="mock-line" />
                      <span className="mock-line mid" />
                    </div>
                  </div>
                  <div className="appearance-card-meta">
                    <div>
                      <strong>{preset.name}</strong>
                    </div>
                    {isCurrent && (
                      <span
                        className="appearance-preset-check"
                        aria-label="当前主题"
                      >
                        <Check aria-hidden="true" />
                      </span>
                    )}
                  </div>
                </button>
                <div className="appearance-card-foot">
                  <button
                    type="button"
                    className={`text-button ${isDayDefault ? "bound" : ""}`}
                    onClick={() => bindSchemePreset("light", preset.id)}
                  >
                    {isDayDefault ? "✓ 日间首选" : "设为日间首选"}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </section>

      <section className="settings-section">
        <h2>深色主题</h2>
        <div className="appearance-gallery">
          {darkPresets.map((preset) => {
            const isCurrent = activePreset === preset.id;
            const isNightDefault = appearance.darkPreset === preset.id;
            return (
              <div
                key={preset.id}
                className={`appearance-theme-card ${isCurrent ? "active" : ""}`}
              >
                <button
                  type="button"
                  className="appearance-card-trigger"
                  onClick={() => selectPreset(preset.id)}
                >
                  <div
                    className="appearance-card-canvas"
                    style={{
                      background: preset.preview.bg,
                      color: preset.preview.ink,
                    }}
                  >
                    <div
                      className="mock-nav"
                      style={{ background: preset.preview.surface }}
                    >
                      <span
                        className="mock-dot"
                        style={{ background: preset.preview.accent }}
                      />
                      <span className="mock-line short" />
                    </div>
                    <div
                      className="mock-card"
                      style={{ background: preset.preview.surface }}
                    >
                      <span
                        className="mock-btn"
                        style={{ background: preset.preview.accent }}
                      />
                      <span className="mock-line" />
                      <span className="mock-line mid" />
                    </div>
                  </div>
                  <div className="appearance-card-meta">
                    <div>
                      <strong>{preset.name}</strong>
                    </div>
                    {isCurrent && (
                      <span
                        className="appearance-preset-check"
                        aria-label="当前主题"
                      >
                        <Check aria-hidden="true" />
                      </span>
                    )}
                  </div>
                </button>
                <div className="appearance-card-foot">
                  <button
                    type="button"
                    className={`text-button ${isNightDefault ? "bound" : ""}`}
                    onClick={() => bindSchemePreset("dark", preset.id)}
                  >
                    {isNightDefault ? "✓ 夜间首选" : "设为夜间首选"}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </section>

      <section className="settings-section">
        <h2>细节配置</h2>
        <div className="appearance-detail-grid">
          <div className="appearance-detail-box">
            <div className="appearance-detail-head">
              <strong>主题强调色</strong>
              <button
                type="button"
                className={`appearance-preset-follow-btn ${!appearance.customAccent ? "active" : ""}`}
                aria-pressed={!appearance.customAccent}
                onClick={() => setCustomAccent("")}
              >
                跟随当前预设
              </button>
            </div>
            <div className="accent-swatch-row large">
              {ACCENT_SWATCHES.map((sw) => (
                <button
                  key={sw.color}
                  type="button"
                  className={`accent-dot ${appearance.customAccent.toLowerCase() === sw.color.toLowerCase() ? "active" : ""}`}
                  style={{ backgroundColor: sw.color }}
                  aria-label={`强调色 ${sw.label}`}
                  title={sw.label}
                  onClick={() => setCustomAccent(sw.color)}
                />
              ))}
              <label
                className={`accent-custom-pick ${
                  appearance.customAccent &&
                  !ACCENT_SWATCHES.some(
                    (sw) =>
                      sw.color.toLowerCase() ===
                      appearance.customAccent.toLowerCase(),
                  )
                    ? "active"
                    : ""
                }`}
                title="自定义强调色"
              >
                <input
                  type="color"
                  aria-label="自定义强调色选择器"
                  value={
                    appearance.customAccent ||
                    THEME_PRESETS.find((p) => p.id === activePreset)?.preview
                      .accent ||
                    "#2563eb"
                  }
                  onChange={(e) => setCustomAccent(e.target.value)}
                />
                <span>自定义</span>
              </label>
            </div>
          </div>

          <div className="appearance-detail-box">
            <div className="appearance-detail-head">
              <strong>光晕与排版密度</strong>
            </div>
            <div className="appearance-detail-rows">
              <label className="appearance-detail-row appearance-toggle-row">
                <span className="appearance-row-label">
                  <Sparkles size={15} aria-hidden="true" />
                  <span>专辑封面动态流光</span>
                </span>
                <span className="appearance-switch">
                  <input
                    type="checkbox"
                    checked={appearance.ambientGlow}
                    onChange={(e) => setAmbientGlow(e.target.checked)}
                  />
                  <span
                    className="appearance-switch-slider"
                    aria-hidden="true"
                  />
                </span>
              </label>
              <div className="appearance-detail-row">
                <span className="appearance-row-label">
                  <span>界面排版密度</span>
                </span>
                <div
                  className="theme-density-switch large"
                  role="group"
                  aria-label="信息密度模式"
                >
                  <button
                    type="button"
                    aria-pressed={appearance.density === "modern"}
                    onClick={() => setDensity("modern")}
                  >
                    标准
                  </button>
                  <button
                    type="button"
                    aria-pressed={appearance.density === "compact"}
                    onClick={() => setDensity("compact")}
                  >
                    紧凑
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
