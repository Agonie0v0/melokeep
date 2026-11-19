export type ThemeMode = "system" | "light" | "dark";

export type ThemePresetId =
  | "pure-light"
  | "warm-vinyl"
  | "nordic-frost"
  | "rose-mist"
  | "obsidian"
  | "vinyl-gold"
  | "midnight-aurora"
  | "cyber-amethyst";

export type UIDensity = "modern" | "compact";

export interface ThemePresetMeta {
  id: ThemePresetId;
  name: string;
  subtitle: string;
  scheme: "light" | "dark";
  preview: {
    bg: string;
    surface: string;
    accent: string;
    ink: string;
  };
}

export const THEME_PRESETS: readonly ThemePresetMeta[] = [
  {
    id: "pure-light",
    name: "纯净晨雾",
    subtitle: "经典冷白 · 清透现代",
    scheme: "light",
    preview: {
      bg: "#f4f6fa",
      surface: "#ffffff",
      accent: "#2563eb",
      ink: "#0f172a",
    },
  },
  {
    id: "warm-vinyl",
    name: "暖调黑胶纸",
    subtitle: "柔和暖米 · 复古纸感",
    scheme: "light",
    preview: {
      bg: "#f5efe6",
      surface: "#fffcf7",
      accent: "#c2591b",
      ink: "#292018",
    },
  },
  {
    id: "nordic-frost",
    name: "北欧冰川",
    subtitle: "冷冽青蓝 · 极简清爽",
    scheme: "light",
    preview: {
      bg: "#edf4f7",
      surface: "#fafdff",
      accent: "#0284c7",
      ink: "#0c1e2c",
    },
  },
  {
    id: "rose-mist",
    name: "雾樱漫音",
    subtitle: "低饱和玫瑰 · 温润质感",
    scheme: "light",
    preview: {
      bg: "#f9f1f4",
      surface: "#fffbfc",
      accent: "#db2777",
      ink: "#2a121e",
    },
  },
  {
    id: "obsidian",
    name: "曜石录音室",
    subtitle: "深空炭黑 · 专业声场",
    scheme: "dark",
    preview: {
      bg: "#090b10",
      surface: "#12151e",
      accent: "#60a5fa",
      ink: "#f8fafc",
    },
  },
  {
    id: "vinyl-gold",
    name: "流金黑胶",
    subtitle: "OLED 纯黑 · 香槟流金",
    scheme: "dark",
    preview: {
      bg: "#060608",
      surface: "#121216",
      accent: "#eab308",
      ink: "#fafaf9",
    },
  },
  {
    id: "midnight-aurora",
    name: "午夜极光",
    subtitle: "深海墨青 · 翡翠微光",
    scheme: "dark",
    preview: {
      bg: "#061114",
      surface: "#0d1b20",
      accent: "#10b981",
      ink: "#ecfdf5",
    },
  },
  {
    id: "cyber-amethyst",
    name: "暗紫霓虹",
    subtitle: "星云夜紫 · 灵感电音",
    scheme: "dark",
    preview: {
      bg: "#0b0816",
      surface: "#141026",
      accent: "#a78bfa",
      ink: "#f5f3ff",
    },
  },
];

export const ACCENT_SWATCHES = [
  { label: "钴蓝", color: "#2563eb" },
  { label: "天青", color: "#0284c7" },
  { label: "翡翠", color: "#10b981" },
  { label: "琥珀金", color: "#d97706" },
  { label: "绯樱", color: "#e11d48" },
  { label: "紫罗兰", color: "#8b5cf6" },
] as const;

export interface AppearanceState {
  mode: ThemeMode;
  lightPreset: ThemePresetId;
  darkPreset: ThemePresetId;
  customAccent: string;
  ambientGlow: boolean;
  density: UIDensity;
}

export const DEFAULT_APPEARANCE: AppearanceState = {
  mode: "system",
  lightPreset: "pure-light",
  darkPreset: "obsidian",
  customAccent: "",
  ambientGlow: true,
  density: "modern",
};

const VALID_LIGHT = new Set<ThemePresetId>(
  THEME_PRESETS.filter((p) => p.scheme === "light").map((p) => p.id),
);
const VALID_DARK = new Set<ThemePresetId>(
  THEME_PRESETS.filter((p) => p.scheme === "dark").map((p) => p.id),
);
export const HEX_ACCENT_COLOR = /^#[0-9a-fA-F]{6}$/;

function readInitialMode(): ThemeMode {
  try {
    const saved = globalThis.localStorage?.getItem("melokeep-theme");
    return saved === "light" || saved === "dark" ? saved : "system";
  } catch {
    return "system";
  }
}

export function loadInitialAppearance(): AppearanceState {
  const mode = readInitialMode();
  try {
    const raw = JSON.parse(
      globalThis.localStorage?.getItem("melokeep-appearance") || "null",
    );
    if (raw && typeof raw === "object") {
      const lightPreset: ThemePresetId = VALID_LIGHT.has(raw.lightPreset)
        ? raw.lightPreset
        : DEFAULT_APPEARANCE.lightPreset;
      const darkPreset: ThemePresetId = VALID_DARK.has(raw.darkPreset)
        ? raw.darkPreset
        : DEFAULT_APPEARANCE.darkPreset;
      const customAccent =
        typeof raw.customAccent === "string" &&
        HEX_ACCENT_COLOR.test(raw.customAccent)
          ? raw.customAccent
          : "";
      const ambientGlow =
        typeof raw.ambientGlow === "boolean"
          ? raw.ambientGlow
          : DEFAULT_APPEARANCE.ambientGlow;
      const density: UIDensity =
        raw.density === "compact" ? "compact" : "modern";
      return {
        mode,
        lightPreset,
        darkPreset,
        customAccent,
        ambientGlow,
        density,
      };
    }
  } catch {
    /* Optional browser preferences. */
  }
  return { ...DEFAULT_APPEARANCE, mode };
}

export function resolveAppearanceTokens(
  state: AppearanceState,
  systemDark: boolean,
) {
  const resolvedScheme: "light" | "dark" =
    state.mode === "system" ? (systemDark ? "dark" : "light") : state.mode;
  const activePreset =
    resolvedScheme === "dark" ? state.darkPreset : state.lightPreset;
  return { resolvedScheme, activePreset };
}

export function applyAppearanceToDOM(
  state: AppearanceState,
  systemDark: boolean,
) {
  const root = document.documentElement;
  const { resolvedScheme, activePreset } = resolveAppearanceTokens(
    state,
    systemDark,
  );

  root.dataset.theme = resolvedScheme;
  root.dataset.preset = activePreset;
  root.dataset.density = state.density;
  root.dataset.ambient = state.ambientGlow ? "on" : "off";

  if (state.customAccent && HEX_ACCENT_COLOR.test(state.customAccent)) {
    root.style.setProperty("--accent", state.customAccent);
    root.style.setProperty(
      "--soft",
      `color-mix(in srgb, ${state.customAccent} 14%, transparent)`,
    );
    root.style.setProperty(
      "--ambient-glow",
      `color-mix(in srgb, ${state.customAccent} 16%, transparent)`,
    );
  } else {
    root.style.removeProperty("--accent");
    root.style.removeProperty("--soft");
    root.style.removeProperty("--ambient-glow");
  }
}
