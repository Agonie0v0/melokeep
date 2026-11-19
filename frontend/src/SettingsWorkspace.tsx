import type { ComponentProps } from "react";
import {
  ArrowUpCircle,
  Bell,
  Download,
  Library,
  WandSparkles,
  MessageSquare,
  Palette,
} from "lucide-react";
import { AISettings } from "./AISettings";
import { AppearanceSettings } from "./AppearanceSettings";
import { EngineSettings } from "./EngineSettings";
import { LibrarySettings } from "./LibrarySettings";
import { BotSettings } from "./BotSettings";
import { UpdateSettings } from "./UpdateSettings";
import { WorkspaceTabs } from "./WorkspaceTabs";
import type { AppearanceController } from "./theme";
import type { SystemUpdateInfo } from "./types";

const tabs = [
  ["engine", "音源与下载", Download],
  ["library", "刮削与归档", WandSparkles],
  ["navidrome", "Navidrome 连接", Library],
  ["bots", "通知与机器人", Bell],
  ["ai", "AI 助手", MessageSquare],
  ["appearance", "外观与主题", Palette],
  ["update", "版本与更新", ArrowUpCircle],
] as const;
export type SettingsTab = (typeof tabs)[number][0];

export function SettingsWorkspace({
  tab,
  setTab,
  updateInfo,
  checkingUpdate,
  updateError,
  onCheckUpdate,
  appearance,
  ...engine
}: ComponentProps<typeof EngineSettings> & {
  tab: SettingsTab;
  setTab: (tab: SettingsTab) => void;
  updateInfo: SystemUpdateInfo | null;
  checkingUpdate: boolean;
  updateError: string;
  onCheckUpdate: (force: boolean) => Promise<void>;
  appearance?: AppearanceController;
}) {
  return (
    <div className="settings-workspace">
      <WorkspaceTabs
        id="settings"
        label="设置分类"
        tabs={tabs}
        active={tab}
        change={(value) => setTab(value as SettingsTab)}
      />
      <section
        className="view"
        role="tabpanel"
        id={`settings-panel-${tab}`}
        aria-labelledby={`settings-tab-${tab}`}
      >
        {tab === "engine" && <EngineSettings {...engine} />}
        {(tab === "library" || tab === "navidrome") && (
          <LibrarySettings
            key={tab}
            section={tab}
            openSources={() => setTab("engine")}
          />
        )}
        {tab === "bots" && <BotSettings />}
        {tab === "ai" && <AISettings />}
        {tab === "appearance" && appearance && (
          <AppearanceSettings controller={appearance} />
        )}
        {tab === "update" && (
          <UpdateSettings
            info={updateInfo}
            checking={checkingUpdate}
            error={updateError}
            onCheck={onCheckUpdate}
          />
        )}
      </section>
    </div>
  );
}
