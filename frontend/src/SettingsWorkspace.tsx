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
  hasUpdate = false,
  ...engine
}: ComponentProps<typeof EngineSettings> & {
  tab: SettingsTab;
  setTab: (tab: SettingsTab) => void;
  updateInfo: SystemUpdateInfo | null;
  checkingUpdate: boolean;
  updateError: string;
  onCheckUpdate: (force: boolean) => Promise<void>;
  appearance?: AppearanceController;
  hasUpdate?: boolean;
}) {
  return (
    <div className="settings-workspace">
      <aside className="settings-nav-rail" aria-label="设置导航">
        <div className="settings-nav-head">
          <strong>系统设置</strong>
          <span className="settings-nav-count">{tabs.length} 项</span>
        </div>
        <WorkspaceTabs
          id="settings"
          label="设置分类"
          orientation="vertical"
          tabs={tabs}
          active={tab}
          change={(value) => setTab(value as SettingsTab)}
          badges={
            updateInfo?.current?.version
              ? { update: updateInfo.current.version }
              : undefined
          }
          dots={hasUpdate ? { update: true } : undefined}
        />
        <div className="settings-nav-foot" aria-label="实例运行状态">
          <div className="settings-nav-status">
            <span className="settings-nav-status-dot" aria-hidden="true" />
            <span>
              {updateInfo?.current?.in_container
                ? "Docker 容器 · 运行正常"
                : "本地服务 · 运行正常"}
            </span>
          </div>
          <div className="settings-nav-meta">
            {`当前版本 ${updateInfo?.current?.version || "已就绪"}${
              updateInfo?.current?.short_commit
                ? ` · ${updateInfo.current.short_commit}`
                : ""
            }`}
          </div>
        </div>
      </aside>
      <section
        className="view settings-panel-host"
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
