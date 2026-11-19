import { useEffect, useMemo, useRef, useState } from "react";
import {
  CheckSquare,
  ChevronDown,
  ChevronRight,
  Disc3,
  FolderKanban,
  History,
  ImagePlus,
  ListMusic,
  MessageSquare,
  Plus,
  RotateCcw,
  Search,
  Send,
  Settings2,
  Sparkles,
  Square,
  Trash2,
  X,
} from "lucide-react";
import { api } from "./api";
import { AIImport } from "./AIImport";
import { AIMarkdown } from "./AIMarkdown";
import type { DownloadJob, PlaylistSummary, Track } from "./types";
import "./ai.css";

type Summary = {
  id: string;
  title: string;
  status: string;
};

type Event = {
  id?: string;
  turn_id?: string;
  created_at?: number;
  tool: string;
  status: string;
  detail: string;
  operation?: string;
  result?: unknown;
};

type Message = {
  role: string;
  content: string;
  has_image?: boolean;
  turn_id?: string;
  created_at?: number;
};

type Session = Summary & {
  detail: string;
  partial?: string;
  active_turn_id?: string;
  plan?: { goal: string; steps: { title: string; status: string }[] };
  decision?: string;
  messages: Message[];
  events: Event[];
};

type ResultRow = {
  id?: string;
  title?: string;
  name?: string;
  artist?: string;
  album?: string;
  source?: string;
  qualities?: { label: string }[];
  status?: string;
  detail?: string;
  track_count?: number;
  track?: { title?: string; artist?: string };
};

type IndexedEvent = {
  event: Event;
  index: number;
};

type Turn = {
  key: string;
  turnId?: string;
  userMessage: Message | null;
  assistantMessages: Message[];
  events: IndexedEvent[];
  isLatest: boolean;
};

type EventFilter = "all" | "complete" | "attention" | "running";

const labels: Record<string, string> = {
  search_music: "搜索音乐",
  list_playlists: "查询歌单",
  read_playlist: "读取歌单",
  create_playlist: "创建歌单",
  add_to_playlist: "加入歌单",
  download_music: "下载音乐",
  get_status: "查询任务",
  get_task_status: "查询指定任务",
  browse_library: "查询音乐库",
  preview_organize: "生成整理预览",
  list_project_operations: "查询项目操作",
  query_project: "查询项目",
  execute_project: "执行项目操作",
  wait_for_tasks: "等待后台任务",
  prepare_import: "识别导入清单",
  update_plan: "更新任务计划",
  request_decision: "需要你的决定",
  recover_download: "恢复下载或整理",
};

const states: Record<string, string> = {
  running: "执行中",
  stopping: "正在停止",
  waiting_tasks: "等待后台任务",
  complete: "已完成",
  failed: "失败",
  stopped: "已停止",
  interrupted: "已中断",
  needs_decision: "需要你的确认",
  idle: "待开始",
  pending: "待处理",
  blocked: "待解决",
};

const examples = [
  "搜索独立音乐《Solar Wind》，列出可下载版本",
  "把图片上的歌曲加入歌单「截图收藏」",
  "搜索开放许可曲目《Open Sky》，下载后整理入库",
];

const active = (session: Summary | null) =>
  !!session &&
  ["running", "stopping", "waiting_tasks"].includes(session.status);

function statusTone(
  status: string,
): "running" | "complete" | "failed" | "stopped" | "idle" {
  if (
    status === "running" ||
    status === "stopping" ||
    status === "waiting_tasks"
  )
    return "running";
  if (status === "complete") return "complete";
  if (status === "failed") return "failed";
  if (
    status === "stopped" ||
    status === "interrupted" ||
    status === "blocked" ||
    status === "needs_decision"
  )
    return "stopped";
  return "idle";
}

function formatTime(timestamp?: number): string {
  if (!timestamp) return "";
  try {
    return new Date(timestamp).toLocaleTimeString("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return "";
  }
}

function eventKey(event: Event, index: number): string {
  return event.id ? `id:${event.id}` : `idx:${index}`;
}

function groupTurns(session: Session | null): Turn[] {
  if (!session) return [];
  const rawTurns: Omit<Turn, "isLatest">[] = [];
  for (const msg of session.messages) {
    if (msg.role === "user") {
      rawTurns.push({
        key: msg.turn_id || `turn-${rawTurns.length}`,
        turnId: msg.turn_id,
        userMessage: msg,
        assistantMessages: [],
        events: [],
      });
    } else {
      const current = rawTurns[rawTurns.length - 1];
      if (
        current &&
        (!msg.turn_id || !current.turnId || msg.turn_id === current.turnId)
      ) {
        if (!current.turnId && msg.turn_id) current.turnId = msg.turn_id;
        current.assistantMessages.push(msg);
      } else {
        rawTurns.push({
          key: msg.turn_id || `turn-${rawTurns.length}`,
          turnId: msg.turn_id,
          userMessage: null,
          assistantMessages: [msg],
          events: [],
        });
      }
    }
  }

  const byTurnId = new Map<string, Omit<Turn, "isLatest">>();
  for (const turn of rawTurns) {
    if (turn.turnId && !byTurnId.has(turn.turnId)) {
      byTurnId.set(turn.turnId, turn);
    }
  }

  const userTurnsCount = rawTurns.filter((turn) => turn.userMessage).length;
  (session.events || []).forEach((event, index) => {
    if (event.turn_id) {
      const target = byTurnId.get(event.turn_id);
      if (target) target.events.push({ event, index });
      return;
    }
    // Legacy sessions without turn_id: only attach when there is at most one user turn,
    // preventing earlier tasks' search candidates from leaking onto subsequent turns.
    if (userTurnsCount <= 1 && rawTurns.length > 0) {
      rawTurns[rawTurns.length - 1].events.push({ event, index });
    }
  });

  return rawTurns.map((turn, idx) => ({
    ...turn,
    isLatest: idx === rawTurns.length - 1,
  }));
}

function ExecutionResult({
  value,
  play,
  download,
}: {
  value: unknown;
  play: (track: Track) => void;
  download: (track: Track) => void;
}) {
  if (!value || typeof value !== "object") return null;
  const envelope = value as Record<string, unknown>;
  const result = (
    envelope.data && typeof envelope.data === "object" ? envelope.data : value
  ) as Record<string, unknown>;
  const rows = [
    "tracks",
    "playlists",
    "downloads",
    "files",
    "library_jobs",
  ].flatMap((key) =>
    Array.isArray(result[key]) ? (result[key] as ResultRow[]) : [],
  );
  if (!rows.length) return <pre>{JSON.stringify(result, null, 2)}</pre>;
  return (
    <ul className="ai-result-list">
      {rows.map((row, index) => (
        <li key={index}>
          <strong>{row.title || row.name || row.track?.title || "任务"}</strong>
          {(row.artist || row.track?.artist) && (
            <span>{row.artist || row.track?.artist}</span>
          )}
          {row.album && <span>{row.album}</span>}
          {row.source && <span>{row.source}</span>}
          {row.qualities?.length ? (
            <span>{row.qualities.map((q) => q.label).join(" / ")}</span>
          ) : row.id && row.title && row.source ? (
            <span>音质待确认</span>
          ) : null}
          {row.track_count !== undefined && <span>{row.track_count} 首</span>}
          {row.status && (
            <span>
              {states[row.status] ||
                (
                  {
                    queued: "已入队",
                    downloading: "下载中",
                    paused: "已暂停",
                    exists: "已存在",
                    needs_review: "待审核",
                    ready: "可审核",
                  } as Record<string, string>
                )[row.status] ||
                row.status}
            </span>
          )}
          {row.detail && <small>{row.detail}</small>}
          {row.id && row.title && row.source && (
            <div className="ai-result-actions">
              <button
                type="button"
                className="text-button"
                onClick={() => play(row as Track)}
              >
                试听
              </button>
              <button
                type="button"
                className="text-button"
                onClick={() => download(row as Track)}
              >
                下载
              </button>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

type Draft = { message: string; image: string; imageName: string };
const drafts = new Map<string, Draft>();

export function AIAssistant({
  openSettings,
  changed,
  navigate,
  play,
  download,
  contextTracks,
  clearContext,
  jobs,
  playlists,
}: {
  openSettings: () => void;
  changed: () => Promise<void>;
  navigate: (view: "queue" | "playlists" | "organize") => void;
  play: (track: Track) => void;
  download: (track: Track) => void;
  contextTracks: Track[];
  clearContext: () => void;
  jobs: DownloadJob[];
  playlists: PlaylistSummary[];
}) {
  const [sessions, setSessions] = useState<Summary[]>([]);
  const [session, setSession] = useState<Session | null>(null);
  const [message, setMessage] = useState(
    () => drafts.get("new")?.message || "",
  );
  const [image, setImage] = useState(() => drafts.get("new")?.image || "");
  const [imageName, setImageName] = useState(
    () => drafts.get("new")?.imageName || "",
  );
  const [allowActions, setAllowActions] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [ready, setReady] = useState<boolean | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [activeTab, setActiveTab] = useState<"chat" | "events">("chat");
  const [showPlan, setShowPlan] = useState(false);
  const [expandedCandidates, setExpandedCandidates] = useState<
    Record<string, boolean>
  >({});
  const [hiddenCandidates, setHiddenCandidates] = useState<
    Record<string, boolean>
  >({});
  const [expandedImports, setExpandedImports] = useState<
    Record<string, boolean>
  >({});
  const [eventFilter, setEventFilter] = useState<EventFilter>("all");
  const [eventQuery, setEventQuery] = useState("");
  const [turnFilter, setTurnFilter] = useState<string | null>(null);
  const [selectedEvents, setSelectedEvents] = useState<Record<string, boolean>>(
    {},
  );

  const upload = useRef<HTMLInputElement>(null);
  const end = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const mounted = useRef(true);
  const draftKey = useRef("new");
  const draft = useRef<Draft>({ message: "", image: "", imageName: "" });
  draft.current = { message, image, imageName };

  function remember(value: Session | null) {
    const nextKey = value?.id || "new";
    if (nextKey !== draftKey.current) {
      drafts.set(draftKey.current, draft.current);
      const next = drafts.get(nextKey) || {
        message: "",
        image: "",
        imageName: "",
      };
      draftKey.current = nextKey;
      draft.current = next;
      setMessage(next.message);
      setImage(next.image);
      setImageName(next.imageName);
      setSelectedEvents({});
      setTurnFilter(null);
      setEventQuery("");
      setEventFilter("all");
    }
    setSession(value);
    try {
      if (value) localStorage.setItem("melokeep-ai-session", value.id);
      else localStorage.removeItem("melokeep-ai-session");
    } catch {
      /* Optional preference. */
    }
  }

  async function load() {
    setError("");
    try {
      const [list, settings] = await Promise.all([
        api<Summary[]>("/agent/sessions"),
        api<{ enabled: boolean; model: string; base_url: string }>(
          "/agent/settings",
        ),
      ]);
      if (!mounted.current) return;
      setSessions(list);
      setReady(!!(settings.enabled && settings.model && settings.base_url));
      let last = "";
      try {
        last = localStorage.getItem("melokeep-ai-session") || "";
      } catch {
        /* Optional preference. */
      }
      const selected = list.find((s) => s.id === last) || list[0];
      if (selected) {
        const value = await api<Session>(`/agent/sessions/${selected.id}`);
        if (mounted.current) remember(value);
      }
      setLoaded(true);
    } catch (e) {
      if (mounted.current) setError((e as Error).message);
    }
  }

  useEffect(() => {
    mounted.current = true;
    void load();
    return () => {
      mounted.current = false;
      drafts.set(draftKey.current, draft.current);
    };
  }, []);

  const isRunning = active(session);

  const jobIds = new Set(
    (session?.events || []).flatMap((event) => {
      if (
        !["download_music", "recover_download", "execute_project"].includes(
          event.tool,
        ) ||
        !event.result ||
        typeof event.result !== "object"
      )
        return [];
      const envelope = event.result as {
        downloads?: { id?: string }[];
        data?: { id?: string }[];
      };
      const downloads =
        envelope.downloads ||
        (event.operation === "POST /api/downloads" ? envelope.data : []);
      return Array.isArray(downloads)
        ? downloads.flatMap((item) => (item.id ? [item.id] : []))
        : [];
    }),
  );
  const relatedJobs = jobs.filter((job) => jobIds.has(job.id));

  useEffect(() => {
    if (!session || !isRunning) return;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const value = await api<Session>(`/agent/sessions/${session!.id}`);
        if (disposed) return;
        setSession(value);
        setError("");
        if (!active(value)) {
          setSessions(await api<Summary[]>("/agent/sessions"));
          await changed();
        } else timer = setTimeout(() => void poll(), 1200);
      } catch (e) {
        if (!disposed) {
          setError((e as Error).message);
          timer = setTimeout(() => void poll(), 4000);
        }
      }
    }
    timer = setTimeout(() => void poll(), 800);
    return () => {
      disposed = true;
      clearTimeout(timer);
    };
  }, [session?.id, isRunning]);

  useEffect(() => {
    if (activeTab === "chat") {
      end.current?.scrollIntoView({ block: "nearest", behavior: "instant" });
    }
  }, [session?.messages.length, activeTab]);

  async function selectSession(id: string) {
    if (!id || id === session?.id) return;
    setBusy(true);
    setError("");
    try {
      remember(await api<Session>(`/agent/sessions/${id}`));
      setActiveTab("chat");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function removeSession(target: Summary) {
    if (
      active(target) ||
      !window.confirm("删除此对话及执行记录？已创建的歌单和下载任务会保留。")
    )
      return;
    setBusy(true);
    setError("");
    try {
      await api(`/agent/sessions/${target.id}`, "DELETE");
      const list = await api<Summary[]>("/agent/sessions");
      setSessions(list);
      if (session?.id === target.id) {
        if (list[0]) {
          remember(await api<Session>(`/agent/sessions/${list[0].id}`));
        } else {
          remember(null);
          setActiveTab("chat");
        }
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function send() {
    if (
      busy ||
      isRunning ||
      ready !== true ||
      !loaded ||
      (!message.trim() && !image)
    )
      return;
    setBusy(true);
    setError("");
    try {
      const target = session || (await api<Session>("/agent/sessions", "POST"));
      const result = await api<Session>(
        `/agent/sessions/${target.id}/messages`,
        "POST",
        {
          message,
          image: image || null,
          allow_actions: allowActions,
          track_ids: contextTracks.map((track) => track.id),
        },
      );
      drafts.delete(draftKey.current);
      draftKey.current = result.id;
      draft.current = { message: "", image: "", imageName: "" };
      remember(result);
      setActiveTab("chat");
      setMessage("");
      setImage("");
      setImageName("");
      clearContext();
      setSessions(await api<Summary[]>("/agent/sessions"));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function attach(file: File) {
    if (
      !["image/png", "image/jpeg", "image/webp"].includes(file.type) ||
      file.size > 5 * 1024 * 1024
    ) {
      setError("请选择 5 MB 以内的 PNG、JPEG 或 WebP 图片");
      return;
    }
    try {
      const data = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () => reject(new Error("图片读取失败，请重新选择"));
        reader.readAsDataURL(file);
      });
      setImage(data);
      setImageName(file.name || "粘贴的图片");
      setError("");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function deleteEvents(payload: {
    ids?: string[];
    indices?: number[];
    statuses?: string[];
    clear_all?: boolean;
  }) {
    if (!session || isRunning || busy) return;
    setBusy(true);
    setError("");
    try {
      const updated = await api<Session>(
        `/agent/sessions/${session.id}/events/delete`,
        "POST",
        payload,
      );
      setSession(updated);
      setSelectedEvents({});
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const turns = useMemo(() => groupTurns(session), [session]);

  const allEvents = useMemo(
    () =>
      (session?.events || []).map((event, index) => ({
        event,
        index,
        key: eventKey(event, index),
      })),
    [session?.events],
  );

  const eventCounts = useMemo(() => {
    let complete = 0;
    let attention = 0;
    let running = 0;
    for (const { event } of allEvents) {
      if (event.status === "complete") complete++;
      else if (["failed", "stopped", "interrupted"].includes(event.status))
        attention++;
      else if (event.status === "running" || event.status === "stopping")
        running++;
    }
    return {
      all: allEvents.length,
      complete,
      attention,
      running,
    };
  }, [allEvents]);

  const filteredEvents = useMemo(() => {
    const q = eventQuery.trim().toLowerCase();
    return allEvents.filter(({ event }) => {
      if (turnFilter && event.turn_id !== turnFilter) return false;
      if (eventFilter === "complete" && event.status !== "complete")
        return false;
      if (
        eventFilter === "attention" &&
        !["failed", "stopped", "interrupted"].includes(event.status)
      )
        return false;
      if (
        eventFilter === "running" &&
        event.status !== "running" &&
        event.status !== "stopping"
      )
        return false;
      if (!q) return true;
      const toolName = (labels[event.tool] || event.tool).toLowerCase();
      const op = (event.operation || "").toLowerCase();
      const detail = (event.detail || "").toLowerCase();
      return (
        toolName.includes(q) ||
        event.tool.toLowerCase().includes(q) ||
        op.includes(q) ||
        detail.includes(q)
      );
    });
  }, [allEvents, turnFilter, eventFilter, eventQuery]);

  const selectedList = filteredEvents.filter(
    (item) => selectedEvents[item.key],
  );
  const allFilteredSelected =
    filteredEvents.length > 0 && selectedList.length === filteredEvents.length;

  const currentStatusText = session
    ? states[session.status] || session.status
    : "新建对话";

  const showPartial =
    !!session?.partial &&
    (turns.length === 0 ||
      turns[turns.length - 1].assistantMessages.length === 0 ||
      session.active_turn_id !== turns[turns.length - 1].turnId);

  const planComplete =
    session?.plan?.steps.filter((s) => s.status === "complete").length || 0;
  const planTotal = session?.plan?.steps.length || 0;

  return (
    <section className="view ai-workspace">
      <div className="ai-mobile-bar">
        <div className="ai-mobile-select">
          <select
            aria-label="选择 AI 对话"
            value={session?.id || ""}
            disabled={busy}
            onChange={(e) => void selectSession(e.target.value)}
          >
            <option value="" disabled>
              新对话
            </option>
            {sessions.map((s) => (
              <option key={s.id} value={s.id}>
                {s.title} ·{" "}
                {states[s.id === session?.id ? session.status : s.status] ||
                  s.status}
              </option>
            ))}
          </select>
        </div>
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={() => {
            remember(null);
            setActiveTab("chat");
            setError("");
            input.current?.focus();
          }}
        >
          <Plus size={15} />
          新对话
        </button>
      </div>

      <div className="ai-shell">
        <aside className="ai-rail" aria-label="对话列表与快捷入口">
          <div className="ai-rail-top">
            <div className="ai-rail-brand">
              <h1>AI 助手</h1>
            </div>
            <button
              type="button"
              className="primary ai-new-chat"
              disabled={busy}
              onClick={() => {
                remember(null);
                setActiveTab("chat");
                setError("");
                input.current?.focus();
              }}
            >
              <Plus size={16} />
              新对话
            </button>
          </div>

          <div className="ai-session-list" role="list" aria-label="历史对话">
            {sessions.length === 0 && (
              <p className="ai-session-empty">暂无历史对话</p>
            )}
            {sessions.map((s) => {
              const liveStatus =
                s.id === session?.id ? session.status : s.status;
              const isSelected = s.id === session?.id;
              return (
                <div
                  key={s.id}
                  role="listitem"
                  className={`ai-session-item ${isSelected ? "active" : ""}`}
                >
                  <button
                    type="button"
                    className="ai-session-pick"
                    disabled={busy}
                    aria-current={isSelected ? "true" : undefined}
                    onClick={() => void selectSession(s.id)}
                  >
                    <span
                      className={`ai-dot ai-dot-${statusTone(liveStatus)}`}
                      aria-hidden="true"
                    />
                    <span className="ai-session-copy">
                      <strong>{s.title || "新对话"}</strong>
                      <small>{states[liveStatus] || liveStatus}</small>
                    </span>
                  </button>
                  <button
                    type="button"
                    className="icon-button ai-session-remove"
                    aria-label={`删除对话 ${s.title || "新对话"}`}
                    disabled={busy || active(s) || (isSelected && isRunning)}
                    onClick={() => void removeSession(s)}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              );
            })}
          </div>

          <div className="ai-rail-footer">
            <div className="ai-rail-shortcuts">
              <button
                type="button"
                className="ai-shortcut-btn"
                onClick={() => navigate("playlists")}
              >
                <ListMusic size={14} />
                歌单
              </button>
              <button
                type="button"
                className="ai-shortcut-btn"
                onClick={() => navigate("queue")}
              >
                <Disc3 size={14} />
                下载队列
              </button>
              <button
                type="button"
                className="ai-shortcut-btn"
                onClick={() => navigate("organize")}
              >
                <FolderKanban size={14} />
                审核整理
              </button>
            </div>
            <div className="ai-rail-settings-row">
              <button
                type="button"
                className="text-button ai-settings-trigger"
                onClick={openSettings}
              >
                <Settings2 size={15} />
                模型设置
              </button>
              {ready !== null && (
                <span
                  className={`ai-ready-tag ${ready ? "ready" : "unready"}`}
                  title={ready ? "模型已配置" : "待配置模型"}
                >
                  {ready ? "已就绪" : "未配置"}
                </span>
              )}
            </div>
          </div>
        </aside>

        <div className="ai-main">
          <header className="ai-main-header">
            <div className="ai-main-title">
              <h2>{session?.title || "新对话"}</h2>
              <div className="ai-main-badges">
                <span
                  className={`ai-status-pill ai-pill-${statusTone(session?.status || "idle")}`}
                >
                  <span className="ai-dot" aria-hidden="true" />
                  {currentStatusText}
                </span>
                <span className="ai-mode-pill">
                  {allowActions ? "可执行项目操作" : "仅查询模式"}
                </span>
              </div>
            </div>
            <div className="ai-main-controls">
              <div
                className="filter-group ai-view-tabs"
                role="tablist"
                aria-label="工作区视图"
              >
                <button
                  type="button"
                  role="tab"
                  aria-selected={activeTab === "chat"}
                  className={activeTab === "chat" ? "active" : ""}
                  onClick={() => setActiveTab("chat")}
                >
                  <MessageSquare size={14} />
                  对话
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={activeTab === "events"}
                  className={activeTab === "events" ? "active" : ""}
                  onClick={() => setActiveTab("events")}
                >
                  <History size={14} />
                  执行记录 ({allEvents.length})
                </button>
              </div>
              <button
                type="button"
                className="icon-button"
                aria-label="删除当前 AI 对话"
                title="删除当前 AI 对话"
                disabled={!session || isRunning || busy}
                onClick={() => session && void removeSession(session)}
              >
                <Trash2 size={16} />
              </button>
              <button
                type="button"
                className="icon-button ai-mobile-settings"
                aria-label="模型设置"
                onClick={openSettings}
              >
                <Settings2 size={16} />
              </button>
            </div>
          </header>

          {ready === false && (
            <div className="ai-setup">
              <div className="ai-setup-copy">
                <strong>先连接你的 AI 模型</strong>
                <p>请先在设置中配置模型接口与密钥。</p>
              </div>
              <button type="button" className="primary" onClick={openSettings}>
                配置 AI 助手
              </button>
            </div>
          )}

          {error && (
            <div className="ai-error-banner" role="alert">
              <span>{error}</span>
              {!loaded && (
                <button
                  type="button"
                  className="secondary"
                  onClick={() => void load()}
                >
                  重新加载
                </button>
              )}
            </div>
          )}

          {activeTab === "chat" ? (
            <div className="ai-chat-pane">
              {(session?.decision ||
                session?.plan ||
                relatedJobs.length > 0) && (
                <div className="ai-context-dock">
                  {session?.decision && (
                    <div className="ai-decision-bar" role="status">
                      <strong>需要你的决定：</strong>
                      <span>{session.decision}</span>
                    </div>
                  )}
                  {session?.plan && (
                    <div className="ai-plan-bar">
                      <button
                        type="button"
                        className="ai-plan-toggle"
                        onClick={() => setShowPlan((prev) => !prev)}
                        aria-expanded={showPlan}
                      >
                        {showPlan ? (
                          <ChevronDown size={15} />
                        ) : (
                          <ChevronRight size={15} />
                        )}
                        <span className="ai-plan-goal">
                          {session.plan.goal}
                        </span>
                        <span className="ai-plan-progress">
                          {planComplete}/{planTotal}
                        </span>
                      </button>
                      {showPlan && (
                        <ol className="ai-plan-steps">
                          {session.plan.steps.map((step, index) => (
                            <li
                              key={index}
                              className={`ai-step-${statusTone(step.status)}`}
                            >
                              <span>{step.title}</span>
                              <small>
                                {states[step.status] || step.status}
                              </small>
                            </li>
                          ))}
                        </ol>
                      )}
                    </div>
                  )}
                  {relatedJobs.length > 0 && (
                    <div className="ai-jobs-bar">
                      <div className="ai-jobs-summary">
                        <strong>本对话的下载任务</strong>
                        <span>
                          {relatedJobs
                            .map(
                              (job) =>
                                `${job.track.title}：${states[job.status] || job.status}${job.processing ? ` · 整理：${states[job.processing.status] || job.processing.status}` : ""}`,
                            )
                            .join("；")}
                        </span>
                      </div>
                      <div className="ai-jobs-actions">
                        <button
                          type="button"
                          className="text-button"
                          onClick={() => navigate("queue")}
                        >
                          查看下载队列
                        </button>
                        {relatedJobs.some((job) =>
                          ["failed", "cancelled", "paused"].includes(
                            job.status,
                          ),
                        ) && (
                          <button
                            type="button"
                            className="secondary"
                            disabled={busy}
                            onClick={async () => {
                              setBusy(true);
                              setError("");
                              try {
                                const failed = relatedJobs.filter((job) =>
                                  ["failed", "cancelled", "paused"].includes(
                                    job.status,
                                  ),
                                );
                                const results = await Promise.allSettled(
                                  failed.map((job) =>
                                    api(
                                      `/downloads/${job.id}/${job.status === "paused" ? "resume" : "retry"}`,
                                      "POST",
                                    ),
                                  ),
                                );
                                const failures = results.filter(
                                  (result) => result.status === "rejected",
                                ).length;
                                if (failures)
                                  setError(
                                    `${failures} 个任务未能继续，请到下载队列查看原因。`,
                                  );
                                await changed();
                              } finally {
                                setBusy(false);
                              }
                            }}
                          >
                            继续失败或暂停的下载
                          </button>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )}

              <div
                className="ai-messages"
                aria-label="AI 对话记录"
                aria-busy={isRunning}
              >
                {!session?.messages.length && !session?.partial && (
                  <div className="ai-empty">
                    <div className="ai-empty-icon">
                      <Sparkles size={22} aria-hidden="true" />
                    </div>
                    <h3>从一句话或一张歌单截图开始</h3>
                    <p>导入歌单、查找版本、下载整理，也可以处理失败任务。</p>
                    <div className="ai-examples">
                      {examples.map((example) => (
                        <button
                          key={example}
                          type="button"
                          className="ai-example-chip"
                          onClick={() => {
                            setMessage(example);
                            input.current?.focus();
                          }}
                        >
                          {example}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {turns.map((turn) => {
                  const searchEntry = [...turn.events]
                    .reverse()
                    .find(
                      ({ event }) =>
                        event.tool === "search_music" &&
                        event.status === "complete" &&
                        event.result,
                    );
                  const candidateKey = searchEntry
                    ? `${session?.id || "s"}:${turn.key}:${searchEntry.event.id ?? searchEntry.index}`
                    : "";
                  const showTurnCandidates =
                    !!candidateKey && !!expandedCandidates[candidateKey];
                  const isCandidateHidden =
                    !!candidateKey && !!hiddenCandidates[candidateKey];
                  const importEntries = turn.events.filter(
                    ({ event }) =>
                      event.tool === "prepare_import" &&
                      event.status === "complete" &&
                      Array.isArray(
                        (event.result as { songs?: unknown[] } | undefined)
                          ?.songs,
                      ),
                  );
                  const uniqueTools = Array.from(
                    new Set(
                      turn.events.map(
                        ({ event }) => labels[event.tool] || event.tool,
                      ),
                    ),
                  );

                  return (
                    <div key={turn.key} className="ai-turn">
                      {turn.userMessage && (
                        <article className="ai-message ai-user">
                          <div className="ai-message-meta">
                            <strong>你</strong>
                            {turn.userMessage.has_image && (
                              <span className="ai-msg-tag">附有图片</span>
                            )}
                            {turn.userMessage.created_at && (
                              <time>
                                {formatTime(turn.userMessage.created_at)}
                              </time>
                            )}
                          </div>
                          <div className="ai-bubble ai-bubble-user">
                            <p>{turn.userMessage.content}</p>
                          </div>
                        </article>
                      )}

                      {turn.assistantMessages.map((item, idx) => (
                        <article
                          key={`${turn.key}-a-${idx}`}
                          className="ai-message ai-assistant"
                        >
                          <div className="ai-message-meta">
                            <strong>MeloKeep 助手</strong>
                            {item.created_at && (
                              <time>{formatTime(item.created_at)}</time>
                            )}
                          </div>
                          <div className="ai-bubble ai-bubble-assistant">
                            <AIMarkdown content={item.content} />
                          </div>
                        </article>
                      ))}

                      {turn.isLatest && session?.partial && !showPartial && (
                        <article
                          className="ai-message ai-assistant ai-streaming"
                          aria-live="polite"
                        >
                          <div className="ai-message-meta">
                            <strong>MeloKeep 助手</strong>
                            <span className="ai-msg-tag">生成中</span>
                          </div>
                          <div className="ai-bubble ai-bubble-assistant">
                            <AIMarkdown content={session.partial} />
                          </div>
                        </article>
                      )}

                      {turn.events.length > 0 && (
                        <div className="ai-turn-summary">
                          <button
                            type="button"
                            className="ai-turn-events-link"
                            onClick={() => {
                              setTurnFilter(turn.turnId || null);
                              setEventFilter("all");
                              setActiveTab("events");
                            }}
                          >
                            <History size={13} />
                            <span>
                              本轮已调用 {turn.events.length} 项操作 ·{" "}
                              {uniqueTools.slice(0, 3).join("、")}
                              {uniqueTools.length > 3
                                ? ` 等 ${uniqueTools.length} 项`
                                : ""}
                            </span>
                          </button>
                        </div>
                      )}

                      {searchEntry && !isCandidateHidden && (
                        <div className="ai-candidates">
                          <div className="ai-candidates-bar">
                            <button
                              type="button"
                              className="secondary"
                              onClick={() =>
                                setExpandedCandidates((prev) => ({
                                  ...prev,
                                  [candidateKey]: !prev[candidateKey],
                                }))
                              }
                            >
                              {showTurnCandidates
                                ? "收起候选版本"
                                : "查看候选版本"}
                            </button>
                            <button
                              type="button"
                              className="text-button ai-candidate-hide"
                              aria-label="隐藏本轮候选版本"
                              onClick={() =>
                                setHiddenCandidates((prev) => ({
                                  ...prev,
                                  [candidateKey]: true,
                                }))
                              }
                            >
                              隐藏
                            </button>
                          </div>
                          {showTurnCandidates && (
                            <ExecutionResult
                              value={searchEntry.event.result}
                              play={play}
                              download={download}
                            />
                          )}
                        </div>
                      )}

                      {session &&
                        !isRunning &&
                        importEntries.map(({ event, index }) => {
                          const importKey = `${session.id}-${turn.key}-${index}`;
                          const isOpen =
                            expandedImports[importKey] ?? turn.isLatest;
                          const songs = (
                            event.result as {
                              songs: { title: string; artist: string }[];
                            }
                          ).songs;
                          return (
                            <div key={importKey} className="ai-turn-import">
                              {!isOpen ? (
                                <button
                                  type="button"
                                  className="secondary"
                                  onClick={() =>
                                    setExpandedImports((prev) => ({
                                      ...prev,
                                      [importKey]: true,
                                    }))
                                  }
                                >
                                  展开本轮导入清单（{songs.length} 首）
                                </button>
                              ) : (
                                <>
                                  {!turn.isLatest && (
                                    <div className="ai-import-collapse">
                                      <button
                                        type="button"
                                        className="text-button"
                                        onClick={() =>
                                          setExpandedImports((prev) => ({
                                            ...prev,
                                            [importKey]: false,
                                          }))
                                        }
                                      >
                                        收起本轮导入清单
                                      </button>
                                    </div>
                                  )}
                                  <AIImport
                                    sessionId={session.id}
                                    initial={songs}
                                    playlists={playlists}
                                    play={play}
                                    changed={async () => {
                                      const value = await api<Session>(
                                        `/agent/sessions/${session.id}`,
                                      );
                                      setSession(value);
                                      await changed();
                                    }}
                                  />
                                </>
                              )}
                            </div>
                          );
                        })}
                    </div>
                  );
                })}

                {showPartial && session?.partial && (
                  <article
                    className="ai-message ai-assistant ai-streaming"
                    aria-live="polite"
                  >
                    <div className="ai-message-meta">
                      <strong>MeloKeep 助手</strong>
                      <span className="ai-msg-tag">生成中</span>
                    </div>
                    <div className="ai-bubble ai-bubble-assistant">
                      <AIMarkdown content={session.partial} />
                    </div>
                  </article>
                )}

                {session && session.detail && (
                  <p
                    className={`ai-turn-status ${session.status === "failed" ? "ai-error" : ""}`}
                    role="status"
                  >
                    {session.detail}
                  </p>
                )}

                {session &&
                  [
                    "failed",
                    "stopped",
                    "interrupted",
                    "needs_decision",
                  ].includes(session.status) && (
                    <div className="ai-recovery">
                      <p>
                        {session.status === "needs_decision" && session.decision
                          ? session.decision
                          : "请先核对执行记录和下载队列，已创建的后台任务可能仍在继续。"}
                      </p>
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={async () => {
                          setBusy(true);
                          setError("");
                          try {
                            remember(
                              await api<Session>(
                                `/agent/sessions/${session.id}/resume`,
                                "POST",
                              ),
                            );
                          } catch (e) {
                            setError((e as Error).message);
                          } finally {
                            setBusy(false);
                          }
                        }}
                      >
                        <RotateCcw size={14} />
                        核对状态并继续任务
                      </button>
                    </div>
                  )}
                <div ref={end} />
              </div>

              <form
                className="ai-composer"
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => {
                  e.preventDefault();
                  const file = e.dataTransfer.files[0];
                  if (file) void attach(file);
                }}
                onSubmit={(e) => {
                  e.preventDefault();
                  void send();
                }}
              >
                {image && (
                  <div className="ai-attachment">
                    <img src={image} alt="待发送的歌单截图" />
                    <span>{imageName}</span>
                    <button
                      type="button"
                      className="icon-button"
                      aria-label="移除图片"
                      onClick={() => {
                        setImage("");
                        setImageName("");
                      }}
                    >
                      <X size={15} />
                    </button>
                  </div>
                )}
                {contextTracks.length > 0 && (
                  <div className="ai-context">
                    <span>
                      已选择 {contextTracks.length} 首：
                      {contextTracks
                        .map((track) => `${track.title} · ${track.artist}`)
                        .join("、")}
                    </span>
                    <button
                      type="button"
                      className="text-button"
                      onClick={clearContext}
                    >
                      移除
                    </button>
                  </div>
                )}
                <label className="sr-only" htmlFor="ai-message">
                  告诉助手你想做什么
                </label>
                <textarea
                  id="ai-message"
                  ref={input}
                  value={message}
                  maxLength={8000}
                  disabled={busy}
                  placeholder="告诉助手你想做什么，例如：把图片里的歌加入「通勤」，并下载整理入库"
                  onChange={(e) => setMessage(e.target.value)}
                  onPaste={(e) => {
                    const file = Array.from(e.clipboardData.files).find(
                      (item) => item.type.startsWith("image/"),
                    );
                    if (file) {
                      e.preventDefault();
                      void attach(file);
                    }
                  }}
                  onKeyDown={(e) => {
                    if (
                      e.key === "Enter" &&
                      !e.shiftKey &&
                      !e.altKey &&
                      !e.nativeEvent.isComposing &&
                      e.keyCode !== 229
                    ) {
                      e.preventDefault();
                      void send();
                    }
                  }}
                />
                <div className="ai-composer-foot">
                  <div className="ai-composer-left">
                    <input
                      type="file"
                      accept="image/png,image/jpeg,image/webp"
                      ref={upload}
                      hidden
                      onChange={async (e) => {
                        const file = e.target.files?.[0];
                        e.target.value = "";
                        if (!file) return;
                        await attach(file);
                      }}
                    />
                    <button
                      className="secondary ai-attach-btn"
                      type="button"
                      disabled={busy || isRunning}
                      onClick={() => upload.current?.click()}
                    >
                      <ImagePlus size={15} />
                      添加图片
                    </button>
                    <label
                      className="ready-filter"
                      title="取消勾选后仅查询，不执行修改或下载操作"
                    >
                      <input
                        type="checkbox"
                        checked={allowActions}
                        disabled={busy || isRunning}
                        onChange={(e) => setAllowActions(e.target.checked)}
                      />
                      允许执行项目操作
                    </label>
                  </div>
                  <div className="ai-composer-right">
                    <small>
                      支持粘贴或拖图 · Enter 发送，Shift + Enter 换行
                    </small>
                    {isRunning ? (
                      <button
                        type="button"
                        className="secondary ai-stop-btn"
                        disabled={session?.status === "stopping"}
                        onClick={async () => {
                          try {
                            remember(
                              await api<Session>(
                                `/agent/sessions/${session!.id}/stop`,
                                "POST",
                              ),
                            );
                          } catch (e) {
                            setError((e as Error).message);
                          }
                        }}
                      >
                        <Square size={14} />
                        停止本轮
                      </button>
                    ) : (
                      <button
                        type="submit"
                        className="primary"
                        disabled={
                          busy ||
                          ready !== true ||
                          !loaded ||
                          (!message.trim() && !image)
                        }
                      >
                        <Send size={14} />
                        {busy ? "正在发送…" : "发送"}
                      </button>
                    )}
                  </div>
                </div>
              </form>
            </div>
          ) : (
            <section className="ai-execution" aria-label="AI 执行记录">
              <div className="ai-events-toolbar">
                <div className="ai-events-filters">
                  <div
                    className="filter-group"
                    role="group"
                    aria-label="状态筛选"
                  >
                    <button
                      type="button"
                      className={eventFilter === "all" ? "active" : ""}
                      onClick={() => setEventFilter("all")}
                    >
                      全部 ({eventCounts.all})
                    </button>
                    <button
                      type="button"
                      className={eventFilter === "complete" ? "active" : ""}
                      onClick={() => setEventFilter("complete")}
                    >
                      已完成 ({eventCounts.complete})
                    </button>
                    <button
                      type="button"
                      className={eventFilter === "attention" ? "active" : ""}
                      onClick={() => setEventFilter("attention")}
                    >
                      异常/中断 ({eventCounts.attention})
                    </button>
                    <button
                      type="button"
                      className={eventFilter === "running" ? "active" : ""}
                      onClick={() => setEventFilter("running")}
                    >
                      进行中 ({eventCounts.running})
                    </button>
                  </div>
                  <label className="ai-events-search">
                    <Search size={14} aria-hidden="true" />
                    <input
                      type="search"
                      aria-label="筛选执行记录"
                      value={eventQuery}
                      onChange={(e) => setEventQuery(e.target.value)}
                      placeholder="按操作名称、接口或详情筛选"
                    />
                  </label>
                </div>

                <div className="ai-events-batch">
                  <label className="ai-select-all">
                    <input
                      type="checkbox"
                      checked={allFilteredSelected}
                      disabled={
                        filteredEvents.length === 0 || isRunning || busy
                      }
                      onChange={(e) => {
                        const checked = e.target.checked;
                        setSelectedEvents((prev) => {
                          const next = { ...prev };
                          for (const item of filteredEvents) {
                            if (checked) next[item.key] = true;
                            else delete next[item.key];
                          }
                          return next;
                        });
                      }}
                    />
                    <CheckSquare size={14} aria-hidden="true" />
                    全选 ({selectedList.length}/{filteredEvents.length})
                  </label>

                  <div className="ai-batch-actions">
                    <button
                      type="button"
                      className="secondary"
                      disabled={selectedList.length === 0 || isRunning || busy}
                      onClick={() => {
                        const ids = selectedList
                          .map((item) => item.event.id)
                          .filter((id): id is string => !!id);
                        const indices = selectedList
                          .filter((item) => !item.event.id)
                          .map((item) => item.index);
                        void deleteEvents({ ids, indices });
                      }}
                    >
                      删除所选 ({selectedList.length})
                    </button>
                    <button
                      type="button"
                      className="secondary"
                      disabled={eventCounts.complete === 0 || isRunning || busy}
                      onClick={() =>
                        void deleteEvents({ statuses: ["complete"] })
                      }
                    >
                      清理已完成
                    </button>
                    <button
                      type="button"
                      className="secondary"
                      disabled={
                        eventCounts.attention === 0 || isRunning || busy
                      }
                      onClick={() =>
                        void deleteEvents({
                          statuses: ["failed", "stopped", "interrupted"],
                        })
                      }
                    >
                      清理异常
                    </button>
                    <button
                      type="button"
                      className="secondary ai-danger-btn"
                      disabled={allEvents.length === 0 || isRunning || busy}
                      onClick={() => {
                        if (
                          window.confirm(
                            "清空当前对话的全部执行记录？已产生的歌单和下载任务不受影响。",
                          )
                        ) {
                          void deleteEvents({ clear_all: true });
                        }
                      }}
                    >
                      清空全部
                    </button>
                  </div>
                </div>

                {turnFilter && (
                  <div className="ai-turn-filter-banner">
                    <span>当前仅显示选定轮次的执行记录</span>
                    <button
                      type="button"
                      className="text-button"
                      onClick={() => setTurnFilter(null)}
                    >
                      查看全部轮次
                    </button>
                  </div>
                )}

                {isRunning && (
                  <p className="ai-events-hint">
                    任务正在执行，暂不可清理记录；停止本轮不会取消已经加入队列的下载。
                  </p>
                )}
              </div>

              <div className="ai-events-scroll">
                {!allEvents.length ? (
                  <div className="ai-events-empty">
                    <History size={24} aria-hidden="true" />
                    <p>当前对话尚未调用项目工具</p>
                  </div>
                ) : filteredEvents.length === 0 ? (
                  <div className="ai-events-empty">
                    <p>没有符合筛选条件的执行记录</p>
                  </div>
                ) : (
                  filteredEvents.map(({ event, index, key }) => (
                    <div key={key} className="ai-event-row">
                      <input
                        type="checkbox"
                        className="ai-event-check"
                        aria-label={`选择记录 ${labels[event.tool] || event.tool}`}
                        checked={!!selectedEvents[key]}
                        disabled={isRunning || busy}
                        onChange={(e) => {
                          const checked = e.target.checked;
                          setSelectedEvents((prev) => {
                            const next = { ...prev };
                            if (checked) next[key] = true;
                            else delete next[key];
                            return next;
                          });
                        }}
                      />
                      <details className="ai-event">
                        <summary>
                          <span className="ai-event-title">
                            {labels[event.tool] || event.tool}
                            {event.operation && (
                              <code className="ai-event-op">
                                {event.operation}
                              </code>
                            )}
                          </span>
                          <span className="ai-event-meta">
                            {event.created_at && (
                              <time>{formatTime(event.created_at)}</time>
                            )}
                            <small
                              className={`ai-event-badge ai-badge-${statusTone(event.status)}`}
                            >
                              {states[event.status] || event.status}
                            </small>
                          </span>
                        </summary>
                        <div className="ai-event-body">
                          <p>{event.detail}</p>
                          {event.result !== undefined && (
                            <ExecutionResult
                              value={event.result}
                              play={play}
                              download={download}
                            />
                          )}
                        </div>
                      </details>
                      <button
                        type="button"
                        className="icon-button ai-event-delete"
                        aria-label={`删除记录 ${labels[event.tool] || event.tool}`}
                        disabled={isRunning || busy}
                        onClick={() =>
                          void deleteEvents(
                            event.id
                              ? { ids: [event.id] }
                              : { indices: [index] },
                          )
                        }
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  ))
                )}
              </div>
            </section>
          )}
        </div>
      </div>
    </section>
  );
}
