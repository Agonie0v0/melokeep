import type { LucideIcon } from "lucide-react";
import { useEffect, useRef } from "react";

export function WorkspaceTabs({
  id,
  label,
  tabs,
  active,
  change,
  orientation = "horizontal",
  className,
  badges,
  dots,
}: {
  id: string;
  label: string;
  tabs: readonly (readonly [string, string, LucideIcon])[];
  active: string;
  change: (tab: string) => void;
  orientation?: "horizontal" | "vertical";
  className?: string;
  badges?: Partial<Record<string, string>>;
  dots?: Partial<Record<string, boolean>>;
}) {
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = container.current;
    if (!element || orientation === "vertical") return;
    const reveal = () => {
      const tab = element.querySelector<HTMLElement>('[aria-selected="true"]');
      if (!tab) return;
      const box = tab.getBoundingClientRect(),
        bounds = element.getBoundingClientRect();
      if (box.left < bounds.left || box.right > bounds.right)
        element.scrollLeft +=
          box.left - bounds.left - (bounds.width - box.width) / 2;
    };
    reveal();
    const observer = new ResizeObserver(reveal);
    observer.observe(element);
    return () => observer.disconnect();
  }, [active, orientation]);
  return (
    <div
      ref={container}
      className={[
        "library-tabs",
        orientation === "vertical" ? "library-tabs-vertical" : "",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      role="tablist"
      aria-label={label}
      aria-orientation={orientation === "vertical" ? "vertical" : undefined}
      onKeyDown={(event) => {
        if (!tabs.length) return;
        const index = tabs.findIndex(([key]) => key === active);
        const currentIndex = index >= 0 ? index : 0;
        const next =
          event.key === "ArrowRight" || event.key === "ArrowDown"
            ? (currentIndex + 1) % tabs.length
            : event.key === "ArrowLeft" || event.key === "ArrowUp"
              ? (currentIndex + tabs.length - 1) % tabs.length
              : event.key === "Home"
                ? 0
                : event.key === "End"
                  ? tabs.length - 1
                  : -1;
        if (next < 0) return;
        event.preventDefault();
        change(tabs[next][0]);
        document.getElementById(`${id}-tab-${tabs[next][0]}`)?.focus();
      }}
    >
      {tabs.map(([key, title, Icon]) => {
        const badge = badges?.[key];
        const hasDot = Boolean(dots?.[key]);
        return (
          <div key={key} className="workspace-tab-item">
            <button
              type="button"
              role="tab"
              tabIndex={active === key ? 0 : -1}
              aria-selected={active === key}
              aria-controls={`${id}-panel-${key}`}
              id={`${id}-tab-${key}`}
              onClick={() => change(key)}
            >
              <Icon aria-hidden="true" />
              <span className="workspace-tab-label">{title}</span>
            </button>
            {hasDot && (
              <span
                className="settings-nav-dot"
                aria-label="有可用更新"
                title="有可用更新"
              />
            )}
            {badge && (
              <span className="settings-nav-badge" aria-hidden="true">
                {badge}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
