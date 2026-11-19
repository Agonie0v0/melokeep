import { useCallback, useEffect, useState } from "react";
import {
  parseAppearancePreferences,
  scheduleSavePreferences,
  toAppearancePreferences,
  type AppearancePreferences,
} from "./preferences";
import {
  ACCENT_SWATCHES,
  DEFAULT_APPEARANCE,
  HEX_ACCENT_COLOR,
  THEME_PRESETS,
  applyAppearanceToDOM,
  loadInitialAppearance,
  resolveAppearanceTokens,
  type AppearanceState,
  type ThemeMode,
  type ThemePresetId,
  type ThemePresetMeta,
  type UIDensity,
} from "./theme-presets";

export {
  ACCENT_SWATCHES,
  DEFAULT_APPEARANCE,
  THEME_PRESETS,
  applyAppearanceToDOM,
  loadInitialAppearance,
  resolveAppearanceTokens,
};
export type {
  AppearanceState,
  ThemeMode,
  ThemePresetId,
  ThemePresetMeta,
  UIDensity,
};

export function useAppearance() {
  const [appearance, setAppearance] = useState<AppearanceState>(
    loadInitialAppearance,
  );
  const [systemDark, setSystemDark] = useState<boolean>(() => {
    try {
      return typeof matchMedia === "function"
        ? matchMedia("(prefers-color-scheme: dark)").matches
        : false;
    } catch {
      return false;
    }
  });

  useEffect(() => {
    if (typeof matchMedia !== "function") return;
    const media = matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => setSystemDark(media.matches);
    onChange();
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, []);

  useEffect(() => {
    applyAppearanceToDOM(appearance, systemDark);
    try {
      localStorage.setItem("melokeep-theme", appearance.mode);
      localStorage.setItem(
        "melokeep-appearance",
        JSON.stringify({
          lightPreset: appearance.lightPreset,
          darkPreset: appearance.darkPreset,
          customAccent: appearance.customAccent,
          ambientGlow: appearance.ambientGlow,
          density: appearance.density,
        }),
      );
    } catch {
      /* Storage may be unavailable. */
    }
  }, [appearance, systemDark]);

  const { resolvedScheme, activePreset } = resolveAppearanceTokens(
    appearance,
    systemDark,
  );

  const commitAppearance = useCallback(
    (updater: (prev: AppearanceState) => AppearanceState) => {
      setAppearance((prev) => {
        const next = updater(prev);
        scheduleSavePreferences({
          appearance: toAppearancePreferences(next),
        });
        return next;
      });
    },
    [],
  );

  const hydrateFromServer = useCallback((dto?: AppearancePreferences) => {
    if (!dto) return;
    setAppearance((prev) => parseAppearancePreferences(dto, prev));
  }, []);

  const setMode = useCallback(
    (mode: ThemeMode) => {
      commitAppearance((prev) => ({ ...prev, mode }));
    },
    [commitAppearance],
  );

  const selectPreset = useCallback(
    (presetId: ThemePresetId) => {
      const preset = THEME_PRESETS.find((p) => p.id === presetId);
      if (!preset) return;
      commitAppearance((prev) => ({
        ...prev,
        mode: preset.scheme,
        lightPreset: preset.scheme === "light" ? presetId : prev.lightPreset,
        darkPreset: preset.scheme === "dark" ? presetId : prev.darkPreset,
      }));
    },
    [commitAppearance],
  );

  const bindSchemePreset = useCallback(
    (scheme: "light" | "dark", presetId: ThemePresetId) => {
      commitAppearance((prev) => ({
        ...prev,
        lightPreset: scheme === "light" ? presetId : prev.lightPreset,
        darkPreset: scheme === "dark" ? presetId : prev.darkPreset,
      }));
    },
    [commitAppearance],
  );

  const setCustomAccent = useCallback(
    (color: string) => {
      const clean = color.trim();
      commitAppearance((prev) => ({
        ...prev,
        customAccent: HEX_ACCENT_COLOR.test(clean) ? clean : "",
      }));
    },
    [commitAppearance],
  );

  const setAmbientGlow = useCallback(
    (ambientGlow: boolean) => {
      commitAppearance((prev) => ({ ...prev, ambientGlow }));
    },
    [commitAppearance],
  );

  const setDensity = useCallback(
    (density: UIDensity) => {
      commitAppearance((prev) => ({ ...prev, density }));
    },
    [commitAppearance],
  );

  const resetAppearance = useCallback(() => {
    commitAppearance(() => ({ ...DEFAULT_APPEARANCE }));
  }, [commitAppearance]);

  return {
    appearance,
    resolvedScheme,
    activePreset,
    hydrateFromServer,
    setMode,
    selectPreset,
    bindSchemePreset,
    setCustomAccent,
    setAmbientGlow,
    setDensity,
    resetAppearance,
  };
}

export type AppearanceController = ReturnType<typeof useAppearance>;
