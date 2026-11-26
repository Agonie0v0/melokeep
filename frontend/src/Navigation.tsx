import {
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from "react";
import {
  Download,
  Folder,
  GripVertical,
  ListMusic,
  Music2,
  MessageSquare,
  RotateCcw,
  Search,
  Settings,
  ScrollText,
  Sparkles,
  X,
} from "lucide-react";

const items = [
  ["search", "搜索音乐", Search],
  ["daily", "每日推荐", Sparkles],
  ["assistant", "AI 助手", MessageSquare],
  ["player", "正在播放", Music2],
  ["playlists", "歌单", ListMusic],
  ["queue", "下载队列", Download],
  ["files", "音乐库", Folder],
  ["logs", "运行日志", ScrollText],
  ["settings", "设置", Settings],
] as const;
export type NavigationView = (typeof items)[number][0];
const defaults = items.map(([id]) => id);

export function Navigation({
  active,
  change,
  downloads,
  hasUpdate = false,
  serverOrder,
  onOrderSave,
}: {
  active: string;
  change: (view: NavigationView) => void;
  downloads: number;
  hasUpdate?: boolean;
  serverOrder?: string[] | null;
  onOrderSave?: (next: NavigationView[]) => void;
}) {
  const [order, setOrder] = useState<NavigationView[]>(() => {
    try {
      const saved: unknown = JSON.parse(
        localStorage.getItem("melokeep-navigation") || "null",
      );
      if (Array.isArray(saved))
        return [...new Set([...saved, ...defaults])].filter(
          (id): id is NavigationView => defaults.includes(id),
        );
    } catch {
      /* Local preferences are optional. */
    }
    return defaults;
  });
  useEffect(() => {
    if (!Array.isArray(serverOrder) || !serverOrder.length) return;
    const normalized = [...new Set([...serverOrder, ...defaults])].filter(
      (id): id is NavigationView => defaults.includes(id as NavigationView),
    );
    if (!normalized.length) return;
    setOrder(normalized);
    try {
      localStorage.setItem("melokeep-navigation", JSON.stringify(normalized));
    } catch {
      /* Storage may be unavailable. */
    }
  }, [serverOrder]);
  const dragged = useRef<NavigationView | null>(null);
  const navigation = useRef<HTMLElement>(null);
  const listRef = useRef<HTMLOListElement>(null);
  const orderRef = useRef(order);
  orderRef.current = order;
  const [draggingId, setDraggingId] = useState<NavigationView | null>(null);
  useEffect(() => {
    const nav = navigation.current;
    if (!nav) return;
    const reveal = () => {
      const item = nav.querySelector<HTMLElement>('[aria-current="page"]');
      if (!item) return;
      const box = item.getBoundingClientRect(),
        bounds = nav.getBoundingClientRect();
      if (box.left < bounds.left || box.right > bounds.right)
        nav.scrollLeft +=
          box.left - bounds.left - (bounds.width - box.width) / 2;
    };
    reveal();
    const observer = new ResizeObserver(reveal);
    observer.observe(nav);
    return () => observer.disconnect();
  }, [active, order]);
  function save(next: NavigationView[]) {
    setOrder(next);
    try {
      localStorage.setItem("melokeep-navigation", JSON.stringify(next));
    } catch {
      /* Storage may be unavailable. */
    }
    onOrderSave?.(next);
  }
  function move(id: NavigationView, target: NavigationView) {
    if (id === target) return;
    const next = order.filter((value) => value !== id);
    next.splice(order.indexOf(target), 0, id);
    save(next);
  }
  function startPointerReorder(
    id: NavigationView,
    event: ReactPointerEvent<HTMLLIElement>,
  ) {
    if (event.button !== 0) return;
    const startY = event.clientY;
    let activeDrag = false;
    const onMove = (moveEvent: PointerEvent) => {
      if (!activeDrag) {
        if (Math.abs(moveEvent.clientY - startY) < 4) return;
        activeDrag = true;
        setDraggingId(id);
      }
      const list = listRef.current;
      if (!list) return;
      const rows = Array.from(
        list.querySelectorAll<HTMLLIElement>("li[data-nav-id]"),
      );
      const current = orderRef.current;
      const fromIndex = current.indexOf(id);
      if (fromIndex < 0) return;
      let toIndex = fromIndex;
      for (let i = 0; i < rows.length; i++) {
        if (i === fromIndex) continue;
        const rect = rows[i].getBoundingClientRect();
        const midpoint = rect.top + rect.height / 2;
        if (i < fromIndex && moveEvent.clientY < midpoint) {
          toIndex = i;
          break;
        }
        if (i > fromIndex && moveEvent.clientY > midpoint) {
          toIndex = i;
        }
      }
      if (toIndex !== fromIndex) {
        const next = current.filter((value) => value !== id);
        next.splice(toIndex, 0, id);
        orderRef.current = next;
        setOrder(next);
      }
    };
    const onEnd = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onEnd);
      window.removeEventListener("pointercancel", onEnd);
      if (activeDrag) {
        setDraggingId(null);
        save(orderRef.current);
      }
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onEnd);
    window.addEventListener("pointercancel", onEnd);
  }
  return (
    <div className="navigation-group">
      <nav ref={navigation} aria-label="主导航">
        {order.map((id, index) => {
          const [, label, Icon] = items.find(([value]) => value === id)!;
          return (
            <button
              key={id}
              className={`nav-item ${active === id ? "active" : ""}`}
              aria-current={active === id ? "page" : undefined}
              draggable
              title={`${label}；拖动可调整顺序，或按 Alt + 左右方向键移动`}
              onDragStart={(event) => {
                dragged.current = id;
                event.dataTransfer.setData("text/plain", id);
                event.dataTransfer.effectAllowed = "move";
              }}
              onDragOver={(event) => {
                if (dragged.current) event.preventDefault();
              }}
              onDrop={(event) => {
                event.preventDefault();
                if (dragged.current) move(dragged.current, id);
                dragged.current = null;
              }}
              onDragEnd={() => {
                dragged.current = null;
              }}
              onKeyDown={(event) => {
                if (
                  !event.altKey ||
                  !["ArrowLeft", "ArrowRight"].includes(event.key)
                )
                  return;
                event.preventDefault();
                const target = index + (event.key === "ArrowLeft" ? -1 : 1);
                if (target >= 0 && target < order.length)
                  move(id, order[target]);
              }}
              onClick={() => change(id)}
            >
              <Icon />
              {label}
              {id === "queue" && downloads > 0 && (
                <span className="nav-count">{downloads}</span>
              )}
              {id === "settings" && hasUpdate && (
                <span
                  className="nav-dot"
                  title="有可用更新"
                  aria-label="有可用更新"
                />
              )}
            </button>
          );
        })}
      </nav>
      <div id="navigation-order" popover="auto" className="navigation-order">
        <div className="navigation-order-heading">
          <strong>导航顺序</strong>
          <button
            className="icon-button"
            aria-label="关闭导航排序"
            popoverTarget="navigation-order"
            popoverTargetAction="hide"
          >
            <X />
          </button>
        </div>
        <ol ref={listRef} className={draggingId ? "reordering" : undefined}>
          {order.map((id, index) => {
            const [, label, Icon] = items.find(([value]) => value === id)!;
            const isCurrent = active === id;
            return (
              <li
                key={id}
                data-nav-id={id}
                tabIndex={0}
                aria-label={`${label}，第 ${index + 1} 项，按住拖动或按上下方向键调整顺序`}
                className={[
                  draggingId === id ? "dragging" : "",
                  isCurrent ? "current" : "",
                ]
                  .filter(Boolean)
                  .join(" ")}
                onPointerDown={(event) => startPointerReorder(id, event)}
                onKeyDown={(event) => {
                  if (!["ArrowUp", "ArrowDown"].includes(event.key)) return;
                  event.preventDefault();
                  const target = index + (event.key === "ArrowUp" ? -1 : 1);
                  if (target >= 0 && target < order.length)
                    move(id, order[target]);
                }}
              >
                <Icon />
                <span>{label}</span>
                <small className="navigation-order-index" aria-hidden="true">
                  {index + 1}
                </small>
                <GripVertical
                  className="navigation-order-grip"
                  aria-hidden="true"
                />
              </li>
            );
          })}
        </ol>
        <button
          className="secondary navigation-order-reset"
          onClick={() => save(defaults)}
        >
          <RotateCcw size={14} />
          恢复默认顺序
        </button>
      </div>
    </div>
  );
}
