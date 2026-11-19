import { useRef } from "react";
import { Check, Monitor, Moon, RotateCcw, Sparkles, Sun } from "lucide-react";
import {
  ACCENT_SWATCHES,
  THEME_PRESETS,
  type AppearanceController,
} from "./theme";

const modes = [
  ["system", "跟随系统", Monitor],
  ["light", "浅色", Sun],
  ["dark", "深色", Moon],
] as const;

export function ThemePicker({
  controller,
  openMore,
}: {
  controller: AppearanceController;
  openMore?: () => void;
}) {
  const menu = useRef<HTMLDivElement>(null);
  const {
    appearance,
    activePreset,
    setMode,
    selectPreset,
    setCustomAccent,
    setAmbientGlow,
    setDensity,
    resetAppearance,
  } = controller;
  const Icon = modes.find(([value]) => value === appearance.mode)![2];

  return (
    <>
      <button
        type="button"
        className="icon-button theme-toggle"
        aria-label="切换主题"
        title="切换主题与外观"
        popoverTarget="theme-picker"
      >
        <Icon aria-hidden="true" />
      </button>
      <div ref={menu} id="theme-picker" popover="auto" className="theme-picker">
        <div className="theme-picker-section">
          <div className="theme-picker-title">外观模式</div>
          <div role="group" aria-label="界面主题" className="theme-mode-group">
            {modes.map(([value, label, OptionIcon]) => (
              <button
                key={value}
                type="button"
                aria-pressed={appearance.mode === value}
                onClick={() => {
                  setMode(value);
                  menu.current?.hidePopover();
                }}
              >
                <OptionIcon aria-hidden="true" />
                <span>{label}</span>
                {appearance.mode === value && <Check aria-hidden="true" />}
              </button>
            ))}
          </div>
        </div>

        <div className="theme-picker-section">
          <div className="theme-picker-title-row">
            <span className="theme-picker-title">精选色板</span>
            <button
              type="button"
              className="theme-reset-mini"
              title="恢复默认外观"
              onClick={resetAppearance}
            >
              <RotateCcw aria-hidden="true" />
              重置
            </button>
          </div>
          <div
            role="group"
            aria-label="主题色板"
            className="theme-preset-mini-grid"
          >
            {THEME_PRESETS.map((preset) => {
              const selected = activePreset === preset.id;
              return (
                <button
                  key={preset.id}
                  type="button"
                  className={`theme-swatch-card ${selected ? "active" : ""}`}
                  aria-pressed={selected}
                  title={`${preset.name}（${preset.subtitle}）`}
                  onClick={() => selectPreset(preset.id)}
                >
                  <span
                    className="theme-swatch-preview"
                    style={{
                      background: preset.preview.bg,
                      borderColor: preset.preview.accent,
                    }}
                  >
                    <i
                      style={{ background: preset.preview.surface }}
                      aria-hidden="true"
                    />
                    <em
                      style={{ background: preset.preview.accent }}
                      aria-hidden="true"
                    />
                  </span>
                  <span className="theme-swatch-name">{preset.name}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="theme-picker-section">
          <div className="theme-picker-title-row">
            <span className="theme-picker-title">主题强调色</span>
            {appearance.customAccent && (
              <button
                type="button"
                className="theme-reset-mini"
                onClick={() => setCustomAccent("")}
              >
                跟随色板
              </button>
            )}
          </div>
          <div className="accent-swatch-row">
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
            <label className="accent-custom-pick" title="自定义颜色">
              <input
                type="color"
                aria-label="自定义强调色"
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

        <div className="theme-picker-section theme-picker-toggles">
          <button
            type="button"
            className="theme-toggle-pill"
            aria-pressed={appearance.ambientGlow}
            onClick={() => setAmbientGlow(!appearance.ambientGlow)}
          >
            <Sparkles aria-hidden="true" />
            <span>动态封面流光</span>
            <strong>{appearance.ambientGlow ? "开" : "关"}</strong>
          </button>
          <div
            className="theme-density-switch"
            role="group"
            aria-label="界面密度"
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

        {openMore && (
          <button
            type="button"
            className="theme-picker-more"
            onClick={() => {
              menu.current?.hidePopover();
              openMore();
            }}
          >
            打开完整外观设置…
          </button>
        )}
      </div>
    </>
  );
}
