import { Cover, Button, Modal, SongTitle } from "./components";
import { SettingsWorkspace, type SettingsTab } from "./SettingsWorkspace";
import { Navigation } from "./Navigation";
import { AIAssistant } from "./AIAssistant";
import { ThemePicker } from "./ThemePicker";
import { RuntimeLogs } from "./RuntimeLogs";
import { LibraryWorkspace } from "./LibraryWorkspace";
import { processingLabels } from "./LibrarySettings";
import { PlaylistLibrary } from "./PlaylistLibrary";
import { PlayerDock } from "./PlayerDock";
import { NowPlayingStage, type SleepTimerState } from "./NowPlayingStage";
import { playbackClock } from "./playback-clock";
import { useAppearance } from "./theme";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowUpCircle,
  AudioLines,
  Check,
  Search,
  ListChecks,
  ListMusic,
  Download,
  GripVertical,
  Heart,
  Play,
  Pause,
  Volume2,
  VolumeX,
  Plus,
  X,
  ArrowRight,
  LogOut,
  History,
  Trash2,
  Github,
  SlidersHorizontal,
  Eye,
  EyeOff,
  CircleAlert,
} from "lucide-react";
import { ApiError, api } from "./api";
import { formatLibraryRefreshNotice } from "./library-utils";
import { playbackErrorMessage } from "./playback-errors";
import {
  formatFileSizeBytes,
  formatFriendlyQuality,
  resolveTrackDisplaySize,
  selectResults,
} from "./results";
import { parseLrc, clock, type LyricLine } from "./lyrics";
import {
  DEFAULT_PLAYER_PREFS,
  fetchServerPreferences,
  getDirtyPreferenceKeys,
  getPreferencesMutationSeq,
  loadInitialPlayerPrefs,
  normalizePlayerPrefs,
  parsePlayerPreferences,
  sanitizeLyricsFontSize,
  saveLocalPlayerPrefs,
  scheduleSavePreferences,
  subscribePreferencesPatch,
  toAppearancePreferences,
  toPlayerPreferences,
  type LocalPlayerState,
  type NavidromeViewPreferences,
  type UIPreferences,
} from "./preferences";
import { updateTargetKey, updateTargetLabel } from "./UpdateSettings";
import { isPluginSource, pickPlaylistImportSource } from "./plugin-utils";
import type {
  Track,
  Config,
  DownloadJob,
  LibraryFile,
  LibraryFilesPage,
  LibraryRefreshResult,
  SourceStatus,
  SavedPlaylist,
  PlaylistSummary,
  SystemUpdateInfo,
} from "./types";

const views = {
  search: "搜索音乐",
  assistant: "AI 助手",
  player: "正在播放",
  import: "歌单导入",
  playlists: "歌单",
  queue: "下载队列",
  favorites: "我的收藏",
  files: "音乐库",
  organize: "刮削整理",
  logs: "运行日志",
  settings: "设置",
} as const;
type View = keyof typeof views;
const LYRICS_OFFSET_STORAGE_KEY = "melokeep-lyrics-offset";
function loadInitialLyricsOffsetMs(): number {
  try {
    const raw = Number(localStorage.getItem(LYRICS_OFFSET_STORAGE_KEY) || "0");
    if (!Number.isFinite(raw)) return 0;
    return Math.max(-2000, Math.min(2000, Math.round(raw / 100) * 100));
  } catch {
    return 0;
  }
}
const playbackSignature = (list: Track[], fromQueue: boolean) =>
  `${fromQueue ? "q" : "l"}:${list.map((t) => t.id).join("\0")}`;
function buildShuffledTracks(
  list: Track[],
  anchorId?: string,
  avoidFirstId?: string,
): Track[] {
  const pool = [...list];
  let anchor: Track | undefined;
  if (anchorId) {
    const idx = pool.findIndex((t) => t.id === anchorId);
    if (idx >= 0) {
      [anchor] = pool.splice(idx, 1);
    }
  }
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  if (anchor) {
    return [anchor, ...pool];
  }
  if (avoidFirstId && pool.length > 1 && pool[0].id === avoidFirstId) {
    const swapIdx = 1 + Math.floor(Math.random() * (pool.length - 1));
    [pool[0], pool[swapIdx]] = [pool[swapIdx], pool[0]];
  }
  return pool;
}
export function App() {
  const appearanceCtrl = useAppearance();
  const [sleepTimer, setSleepTimer] = useState<SleepTimerState | null>(null);
  const sleepTimerHandleRef = useRef<ReturnType<typeof setTimeout> | null>(
    null,
  );
  const sleepStopAfterCurrentRef = useRef(false);
  const handleSetSleepTimer = useCallback(
    (minutes: number, stopAfterCurrent = false) => {
      if (sleepTimerHandleRef.current !== null) {
        clearTimeout(sleepTimerHandleRef.current);
        sleepTimerHandleRef.current = null;
      }
      sleepStopAfterCurrentRef.current = stopAfterCurrent;
      if (!stopAfterCurrent && minutes <= 0) {
        setSleepTimer(null);
        setNotice("已关闭睡眠定时");
        return;
      }
      if (stopAfterCurrent) {
        setSleepTimer({ endsAtMs: 0, stopAfterCurrent: true });
        setNotice("已设置：播完本首后停止播放");
        return;
      }
      const cleanMinutes = Math.max(1, Math.round(minutes));
      const endsAtMs = Date.now() + cleanMinutes * 60_000;
      setSleepTimer({ endsAtMs, stopAfterCurrent: false });
      sleepTimerHandleRef.current = setTimeout(() => {
        sleepTimerHandleRef.current = null;
        sleepStopAfterCurrentRef.current = false;
        audio.current?.pause();
        setSleepTimer(null);
        setNotice("睡眠定时已结束，已停止播放");
      }, cleanMinutes * 60_000);
      setNotice(`已开启睡眠定时：${cleanMinutes} 分钟后暂停播放`);
    },
    [],
  );
  useEffect(() => {
    return () => {
      if (sleepTimerHandleRef.current !== null) {
        clearTimeout(sleepTimerHandleRef.current);
        sleepTimerHandleRef.current = null;
      }
    };
  }, []);
  const [lyricsOffsetMs, setLyricsOffsetMs] = useState<number>(
    loadInitialLyricsOffsetMs,
  );
  const handleChangeLyricsOffsetMs = useCallback((nextOffset: number) => {
    const clamped = Math.max(
      -2000,
      Math.min(2000, Math.round(nextOffset / 100) * 100),
    );
    setLyricsOffsetMs(clamped);
    try {
      localStorage.setItem(LYRICS_OFFSET_STORAGE_KEY, String(clamped));
    } catch {
      /* Storage is optional. */
    }
  }, []);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerTab, setDrawerTab] = useState<"lyrics" | "queue">("lyrics");
  const [recentSearches, setRecentSearches] = useState<string[]>(() => {
    try {
      const saved: unknown = JSON.parse(
        localStorage.getItem("melokeep-searches") || "[]",
      );
      return Array.isArray(saved)
        ? saved.filter((v): v is string => typeof v === "string").slice(0, 8)
        : [];
    } catch {
      return [];
    }
  });
  const searchInput = useRef<HTMLInputElement>(null);
  const [view, setView] = useState<View>("search"),
    [returnView, setReturnView] = useState<View>("search"),
    [query, setQuery] = useState(""),
    [tracks, setTracks] = useState<Track[]>([]),
    [searching, setSearching] = useState(false),
    [statuses, setStatuses] = useState<Record<string, SourceStatus>>({}),
    [firstMs, setFirstMs] = useState<number | null>(null);
  const [config, setConfig] = useState<Config | null>(null),
    [sourceFilter, setSourceFilter] = useState("all"),
    [filter, setFilter] = useState("all"),
    [sort, setSort] = useState("relevance"),
    [selected, setSelected] = useState<Set<string>>(new Set());
  const sources = config?.available_sources ?? [];
  const playlistSources = useMemo(
    () => sources.filter((s) => Boolean(s.capability?.playlist)),
    [sources],
  );
  const hasPluginSource = useMemo(
    () => sources.some((s) => isPluginSource(s)),
    [sources],
  );
  const sourceLabel = (id: string, playlistContext = false) =>
    id === "navidrome"
      ? "Navidrome"
      : id === "custom"
        ? "自建歌单"
        : id === "local"
          ? playlistContext
            ? "自建歌单"
            : "本地音乐"
          : sources.find((s) => s.id === id)?.label || id;
  useEffect(() => {
    if (view !== "player") setReturnView(view);
  }, [view]);
  const [favorites, setFavorites] = useState<Track[]>([]),
    [playlist, setPlaylist] = useState<Track[]>([]),
    [jobs, setJobs] = useState<DownloadJob[]>([]),
    [files, setFiles] = useState<LibraryFile[]>([]);
  const [filesCursor, setFilesCursor] = useState<string | null>(null);
  const [filesIndexing, setFilesIndexing] = useState(false);
  const [filesLoadingMore, setFilesLoadingMore] = useState(false);
  const [filesRefreshing, setFilesRefreshing] = useState(false);
  const filesRefreshingRef = useRef(false);
  const [libraryFolders, setLibraryFolders] = useState<
    { root: string; folder: string }[] | undefined
  >(undefined);
  const libraryFiltersRef = useRef({
    query: "",
    root: "",
    folder: "",
    sort: "title",
  });
  const libraryGenRef = useRef(0);
  const libraryFilterTimerRef = useRef<ReturnType<typeof setTimeout> | null>(
    null,
  );
  async function loadLibraryFiles(
    options: {
      cursor?: string | null;
      filters?: { query: string; root: string; folder: string; sort: string };
      rescan?: boolean;
    } = {},
  ) {
    const filters = options.filters ?? libraryFiltersRef.current;
    libraryFiltersRef.current = filters;
    const cursor = options.cursor ?? null;
    const generation = ++libraryGenRef.current;
    if (cursor) setFilesLoadingMore(true);
    const params = new URLSearchParams({
      limit: "100",
      sort: filters.sort || "title",
    });
    if (filters.query) params.set("query", filters.query);
    if (filters.root) params.set("root", filters.root);
    if (filters.folder) params.set("folder", filters.folder);
    if (cursor) params.set("cursor", cursor);
    if (options.rescan) params.set("rescan", "1");
    try {
      const page = await api<LibraryFilesPage>(
        `/library/files?${params.toString()}`,
      );
      if (generation !== libraryGenRef.current) return false;
      setFiles((previous) => {
        if (!cursor) return page.items;
        const seen = new Set(previous.map((item) => item.path));
        return [
          ...previous,
          ...page.items.filter((item) => !seen.has(item.path)),
        ];
      });
      setFilesCursor(page.next_cursor);
      setFilesIndexing(page.indexing);
      if (Array.isArray(page.folders)) {
        setLibraryFolders(page.folders);
      }
      return true;
    } catch (error) {
      if (generation !== libraryGenRef.current) return false;
      if (error instanceof ApiError && error.status === 409 && cursor) {
        setFilesCursor(null);
        return await loadLibraryFiles({ cursor: null, filters });
      }
      fail(error);
      return false;
    } finally {
      if (cursor && generation === libraryGenRef.current) {
        setFilesLoadingMore(false);
      }
    }
  }
  async function refreshLibraryFiles() {
    if (filesRefreshingRef.current) return;
    filesRefreshingRef.current = true;
    setFilesRefreshing(true);
    try {
      let summaryNotice = "";
      let useRescanFallback = false;
      try {
        const res = await api<LibraryRefreshResult>("/library/refresh", "POST");
        summaryNotice = formatLibraryRefreshNotice(res);
      } catch (err) {
        if (
          err instanceof ApiError &&
          (err.status === 404 || err.status === 405)
        ) {
          useRescanFallback = true;
        } else {
          throw err;
        }
      }
      const loaded = await loadLibraryFiles({
        cursor: null,
        rescan: useRescanFallback,
      });
      if (loaded) {
        setNotice(summaryNotice || "已重新扫描并刷新音乐库");
      }
    } catch (err) {
      fail(err);
    } finally {
      filesRefreshingRef.current = false;
      setFilesRefreshing(false);
    }
  }
  const [savedPlaylists, setSavedPlaylists] = useState<PlaylistSummary[]>([]),
    [activePlaylist, setActivePlaylist] = useState<SavedPlaylist | null>(null),
    [importName, setImportName] = useState("");
  const [remotePlaylists, setRemotePlaylists] = useState<PlaylistSummary[]>([]);
  const [remotePlaylistError, setRemotePlaylistError] = useState("");
  const [playlistRefresh, setPlaylistRefresh] = useState(0);
  const allPlaylists = [...savedPlaylists, ...remotePlaylists];
  const [selectedJobs, setSelectedJobs] = useState<string[]>([]);
  const [queueStatus, setQueueStatus] = useState("all");
  const [selectedFiles, setSelectedFiles] = useState<string[]>([]);
  const [workbenchTarget, setWorkbenchTarget] = useState<{
    directoryMode: boolean;
    directory: string;
    nonce: number;
  } | null>(null);
  const [assistantTracks, setAssistantTracks] = useState<Track[]>([]);
  const [libraryTab, setLibraryTab] = useState("local");
  const [processingTab, setProcessingTab] = useState("workbench");
  const [settingsTab, setSettingsTab] = useState<SettingsTab>("engine");
  const [updateInfo, setUpdateInfo] = useState<SystemUpdateInfo | null>(null);
  const [checkingUpdate, setCheckingUpdate] = useState(false);
  const [updateError, setUpdateError] = useState("");
  const [dismissedUpdate, setDismissedUpdate] = useState(() => {
    try {
      return localStorage.getItem("melokeep-dismissed-update") || "";
    } catch {
      return "";
    }
  });
  const [dismissedPluginHint, setDismissedPluginHint] = useState(() => {
    try {
      return localStorage.getItem("melokeep-dismissed-plugin-hint") === "1";
    } catch {
      return false;
    }
  });
  const updateAutoChecked = useRef(false);
  const collectionView = ["playlists", "favorites", "import"].includes(view);
  function openSettings(tab: SettingsTab = "engine") {
    setSettingsTab(tab);
    setView("settings");
  }
  const [managing, setManaging] = useState(false);
  const [managementError, setManagementError] = useState("");
  const [queueManaging, setQueueManaging] = useState(false);
  const [queueSelected, setQueueSelected] = useState<Set<string>>(new Set());
  const [queueSaving, setQueueSaving] = useState(false);
  const queueWritePending = useRef(false);
  const [confirmation, setConfirmation] = useState<{
    title: string;
    description: string;
    run: () => Promise<void>;
  } | null>(null);
  const [initialPlayer] = useState<LocalPlayerState>(() =>
    loadInitialPlayerPrefs(),
  );
  const [serverNavOrder, setServerNavOrder] = useState<string[] | null>(null);
  const [serverTreeWidth, setServerTreeWidth] = useState<number | null>(null);
  const [serverNavidromeView, setServerNavidromeView] =
    useState<NavidromeViewPreferences | null>(null);
  const [current, setCurrent] = useState<Track | null>(null),
    [playing, setPlaying] = useState(false),
    [loadingPlay, setLoadingPlay] = useState(false),
    [duration, setDuration] = useState(0),
    [volume, setVolume] = useState(initialPlayer.volume),
    [shuffle, setShuffle] = useState(initialPlayer.shuffle),
    [repeat, setRepeat] = useState(initialPlayer.repeat);
  const [lyricText, setLyricText] = useState(""),
    [lines, setLines] = useState<LyricLine[]>([]),
    [follow, setFollow] = useState(initialPlayer.follow),
    [largeLyrics, setLargeLyrics] = useState(initialPlayer.largeLyrics),
    [lyricsFontSize, setLyricsFontSize] = useState(
      initialPlayer.lyricsFontSize,
    ),
    [lyricsActiveColor, setLyricsActiveColor] = useState(
      initialPlayer.lyricsActiveColor,
    ),
    [lyricsInactiveOpacity, setLyricsInactiveOpacity] = useState(
      initialPlayer.lyricsInactiveOpacity,
    ),
    [lyricsAlign, setLyricsAlign] = useState(initialPlayer.lyricsAlign);
  const playerPrefsRef = useRef<LocalPlayerState>(initialPlayer);
  const lastLocalPrefAtRef = useRef(0);
  playerPrefsRef.current = {
    volume,
    shuffle,
    repeat,
    follow,
    largeLyrics,
    lyricsFontSize,
    lyricsActiveColor,
    lyricsInactiveOpacity,
    lyricsAlign,
  };
  useEffect(() => {
    return subscribePreferencesPatch((patch) => {
      lastLocalPrefAtRef.current = Date.now();
      if (patch.navigation_order?.length) {
        setServerNavOrder(patch.navigation_order);
      }
      if (patch.library_tree_width !== undefined) {
        setServerTreeWidth(patch.library_tree_width);
      }
      if (patch.navidrome_view) {
        setServerNavidromeView((prev) => ({
          ...prev,
          ...patch.navidrome_view,
        }));
      }
    });
  }, []);
  const updatePlayerPrefs = useCallback(
    (
      patch: Partial<LocalPlayerState>,
      options: { debounceMs?: number } = {},
    ) => {
      const merged: Partial<LocalPlayerState> = {
        ...playerPrefsRef.current,
        ...patch,
      };
      if (
        patch.lyricsFontSize !== undefined &&
        patch.largeLyrics === undefined
      ) {
        merged.largeLyrics = sanitizeLyricsFontSize(patch.lyricsFontSize) >= 28;
      } else if (
        patch.largeLyrics !== undefined &&
        patch.lyricsFontSize === undefined
      ) {
        merged.lyricsFontSize = patch.largeLyrics ? 28 : 24;
      }
      const next = normalizePlayerPrefs(merged, playerPrefsRef.current);
      playerPrefsRef.current = next;
      if (patch.volume !== undefined) setVolume(next.volume);
      if (patch.shuffle !== undefined) setShuffle(next.shuffle);
      if (patch.repeat !== undefined) setRepeat(next.repeat);
      if (patch.follow !== undefined) setFollow(next.follow);
      if (
        patch.largeLyrics !== undefined ||
        patch.lyricsFontSize !== undefined
      ) {
        setLargeLyrics(next.largeLyrics);
        setLyricsFontSize(next.lyricsFontSize);
      }
      if (patch.lyricsActiveColor !== undefined) {
        setLyricsActiveColor(next.lyricsActiveColor);
      }
      if (patch.lyricsInactiveOpacity !== undefined) {
        setLyricsInactiveOpacity(next.lyricsInactiveOpacity);
      }
      if (patch.lyricsAlign !== undefined) {
        setLyricsAlign(next.lyricsAlign);
      }
      saveLocalPlayerPrefs(next);
      lastLocalPrefAtRef.current = Date.now();
      const defaultDebounce =
        patch.volume !== undefined ||
        patch.lyricsFontSize !== undefined ||
        patch.lyricsInactiveOpacity !== undefined ||
        patch.lyricsActiveColor !== undefined
          ? 180
          : 0;
      scheduleSavePreferences(
        {
          player: toPlayerPreferences(next),
        },
        { debounceMs: options.debounceMs ?? defaultDebounce },
      );
    },
    [],
  );
  const [notice, setNotice] = useState(""),
    [authEnabled, setAuthEnabled] = useState(false),
    [loginRequired, setLoginRequired] = useState(false),
    [loginUser, setLoginUser] = useState(""),
    [loginPass, setLoginPass] = useState(""),
    [loginError, setLoginError] = useState(""),
    [loginBusy, setLoginBusy] = useState(false),
    [showLoginPassword, setShowLoginPassword] = useState(false),
    [busy, setBusy] = useState(false);
  const [qualityTracks, setQualityTracks] = useState<Track[] | null>(null),
    [qualities, setQualities] = useState<Record<string, string>>({}),
    [showPlaylist, setShowPlaylist] = useState(false),
    [playlistModalTracks, setPlaylistModalTracks] = useState<Track[] | null>(
      null,
    ),
    [targetPlaylistId, setTargetPlaylistId] = useState(""),
    [newPlaylistName, setNewPlaylistName] = useState(""),
    [importSource, setImportSource] = useState(""),
    [importId, setImportId] = useState("");
  useEffect(() => {
    setImportSource((current) => pickPlaylistImportSource(sources, current));
  }, [sources]);
  const audio = useRef<HTMLAudioElement>(null),
    playGeneration = useRef(0),
    searchGeneration = useRef(0),
    searchJob = useRef<string | null>(null),
    eventSource = useRef<EventSource | null>(null),
    startTime = useRef(0),
    currentRef = useRef<Track | null>(null),
    playbackQueue = useRef<Track[]>([]),
    playingFromQueue = useRef(false),
    shuffleOrderRef = useRef<{
      signature: string;
      tracks: Track[];
      cursor: number;
    }>({
      signature: "",
      tracks: [],
      cursor: -1,
    }),
    bufferingRef = useRef(false),
    nextRef = useRef<() => void>(() => {}),
    draggedQueueIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (!shuffle) {
      shuffleOrderRef.current = { signature: "", tracks: [], cursor: -1 };
    }
  }, [shuffle]);
  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if (
        loginRequired ||
        document.querySelector(
          'dialog[open], [role="dialog"][aria-modal="true"]',
        )
      )
        return;
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setView("search");
        requestAnimationFrame(() => searchInput.current?.focus());
      } else if (
        event.code === "Space" &&
        !(
          event.target instanceof HTMLElement &&
          (event.target.closest(
            "input,textarea,select,button,a,summary,[role=button]",
          ) ||
            event.target.isContentEditable)
        )
      ) {
        event.preventDefault();
        const element = audio.current;
        if (currentRef.current && element) {
          if (element.paused)
            void element.play().catch((error) => {
              reportPlayback(
                currentRef.current!,
                "failed",
                "play",
                error.message,
              );
              fail(error);
            });
          else element.pause();
        }
      }
    };
    window.addEventListener("keydown", shortcut);
    return () => window.removeEventListener("keydown", shortcut);
  }, [loginRequired]);
  const fail = (error: unknown) =>
    setNotice(error instanceof Error ? error.message : String(error));
  const checkSystemUpdate = useCallback(async (force = false) => {
    setCheckingUpdate(true);
    setUpdateError("");
    try {
      const data = await api<SystemUpdateInfo>(
        force ? "/system/update?force=1" : "/system/update",
      );
      setUpdateInfo(data);
    } catch (error) {
      setUpdateError(error instanceof Error ? error.message : String(error));
    } finally {
      setCheckingUpdate(false);
    }
  }, []);
  const updateKey = updateTargetKey(updateInfo);
  const hasActiveUpdate = Boolean(
    updateInfo?.has_update && updateKey && updateKey !== dismissedUpdate,
  );
  function dismissUpdate(key: string) {
    setDismissedUpdate(key);
    try {
      localStorage.setItem("melokeep-dismissed-update", key);
    } catch {
      /* Storage is optional. */
    }
    lastLocalPrefAtRef.current = Date.now();
    scheduleSavePreferences({ dismissed_update: key });
  }
  const applyServerPreferences = useCallback(
    (prefs: Awaited<ReturnType<typeof fetchServerPreferences>>) => {
      const dirtyKeys = getDirtyPreferenceKeys();
      const pendingFlush: UIPreferences = {};
      if (dirtyKeys.has("appearance")) {
        pendingFlush.appearance = toAppearancePreferences(
          appearanceCtrl.appearance,
        );
      }
      if (dirtyKeys.has("navigation_order")) {
        try {
          const navRaw = localStorage.getItem("melokeep-navigation");
          const parsed: unknown = navRaw ? JSON.parse(navRaw) : null;
          if (Array.isArray(parsed) && parsed.length) {
            pendingFlush.navigation_order = parsed;
          }
        } catch {
          /* Storage is optional. */
        }
      }
      if (dirtyKeys.has("player")) {
        pendingFlush.player = toPlayerPreferences(playerPrefsRef.current);
      }
      if (dirtyKeys.has("library_tree_width")) {
        try {
          const raw = localStorage.getItem("melokeep-library-tree-width");
          const w = Number(raw);
          pendingFlush.library_tree_width =
            raw && Number.isFinite(w) && w >= 200 && w <= 480
              ? Math.round(w)
              : 0;
        } catch {
          /* Storage is optional. */
        }
      }
      if (dirtyKeys.has("navidrome_view")) {
        try {
          const navMode = localStorage.getItem("melokeep-navidrome-mode");
          const navLayout = localStorage.getItem("melokeep-navidrome-layout");
          if (navMode || navLayout) {
            pendingFlush.navidrome_view = {
              ...(navMode ? { mode: navMode } : {}),
              ...(navLayout ? { layout: navLayout } : {}),
            };
          }
        } catch {
          /* Storage is optional. */
        }
      }
      if (dirtyKeys.has("dismissed_update")) {
        try {
          pendingFlush.dismissed_update =
            localStorage.getItem("melokeep-dismissed-update") || "";
        } catch {
          /* Storage is optional. */
        }
      }

      const hasServerData = Boolean(
        prefs.appearance ||
        prefs.navigation_order?.length ||
        prefs.player ||
        prefs.library_tree_width !== undefined ||
        prefs.navidrome_view ||
        prefs.dismissed_update !== undefined,
      );
      if (hasServerData) {
        if (prefs.appearance && !dirtyKeys.has("appearance")) {
          appearanceCtrl.hydrateFromServer(prefs.appearance);
        }
        if (
          prefs.navigation_order?.length &&
          !dirtyKeys.has("navigation_order")
        ) {
          setServerNavOrder(prefs.navigation_order);
        }
        if (prefs.player && !dirtyKeys.has("player")) {
          const nextPlayer = parsePlayerPreferences(
            prefs.player,
            playerPrefsRef.current,
          );
          playerPrefsRef.current = nextPlayer;
          setVolume(nextPlayer.volume);
          setShuffle(nextPlayer.shuffle);
          setRepeat(nextPlayer.repeat);
          setFollow(nextPlayer.follow);
          setLargeLyrics(nextPlayer.largeLyrics);
          setLyricsFontSize(nextPlayer.lyricsFontSize);
          setLyricsActiveColor(nextPlayer.lyricsActiveColor);
          setLyricsInactiveOpacity(nextPlayer.lyricsInactiveOpacity);
          setLyricsAlign(nextPlayer.lyricsAlign);
          saveLocalPlayerPrefs(nextPlayer);
        }
        if (
          typeof prefs.library_tree_width === "number" &&
          !dirtyKeys.has("library_tree_width")
        ) {
          setServerTreeWidth(prefs.library_tree_width);
        }
        if (prefs.navidrome_view && !dirtyKeys.has("navidrome_view")) {
          setServerNavidromeView(prefs.navidrome_view);
        }
        if (
          typeof prefs.dismissed_update === "string" &&
          !dirtyKeys.has("dismissed_update")
        ) {
          setDismissedUpdate(prefs.dismissed_update);
          try {
            localStorage.setItem(
              "melokeep-dismissed-update",
              prefs.dismissed_update,
            );
          } catch {
            /* Storage is optional. */
          }
        }
        if (Object.keys(pendingFlush).length > 0) {
          scheduleSavePreferences(pendingFlush);
        }
        return;
      }

      // Migrate pre-existing non-default localStorage preferences on first server sync.
      const patch: UIPreferences = { ...pendingFlush };
      try {
        const curApp = appearanceCtrl.appearance;
        if (
          !patch.appearance &&
          (curApp.mode !== "system" ||
            curApp.lightPreset !== "pure-light" ||
            curApp.darkPreset !== "obsidian" ||
            curApp.customAccent !== "" ||
            !curApp.ambientGlow ||
            curApp.density !== "modern")
        ) {
          patch.appearance = toAppearancePreferences(curApp);
        }
        const navRaw = localStorage.getItem("melokeep-navigation");
        if (!patch.navigation_order && navRaw) {
          const parsed: unknown = JSON.parse(navRaw);
          if (Array.isArray(parsed) && parsed.length) {
            patch.navigation_order = parsed;
          }
        }
        const treeW = Number(
          localStorage.getItem("melokeep-library-tree-width"),
        );
        if (
          patch.library_tree_width === undefined &&
          Number.isFinite(treeW) &&
          treeW >= 200 &&
          treeW <= 480
        ) {
          patch.library_tree_width = Math.round(treeW);
        }
        const navMode = localStorage.getItem("melokeep-navidrome-mode");
        const navLayout = localStorage.getItem("melokeep-navidrome-layout");
        if (!patch.navidrome_view && (navMode || navLayout)) {
          patch.navidrome_view = {
            ...(navMode ? { mode: navMode } : {}),
            ...(navLayout ? { layout: navLayout } : {}),
          };
        }
        const dismissed = localStorage.getItem("melokeep-dismissed-update");
        if (patch.dismissed_update === undefined && dismissed) {
          patch.dismissed_update = dismissed;
        }
        const curPlayer = playerPrefsRef.current;
        if (
          !patch.player &&
          (curPlayer.volume !== DEFAULT_PLAYER_PREFS.volume ||
            curPlayer.shuffle !== DEFAULT_PLAYER_PREFS.shuffle ||
            curPlayer.repeat !== DEFAULT_PLAYER_PREFS.repeat ||
            curPlayer.follow !== DEFAULT_PLAYER_PREFS.follow ||
            curPlayer.largeLyrics !== DEFAULT_PLAYER_PREFS.largeLyrics ||
            curPlayer.lyricsFontSize !== DEFAULT_PLAYER_PREFS.lyricsFontSize ||
            curPlayer.lyricsActiveColor !==
              DEFAULT_PLAYER_PREFS.lyricsActiveColor ||
            curPlayer.lyricsInactiveOpacity !==
              DEFAULT_PLAYER_PREFS.lyricsInactiveOpacity ||
            curPlayer.lyricsAlign !== DEFAULT_PLAYER_PREFS.lyricsAlign)
        ) {
          patch.player = toPlayerPreferences(curPlayer);
        }
      } catch {
        /* Storage is optional. */
      }
      if (Object.keys(patch).length > 0) {
        scheduleSavePreferences(patch);
      }
    },
    [appearanceCtrl],
  );
  async function load() {
    const prefSeq = getPreferencesMutationSeq();
    const [cfg, favs, list, downloads, collections, serverPrefs] =
      await Promise.all([
        api<Config>("/config"),
        api<Track[]>("/favorites"),
        api<Track[]>("/playlist"),
        api<DownloadJob[]>("/downloads"),
        api<PlaylistSummary[]>("/playlists"),
        fetchServerPreferences().catch(() => null),
      ]);
    setConfig(cfg);
    setFavorites(favs);
    setPlaylist(list);
    setJobs(downloads);
    setSavedPlaylists(collections);
    if (serverPrefs && getPreferencesMutationSeq() === prefSeq) {
      applyServerPreferences(serverPrefs);
    }
    if (!updateAutoChecked.current) {
      updateAutoChecked.current = true;
      void checkSystemUpdate(false);
    }
  }
  useEffect(() => {
    if (loginRequired) return;
    const onVisible = () => {
      if (document.visibilityState !== "visible") return;
      if (Date.now() - lastLocalPrefAtRef.current < 1000) return;
      const prefSeq = getPreferencesMutationSeq();
      void fetchServerPreferences()
        .then((prefs) => {
          if (getPreferencesMutationSeq() === prefSeq) {
            applyServerPreferences(prefs);
          }
        })
        .catch(() => {});
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [loginRequired, applyServerPreferences]);
  useEffect(() => {
    const login = () => {
      searchGeneration.current++;
      eventSource.current?.close();
      eventSource.current = null;
      searchJob.current = null;
      setSearching(false);
      setAuthEnabled(true);
      setLoginRequired(true);
    };
    window.addEventListener("melokeep-login", login);
    api<{ authenticated: boolean; required?: boolean }>("/auth/status")
      .then((status) => {
        setAuthEnabled(Boolean(status.required));
        if (status.authenticated) load().catch(fail);
        else setLoginRequired(true);
      })
      .catch(fail);
    return () => {
      window.removeEventListener("melokeep-login", login);
      eventSource.current?.close();
      if (searchJob.current)
        void api(`/search/jobs/${searchJob.current}`, "DELETE").catch(() => {});
    };
  }, []);
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(""), 6500);
    return () => clearTimeout(timer);
  }, [notice]);
  const hasActiveDownloads = jobs.some((job) =>
    ["queued", "resolving", "downloading", "enriching"].includes(job.status),
  );
  useEffect(() => {
    if (loginRequired) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    let running = false;
    async function poll() {
      if (running || controller.signal.aborted) return;
      clearTimeout(timer);
      running = true;
      try {
        if (!document.hidden) {
          const next = await api<DownloadJob[]>(
            "/downloads",
            "GET",
            undefined,
            controller.signal,
          );
          if (!controller.signal.aborted)
            setJobs((previous) =>
              JSON.stringify(previous) === JSON.stringify(next)
                ? previous
                : next,
            );
        }
      } catch {
        /* The next poll recovers transient network failures. */
      } finally {
        running = false;
        if (!controller.signal.aborted)
          timer = setTimeout(
            () => void poll(),
            document.hidden ? 30000 : hasActiveDownloads ? 1500 : 15000,
          );
      }
    }
    const resume = () => {
      if (!document.hidden) void poll();
    };
    timer = setTimeout(() => void poll(), hasActiveDownloads ? 1500 : 15000);
    document.addEventListener("visibilitychange", resume);
    return () => {
      controller.abort();
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", resume);
    };
  }, [loginRequired, hasActiveDownloads]);
  useEffect(() => {
    if (view === "files" || view === "organize")
      void loadLibraryFiles({ cursor: null });
  }, [view]);
  useEffect(() => {
    const element = audio.current;
    if (!element) return;
    element.volume = volume;
  }, [volume]);
  currentRef.current = current;
  const favIds = useMemo(
    () => new Set(favorites.map((t) => t.id)),
    [favorites],
  );
  const tableTracks =
    view === "favorites"
      ? favorites
      : view === "playlists"
        ? activePlaylist?.tracks || []
        : tracks;
  const display = useMemo(
    () => selectResults(tableTracks, sourceFilter, filter, false, sort),
    [tableTracks, sourceFilter, filter, sort],
  );
  const lastSearch = useRef("");
  async function search(keyword = query) {
    if (!keyword.trim() || !config) return;
    const generation = ++searchGeneration.current;
    eventSource.current?.close();
    const previousJob = searchJob.current;
    searchJob.current = null;
    setView("search");
    setQuery(keyword);
    setTracks([]);
    setSelected(new Set());
    setStatuses(
      Object.fromEntries(
        config.sources.map((id) => [id, { state: "searching" }]),
      ),
    );
    setSearching(true);
    setFirstMs(null);
    startTime.current = performance.now();
    try {
      if (previousJob) await api(`/search/jobs/${previousJob}`, "DELETE");
      if (generation !== searchGeneration.current) return;
      const { id } = await api<{ id: string }>("/search/jobs", "POST", {
        keyword,
        sources: config.sources,
        refresh: lastSearch.current === keyword.trim(),
      });
      lastSearch.current = keyword.trim();
      if (generation !== searchGeneration.current) {
        void api(`/search/jobs/${id}`, "DELETE").catch(() => {});
        return;
      }
      searchJob.current = id;
      const events = new EventSource(`/api/search/jobs/${id}/events`, {
        withCredentials: true,
      });
      eventSource.current = events;
      const receive = (event: MessageEvent) => {
        if (generation !== searchGeneration.current) return;
        const track: Track = JSON.parse(event.data);
        setTracks((previous) => {
          const index = previous.findIndex((t) => t.id === track.id);
          if (index === -1) return [...previous, track];
          const next = [...previous];
          next[index] = track;
          return next;
        });
        setFirstMs(
          (previous) =>
            previous ?? Math.round(performance.now() - startTime.current),
        );
      };
      events.addEventListener("candidate", receive as EventListener);
      events.addEventListener("track", receive as EventListener);
      events.addEventListener("source", (event) => {
        if (generation !== searchGeneration.current) return;
        const value = JSON.parse((event as MessageEvent).data);
        setStatuses((previous) => ({ ...previous, [value.id]: value.status }));
      });
      const finish = (detail = "本次搜索已结束，音源未返回结果") => {
        if (generation !== searchGeneration.current) return;
        setSearching(false);
        searchJob.current = null;
        eventSource.current = null;
        setStatuses((previous) =>
          Object.fromEntries(
            Object.entries(previous).map(([id, status]) => [
              id,
              status.state === "searching"
                ? { state: "stopped", detail }
                : status,
            ]),
          ),
        );
        events.close();
      };
      events.addEventListener("done", () => finish());
      events.addEventListener("error", (event) => {
        if (generation !== searchGeneration.current) return;
        const hasPayload =
          "data" in event &&
          String((event as MessageEvent).data || "").trim() !== "";
        const detail = hasPayload
          ? JSON.parse(String((event as MessageEvent).data)).detail
          : "搜索连接已断开，请重新搜索";
        finish(detail);
        setNotice(detail);
        void api(`/search/jobs/${id}`, "DELETE").catch(() => {});
      });
    } catch (error) {
      if (generation === searchGeneration.current) {
        setSearching(false);
        fail(error);
      }
    }
  }
  function reportPlayback(
    track: Track,
    event: "started" | "failed" | "stalled" | "recovered",
    stage: "prepare" | "format" | "play" | "media" | "buffer",
    detail = "",
  ) {
    const element = audio.current;
    void api("/playback/events", "POST", {
      track_id: track.id,
      event,
      stage,
      detail: detail.slice(0, 2000),
      code: element?.error?.code || 0,
      network_state: element?.networkState || 0,
      ready_state: element?.readyState || 0,
      position: Math.max(
        0,
        Number.isFinite(element?.currentTime) ? element!.currentTime : 0,
      ),
      browser: navigator.userAgent.slice(0, 300),
    }).catch(() => {});
  }
  async function play(
    track: Track,
    context: Track[] = [track],
    fromQueue = false,
    preserveShuffle = false,
  ) {
    playbackQueue.current = context;
    playingFromQueue.current = fromQueue;
    if (shuffle && !preserveShuffle) {
      const tracks = buildShuffledTracks(context, track.id);
      shuffleOrderRef.current = {
        signature: playbackSignature(context, fromQueue),
        tracks,
        cursor: 0,
      };
    } else if (!shuffle) {
      shuffleOrderRef.current = { signature: "", tracks: [], cursor: -1 };
    }
    const generation = ++playGeneration.current;
    audio.current?.pause();
    // load() can discard the queued pause event; keep failed preparation in sync.
    setPlaying(false);
    audio.current?.removeAttribute("src");
    audio.current?.load();
    currentRef.current = track;
    bufferingRef.current = false;
    setCurrent(track);
    setLoadingPlay(true);
    setLyricText("正在获取歌词…");
    setLines([]);
    playbackClock.set(0);
    setDuration(track.duration);
    void api<{ text: string }>(`/tracks/${track.id}/lyrics`)
      .then(({ text }) => {
        if (generation !== playGeneration.current) return;
        const parsed = parseLrc(text);
        setLines(parsed);
        setLyricText(parsed.length ? "" : text || "此音源暂无歌词");
      })
      .catch((error) => {
        if (generation === playGeneration.current)
          setLyricText(error instanceof Error ? error.message : "歌词加载失败");
      });
    let stage: "prepare" | "format" | "play" = "prepare";
    try {
      const prepared =
        track.source === "navidrome" &&
        track.playback?.state === "ready" &&
        !!track.playback?.url
          ? track
          : await api<Track>(`/tracks/${track.id}/playback`, "POST");
      if (generation !== playGeneration.current) return false;
      setCurrent(prepared);
      setTracks((previous) =>
        previous.map((item) => (item.id === prepared.id ? prepared : item)),
      );
      if (prepared.playback.state !== "ready" || !prepared.playback.url)
        throw new Error(
          prepared.playback.detail || "曲目暂不可试听，请更换音源",
        );
      const element = audio.current!;
      stage = "format";
      const mediaType: { [key: string]: string } = {
        flac: "audio/flac",
        mp3: "audio/mpeg",
        m4a: "audio/mp4",
        ogg: "audio/ogg",
        wav: "audio/wav",
        opus: "audio/ogg;codecs=opus",
        aac: "audio/aac",
        mp4: "audio/mp4",
      };
      if (
        prepared.playback.codec &&
        !element.canPlayType(mediaType[prepared.playback.codec] || "")
      )
        throw new Error(
          prepared.source === "local"
            ? "浏览器不支持该音频格式，可以下载到本机后播放"
            : "浏览器不支持该试听格式，请选择其他音源",
        );
      element.src = prepared.playback.url;
      element.load();
      stage = "play";
      await element.play();
      return true;
    } catch (error) {
      if (generation === playGeneration.current) {
        const message = playbackErrorMessage(error);
        if (!message) return false;
        reportPlayback(
          track,
          "failed",
          stage,
          error instanceof Error
            ? `${error.name}: ${error.message}`
            : String(error),
        );
        setNotice(message);
      }
      return false;
    } finally {
      if (generation === playGeneration.current) setLoadingPlay(false);
    }
  }
  async function playSequence(
    index: number,
    list: Track[],
    fromQueue: boolean,
    direction = 1,
    canonicalList?: Track[],
  ) {
    // Try each entry at most once; an unavailable song must not block the queue.
    for (let attempted = 0; attempted < list.length; attempted++) {
      if (canonicalList) {
        shuffleOrderRef.current.cursor = index;
      }
      const generation = playGeneration.current + 1;
      if (
        await play(
          list[index],
          canonicalList ?? list,
          fromQueue,
          Boolean(canonicalList),
        )
      )
        return;
      if (generation !== playGeneration.current) return;
      index += direction;
      if (index < 0 || index >= list.length) {
        if (!repeat) return;
        index = (index + list.length) % list.length;
      }
    }
  }
  function seek(seconds: number) {
    if (audio.current?.src && audio.current.readyState > 0) {
      audio.current.currentTime = Math.min(
        seconds,
        audio.current.duration || duration,
      );
      playbackClock.set(seconds);
    }
  }
  async function toggle() {
    if (!current) return;
    if (audio.current?.paused) {
      if (!audio.current.src) {
        await play(current, playbackQueue.current, playingFromQueue.current);
        return;
      }
      await audio.current.play().catch((error) => {
        const message = playbackErrorMessage(error);
        if (!message) return;
        reportPlayback(
          current,
          "failed",
          "play",
          `${error.name}: ${error.message}`,
        );
        setNotice(message);
      });
    } else audio.current?.pause();
  }
  function step(direction: number) {
    const fromQueue = playingFromQueue.current;
    const list = fromQueue ? playlist : playbackQueue.current;
    if (!list.length) return;
    if (shuffle) {
      const sig = playbackSignature(list, fromQueue);
      let state = shuffleOrderRef.current;
      if (state.signature !== sig || state.tracks.length !== list.length) {
        const tracks = buildShuffledTracks(list, current?.id);
        state = { signature: sig, tracks, cursor: 0 };
        shuffleOrderRef.current = state;
      }
      let next = state.cursor + direction;
      if (next >= state.tracks.length) {
        if (!repeat) return;
        const lastId = current?.id || state.tracks[state.tracks.length - 1]?.id;
        state = {
          signature: sig,
          tracks: buildShuffledTracks(list, undefined, lastId),
          cursor: 0,
        };
        shuffleOrderRef.current = state;
        next = 0;
      } else if (next < 0) {
        if (!repeat) return;
        next = state.tracks.length - 1;
      }
      void playSequence(next, state.tracks, fromQueue, direction, list);
      return;
    }
    const index = list.findIndex((t) => t.id === current?.id);
    let next = index + direction;
    if (next >= list.length) {
      if (!repeat) return;
      next = 0;
    }
    if (next < 0) next = list.length - 1;
    void playSequence(next, list, fromQueue, direction);
  }
  nextRef.current = () => step(1);
  async function favorite(track: Track) {
    try {
      const exists = favIds.has(track.id);
      await api(`/favorites/${track.id}`, exists ? "DELETE" : "PUT");
      setFavorites((previous) =>
        exists
          ? previous.filter((t) => t.id !== track.id)
          : [track, ...previous],
      );
    } catch (error) {
      fail(error);
    }
  }
  async function savePlaybackQueue(
    next: Track[],
    options: { silent?: boolean } = {},
  ) {
    const { silent = false } = options;
    if (queueWritePending.current) {
      if (!silent) setNotice("播放列表正在保存，请稍后再试");
      return false;
    }
    queueWritePending.current = true;
    if (!silent) setQueueSaving(true);
    try {
      await api(
        "/playlist",
        "PUT",
        next.map((t) => t.id),
      );
      setPlaylist(next);
      return true;
    } finally {
      queueWritePending.current = false;
      if (!silent) setQueueSaving(false);
    }
  }
  async function addToPlaylist(items: Track[]) {
    try {
      const next = [
        ...playlist,
        ...items.filter((t) => !playlist.some((p) => p.id === t.id)),
      ].slice(0, 1000);
      if (!(await savePlaybackQueue(next))) return;
      setNotice(`已加入播放列表，共 ${next.length} 首`);
    } catch (error) {
      fail(error);
    }
  }
  async function playNextInQueue(track: Track) {
    try {
      const withoutTarget = playlist.filter((t) => t.id !== track.id);
      const curIdx = current
        ? withoutTarget.findIndex((t) => t.id === current.id)
        : -1;
      const insertAt = curIdx >= 0 ? curIdx + 1 : 0;
      const next = [
        ...withoutTarget.slice(0, insertAt),
        track,
        ...withoutTarget.slice(insertAt),
      ].slice(0, 1000);
      if (shuffle && shuffleOrderRef.current.tracks.length) {
        const state = shuffleOrderRef.current;
        const filteredShuffle = state.tracks.filter((t) => t.id !== track.id);
        const activeIdx = current
          ? filteredShuffle.findIndex((t) => t.id === current.id)
          : -1;
        const sIdx =
          activeIdx >= 0
            ? activeIdx + 1
            : Math.max(0, Math.min(state.cursor + 1, filteredShuffle.length));
        shuffleOrderRef.current = {
          ...state,
          signature: playbackSignature(next, playingFromQueue.current),
          tracks: [
            ...filteredShuffle.slice(0, sIdx),
            track,
            ...filteredShuffle.slice(sIdx),
          ],
          cursor: activeIdx >= 0 ? activeIdx : state.cursor,
        };
      }
      if (!(await savePlaybackQueue(next, { silent: true }))) return;
      setNotice(`已安排下一首播放：${track.title}`);
    } catch (error) {
      fail(error);
    }
  }
  async function removeFromPlaylist(ids: string[]) {
    const removed = new Set(ids);
    const next = playlist.filter((t) => !removed.has(t.id));
    try {
      if (!(await savePlaybackQueue(next))) return;
      setQueueSelected(new Set());
      if (!next.length) setQueueManaging(false);
      setNotice(
        `已从播放列表移除 ${playlist.length - next.length} 首，音乐文件保留`,
      );
    } catch (error) {
      fail(error);
    }
  }
  async function chooseDownload(items: Track[]) {
    const skippedCount = items.filter(
      (track) => track.source === "navidrome" || track.source === "local",
    ).length;
    const downloadable = items.filter(
      (track) => track.source !== "navidrome" && track.source !== "local",
    );
    if (!downloadable.length) {
      if (items.some((track) => track.source === "navidrome")) {
        setNotice("Navidrome 已提供原文件，可通过曲目旁的下载按钮保存到本机。");
      } else if (items.some((track) => track.source === "local")) {
        setNotice("歌曲已在音乐库中，可在音乐文件页下载到本机");
      } else {
        setNotice("请先选择曲目");
      }
      return;
    }
    if (skippedCount > 0) {
      setNotice(`已自动跳过 ${skippedCount} 首已在本地/Navidrome 的曲目`);
    }
    items = downloadable;
    const canProbeSize = items.length <= 8;
    const needsVersionResolve = (t: Track) => !t.qualities.length;
    const needsSizeProbe = (t: Track) =>
      canProbeSize &&
      t.qualities.length > 0 &&
      t.qualities.every(
        (q) =>
          (typeof q.size_bytes !== "number" || q.size_bytes <= 0) &&
          !q.size_label?.trim(),
      ) &&
      !t.qualities.some((q) => q.evidence === "engine");
    try {
      if (items.some(needsVersionResolve)) {
        setNotice("正在确认可下载音质…");
      } else if (items.some(needsSizeProbe) && skippedCount === 0) {
        setNotice("正在获取音质文件大小…");
      }
      const prepared: Track[] = [];
      for (let offset = 0; offset < items.length; offset += 4) {
        prepared.push(
          ...(await Promise.all(
            items.slice(offset, offset + 4).map((t) => {
              if (needsVersionResolve(t)) {
                return api<Track>(`/tracks/${t.id}/versions`, "POST");
              }
              if (needsSizeProbe(t)) {
                return api<Track>(`/tracks/${t.id}/versions`, "POST")
                  .then((next) => (next.qualities.length ? next : t))
                  .catch(() => t);
              }
              return t;
            }),
          )),
        );
      }
      items = prepared;
      const resolved = new Map(items.map((t) => [t.id, t]));
      setTracks((previous) => previous.map((t) => resolved.get(t.id) || t));
      setFavorites((previous) => previous.map((t) => resolved.get(t.id) || t));
      setCurrent((previous) =>
        previous ? resolved.get(previous.id) || previous : null,
      );
    } catch (error) {
      fail(error);
      return;
    }
    setQualityTracks(items);
    setQualities(
      Object.fromEntries(
        items.map((t) => [
          t.id,
          t.qualities.find((q) => q.lossless)?.id || t.qualities[0]?.id || "",
        ]),
      ),
    );
  }
  async function download() {
    if (!qualityTracks) return;
    setBusy(true);
    let queued = 0;
    try {
      for (let offset = 0; offset < qualityTracks.length; offset += 200) {
        const created = await api<DownloadJob[]>(
          "/downloads",
          "POST",
          qualityTracks.slice(offset, offset + 200).map((track) => ({
            track_id: track.id,
            quality_id: qualities[track.id],
          })),
        );
        queued += created.length;
        setJobs((previous) => [...created, ...previous]);
      }
      setQualityTracks(null);
      setNotice(`已加入 ${queued} 首下载任务`);
    } catch (error) {
      if (queued) setQualityTracks(qualityTracks.slice(queued));
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  async function importList() {
    setBusy(true);
    try {
      const id = importId.trim();
      const result = await api<{ playlist: SavedPlaylist; detail: string }>(
        "/playlist/import",
        "POST",
        { source: importSource, id, name: importName.trim() },
      );
      setActivePlaylist(result.playlist);
      resetCollectionFilters();
      setView("playlists");
      setNotice(result.detail);
      setImportId("");
      setImportName("");
      await refreshPlaylists();
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  function resetCollectionFilters() {
    setSourceFilter("all");
    setFilter("all");
    setSort("relevance");
    setSelected(new Set());
  }
  async function refreshPlaylists() {
    setSavedPlaylists(await api<PlaylistSummary[]>("/playlists"));
  }
  const remotePlaylistId =
    activePlaylist?.source === "navidrome" ? activePlaylist.id : null;
  useEffect(() => {
    if (view !== "playlists" || loginRequired || !config) return;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function sync() {
      try {
        const lists = await api<PlaylistSummary[]>(
          "/navidrome/playlists",
          "GET",
          undefined,
          abort.signal,
        );
        if (!abort.signal.aborted) {
          setRemotePlaylists(lists);
          setRemotePlaylistError("");
          if (remotePlaylistId) {
            if (lists.some((list) => list.id === remotePlaylistId)) {
              const updated = await api<SavedPlaylist>(
                `/navidrome/playlists/${encodeURIComponent(remotePlaylistId.slice(10))}`,
                "GET",
                undefined,
                abort.signal,
              );
              if (!abort.signal.aborted)
                setActivePlaylist((current) =>
                  current?.id === remotePlaylistId ? updated : current,
                );
            } else {
              setActivePlaylist((current) =>
                current?.id === remotePlaylistId ? null : current,
              );
            }
          }
        }
      } catch (error) {
        if (!abort.signal.aborted)
          setRemotePlaylistError((error as Error).message);
      } finally {
        if (!abort.signal.aborted) timer = setTimeout(() => void sync(), 60000);
      }
    }
    void sync();
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [view, loginRequired, !!config, playlistRefresh, remotePlaylistId]);
  function openAddToPlaylistModal(items: Track[]) {
    setPlaylistModalTracks(items);
    setTargetPlaylistId(items.length ? savedPlaylists[0]?.id || "new" : "new");
    setNewPlaylistName("");
    if (items.every((track) => track.source === "navidrome"))
      void api<PlaylistSummary[]>("/navidrome/playlists")
        .then(setRemotePlaylists)
        .catch(fail);
  }
  async function submitAddToSavedPlaylist() {
    if (!playlistModalTracks) return;
    setBusy(true);
    try {
      const track_ids = playlistModalTracks.map((t) => t.id);
      let updated: SavedPlaylist;
      if (
        targetPlaylistId === "new-navidrome" ||
        targetPlaylistId.startsWith("navidrome:")
      ) {
        if (playlistModalTracks.some((track) => track.source !== "navidrome"))
          throw new Error(
            "Navidrome 歌单只能加入已经在 Navidrome 曲库中的歌曲",
          );
        const creating = targetPlaylistId === "new-navidrome";
        updated = await api<SavedPlaylist>(
          creating
            ? "/navidrome/playlists"
            : `/navidrome/playlists/${encodeURIComponent(targetPlaylistId.slice(10))}/tracks`,
          "POST",
          {
            ...(creating ? { name: newPlaylistName.trim() } : {}),
            song_ids: playlistModalTracks.map((track) => track.source_id),
          },
        );
        setRemotePlaylists(
          await api<PlaylistSummary[]>("/navidrome/playlists"),
        );
        setPlaylistRefresh((value) => value + 1);
      } else if (targetPlaylistId === "new") {
        const name = newPlaylistName.trim();
        if (!name) {
          setNotice("请输入新歌单名称");
          return;
        }
        updated = await api<SavedPlaylist>("/playlists", "POST", {
          name,
          track_ids,
        });
      } else {
        updated = await api<SavedPlaylist>(
          `/playlists/${targetPlaylistId}/tracks`,
          "POST",
          { track_ids },
        );
      }
      await refreshPlaylists();
      if (activePlaylist?.id === updated.id || !playlistModalTracks.length) {
        setActivePlaylist(updated);
        resetCollectionFilters();
        setView("playlists");
      }
      setPlaylistModalTracks(null);
      setNewPlaylistName("");
      setNotice(updated.detail || `已加入歌单“${updated.name}”`);
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  async function openPlaylist(id: string) {
    try {
      setActivePlaylist(
        await api<SavedPlaylist>(
          id.startsWith("navidrome:")
            ? `/navidrome/playlists/${encodeURIComponent(id.slice(10))}`
            : `/playlists/${encodeURIComponent(id)}`,
        ),
      );
      resetCollectionFilters();
    } catch (error) {
      fail(error);
    }
  }
  async function playCollection(items: Track[]) {
    if (!items.length) {
      setNotice("此歌单暂无曲目");
      return;
    }
    const queued = items.slice(0, 1000);
    setPlaylist(queued);
    void savePlaybackQueue(queued, { silent: true }).catch((error) => {
      fail(error);
    });
    if (shuffle) {
      const tracks = buildShuffledTracks(queued);
      shuffleOrderRef.current = {
        signature: playbackSignature(queued, true),
        tracks,
        cursor: 0,
      };
      await playSequence(0, tracks, true, 1, queued);
      return;
    }
    await playSequence(0, queued, true);
  }
  async function removeCollectionTrack(track: Track) {
    if (!activePlaylist || activePlaylist.read_only) return;
    const currentList = activePlaylist;
    if (currentList.source === "navidrome") {
      if (track.playlist_index === undefined || !currentList.revision) {
        setNotice("请先同步歌单，再移除歌曲");
        return;
      }
      setManagementError("");
      setConfirmation({
        title: "从 Navidrome 歌单移除歌曲",
        description: `从“${currentList.name}”移除“${track.title}”？此操作同步到 Navidrome，音乐文件会保留。`,
        run: async () => {
          setManaging(true);
          try {
            const updated = await api<SavedPlaylist>(
              `/navidrome/playlists/${encodeURIComponent(currentList.id.slice(10))}/tracks/remove`,
              "POST",
              {
                indexes: [track.playlist_index],
                revision: currentList.revision,
              },
            );
            setActivePlaylist((current) =>
              current?.id === updated.id ? updated : current,
            );
            setSelected(
              (previous) =>
                new Set([...previous].filter((id) => id !== track.id)),
            );
            setRemotePlaylists(
              await api<PlaylistSummary[]>("/navidrome/playlists"),
            );
            setConfirmation(null);
            setNotice("已从 Navidrome 歌单移除，音乐文件保留");
          } catch (error) {
            setManagementError((error as Error).message);
          } finally {
            setManaging(false);
          }
        },
      });
      return;
    }
    try {
      setActivePlaylist(
        await api<SavedPlaylist>(
          `/playlists/${activePlaylist.id}/tracks/${track.id}`,
          "DELETE",
        ),
      );
      setSelected(
        (previous) => new Set([...previous].filter((s) => s !== track.id)),
      );
      await refreshPlaylists();
    } catch (error) {
      fail(error);
    }
  }
  const selection = tableTracks.filter((t) => selected.has(t.id));
  const selectedToggle = (id: string) =>
    setSelected((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const statusLabels: Record<string, string> = {
    queued: "排队中",
    resolving: "解析中",
    downloading: "下载中",
    enriching: "写入标签",
    complete: "已完成",
    failed: "失败",
    paused: "已暂停",
    cancelled: "已取消",
  };
  const visibleJobs = jobs.filter(
    (job) => queueStatus === "all" || job.status === queueStatus,
  );
  const chosenJobs = visibleJobs.filter((job) => selectedJobs.includes(job.id));
  const stopped = (job: DownloadJob) =>
    ["complete", "failed", "paused", "cancelled"].includes(job.status);
  async function manageJobs(action: string, targets: DownloadJob[]) {
    setManaging(true);
    setManagementError("");
    try {
      for (const job of targets) {
        await api(
          action === "remove"
            ? `/downloads/${job.id}`
            : `/downloads/${job.id}/${action === "resume" && job.status === "cancelled" ? "retry" : action}`,
          action === "remove" ? "DELETE" : "POST",
        );
        if (action === "remove")
          setSelectedJobs((previous) => previous.filter((id) => id !== job.id));
      }
      setNotice(
        action === "remove"
          ? "下载记录已移除，已下载文件保留"
          : "下载任务已更新",
      );
      setConfirmation(null);
    } catch (error) {
      setManagementError((error as Error).message);
      setConfirmation(null);
    } finally {
      await api<DownloadJob[]>("/downloads").then(setJobs).catch(fail);
      setManaging(false);
    }
  }
  function confirmRemoval(targets: DownloadJob[]) {
    setManagementError("");
    setConfirmation({
      title: "移除下载记录",
      description: `移除 ${targets.length} 条记录及未完成的临时文件。已下载的音乐文件会保留。`,
      run: () => manageJobs("remove", targets),
    });
  }
  function confirmFileDeletion(targets: LibraryFile[]) {
    setManagementError("");
    setConfirmation({
      title: "删除音乐文件",
      description: `将从音乐库所在磁盘永久删除 ${targets.length} 个文件，无法撤销。${targets
        .slice(0, 3)
        .map((file) => file.name)
        .join("、")}${targets.length > 3 ? "…" : ""}`,
      run: async () => {
        setManaging(true);
        try {
          const deletedTrackIds = new Set<string>();
          for (const file of targets) {
            if (file.track) deletedTrackIds.add(file.track.id);
          }
          if (targets.length === 1) {
            const res = await api<{ track_ids?: string[] }>(
              `/files?path=${encodeURIComponent(targets[0].path)}`,
              "DELETE",
            );
            for (const tid of res?.track_ids ?? []) deletedTrackIds.add(tid);
          } else {
            const res = await api<{
              track_ids?: string[];
              failed?: string[];
            }>("/files/batch-delete", "POST", {
              files: targets.map((file) => file.path),
            });
            for (const tid of res?.track_ids ?? []) deletedTrackIds.add(tid);
            if (res?.failed?.length) {
              setNotice(`部分文件删除失败：${res.failed.join("；")}`);
            }
          }
          if (deletedTrackIds.size > 0) {
            playbackQueue.current = playbackQueue.current.filter(
              (track) => !deletedTrackIds.has(track.id),
            );
            shuffleOrderRef.current = {
              ...shuffleOrderRef.current,
              tracks: shuffleOrderRef.current.tracks.filter(
                (track) => !deletedTrackIds.has(track.id),
              ),
            };
            setPlaylist((previous) =>
              previous.filter((track) => !deletedTrackIds.has(track.id)),
            );
            setFavorites((previous) =>
              previous.filter((track) => !deletedTrackIds.has(track.id)),
            );
            setActivePlaylist((previous) =>
              previous
                ? {
                    ...previous,
                    tracks: previous.tracks.filter(
                      (track) => !deletedTrackIds.has(track.id),
                    ),
                  }
                : null,
            );
            if (current && deletedTrackIds.has(current.id)) {
              ++playGeneration.current;
              audio.current?.pause();
              audio.current?.removeAttribute("src");
              audio.current?.load();
              setCurrent(null);
              setLoadingPlay(false);
            }
          }
          const removedPaths = new Set(targets.map((file) => file.path));
          setSelectedFiles((previous) =>
            previous.filter((path) => !removedPaths.has(path)),
          );
          setConfirmation(null);
          setNotice("音乐文件已删除");
        } catch (error) {
          setManagementError((error as Error).message);
          setConfirmation(null);
        } finally {
          await loadLibraryFiles({ cursor: null });
          await api<DownloadJob[]>("/downloads").then(setJobs).catch(fail);
          setManaging(false);
        }
      },
    });
  }
  const playbackCodec = current?.playback.codec?.toLowerCase() ?? "";
  const isPlaybackLossless = [
    "flac",
    "alac",
    "ape",
    "wav",
    "aiff",
    "aif",
    "wv",
    "dsf",
    "dff",
  ].includes(playbackCodec);
  const playbackBitrateText =
    current?.playback.bitrate &&
    !(isPlaybackLossless && current.playback.bitrate === 999)
      ? ` ${current.playback.bitrate} kbps`
      : isPlaybackLossless
        ? " 无损"
        : "";
  const playbackLabel =
    current?.playback.state === "ready"
      ? `${current.playback.preview_only ? "片段试听 · " : ""}${current.playback.codec?.toUpperCase()}${playbackBitrateText}`
      : loadingPlay
        ? "正在准备试听"
        : current?.playback.detail || "尚未播放";
  const queueSelection = playlist.filter((track) =>
    queueSelected.has(track.id),
  );
  function toggleQueueSelection(id: string) {
    setQueueSelected((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }
  function toggleQueueManaging() {
    setQueueManaging((prev) => !prev);
    setQueueSelected(new Set());
  }
  function handleQueueDrop(targetId: string) {
    const sourceId = draggedQueueIdRef.current;
    draggedQueueIdRef.current = null;
    if (!sourceId || sourceId === targetId || queueManaging || queueSaving)
      return;
    const fromIdx = playlist.findIndex((t) => t.id === sourceId);
    const toIdx = playlist.findIndex((t) => t.id === targetId);
    if (fromIdx < 0 || toIdx < 0) return;
    const next = [...playlist];
    const [moved] = next.splice(fromIdx, 1);
    next.splice(toIdx, 0, moved);
    setPlaylist(next);
    void savePlaybackQueue(next, { silent: true }).catch(fail);
  }
  const renderQueueList = (withInlineToggle: boolean) => (
    <div className="playback-list" aria-busy={queueSaving}>
      {withInlineToggle && !!playlist.length && !queueManaging && (
        <div className="playback-list-toolbar queue-modal-top-bar">
          <button
            type="button"
            className="icon-button queue-manage-btn"
            disabled={queueSaving}
            aria-label="批量管理"
            aria-pressed={false}
            title="批量管理"
            onClick={toggleQueueManaging}
          >
            <ListChecks />
          </button>
        </div>
      )}
      {queueManaging && !!playlist.length && (
        <div className="playback-list-toolbar playback-list-actions">
          <label className="playback-list-select">
            <input
              type="checkbox"
              aria-label="全选播放列表"
              checked={queueSelection.length === playlist.length}
              ref={(input) => {
                if (input)
                  input.indeterminate =
                    queueSelection.length > 0 &&
                    queueSelection.length < playlist.length;
              }}
              disabled={queueSaving}
              onChange={(event) =>
                setQueueSelected(
                  new Set(
                    event.target.checked ? playlist.map((t) => t.id) : [],
                  ),
                )
              }
            />
            全选
          </label>
          <span className="playback-list-count" aria-live="polite">
            已选 {queueSelection.length} / {playlist.length} 首
          </span>
          <button
            type="button"
            className="playback-list-remove"
            disabled={queueSaving || !queueSelection.length}
            onClick={() =>
              void removeFromPlaylist(queueSelection.map((track) => track.id))
            }
          >
            <Trash2 aria-hidden="true" />
            {queueSaving ? "正在移除…" : `移除所选（${queueSelection.length}）`}
          </button>
          {withInlineToggle && (
            <button
              type="button"
              className="icon-button queue-manage-btn active"
              disabled={queueSaving}
              aria-label="完成"
              aria-pressed={true}
              title="完成"
              onClick={toggleQueueManaging}
            >
              <Check />
            </button>
          )}
        </div>
      )}
      <div className="playlist-items">
        {playlist.length ? (
          playlist.map((track) => (
            <div
              className={`playlist-item ${current?.id === track.id ? "active" : ""}`}
              key={track.id}
              draggable={!queueManaging && !queueSaving && playlist.length > 1}
              onDragStart={(e) => {
                if (queueManaging || queueSaving) return;
                draggedQueueIdRef.current = track.id;
                e.dataTransfer.effectAllowed = "move";
              }}
              onDragOver={(e) => {
                if (
                  !queueManaging &&
                  draggedQueueIdRef.current &&
                  draggedQueueIdRef.current !== track.id
                ) {
                  e.preventDefault();
                }
              }}
              onDrop={(e) => {
                e.preventDefault();
                handleQueueDrop(track.id);
              }}
              onDragEnd={() => {
                draggedQueueIdRef.current = null;
              }}
            >
              {!queueManaging && playlist.length > 1 && (
                <span
                  className="np-queue-drag-handle"
                  aria-hidden="true"
                  title="拖拽调整播放顺序"
                >
                  <GripVertical size={14} />
                </span>
              )}
              {queueManaging && (
                <label className="playback-list-select">
                  <input
                    type="checkbox"
                    aria-label={`选择待播曲目 ${track.title}`}
                    checked={queueSelected.has(track.id)}
                    disabled={queueSaving}
                    onChange={() => toggleQueueSelection(track.id)}
                  />
                </label>
              )}
              <button
                className="playlist-song"
                disabled={queueManaging && queueSaving}
                aria-label={
                  queueManaging ? `切换选择 ${track.title}` : undefined
                }
                aria-pressed={
                  queueManaging ? queueSelected.has(track.id) : undefined
                }
                onClick={() =>
                  queueManaging
                    ? toggleQueueSelection(track.id)
                    : void play(track, playlist, true)
                }
              >
                <Cover track={track} />
                <span>
                  <strong>{track.title}</strong>
                  <small>{track.artist}</small>
                </span>
              </button>
              {!queueManaging && (
                <Button
                  label={`移除 ${track.title}`}
                  disabled={queueSaving}
                  onClick={() => void removeFromPlaylist([track.id])}
                >
                  <X />
                </Button>
              )}
            </div>
          ))
        ) : (
          <p className="empty">选择搜索结果，加入待播列表。</p>
        )}
      </div>
    </div>
  );
  const volumeControl = (
    <div className="player-extras">
      <Button
        label={volume ? "静音" : "取消静音"}
        active={volume === 0}
        onClick={() => updatePlayerPrefs({ volume: volume ? 0 : 0.65 })}
      >
        {volume ? <Volume2 /> : <VolumeX />}
      </Button>
      <input
        type="range"
        min="0"
        max="1"
        step=".01"
        value={volume}
        style={
          {
            "--progress": `${Math.round(volume * 100)}%`,
          } as React.CSSProperties
        }
        onChange={(e) => updatePlayerPrefs({ volume: Number(e.target.value) })}
        aria-label="音量"
        aria-valuetext={`${Math.round(volume * 100)}%`}
      />
      <span className="volume-value" aria-hidden="true">
        {Math.round(volume * 100)}%
      </span>
    </div>
  );
  const trackTable = (
    <>
      <section className="results">
        <div className="result-tools">
          <strong>
            {view === "favorites"
              ? "收藏曲目"
              : view === "playlists"
                ? "歌单曲目"
                : "搜索结果"}{" "}
            <span>{display.length}</span>
          </strong>
          <div className="filter-group" role="group" aria-label="音质筛选">
            {[
              ["all", "全部"],
              ["lossless", "仅无损"],
              ["lossy", "普通音质"],
              ["mp3", "MP3"],
              ["unknown", "音质未知"],
            ].map(([id, label]) => (
              <button
                key={id}
                className={filter === id ? "active" : ""}
                aria-pressed={filter === id}
                onClick={() => setFilter(id)}
              >
                {label}
              </button>
            ))}
          </div>
          <select
            aria-label="结果排序"
            value={sort}
            onChange={(e) => setSort(e.target.value)}
          >
            <option value="relevance">
              {view === "playlists"
                ? "歌单曲序"
                : view === "favorites"
                  ? "收藏顺序"
                  : "相关度"}
            </option>
            <option value="name">曲名</option>
            <option value="artist">歌手</option>
            <option value="source">音源</option>
            <option value="size-desc">大小：大到小</option>
            <option value="size-asc">大小：小到大</option>
            <option value="quality-desc">品质：高到低</option>
            <option value="quality-asc">品质：低到高</option>
            <option value="duration-asc">时长：短到长</option>
            <option value="duration-desc">时长：长到短</option>
          </select>
        </div>
        <table className="track-table">
          <thead>
            <tr>
              <th className="col-check">
                <input
                  aria-label="全选当前结果"
                  type="checkbox"
                  checked={
                    display.length > 0 &&
                    display.every((t) => selected.has(t.id))
                  }
                  onChange={(e) =>
                    setSelected(
                      e.target.checked
                        ? new Set(display.map((t) => t.id))
                        : new Set(),
                    )
                  }
                />
              </th>
              <th className="col-title">曲目</th>
              <th className="col-artist">歌手</th>
              <th className="col-album">专辑</th>
              <th className="col-source">音源</th>
              <th className="col-versions">音质版本</th>
              <th className="col-meta">时长 / 大小</th>
              <th className="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            {display.map((track, index) => {
              const primaryQuality = track.qualities[0];
              const primaryFriendly = primaryQuality
                ? formatFriendlyQuality(primaryQuality, track)
                : null;
              const extraCount = Math.max(0, track.qualities.length - 1);
              const sizeText = resolveTrackDisplaySize(track);
              return (
                <tr
                  key={`${track.id}:${index}`}
                  className={`${selected.has(track.id) ? "selected" : ""} ${current?.id === track.id ? "current" : ""}`}
                >
                  <td className="col-check">
                    <input
                      type="checkbox"
                      aria-label={`选择 ${track.title}`}
                      checked={selected.has(track.id)}
                      onChange={() => selectedToggle(track.id)}
                    />
                  </td>
                  <td className="col-title">
                    <div className="track">
                      <Cover
                        track={track}
                        playing={playing && current?.id === track.id}
                        onPlay={() => void play(track, display)}
                      />
                      <div className="song-copy">
                        <SongTitle
                          className="song-name"
                          track={track}
                          onPlay={() => void play(track, display)}
                        />
                        {!!track.episodes && (
                          <button
                            className="text-button"
                            onClick={() => {
                              searchGeneration.current++;
                              eventSource.current?.close();
                              eventSource.current = null;
                              searchJob.current = null;
                              setSearching(false);
                              void api<Track[]>(`/tracks/${track.id}/episodes`)
                                .then((items) => {
                                  setTracks(items);
                                  setSelected(new Set());
                                  setView("search");
                                })
                                .catch(fail);
                            }}
                          >
                            展开 {track.episodes} 个分集
                          </button>
                        )}
                        <div
                          className="song-meta mobile-only"
                          title={`${track.artist} / ${track.album}`}
                        >
                          {track.artist || "未知歌手"} ·{" "}
                          {track.album || "未知专辑"}
                        </div>
                      </div>
                    </div>
                  </td>
                  <td
                    className="col-artist"
                    data-label="歌手"
                    title={track.artist || "未知歌手"}
                  >
                    <span>{track.artist || "未知歌手"}</span>
                  </td>
                  <td
                    className="col-album"
                    data-label="专辑"
                    title={track.album || "未知专辑"}
                  >
                    <span>{track.album || "—"}</span>
                  </td>
                  <td className="track-source col-source" data-label="音源">
                    <span className="source-inline">
                      {sourceLabel(track.source)}
                    </span>
                  </td>
                  <td
                    className="track-versions col-versions"
                    data-label="音质版本"
                  >
                    {track.source === "navidrome" ? (
                      <span className="quality-inline">
                        {track.playback.codec?.toUpperCase() || "原格式"}
                      </span>
                    ) : (
                      <button
                        className="quality"
                        aria-label={`选择 ${track.title} 下载版本`}
                        title={
                          track.qualities.length
                            ? track.qualities
                                .map(
                                  (q) => formatFriendlyQuality(q, track).title,
                                )
                                .join(" / ")
                            : "点击查看可用音质"
                        }
                        onClick={() => chooseDownload([track])}
                      >
                        {primaryQuality && primaryFriendly ? (
                          <>
                            <span
                              className={`quality-inline ${primaryFriendly.isLossless ? "is-lossless" : ""}`}
                              title={
                                primaryQuality.evidence === "engine"
                                  ? [
                                      primaryFriendly.title,
                                      primaryFriendly.subtitle,
                                      "已确认可用版本",
                                    ]
                                      .filter(Boolean)
                                      .join(" · ")
                                  : [
                                      primaryFriendly.title,
                                      primaryFriendly.subtitle,
                                      primaryFriendly.needsLogin
                                        ? "需要账号权限"
                                        : "点击选择并确认下载音质",
                                    ]
                                      .filter(Boolean)
                                      .join(" · ")
                              }
                            >
                              {primaryFriendly.title}
                            </span>
                            {extraCount > 0 && (
                              <span className="quality-more">
                                · +{extraCount}
                              </span>
                            )}
                          </>
                        ) : (
                          <span className="quality-inline is-pending">
                            音质待确认
                          </span>
                        )}
                      </button>
                    )}
                  </td>
                  <td className="col-meta" data-label="时长 / 大小">
                    <div className="meta-stack">
                      <span className="duration">
                        {track.duration ? clock(track.duration) : "—"}
                      </span>
                      <small className="track-size">{sizeText}</small>
                    </div>
                  </td>
                  <td className="track-action col-actions">
                    <div className="row-action-cluster">
                      <button
                        className="row-play"
                        aria-label={`播放 ${track.title}`}
                        title="播放"
                        onClick={() => void play(track, display)}
                      >
                        <Play />
                        <span>播放</span>
                      </button>
                      <button
                        className={`icon-button favorite ${favIds.has(track.id) ? "on" : ""}`}
                        aria-label={`收藏 ${track.title}`}
                        title={favIds.has(track.id) ? "取消收藏" : "收藏"}
                        aria-pressed={favIds.has(track.id)}
                        onClick={() => void favorite(track)}
                      >
                        <Heart />
                        <span>收藏</span>
                      </button>
                      {track.source === "navidrome" ? (
                        <a
                          className="icon-button"
                          aria-label={`下载 ${track.title}`}
                          title="下载 Navidrome 原文件"
                          href={`/api/navidrome/stream/${encodeURIComponent(track.source_id)}`}
                          download={`${track.title}.${track.playback.codec || "audio"}`}
                        >
                          <Download />
                          <span>下载</span>
                        </a>
                      ) : (
                        <button
                          className="icon-button"
                          aria-label={`下载 ${track.title}`}
                          title="下载"
                          onClick={() => chooseDownload([track])}
                        >
                          <Download />
                          <span>下载</span>
                        </button>
                      )}
                      <button
                        className="icon-button"
                        aria-label={`加入播放列表 ${track.title}`}
                        title="加入待播"
                        onClick={() => void addToPlaylist([track])}
                      >
                        <Plus />
                        <span>待播</span>
                      </button>
                      {view === "playlists" && !activePlaylist?.read_only && (
                        <Button
                          label={`移出歌单 ${track.title}`}
                          onClick={() => void removeCollectionTrack(track)}
                        >
                          <Trash2 />
                          <span>移出</span>
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {view === "search" && searching && !display.length && (
          <div className="shimmer-list" aria-hidden="true">
            <div className="shimmer-row" />
            <div className="shimmer-row" />
            <div className="shimmer-row" />
            <div className="shimmer-row" />
          </div>
        )}
        {!display.length && !(view === "search" && searching) && (
          <div className="empty">
            {view === "favorites"
              ? "还没有收藏，点击曲目旁的爱心保存。"
              : tableTracks.length
                ? "没有符合筛选条件的曲目。"
                : view === "playlists"
                  ? "歌单暂无可用曲目，可在上方查看未导入原因。"
                  : "输入歌曲、歌手或专辑开始搜索。"}
          </div>
        )}
      </section>
      {view === "search" && (firstMs !== null || searching) && (
        <div className="result-foot">
          <span>
            {firstMs !== null
              ? `首个结果 ${(firstMs / 1000).toFixed(2)} 秒`
              : "正在搜索…"}
          </span>
        </div>
      )}
      <div className="selection-bar">
        <span>
          已选择 <b>{selection.length}</b> 首
        </span>
        <div>
          <button onClick={() => setSelected(new Set())}>取消选择</button>
          <button
            className="secondary"
            aria-label="加入播放列表"
            disabled={!selection.length}
            onClick={() => void addToPlaylist(selection)}
          >
            <Plus />
            加入待播
          </button>
          <button
            className="secondary"
            disabled={!selection.length}
            onClick={() => openAddToPlaylistModal(selection)}
          >
            <ListMusic />
            加入歌单
          </button>
          <button
            className="secondary"
            disabled={!selection.length || selection.length > 30}
            onClick={() => {
              setAssistantTracks(selection);
              setView("assistant");
            }}
          >
            交给 AI 助手
          </button>
          <button
            className="primary"
            disabled={!selection.length}
            onClick={() => chooseDownload(selection)}
          >
            <Download />
            批量下载
          </button>
        </div>
      </div>
    </>
  );
  return (
    <>
      <audio
        ref={audio}
        preload="none"
        onTimeUpdate={() => playbackClock.set(audio.current?.currentTime || 0)}
        onDurationChange={() =>
          setDuration(
            Number.isFinite(audio.current?.duration)
              ? audio.current!.duration
              : currentRef.current?.duration || 0,
          )
        }
        onPlay={() => setPlaying(true)}
        onPlaying={() => {
          if (currentRef.current)
            reportPlayback(
              currentRef.current,
              bufferingRef.current ? "recovered" : "started",
              bufferingRef.current ? "buffer" : "play",
            );
          bufferingRef.current = false;
        }}
        onStalled={() => {
          if (
            currentRef.current &&
            audio.current?.getAttribute("src") &&
            !bufferingRef.current
          ) {
            bufferingRef.current = true;
            reportPlayback(
              currentRef.current,
              "stalled",
              "buffer",
              "浏览器未能继续获取音频，等待网络恢复",
            );
          }
        }}
        onPause={() => setPlaying(false)}
        onEnded={() => {
          if (sleepStopAfterCurrentRef.current) {
            sleepStopAfterCurrentRef.current = false;
            setSleepTimer(null);
            setNotice("睡眠定时已结束，已停止播放");
            return;
          }
          nextRef.current();
        }}
        onError={() => {
          if (!audio.current?.getAttribute("src") || !currentRef.current)
            return;
          const code = audio.current.error?.code || 0;
          const reason =
            (
              {
                1: "播放请求被中止",
                2: "音频网络请求失败或传输中断",
                3: "音频解码失败，内容损坏或编码不支持",
                4: "音频地址不可用或浏览器不支持该格式",
              } as Record<number, string>
            )[code] || "未知媒体错误";
          reportPlayback(
            currentRef.current,
            "failed",
            "media",
            `${reason}；${audio.current.error?.message || "浏览器未提供更多信息"}`,
          );
          setLoadingPlay(false);
          setPlaying(false);
          setNotice(`${reason}，请查看运行日志或重新试听`);
        }}
      />
      <div className="app">
        <aside className="sidebar">
          <div className="brand">
            <AudioLines />
            MeloKeep
          </div>
          <div className="sidebar-capsules">
            <Navigation
              active={collectionView ? "playlists" : view}
              change={(name) => {
                setView(name);
                if (name === "playlists")
                  setPlaylistRefresh((value) => value + 1);
                setSourceFilter("all");
                setSelected(new Set());
              }}
              downloads={
                jobs.filter(
                  (job) => !["complete", "cancelled"].includes(job.status),
                ).length
              }
              hasUpdate={hasActiveUpdate}
              serverOrder={serverNavOrder}
              onOrderSave={(next) =>
                scheduleSavePreferences({ navigation_order: next })
              }
            />
          </div>
          <div className="sidebar-bottom">
            <a
              className="icon-button project-link"
              href="https://github.com/Agonie0v0/melokeep"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="项目地址（在新标签页打开）"
              title="GitHub 项目地址"
            >
              <Github aria-hidden="true" />
            </a>
            <ThemePicker
              controller={appearanceCtrl}
              openMore={() => openSettings("appearance")}
            />
            <button
              className="icon-button nav-order-toggle"
              aria-label="调整导航顺序"
              title="调整导航顺序"
              popoverTarget="navigation-order"
            >
              <SlidersHorizontal aria-hidden="true" />
            </button>
            {authEnabled && (
              <button
                className="icon-button logout"
                aria-label="退出登录"
                title="退出登录"
                onClick={() =>
                  void api("/auth/logout", "POST")
                    .then(() => api<{ required: boolean }>("/auth/status"))
                    .then((s) => {
                      setAuthEnabled(Boolean(s.required));
                      if (s.required) {
                        setLoginPass("");
                        setLoginError("");
                        setShowLoginPassword(false);
                        setLoginRequired(true);
                      } else {
                        setNotice("当前未启用登录认证");
                      }
                    })
                    .catch(fail)
                }
              >
                <LogOut aria-hidden="true" />
              </button>
            )}
          </div>
        </aside>
        <main className="workspace">
          {hasActiveUpdate &&
            view !== "player" &&
            !(view === "settings" && settingsTab === "update") && (
              <div className="update-banner" role="status">
                <div className="update-banner-text">
                  <ArrowUpCircle aria-hidden="true" />
                  <span>
                    发现新版本 <strong>{updateTargetLabel(updateInfo)}</strong>
                    {updateInfo?.ahead_by
                      ? `（落后 ${updateInfo.ahead_by} 个提交）`
                      : ""}
                  </span>
                </div>
                <div className="update-banner-actions">
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => openSettings("update")}
                  >
                    查看更新日志
                  </button>
                  <button
                    type="button"
                    className="update-banner-dismiss"
                    aria-label="忽略此版本更新提示"
                    onClick={() => dismissUpdate(updateKey)}
                  >
                    忽略此版本
                  </button>
                </div>
              </div>
            )}
          {view !== "player" && view !== "assistant" && (
            <h1 className="sr-only">
              {collectionView ? views.playlists : views[view]}
            </h1>
          )}
          {collectionView && (
            <nav className="library-tabs collection-tabs" aria-label="歌单分类">
              <button
                aria-current={view === "playlists" ? "page" : undefined}
                onClick={() => {
                  setView("playlists");
                  setSelected(new Set());
                }}
              >
                <ListMusic />
                我的歌单 · {allPlaylists.length}
              </button>
              <button
                aria-current={view === "favorites" ? "page" : undefined}
                onClick={() => {
                  setView("favorites");
                  setSelected(new Set());
                  resetCollectionFilters();
                }}
              >
                <Heart />
                我的收藏 · {favorites.length}
              </button>
              <button
                aria-current={view === "import" ? "page" : undefined}
                onClick={() => setView("import")}
              >
                <Plus />
                歌单导入
              </button>
            </nav>
          )}
          {(view === "search" || view === "favorites") && (
            <section className="view search-view">
              <div className="result-column single-deck">
                {view === "search" && (
                  <>
                    <form
                      className="searchbox"
                      onSubmit={(e) => {
                        e.preventDefault();
                        const keyword = query.trim();
                        if (keyword) {
                          const history = [
                            keyword,
                            ...recentSearches.filter((v) => v !== keyword),
                          ].slice(0, 8);
                          setRecentSearches(history);
                          try {
                            localStorage.setItem(
                              "melokeep-searches",
                              JSON.stringify(history),
                            );
                          } catch {
                            /* Storage is optional. */
                          }
                        }
                        void search();
                      }}
                    >
                      <Search />
                      <input
                        ref={searchInput}
                        aria-label="搜索音乐"
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        placeholder="搜索歌曲、歌手或专辑"
                        maxLength={200}
                      />
                      {searching && (
                        <button
                          type="button"
                          className="scope"
                          onClick={() => {
                            searchGeneration.current++;
                            eventSource.current?.close();
                            if (searchJob.current)
                              void api(
                                `/search/jobs/${searchJob.current}`,
                                "DELETE",
                              ).catch(fail);
                            searchJob.current = null;
                            eventSource.current = null;
                            setSearching(false);
                          }}
                        >
                          停止搜索
                        </button>
                      )}
                      <button
                        type="button"
                        className="scope"
                        onClick={() => openSettings()}
                      >
                        音源设置
                      </button>
                      <button
                        className="primary"
                        type="submit"
                        disabled={!query.trim() || !config}
                      >
                        搜索
                      </button>
                    </form>
                    {!tracks.length &&
                      !searching &&
                      !!recentSearches.length && (
                        <div className="recent-searches">
                          <History />
                          <span>最近搜索</span>
                          {recentSearches.map((keyword) => (
                            <button
                              key={keyword}
                              onClick={() => {
                                setQuery(keyword);
                                searchInput.current?.focus();
                              }}
                            >
                              {keyword}
                            </button>
                          ))}
                          <button
                            aria-label="清空搜索历史"
                            onClick={() => {
                              setRecentSearches([]);
                              try {
                                localStorage.removeItem("melokeep-searches");
                              } catch {
                                /* Storage is optional. */
                              }
                            }}
                          >
                            <X />
                          </button>
                        </div>
                      )}
                    {!tracks.length && !searching && !!favorites.length && (
                      <section
                        className="collection-shelf"
                        aria-label="收藏速览"
                      >
                        <div className="section-heading">
                          <h2>从收藏开始听</h2>
                          <button
                            className="text-button"
                            onClick={() => setView("favorites")}
                          >
                            查看全部
                            <ArrowRight />
                          </button>
                        </div>
                        <div className="collection-covers">
                          {favorites.slice(0, 6).map((track) => (
                            <button
                              key={track.id}
                              onClick={() => void play(track, favorites)}
                            >
                              <Cover track={track} large />
                              <strong>{track.title}</strong>
                              <small>{track.artist}</small>
                            </button>
                          ))}
                        </div>
                      </section>
                    )}
                    {config && !hasPluginSource && !dismissedPluginHint && (
                      <div className="search-plugin-hint" role="note">
                        <span>
                          当前使用内置开放许可与官方试听音源；如需接入更多公开音频或自托管源，可前往「设置
                          → 音源与下载」安装自定义 JS 插件。
                        </span>
                        <div className="search-plugin-hint-actions">
                          <button
                            type="button"
                            className="text-button"
                            onClick={() => openSettings("engine")}
                          >
                            前往设置
                          </button>
                          <button
                            type="button"
                            className="icon-button"
                            aria-label="关闭音源提示"
                            onClick={() => {
                              setDismissedPluginHint(true);
                              try {
                                localStorage.setItem(
                                  "melokeep-dismissed-plugin-hint",
                                  "1",
                                );
                              } catch {
                                /* Storage is optional. */
                              }
                            }}
                          >
                            <X />
                          </button>
                        </div>
                      </div>
                    )}
                    <div className="sources">
                      <button
                        className={`source ${sourceFilter === "all" ? "active" : ""}`}
                        aria-pressed={sourceFilter === "all"}
                        onClick={() => setSourceFilter("all")}
                      >
                        全部音源<span>{tracks.length}</span>
                      </button>
                      {sources
                        .filter((s) => config?.sources.includes(s.id))
                        .map((s) => {
                          const st = statuses[s.id]?.state;
                          return (
                            <button
                              key={s.id}
                              className={`source ${sourceFilter === s.id ? "active" : ""} ${st === "failed" ? "failed" : ""}`}
                              aria-pressed={sourceFilter === s.id}
                              onClick={() => setSourceFilter(s.id)}
                            >
                              {(st === "searching" || st === "failed") && (
                                <i aria-hidden="true" />
                              )}
                              {s.label}
                              <span>
                                {st === "searching"
                                  ? "搜索中"
                                  : tracks.filter((t) => t.source === s.id)
                                      .length}
                              </span>
                            </button>
                          );
                        })}
                    </div>
                  </>
                )}
                {trackTable}
              </div>
            </section>
          )}
          {view === "player" && (
            <NowPlayingStage
              current={current}
              playing={playing}
              loadingPlay={loadingPlay}
              duration={duration}
              shuffle={shuffle}
              repeat={repeat}
              isFavorite={Boolean(current && favIds.has(current.id))}
              inQueue={Boolean(
                current && playlist.some((item) => item.id === current.id),
              )}
              playbackLabel={playbackLabel}
              sourceName={current ? sourceLabel(current.source) : ""}
              returnLabel={
                returnView === "search"
                  ? "返回搜索结果"
                  : `返回${["playlists", "favorites", "import"].includes(returnView) ? views.playlists : views[returnView]}`
              }
              lines={lines}
              lyricText={lyricText}
              follow={follow}
              largeLyrics={largeLyrics}
              lyricsFontSize={lyricsFontSize}
              lyricsActiveColor={lyricsActiveColor}
              lyricsInactiveOpacity={lyricsInactiveOpacity}
              lyricsAlign={lyricsAlign}
              lyricsOffsetMs={lyricsOffsetMs}
              sleepTimer={sleepTimer}
              playlist={playlist}
              queueManaging={queueManaging}
              queueSaving={queueSaving}
              volumeControl={volumeControl}
              queueListContent={renderQueueList(false)}
              onBack={() => setView(returnView)}
              onTogglePlay={() => void toggle()}
              onSeek={seek}
              onStep={step}
              onToggleShuffle={() => updatePlayerPrefs({ shuffle: !shuffle })}
              onToggleRepeat={() => updatePlayerPrefs({ repeat: !repeat })}
              onFavorite={() => current && void favorite(current)}
              onDownload={() => current && void chooseDownload([current])}
              onAddToQueue={() => current && void addToPlaylist([current])}
              onAddToPlaylist={() =>
                current && openAddToPlaylistModal([current])
              }
              onToggleFollow={() => updatePlayerPrefs({ follow: !follow })}
              onToggleLargeLyrics={() =>
                updatePlayerPrefs({ largeLyrics: !largeLyrics })
              }
              onChangeLyricsFontSize={(nextSize) =>
                updatePlayerPrefs({ lyricsFontSize: nextSize })
              }
              onChangeLyricsActiveColor={(nextColor) =>
                updatePlayerPrefs({ lyricsActiveColor: nextColor })
              }
              onChangeLyricsInactiveOpacity={(nextOpacity) =>
                updatePlayerPrefs({ lyricsInactiveOpacity: nextOpacity })
              }
              onChangeLyricsAlign={(nextAlign) =>
                updatePlayerPrefs({ lyricsAlign: nextAlign })
              }
              onResetLyricsStyle={() =>
                updatePlayerPrefs(
                  {
                    largeLyrics: DEFAULT_PLAYER_PREFS.largeLyrics,
                    lyricsFontSize: DEFAULT_PLAYER_PREFS.lyricsFontSize,
                    lyricsActiveColor: DEFAULT_PLAYER_PREFS.lyricsActiveColor,
                    lyricsInactiveOpacity:
                      DEFAULT_PLAYER_PREFS.lyricsInactiveOpacity,
                    lyricsAlign: DEFAULT_PLAYER_PREFS.lyricsAlign,
                  },
                  { debounceMs: 0 },
                )
              }
              onChangeLyricsOffsetMs={handleChangeLyricsOffsetMs}
              onSetSleepTimer={handleSetSleepTimer}
              onToggleQueueManaging={toggleQueueManaging}
              onPlayQueueTrack={(track) => void play(track, playlist, true)}
              onPlayNextQueueTrack={(track) => void playNextInQueue(track)}
              onRemoveQueueTrack={(id) => void removeFromPlaylist([id])}
              onQuickSearch={(kw) => void search(kw)}
            />
          )}
          {view === "queue" && (
            <section className="view queue-view">
              <div className="queue-toolbar">
                <div className="queue-toolbar-left">
                  <label className="queue-status-filter">
                    <span className="sr-only">状态</span>
                    <select
                      aria-label="筛选下载状态"
                      value={queueStatus}
                      onChange={(e) => {
                        setQueueStatus(e.target.value);
                        setSelectedJobs([]);
                      }}
                    >
                      <option value="all">全部状态（{jobs.length}）</option>
                      {Object.entries(statusLabels).map(([status, label]) => (
                        <option key={status} value={status}>
                          {label}（
                          {jobs.filter((job) => job.status === status).length}）
                        </option>
                      ))}
                    </select>
                  </label>
                  <span className="queue-count muted" aria-live="polite">
                    {visibleJobs.length} / {jobs.length} 首
                  </span>
                </div>
                <div className="queue-toolbar-actions">
                  {visibleJobs.some((job) =>
                    ["complete", "cancelled"].includes(job.status),
                  ) && (
                    <button
                      type="button"
                      className="text-button queue-clear-btn"
                      disabled={managing}
                      onClick={() =>
                        confirmRemoval(
                          visibleJobs.filter((job) =>
                            ["complete", "cancelled"].includes(job.status),
                          ),
                        )
                      }
                    >
                      清理已结束记录
                    </button>
                  )}
                  <button
                    type="button"
                    className="secondary"
                    onClick={() =>
                      void api<DownloadJob[]>("/downloads")
                        .then(setJobs)
                        .catch(fail)
                    }
                  >
                    刷新进度
                  </button>
                </div>
              </div>
              {chosenJobs.length > 0 && (
                <div
                  className="management-toolbar queue-batch-bar"
                  role="region"
                  aria-label="已选下载任务操作"
                >
                  <label className="check-label">
                    <input
                      type="checkbox"
                      aria-label="全选下载任务"
                      checked={
                        visibleJobs.length > 0 &&
                        chosenJobs.length === visibleJobs.length
                      }
                      disabled={managing}
                      onChange={(e) =>
                        setSelectedJobs(
                          e.target.checked
                            ? visibleJobs.map((job) => job.id)
                            : [],
                        )
                      }
                    />
                    全选
                  </label>
                  <span className="muted">已选 {chosenJobs.length} 项</span>
                  <button
                    type="button"
                    className="secondary"
                    disabled={
                      managing || !chosenJobs.some((job) => !stopped(job))
                    }
                    onClick={() =>
                      void manageJobs(
                        "pause",
                        chosenJobs.filter((job) => !stopped(job)),
                      )
                    }
                  >
                    暂停选中
                  </button>
                  <button
                    type="button"
                    className="secondary"
                    disabled={
                      managing ||
                      !chosenJobs.some((job) =>
                        ["paused", "failed", "cancelled"].includes(job.status),
                      )
                    }
                    onClick={() =>
                      void manageJobs(
                        "resume",
                        chosenJobs.filter((job) =>
                          ["paused", "failed", "cancelled"].includes(
                            job.status,
                          ),
                        ),
                      )
                    }
                  >
                    继续选中
                  </button>
                  <button
                    type="button"
                    className="secondary"
                    disabled={
                      managing ||
                      !chosenJobs.some(
                        (job) =>
                          !["complete", "cancelled"].includes(job.status),
                      )
                    }
                    onClick={() =>
                      void manageJobs(
                        "cancel",
                        chosenJobs.filter(
                          (job) =>
                            !["complete", "cancelled"].includes(job.status),
                        ),
                      )
                    }
                  >
                    取消选中
                  </button>
                  <button
                    type="button"
                    className="secondary"
                    disabled={
                      managing ||
                      !chosenJobs.length ||
                      !chosenJobs.every(stopped)
                    }
                    onClick={() => confirmRemoval(chosenJobs)}
                  >
                    移除选中记录
                  </button>
                  <button
                    type="button"
                    className="text-button"
                    disabled={managing}
                    onClick={() => setSelectedJobs([])}
                  >
                    取消选择
                  </button>
                </div>
              )}
              {managementError && <p role="alert">{managementError}</p>}
              {visibleJobs.length > 0 && (
                <div className="download-list">
                  {visibleJobs.map((job) => (
                    <div className="download-row" key={job.id}>
                      <div className="job-selection">
                        <input
                          type="checkbox"
                          aria-label={`选择下载任务 ${job.track.title}`}
                          checked={selectedJobs.includes(job.id)}
                          disabled={managing}
                          onChange={(e) =>
                            setSelectedJobs(
                              e.target.checked
                                ? [...selectedJobs, job.id]
                                : selectedJobs.filter((id) => id !== job.id),
                            )
                          }
                        />
                        <Cover
                          track={job.track}
                          onPlay={() => void play(job.track)}
                        />
                      </div>
                      <div className="download-row-main">
                        <strong>
                          <SongTitle
                            track={job.track}
                            onPlay={() => void play(job.track)}
                          />
                        </strong>
                        <small>
                          {job.track.artist} · {job.quality_label}
                          {job.download_source &&
                            ` · ${sources.find((source) => source.id === job.download_source)?.label || job.download_source}`}
                          {job.total_bytes || job.bytes
                            ? ` · ${formatFileSizeBytes(job.bytes)}${job.total_bytes ? ` / ${formatFileSizeBytes(job.total_bytes)}` : ""}`
                            : ""}
                          {job.detail && ` · ${job.detail}`}
                        </small>
                        {job.status !== "complete" && (
                          <div className="progress">
                            <span
                              style={{
                                width: job.total_bytes
                                  ? `${Math.min(100, (job.bytes / job.total_bytes) * 100)}%`
                                  : "0%",
                              }}
                            />
                          </div>
                        )}
                        {job.processing && (
                          <small
                            className={`processing-inline is-${job.processing.status}`}
                            role="status"
                          >
                            {processingLabels[job.processing.status] ||
                              job.processing.status}{" "}
                            · {job.processing.detail}
                          </small>
                        )}
                        {job.file && (
                          <a
                            href={`/api/files/content?path=${encodeURIComponent(job.file)}`}
                            download
                          >
                            下载到本机
                          </a>
                        )}
                      </div>
                      <span
                        className={`state status-dot-label is-${job.status} ${job.status === "complete" ? "done" : ""}`}
                      >
                        <i className="status-dot" aria-hidden="true" />
                        {statusLabels[job.status]}
                      </span>
                      <div className="job-actions">
                        {["failed", "paused", "cancelled"].includes(
                          job.status,
                        ) ? (
                          <button
                            className="secondary"
                            disabled={managing}
                            onClick={() =>
                              void api<DownloadJob>(
                                `/downloads/${job.id}/${job.status === "cancelled" ? "retry" : "resume"}`,
                                "POST",
                              )
                                .then((updated) =>
                                  setJobs((old) =>
                                    old.map((j) =>
                                      j.id === updated.id ? updated : j,
                                    ),
                                  ),
                                )
                                .catch(fail)
                            }
                          >
                            {job.status === "cancelled"
                              ? "重新下载"
                              : "继续下载"}
                          </button>
                        ) : (
                          job.status !== "complete" && (
                            <>
                              <Button
                                label="暂停下载"
                                disabled={managing}
                                onClick={() =>
                                  void api(`/downloads/${job.id}/pause`, "POST")
                                    .then(() =>
                                      api<DownloadJob[]>("/downloads"),
                                    )
                                    .then(setJobs)
                                    .catch(fail)
                                }
                              >
                                <Pause />
                              </Button>
                              <Button
                                label="取消下载"
                                disabled={managing}
                                onClick={() =>
                                  void api(
                                    `/downloads/${job.id}/cancel`,
                                    "POST",
                                  )
                                    .then(() =>
                                      api<DownloadJob[]>("/downloads"),
                                    )
                                    .then(setJobs)
                                    .catch(fail)
                                }
                              >
                                <X />
                              </Button>
                            </>
                          )
                        )}
                        {stopped(job) && (
                          <Button
                            label={`移除下载记录 ${job.track.title}`}
                            disabled={managing}
                            onClick={() => confirmRemoval([job])}
                          >
                            <Trash2 />
                          </Button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
              {!jobs.length && (
                <div className="empty">
                  选择曲目和音质后，下载任务将在这里显示。
                </div>
              )}
              {!!jobs.length && !visibleJobs.length && (
                <div className="empty" role="status">
                  当前没有“{statusLabels[queueStatus]}
                  ”的任务，可切换状态查看其他下载。
                </div>
              )}
            </section>
          )}
          {view === "playlists" && (
            <PlaylistLibrary
              lists={allPlaylists}
              syncError={remotePlaylistError}
              sync={() => {
                setPlaylistRefresh((value) => value + 1);
                void refreshPlaylists().catch(fail);
              }}
              current={activePlaylist}
              sourceName={(id) => sourceLabel(id, true)}
              open={(id) => void openPlaylist(id)}
              back={() => {
                setActivePlaylist(null);
                setSelected(new Set());
                void refreshPlaylists().catch(fail);
              }}
              onImport={() => setView("import")}
              onCreate={() => openAddToPlaylistModal([])}
              changed={(list) => {
                setActivePlaylist(list);
                void refreshPlaylists().catch(fail);
                if (list.source === "navidrome")
                  setPlaylistRefresh((value) => value + 1);
              }}
              deleted={(id) => {
                setActivePlaylist(null);
                setSavedPlaylists((previous) =>
                  previous.filter((l) => l.id !== id),
                );
                setRemotePlaylists((previous) =>
                  previous.filter((list) => list.id !== id),
                );
                setSelected(new Set());
              }}
              playAll={(items) => void playCollection(items)}
              queueAll={(items) => void addToPlaylist(items)}
              downloadAll={(items) => void chooseDownload(items)}
              search={(keyword) => {
                resetCollectionFilters();
                setQuery(keyword);
                void search(keyword);
              }}
              logs={() => setView("logs")}
            >
              {trackTable}
            </PlaylistLibrary>
          )}
          {view === "import" && (
            <section className="view">
              <form
                className="form-panel"
                onSubmit={(e) => {
                  e.preventDefault();
                  void importList();
                }}
              >
                <h2>导入平台歌单</h2>
                {!playlistSources.length ? (
                  <div className="import-empty-guide">
                    <p className="setting-help">
                      当前可用音源未声明歌单导入能力。请前往「设置 →
                      音源与下载」启用支持歌单的内置音源（如
                      Jamendo），或按插件规范安装支持歌单导入的自定义 JS 插件。
                    </p>
                    <button
                      type="button"
                      className="secondary"
                      onClick={() => openSettings("engine")}
                    >
                      前往音源与插件设置
                    </button>
                  </div>
                ) : (
                  <>
                    <label>
                      歌单名称（可选）
                      <input
                        value={importName}
                        onChange={(e) => setImportName(e.target.value)}
                        maxLength={100}
                        placeholder="例如：通勤时光"
                      />
                    </label>
                    <label>
                      歌单音源
                      <select
                        value={importSource}
                        onChange={(e) => setImportSource(e.target.value)}
                      >
                        {playlistSources.map((s) => (
                          <option value={s.id} key={s.id}>
                            {s.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      歌单链接 / ID
                      <input
                        value={importId}
                        onChange={(e) => setImportId(e.target.value)}
                        placeholder="输入公开歌单链接或 ID"
                        required
                      />
                    </label>
                    <p className="setting-help">
                      私有歌单或受限访问需先在音源设置中配置对应账号或插件参数。
                    </p>
                    <button
                      className="primary"
                      disabled={busy || !importSource || !importId.trim()}
                    >
                      {busy ? "正在解析并保存…" : "导入并保存歌单"}
                    </button>
                  </>
                )}
              </form>
            </section>
          )}
          {(view === "files" || view === "organize") && (
            <LibraryWorkspace
              mode={view === "files" ? "library" : "processing"}
              tab={view === "files" ? libraryTab : processingTab}
              setTab={view === "files" ? setLibraryTab : setProcessingTab}
              openSettings={openSettings}
              initialDirectoryMode={workbenchTarget?.directoryMode}
              initialDirectory={workbenchTarget?.directory}
              initialDirectoryKey={workbenchTarget?.nonce}
              process={(items) => {
                setSelectedFiles(items.map((file) => file.path));
                setWorkbenchTarget((prev) => ({
                  directoryMode: false,
                  directory: prev?.directory ?? "incoming",
                  nonce: (prev?.nonce ?? 0) + 1,
                }));
                setProcessingTab("workbench");
                setView("organize");
              }}
              processDirectory={(scopeDir) => {
                setWorkbenchTarget((prev) => ({
                  directoryMode: true,
                  directory: scopeDir,
                  nonce: (prev?.nonce ?? 0) + 1,
                }));
                setProcessingTab("workbench");
                setView("organize");
              }}
              files={files}
              selected={selectedFiles}
              setSelected={setSelectedFiles}
              current={current}
              busy={managing}
              error={managementError}
              hasMore={filesCursor !== null}
              loadingMore={filesLoadingMore}
              indexing={filesIndexing}
              serverFolders={libraryFolders}
              serverTreeWidth={serverTreeWidth}
              serverNavidromeView={serverNavidromeView}
              loadMore={() => {
                if (filesCursor && !filesLoadingMore) {
                  void loadLibraryFiles({ cursor: filesCursor });
                }
              }}
              onServerFilterChange={(filters) => {
                const queryChanged =
                  filters.query !== libraryFiltersRef.current.query;
                libraryFiltersRef.current = filters;
                if (libraryFilterTimerRef.current) {
                  clearTimeout(libraryFilterTimerRef.current);
                  libraryFilterTimerRef.current = null;
                }
                if (queryChanged) {
                  libraryFilterTimerRef.current = setTimeout(() => {
                    void loadLibraryFiles({ cursor: null, filters });
                  }, 250);
                } else {
                  void loadLibraryFiles({ cursor: null, filters });
                }
              }}
              onNotice={setNotice}
              refresh={async () => {
                await loadLibraryFiles({ cursor: null });
              }}
              rescan={() => refreshLibraryFiles()}
              rescanning={filesRefreshing}
              play={(track, context) => void play(track, context)}
              playAll={(items) => void playCollection(items)}
              queue={(items) => void addToPlaylist(items)}
              savePlaylist={openAddToPlaylistModal}
              remove={confirmFileDeletion}
              remoteDeleted={(ids) => {
                const idSet = new Set(ids);
                playbackQueue.current = playbackQueue.current.filter(
                  (track) => !idSet.has(track.id),
                );
                shuffleOrderRef.current = {
                  ...shuffleOrderRef.current,
                  tracks: shuffleOrderRef.current.tracks.filter(
                    (track) => !idSet.has(track.id),
                  ),
                };
                setPlaylist((previous) =>
                  previous.filter((track) => !idSet.has(track.id)),
                );
                setFavorites((previous) =>
                  previous.filter((track) => !idSet.has(track.id)),
                );
                setActivePlaylist((previous) =>
                  previous
                    ? {
                        ...previous,
                        tracks: previous.tracks.filter(
                          (track) => !idSet.has(track.id),
                        ),
                      }
                    : null,
                );
                if (current && idSet.has(current.id)) {
                  ++playGeneration.current;
                  audio.current?.pause();
                  audio.current?.removeAttribute("src");
                  audio.current?.load();
                  setCurrent(null);
                  setLoadingPlay(false);
                }
                setPlaylistRefresh((value) => value + 1);
                void loadLibraryFiles({ cursor: null });
              }}
              changed={(file) => {
                setFiles((previous) =>
                  previous.map((item) =>
                    item.path === file.path ? file : item,
                  ),
                );
                if (file.track) {
                  const updated = file.track;
                  setPlaylist((previous) =>
                    previous.map((track) =>
                      track.id === updated.id ? updated : track,
                    ),
                  );
                  setFavorites((previous) =>
                    previous.map((track) =>
                      track.id === updated.id ? updated : track,
                    ),
                  );
                  setCurrent((previous) =>
                    previous?.id === updated.id ? updated : previous,
                  );
                }
              }}
            />
          )}
          {view === "settings" && config && (
            <SettingsWorkspace
              sources={sources}
              saved={load}
              tab={settingsTab}
              setTab={setSettingsTab}
              updateInfo={updateInfo}
              checkingUpdate={checkingUpdate}
              updateError={updateError}
              onCheckUpdate={checkSystemUpdate}
              appearance={appearanceCtrl}
            />
          )}
          {view === "logs" && <RuntimeLogs active={!loginRequired} />}
          {view === "assistant" && !loginRequired && (
            <AIAssistant
              openSettings={() => openSettings("ai")}
              changed={load}
              navigate={setView}
              play={(track) => void play(track)}
              download={(track) => void chooseDownload([track])}
              contextTracks={assistantTracks}
              clearContext={() => setAssistantTracks([])}
              jobs={jobs}
              playlists={savedPlaylists}
            />
          )}
          <PlayerDock
            view={view}
            current={current}
            firstQueuedTrack={playlist[0] ?? null}
            playing={playing}
            loadingPlay={loadingPlay}
            duration={duration}
            shuffle={shuffle}
            repeat={repeat}
            isFavorite={Boolean(current && favIds.has(current.id))}
            ambientGlow={appearanceCtrl.appearance.ambientGlow}
            volume={volume}
            volumeControl={volumeControl}
            lines={lines}
            lyricText={lyricText}
            follow={follow}
            largeLyrics={largeLyrics}
            lyricsFontSize={lyricsFontSize}
            lyricsActiveColor={lyricsActiveColor}
            lyricsInactiveOpacity={lyricsInactiveOpacity}
            lyricsAlign={lyricsAlign}
            lyricsOffsetMs={lyricsOffsetMs}
            playbackLabel={playbackLabel}
            drawerOpen={drawerOpen}
            drawerTab={drawerTab}
            playlistCount={playlist.length}
            queueManaging={queueManaging}
            queueSaving={queueSaving}
            queueListContent={renderQueueList(false)}
            onSetViewPlayer={() => setView("player")}
            onPlayFirstQueued={() =>
              playlist[0] && void play(playlist[0], playlist, true)
            }
            onTogglePlay={() => void toggle()}
            onSeek={seek}
            onStep={step}
            onToggleShuffle={() => updatePlayerPrefs({ shuffle: !shuffle })}
            onToggleRepeat={() => updatePlayerPrefs({ repeat: !repeat })}
            onFavorite={() => current && void favorite(current)}
            onDownload={() => current && void chooseDownload([current])}
            onAddToPlaylist={() => current && openAddToPlaylistModal([current])}
            onToggleFollow={() => updatePlayerPrefs({ follow: !follow })}
            onToggleLargeLyrics={() =>
              updatePlayerPrefs({ largeLyrics: !largeLyrics })
            }
            onToggleDrawer={(open) =>
              setDrawerOpen((prev) =>
                typeof open === "boolean" ? open : !prev,
              )
            }
            onSelectDrawerTab={setDrawerTab}
            onToggleQueueManaging={toggleQueueManaging}
            onOpenQueueModal={() => setShowPlaylist(true)}
          />
        </main>
      </div>
      {notice && (
        <div id="toast" role="status">
          {notice}
          <button aria-label="关闭提示" onClick={() => setNotice("")}>
            <X />
          </button>
        </div>
      )}
      {confirmation && (
        <Modal
          title={confirmation.title}
          close={managing ? undefined : () => setConfirmation(null)}
        >
          <p>{confirmation.description}</p>
          {managementError && <p role="alert">{managementError}</p>}
          <div className="management-toolbar">
            <button
              className="secondary"
              disabled={managing}
              onClick={() => setConfirmation(null)}
            >
              取消
            </button>
            <button
              className="primary danger"
              disabled={managing}
              onClick={() => void confirmation.run()}
            >
              {managing ? "正在处理…" : "确认操作"}
            </button>
          </div>
        </Modal>
      )}
      {qualityTracks && (
        <Modal
          title={qualityTracks.length > 1 ? "批量选择下载音质" : "选择下载音质"}
          close={() => setQualityTracks(null)}
        >
          <div className="quality-list">
            {qualityTracks.map((track) => (
              <fieldset key={track.id}>
                <legend>
                  {track.title} · {track.artist}
                </legend>
                {track.qualities.length ? (
                  track.qualities.map((q) => {
                    const friendly = formatFriendlyQuality(q, track);
                    return (
                      <label className="version-option" key={q.id}>
                        <input
                          type="radio"
                          name={track.id}
                          value={q.id}
                          checked={qualities[track.id] === q.id}
                          onChange={() =>
                            setQualities((previous) => ({
                              ...previous,
                              [track.id]: q.id,
                            }))
                          }
                        />
                        <span className="version-option-main">
                          <span className="version-option-title">
                            {friendly.title}
                          </span>
                          {friendly.bitrateText && (
                            <span className="version-option-bitrate">
                              {friendly.bitrateText}
                            </span>
                          )}
                          {friendly.badges.map((badge) => (
                            <small
                              key={badge}
                              className={`version-option-badge ${
                                badge === "仅试听片段"
                                  ? "is-preview"
                                  : "is-login"
                              }`}
                            >
                              {badge}
                            </small>
                          ))}
                        </span>
                        <span
                          className={`version-option-size ${
                            friendly.sizeText ? "" : "is-unknown"
                          }`}
                        >
                          {friendly.sizeText || "—"}
                        </span>
                      </label>
                    );
                  })
                ) : (
                  <p>音源未提供音质信息，请选择其他曲目或音源。</p>
                )}
              </fieldset>
            ))}
          </div>
          <div className="dialog-actions">
            <button onClick={() => setQualityTracks(null)}>取消</button>
            <button
              className="primary"
              disabled={busy || qualityTracks.some((t) => !qualities[t.id])}
              onClick={() => void download()}
            >
              {busy ? "正在加入…" : "加入下载队列"}
            </button>
          </div>
        </Modal>
      )}
      {showPlaylist && (
        <Modal title="播放列表" close={() => setShowPlaylist(false)}>
          {renderQueueList(true)}
        </Modal>
      )}
      {playlistModalTracks && (
        <Modal
          title={playlistModalTracks.length ? "加入歌单" : "新建歌单"}
          close={busy ? undefined : () => setPlaylistModalTracks(null)}
        >
          {playlistModalTracks.length > 0 && (
            <p>已选 {playlistModalTracks.length} 首曲目。</p>
          )}
          <div className="playlist-picker-list">
            {!!playlistModalTracks.length &&
              savedPlaylists.map((list) => (
                <label className="version-option" key={list.id}>
                  <input
                    type="radio"
                    name="target-playlist"
                    value={list.id}
                    checked={targetPlaylistId === list.id}
                    onChange={() => setTargetPlaylistId(list.id)}
                  />
                  <span>
                    {list.name}
                    <small>{list.track_count} 首曲目</small>
                  </span>
                </label>
              ))}
            <label className="version-option">
              <input
                type="radio"
                name="target-playlist"
                value="new"
                checked={targetPlaylistId === "new"}
                onChange={() => setTargetPlaylistId("new")}
              />
              <span>新建歌单</span>
            </label>
            {playlistModalTracks.every(
              (track) => track.source === "navidrome",
            ) && (
              <>
                {!!playlistModalTracks.length &&
                  remotePlaylists
                    .filter((list) => !list.read_only)
                    .map((list) => (
                      <label className="version-option" key={list.id}>
                        <input
                          type="radio"
                          name="target-playlist"
                          value={list.id}
                          checked={targetPlaylistId === list.id}
                          onChange={() => setTargetPlaylistId(list.id)}
                        />
                        <span>
                          {list.name}
                          <small>Navidrome · {list.track_count} 首曲目</small>
                        </span>
                      </label>
                    ))}
                <label className="version-option">
                  <input
                    type="radio"
                    name="target-playlist"
                    value="new-navidrome"
                    checked={targetPlaylistId === "new-navidrome"}
                    onChange={() => setTargetPlaylistId("new-navidrome")}
                  />
                  <span>新建 Navidrome 歌单</span>
                </label>
              </>
            )}
          </div>
          {(targetPlaylistId === "new" ||
            targetPlaylistId === "new-navidrome") && (
            <label className="playlist-new-field">
              <span>新歌单名称</span>
              <input
                aria-label="新歌单名称"
                placeholder="例如：清晨纯音、通勤歌单"
                maxLength={100}
                value={newPlaylistName}
                onChange={(e) => setNewPlaylistName(e.target.value)}
                autoFocus
              />
            </label>
          )}
          <div className="dialog-actions">
            <button
              type="button"
              disabled={busy}
              onClick={() => setPlaylistModalTracks(null)}
            >
              取消
            </button>
            <button
              type="button"
              className="primary"
              disabled={
                busy ||
                ((targetPlaylistId === "new" ||
                  targetPlaylistId === "new-navidrome") &&
                  !newPlaylistName.trim())
              }
              onClick={() => void submitAddToSavedPlaylist()}
            >
              {busy
                ? "正在保存…"
                : playlistModalTracks.length
                  ? "确定加入"
                  : "创建歌单"}
            </button>
          </div>
        </Modal>
      )}
      {loginRequired && (
        <Modal
          title="登录音乐库"
          className="login-dialog"
          heading={
            <header className="login-header">
              <div className="login-brand">
                <AudioLines aria-hidden="true" />
                <span>MeloKeep</span>
              </div>
              <h2>登录音乐库</h2>
            </header>
          }
        >
          <form
            className="login-form"
            aria-busy={loginBusy}
            onSubmit={async (e) => {
              e.preventDefault();
              if (loginBusy) return;
              setLoginBusy(true);
              setLoginError("");
              try {
                await api("/auth/login", "POST", {
                  username: loginUser,
                  password: loginPass,
                });
                setAuthEnabled(true);
                setLoginRequired(false);
                setLoginPass("");
                setShowLoginPassword(false);
                await load().catch(fail);
              } catch (error) {
                setLoginError(
                  error instanceof Error ? error.message : String(error),
                );
              } finally {
                setLoginBusy(false);
              }
            }}
          >
            <div className="login-field">
              <label htmlFor="login-username">用户名</label>
              <input
                id="login-username"
                className="login-input"
                autoFocus
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                placeholder="输入用户名"
                value={loginUser}
                disabled={loginBusy}
                aria-invalid={!!loginError}
                aria-describedby={loginError ? "login-error" : undefined}
                onChange={(e) => {
                  setLoginUser(e.target.value);
                  setLoginError("");
                }}
                required
              />
            </div>
            <div className="login-field">
              <label htmlFor="login-password">密码</label>
              <div className="login-password">
                <input
                  id="login-password"
                  className="login-input"
                  autoComplete="current-password"
                  type={showLoginPassword ? "text" : "password"}
                  placeholder="输入密码"
                  value={loginPass}
                  disabled={loginBusy}
                  aria-invalid={!!loginError}
                  aria-describedby={loginError ? "login-error" : undefined}
                  onChange={(e) => {
                    setLoginPass(e.target.value);
                    setLoginError("");
                  }}
                  required
                />
                <button
                  type="button"
                  className="login-reveal"
                  aria-label={showLoginPassword ? "隐藏密码" : "显示密码"}
                  aria-pressed={showLoginPassword}
                  aria-controls="login-password"
                  disabled={loginBusy}
                  onClick={() => setShowLoginPassword(!showLoginPassword)}
                >
                  {showLoginPassword ? (
                    <EyeOff aria-hidden="true" />
                  ) : (
                    <Eye aria-hidden="true" />
                  )}
                </button>
              </div>
            </div>
            {loginError && (
              <p className="login-error" id="login-error" role="alert">
                <CircleAlert aria-hidden="true" />
                <span>{loginError}</span>
              </p>
            )}
            <button
              className="primary login-submit"
              type="submit"
              disabled={loginBusy}
            >
              {loginBusy ? "正在登录…" : "登录"}
            </button>
          </form>
        </Modal>
      )}
    </>
  );
}
