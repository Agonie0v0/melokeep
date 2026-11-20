import { useEffect, useId, useRef, useState } from "react";
import {
  CheckSquare,
  ChevronDown,
  ChevronRight,
  Folder,
  FolderInput,
  FolderPlus,
  MoreHorizontal,
  Play,
  Pencil,
  Trash2,
  Download,
  Music2,
  WandSparkles,
  ListMusic,
} from "lucide-react";
import { Button, Cover, Modal, SongTitle, SongActions } from "./components";
import { api } from "./api";
import { extractScopeDirFromFolderKey } from "./library-utils";
import { clock } from "./lyrics";
import { scheduleSavePreferences } from "./preferences";
import type { LibraryFile, Track } from "./types";
import type { LibraryAction } from "./LibraryWorkspace";
import { tagLabels } from "./LibrarySettings";

const TREE_WIDTH_STORAGE_KEY = "melokeep-library-tree-width";
const MIN_TREE_WIDTH = 200;
const MAX_TREE_WIDTH = 480;

function readSavedTreeWidth(): number | null {
  try {
    const raw = Number(localStorage.getItem(TREE_WIDTH_STORAGE_KEY));
    if (
      Number.isFinite(raw) &&
      raw >= MIN_TREE_WIDTH &&
      raw <= MAX_TREE_WIDTH
    ) {
      return Math.round(raw);
    }
  } catch {
    /* Local preferences are optional. */
  }
  return null;
}

function clampTreeWidth(width: number): number {
  return Math.min(MAX_TREE_WIDTH, Math.max(MIN_TREE_WIDTH, Math.round(width)));
}

type FolderEntry = { root: string; name: string };
type FolderTreeNode = {
  key: string;
  root: string;
  folder: string; // "" (entire root), "." (root direct files), or "a/b"
  label: string;
  children: FolderTreeNode[];
};

const makeFolderKey = (root: string, folderPath: string) =>
  JSON.stringify([root || "", folderPath || "."]);

const normalizeFolderPath = (raw?: string) => {
  const cleaned = (raw || "").replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
  return cleaned || ".";
};

const rootDisplayName = (root: string) =>
  root.split(/[\\/]/).filter(Boolean).pop() || root || "音乐目录";

function formatFlatFolderName(
  root: string,
  name: string,
  hasMultipleRoots: boolean,
) {
  const prefix = hasMultipleRoots ? `${rootDisplayName(root)} / ` : "";
  return `${prefix}${name === "." ? "根目录" : name}`;
}

function buildFolderTree(
  entries: FolderEntry[],
  hasMultipleRoots: boolean,
): {
  roots: FolderTreeNode[];
  byKey: Record<string, FolderTreeNode>;
  parents: Record<string, string>;
} {
  const byKey: Record<string, FolderTreeNode> = {};
  const parents: Record<string, string> = {};
  const rootGroups = new Map<string, FolderEntry[]>();

  for (const item of entries) {
    const r = item.root || "";
    const list = rootGroups.get(r) || [];
    list.push({ root: r, name: normalizeFolderPath(item.name) });
    rootGroups.set(r, list);
  }

  const rootNodes: FolderTreeNode[] = [];
  const sortedRoots = [...rootGroups.keys()].sort((a, b) =>
    a.localeCompare(b, "zh-CN"),
  );

  for (const r of sortedRoots) {
    const groupEntries = rootGroups.get(r) || [];
    const nodeMap = new Map<string, FolderTreeNode>();
    let parentTarget: FolderTreeNode | null = null;
    if (hasMultipleRoots) {
      const rootKey = JSON.stringify([r, ""]);
      parentTarget = {
        key: rootKey,
        root: r,
        folder: "",
        label: rootDisplayName(r),
        children: [],
      };
      byKey[rootKey] = parentTarget;
      rootNodes.push(parentTarget);
    }

    const ensureNode = (
      folderPath: string,
      label: string,
      parentNode: FolderTreeNode | null,
    ) => {
      const key = makeFolderKey(r, folderPath);
      const existing = nodeMap.get(folderPath);
      if (existing) return existing;
      const node: FolderTreeNode = {
        key,
        root: r,
        folder: folderPath,
        label,
        children: [],
      };
      nodeMap.set(folderPath, node);
      byKey[key] = node;
      if (parentNode) {
        parents[key] = parentNode.key;
        parentNode.children.push(node);
      } else {
        rootNodes.push(node);
      }
      return node;
    };

    const sortedPaths = [...new Set(groupEntries.map((e) => e.name))].sort(
      (a, b) => {
        if (a === ".") return -1;
        if (b === ".") return 1;
        return a.localeCompare(b, "zh-CN");
      },
    );

    for (const folderPath of sortedPaths) {
      if (folderPath === ".") {
        ensureNode(".", "根目录（直属文件）", parentTarget);
        continue;
      }
      const segments = folderPath.split("/").filter(Boolean);
      let acc = "";
      let curParent = parentTarget;
      for (const seg of segments) {
        acc = acc ? `${acc}/${seg}` : seg;
        curParent = ensureNode(acc, seg, curParent);
      }
    }
  }

  const sortChildren = (nodes: FolderTreeNode[]) => {
    nodes.sort((a, b) => {
      if (a.folder === ".") return -1;
      if (b.folder === ".") return 1;
      return a.label.localeCompare(b.label, "zh-CN");
    });
    for (const n of nodes) {
      sortChildren(n.children);
    }
  };
  sortChildren(rootNodes);
  return { roots: rootNodes, byKey, parents };
}

function fileMatchesFolderSelection(
  file: LibraryFile,
  selectedKey: string,
  node?: FolderTreeNode,
) {
  if (selectedKey === "all" || !node) return true;
  if ((file.root || "") !== node.root) return false;
  if (node.folder === "") return true;
  const fileFolder = normalizeFolderPath(file.folder);
  if (node.folder === ".") return fileFolder === ".";
  return fileFolder === node.folder || fileFolder.startsWith(node.folder + "/");
}

function FolderRowActions({
  node,
  disabled,
  onMkdir,
  onRename,
  onMove,
  onDelete,
}: {
  node: FolderTreeNode;
  disabled: boolean;
  onMkdir: () => void;
  onRename: () => void;
  onMove: () => void;
  onDelete: () => void;
}) {
  const id = useId();
  const anchor = `--folder-${id.replace(/[^a-zA-Z0-9]/g, "")}`;
  return (
    <>
      <button
        type="button"
        className="library-tree-more"
        aria-label={`管理文件夹 ${node.label}`}
        title="文件夹操作"
        disabled={disabled}
        popoverTarget={id}
        style={{ anchorName: anchor } as React.CSSProperties}
      >
        <MoreHorizontal />
      </button>
      <div
        id={id}
        popover="auto"
        className="song-action-menu library-folder-menu"
        style={{ positionAnchor: anchor } as React.CSSProperties}
        onClick={(event) => {
          if ((event.target as HTMLElement).closest("button:not(:disabled)")) {
            event.currentTarget.hidePopover();
          }
        }}
      >
        <button type="button" className="icon-button" onClick={onMkdir}>
          <FolderPlus />
          <span>新建子文件夹</span>
        </button>
        <button type="button" className="icon-button" onClick={onRename}>
          <Pencil />
          <span>重命名</span>
        </button>
        <button type="button" className="icon-button" onClick={onMove}>
          <FolderInput />
          <span>移动到…</span>
        </button>
        <button
          type="button"
          className="icon-button danger-action"
          onClick={onDelete}
        >
          <Trash2 />
          <span>删除文件夹</span>
        </button>
      </div>
    </>
  );
}

type FolderModalState =
  | { type: "mkdir"; root: string; parent: string; parentLabel: string }
  | { type: "rename"; node: FolderTreeNode }
  | { type: "move"; files?: LibraryFile[]; folders?: FolderTreeNode[] }
  | { type: "delete"; folders: FolderTreeNode[] };

export function MusicLibrary({
  files,
  selected,
  setSelected,
  current,
  busy,
  error,
  refresh,
  rescan,
  rescanning = false,
  play,
  playAll,
  queue,
  savePlaylist,
  remove,
  remoteDeleted,
  changed,
  manage,
  process,
  processDirectory,
  processing = false,
  hasMore = false,
  loadingMore = false,
  indexing = false,
  serverFolders,
  serverTreeWidth,
  loadMore,
  onServerFilterChange,
  onNotice,
}: {
  files: LibraryFile[];
  selected: string[];
  setSelected: (paths: string[]) => void;
  current: Track | null;
  busy: boolean;
  error: string;
  refresh: () => void | Promise<void>;
  rescan?: () => void | Promise<void>;
  rescanning?: boolean;
  play: (track: Track, context?: Track[]) => void;
  playAll: (tracks: Track[]) => void;
  queue: (tracks: Track[]) => void;
  savePlaylist?: (tracks: Track[]) => void;
  remove: (files: LibraryFile[]) => void;
  remoteDeleted?: (ids: string[]) => void;
  changed: (file: LibraryFile) => void;
  manage?: (action: LibraryAction, files: LibraryFile[]) => void;
  processing?: boolean;
  process?: (files: LibraryFile[]) => void;
  processDirectory?: (scopeDir: string) => void;
  hasMore?: boolean;
  loadingMore?: boolean;
  indexing?: boolean;
  serverFolders?: { root: string; folder: string }[];
  serverTreeWidth?: number | null;
  loadMore?: () => void;
  onServerFilterChange?: (filters: {
    query: string;
    root: string;
    folder: string;
    sort: string;
  }) => void;
  onNotice?: (message: string) => void;
}) {
  const layoutRef = useRef<HTMLDivElement>(null);
  const [treeWidth, setTreeWidth] = useState<number | null>(readSavedTreeWidth);
  useEffect(() => {
    if (
      typeof serverTreeWidth === "number" &&
      serverTreeWidth >= MIN_TREE_WIDTH &&
      serverTreeWidth <= MAX_TREE_WIDTH
    ) {
      const clamped = clampTreeWidth(serverTreeWidth);
      setTreeWidth(clamped);
      try {
        localStorage.setItem(TREE_WIDTH_STORAGE_KEY, String(clamped));
      } catch {
        /* Local preferences are optional. */
      }
    } else if (serverTreeWidth === 0) {
      setTreeWidth(null);
      try {
        localStorage.removeItem(TREE_WIDTH_STORAGE_KEY);
      } catch {
        /* Local preferences are optional. */
      }
    }
  }, [serverTreeWidth]);
  const [resizingTree, setResizingTree] = useState(false);
  const [folderManageMode, setFolderManageMode] = useState(false);
  const [selectedFolderKeys, setSelectedFolderKeys] = useState<string[]>([]);
  const [folderModal, setFolderModal] = useState<FolderModalState | null>(null);
  const [folderInput, setFolderInput] = useState("");
  const [moveTargetRoot, setMoveTargetRoot] = useState("");
  const [moveTargetFolder, setMoveTargetFolder] = useState("");
  const [inlineNewDir, setInlineNewDir] = useState("");
  const [showInlineMkdir, setShowInlineMkdir] = useState(false);
  const [folderBusy, setFolderBusy] = useState(false);
  const [folderModalError, setFolderModalError] = useState("");
  const [query, setQuery] = useState("");
  const [folderQuery, setFolderQuery] = useState("");
  const [folder, setFolder] = useState("all");
  const [expandedFolders, setExpandedFolders] = useState<
    Record<string, boolean>
  >({});
  const [sort, setSort] = useState("title");
  const [editing, setEditing] = useState<LibraryFile | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({
    title: "",
    artist: "",
    album: "",
  });
  const [initialDraft, setInitialDraft] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState("");
  const [knownFolders, setKnownFolders] = useState<Record<string, FolderEntry>>(
    {},
  );
  const [localRefreshing, setLocalRefreshing] = useState(false);
  const pendingFolderRebuildRef = useRef(false);
  const isRefreshing = rescanning || localRefreshing;
  useEffect(() => {
    let resetDeletedActiveFolder = false;
    setKnownFolders((prev) => {
      const shouldRebuildFresh =
        pendingFolderRebuildRef.current ||
        serverFolders !== undefined ||
        (folder === "all" && !query.trim());
      pendingFolderRebuildRef.current = false;

      if (
        files.length === 0 &&
        !(serverFolders && serverFolders.length > 0) &&
        folder === "all" &&
        !query.trim()
      ) {
        return Object.keys(prev).length ? {} : prev;
      }

      const next: Record<string, FolderEntry> = shouldRebuildFresh
        ? {}
        : { ...prev };
      for (const file of files) {
        const norm = normalizeFolderPath(file.folder);
        const key = makeFolderKey(file.root || "", norm);
        if (!next[key]) {
          next[key] = { root: file.root || "", name: norm };
        }
      }
      for (const item of serverFolders ?? []) {
        const norm = normalizeFolderPath(item.folder);
        const root = item.root || "";
        const key = makeFolderKey(root, norm);
        if (!next[key]) {
          next[key] = { root, name: norm };
        }
      }

      if (
        shouldRebuildFresh &&
        folder !== "all" &&
        prev[folder] &&
        !next[folder]
      ) {
        resetDeletedActiveFolder = true;
      }

      const prevKeys = Object.keys(prev);
      const nextKeys = Object.keys(next);
      if (
        prevKeys.length === nextKeys.length &&
        prevKeys.every(
          (k) =>
            next[k] &&
            next[k].root === prev[k].root &&
            next[k].name === prev[k].name,
        )
      ) {
        return prev;
      }
      return next;
    });

    if (resetDeletedActiveFolder) {
      setFolder("all");
      setSelected([]);
      notifyFilters(query, "all", sort);
    }
  }, [files, serverFolders, folder, query]);

  const folders = Object.entries(knownFolders).sort((a, b) =>
    a[0].localeCompare(b[0]),
  );
  const distinctRoots = new Set(folders.map(([, v]) => v.root || ""));
  const hasMultipleRoots = distinctRoots.size > 1;
  const folderTree = buildFolderTree(
    folders.map(([, value]) => value),
    hasMultipleRoots,
  );
  const activeNode = folder === "all" ? undefined : folderTree.byKey[folder];
  const folderNeedle = folderQuery.trim().toLowerCase();

  function filterTreeNodes(nodes: FolderTreeNode[]): FolderTreeNode[] {
    if (!folderNeedle) return nodes;
    const out: FolderTreeNode[] = [];
    for (const node of nodes) {
      const matchedChildren = filterTreeNodes(node.children);
      const selfMatches =
        node.label.toLowerCase().includes(folderNeedle) ||
        node.folder.toLowerCase().includes(folderNeedle);
      if (selfMatches || matchedChildren.length > 0) {
        out.push({
          ...node,
          children:
            selfMatches && matchedChildren.length === 0
              ? node.children
              : matchedChildren,
        });
      }
    }
    return out;
  }
  const visibleTreeRoots = filterTreeNodes(folderTree.roots);

  const breadcrumbs: FolderTreeNode[] = [];
  if (activeNode) {
    let cur: FolderTreeNode | undefined = activeNode;
    while (cur) {
      breadcrumbs.unshift(cur);
      const parentKey: string | undefined = folderTree.parents[cur.key];
      cur = parentKey ? folderTree.byKey[parentKey] : undefined;
    }
  }
  const currentChildFolders = activeNode
    ? activeNode.children
    : folderTree.roots;

  function notifyFilters(
    nextQuery: string,
    nextFolderKey: string,
    nextSort: string,
  ) {
    if (!onServerFilterChange) return;
    const node =
      nextFolderKey === "all" ? undefined : folderTree.byKey[nextFolderKey];
    const folderInfo =
      nextFolderKey === "all" ? undefined : knownFolders[nextFolderKey];
    const folderParam = node ? node.folder : folderInfo ? folderInfo.name : "";
    const rootParam = node ? node.root : folderInfo ? folderInfo.root : "";
    onServerFilterChange({
      query: nextQuery.trim(),
      root: rootParam,
      folder: folderParam,
      sort: nextSort,
    });
  }
  const visible = files
    .filter(
      (file) =>
        !!file.track &&
        fileMatchesFolderSelection(file, folder, activeNode) &&
        [file.name, file.track?.title, file.track?.artist, file.track?.album]
          .join(" ")
          .toLowerCase()
          .includes(query.trim().toLowerCase()),
    )
    .sort((a, b) => {
      if (sort === "duration")
        return (b.track?.duration || 0) - (a.track?.duration || 0);
      const key = sort as "title" | "artist" | "album";
      return (a.track?.[key] || a.name).localeCompare(
        b.track?.[key] || b.name,
        "zh-CN",
      );
    });
  const chosen = visible.filter((file) => selected.includes(file.path));
  const playable = visible.flatMap((file) => (file.track ? [file.track] : []));
  const selectedTracks = chosen.flatMap((file) =>
    file.track ? [file.track] : [],
  );
  const songs = files.filter((file) => file.track).length;
  function selectFolder(value: string) {
    setFolder(value);
    setSelected([]);
    if (value !== "all") {
      setExpandedFolders((prev) => {
        const next = { ...prev };
        let curKey: string | undefined = value;
        while (curKey) {
          next[curKey] = true;
          curKey = folderTree.parents[curKey];
        }
        return next;
      });
    }
    notifyFilters(query, value, sort);
  }
  function edit(file: LibraryFile) {
    if (!file.track) return;
    const initial = {
      ...file.metadata,
      title: file.track.title,
      artist: file.track.artist,
      album: file.track.album,
    };
    setDraft(initial);
    setInitialDraft(initial);
    setEditError("");
    setEditing(file);
  }
  const manageableNodes = Object.values(folderTree.byKey).filter(
    (node) => node.folder !== "" && node.folder !== ".",
  );
  const visibleManageableKeys = (() => {
    const out: string[] = [];
    const walk = (nodes: FolderTreeNode[]) => {
      for (const n of nodes) {
        if (n.folder !== "" && n.folder !== ".") out.push(n.key);
        walk(n.children);
      }
    };
    walk(visibleTreeRoots);
    return out;
  })();
  const selectedFolderNodes = selectedFolderKeys
    .map((key) => folderTree.byKey[key])
    .filter((node): node is FolderTreeNode => Boolean(node));
  const rootCandidates = [...distinctRoots];
  const defaultRoot =
    activeNode?.root || rootCandidates[0] || files[0]?.root || "";
  const currentFolderEntry =
    folder === "all" ? undefined : knownFolders[folder];
  const currentScopeDir = extractScopeDirFromFolderKey(folder, {
    root: activeNode?.root ?? currentFolderEntry?.root,
    folder: activeNode?.folder ?? currentFolderEntry?.name,
    hasMultipleRoots,
  });

  function openMkdirModal(root: string, parent: string, parentLabel: string) {
    setFolderInput("");
    setFolderModalError("");
    setFolderModal({ type: "mkdir", root, parent, parentLabel });
  }

  function openRenameModal(node: FolderTreeNode) {
    setFolderInput(node.label);
    setFolderModalError("");
    setFolderModal({ type: "rename", node });
  }

  function openMoveModal(opts: {
    files?: LibraryFile[];
    folders?: FolderTreeNode[];
  }) {
    const firstRoot =
      opts.folders?.[0]?.root || opts.files?.[0]?.root || defaultRoot;
    setMoveTargetRoot(firstRoot);
    setMoveTargetFolder("");
    setInlineNewDir("");
    setShowInlineMkdir(false);
    setFolderModalError("");
    setFolderModal({ type: "move", ...opts });
  }

  function openDeleteFoldersModal(targets: FolderTreeNode[]) {
    if (!targets.length) return;
    setFolderModalError("");
    setFolderModal({ type: "delete", folders: targets });
  }

  async function reloadAfterFolderChange(
    nextFolderKey?: string,
    nextFilterOverride?: { root: string; folder: string },
  ) {
    setKnownFolders({});
    setSelected([]);
    setSelectedFolderKeys([]);
    if (nextFolderKey === "all") {
      setFolder("all");
    } else if (nextFolderKey) {
      setFolder(nextFolderKey);
    }
    if (onServerFilterChange && nextFilterOverride) {
      onServerFilterChange({
        query: query.trim(),
        root: nextFilterOverride.root,
        folder: nextFilterOverride.folder,
        sort,
      });
      return;
    }
    await refresh();
  }

  async function submitFolderModal() {
    if (!folderModal) return;
    setFolderBusy(true);
    setFolderModalError("");
    try {
      if (folderModal.type === "mkdir") {
        const created = await api<{ root: string; folder: string }>(
          "/files/mkdir",
          "POST",
          {
            root: folderModal.root,
            parent: folderModal.parent,
            name: folderInput,
          },
        );
        const createdKey = makeFolderKey(created.root, created.folder);
        setFolderModal(null);
        await reloadAfterFolderChange(createdKey, {
          root: created.root,
          folder: created.folder,
        });
      } else if (folderModal.type === "rename") {
        const renamed = await api<{ root: string; folder: string }>(
          "/files/rename",
          "POST",
          {
            root: folderModal.node.root,
            folder: folderModal.node.folder,
            new_name: folderInput,
          },
        );
        const wasActive =
          folder === folderModal.node.key ||
          (activeNode &&
            activeNode.root === folderModal.node.root &&
            activeNode.folder.startsWith(folderModal.node.folder + "/"));
        const nextKey = makeFolderKey(renamed.root, renamed.folder);
        setFolderModal(null);
        await reloadAfterFolderChange(
          wasActive ? nextKey : undefined,
          wasActive
            ? { root: renamed.root, folder: renamed.folder }
            : undefined,
        );
      } else if (folderModal.type === "move") {
        let destinationFolder = moveTargetFolder;
        if (showInlineMkdir && inlineNewDir.trim()) {
          const created = await api<{ root: string; folder: string }>(
            "/files/mkdir",
            "POST",
            {
              root: moveTargetRoot,
              parent: moveTargetFolder,
              name: inlineNewDir.trim(),
            },
          );
          destinationFolder = created.folder;
        }
        const moveRes = await api<{ moved?: number; failed?: string[] }>(
          "/files/move",
          "POST",
          {
            files: folderModal.files?.map((f) => f.path) ?? [],
            folders:
              folderModal.folders?.map((n) => ({
                root: n.root,
                folder: n.folder,
              })) ?? [],
            target_root: moveTargetRoot,
            target_folder: destinationFolder,
          },
        );
        const movedActiveFolder = Boolean(
          folderModal.folders?.some(
            (n) =>
              folder === n.key ||
              (activeNode &&
                activeNode.root === n.root &&
                activeNode.folder.startsWith(n.folder + "/")),
          ),
        );
        setFolderModal(null);
        if (moveRes?.failed?.length) {
          onNotice?.(`部分移动失败：${moveRes.failed.join("；")}`);
        }
        await reloadAfterFolderChange(
          movedActiveFolder ? "all" : undefined,
          movedActiveFolder ? { root: "", folder: "" } : undefined,
        );
      } else if (folderModal.type === "delete") {
        const deletedTargets = folderModal.folders;
        const res = await api<{ track_ids?: string[]; failed?: string[] }>(
          "/files/batch-delete",
          "POST",
          {
            folders: deletedTargets.map((n) => ({
              root: n.root,
              folder: n.folder,
            })),
          },
        );
        if (res?.track_ids?.length) {
          remoteDeleted?.(res.track_ids);
        }
        if (res?.failed?.length) {
          onNotice?.(`部分文件夹删除失败：${res.failed.join("；")}`);
        }
        const activeDeleted = Boolean(
          activeNode &&
          deletedTargets.some(
            (n) =>
              n.root === activeNode.root &&
              (activeNode.folder === n.folder ||
                activeNode.folder.startsWith(n.folder + "/")),
          ),
        );
        setFolderModal(null);
        setFolderManageMode(false);
        await reloadAfterFolderChange(
          activeDeleted ? "all" : undefined,
          activeDeleted ? { root: "", folder: "" } : undefined,
        );
      }
    } catch (err) {
      setFolderModalError((err as Error).message);
    } finally {
      setFolderBusy(false);
    }
  }

  function startTreeResize(event: React.PointerEvent<HTMLDivElement>) {
    if (event.button !== 0 || !layoutRef.current) return;
    event.preventDefault();
    const layoutLeft = layoutRef.current.getBoundingClientRect().left;
    setResizingTree(true);
    const onMove = (moveEvent: PointerEvent) => {
      const next = clampTreeWidth(moveEvent.clientX - layoutLeft);
      setTreeWidth(next);
    };
    const onEnd = (endEvent: PointerEvent) => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onEnd);
      window.removeEventListener("pointercancel", onEnd);
      setResizingTree(false);
      const finalWidth = clampTreeWidth(endEvent.clientX - layoutLeft);
      setTreeWidth(finalWidth);
      try {
        localStorage.setItem(TREE_WIDTH_STORAGE_KEY, String(finalWidth));
      } catch {
        /* Local preferences are optional. */
      }
      scheduleSavePreferences({ library_tree_width: finalWidth });
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onEnd);
    window.addEventListener("pointercancel", onEnd);
  }

  const renderTreeNodes = (nodes: FolderTreeNode[], depth = 0) =>
    nodes.map((node) => {
      const hasChildren = node.children.length > 0;
      const isExpanded = folderNeedle ? true : !!expandedFolders[node.key];
      const isActive = folder === node.key;
      const isManageable = node.folder !== "" && node.folder !== ".";
      const isChecked = selectedFolderKeys.includes(node.key);
      return (
        <div key={node.key} className="library-tree-node">
          <div
            className={`library-tree-row ${isActive ? "active" : ""}`}
            style={{ paddingLeft: `${depth * 12 + 4}px` }}
          >
            {folderManageMode && isManageable && (
              <input
                type="checkbox"
                className="library-tree-check"
                aria-label={`选择文件夹 ${node.label}`}
                disabled={busy || folderBusy}
                checked={isChecked}
                onChange={(e) =>
                  setSelectedFolderKeys((prev) =>
                    e.target.checked
                      ? [...prev, node.key]
                      : prev.filter((key) => key !== node.key),
                  )
                }
              />
            )}
            {hasChildren ? (
              <button
                type="button"
                className="library-tree-toggle"
                aria-label={`${isExpanded ? "收起" : "展开"} ${node.label}`}
                aria-expanded={isExpanded}
                onClick={() =>
                  setExpandedFolders((prev) => ({
                    ...prev,
                    [node.key]: !prev[node.key],
                  }))
                }
              >
                {isExpanded ? <ChevronDown /> : <ChevronRight />}
              </button>
            ) : (
              <span className="library-tree-spacer" aria-hidden="true" />
            )}
            <button
              type="button"
              className={`library-tree-item ${isActive ? "active" : ""}`}
              aria-pressed={isActive}
              onClick={() => selectFolder(node.key)}
              title={
                node.folder === "" ? node.root : `${node.root}/${node.folder}`
              }
            >
              <Folder />
              <span>{node.label}</span>
            </button>
            {!folderManageMode && isManageable && (
              <FolderRowActions
                node={node}
                disabled={busy || folderBusy}
                onMkdir={() =>
                  openMkdirModal(node.root, node.folder, node.label)
                }
                onRename={() => openRenameModal(node)}
                onMove={() => openMoveModal({ folders: [node] })}
                onDelete={() => openDeleteFoldersModal([node])}
              />
            )}
          </div>
          {hasChildren && isExpanded && (
            <div className="library-tree-children">
              {renderTreeNodes(node.children, depth + 1)}
            </div>
          )}
        </div>
      );
    });
  return (
    <section className="view music-library">
      <div className="library-heading library-heading-compact">
        <div className="library-heading-meta">
          {processing && <h2>选择要处理的歌曲</h2>}
          <p className="library-stats">
            {songs} 首歌曲{hasMore ? "+" : ""} · {folders.length} 个文件夹
            {indexing ? " · 正在索引…" : ""}
          </p>
        </div>
        <button
          className="secondary"
          onClick={() => {
            if (isRefreshing || busy || loadingMore) return;
            pendingFolderRebuildRef.current = true;
            setLocalRefreshing(true);
            setKnownFolders({});
            void Promise.resolve(rescan ? rescan() : refresh()).finally(() => {
              setLocalRefreshing(false);
            });
          }}
          disabled={busy || loadingMore || isRefreshing}
        >
          {isRefreshing ? "正在扫描刷新…" : "刷新音乐库"}
        </button>
      </div>
      {indexing && (
        <p className="library-inline-status" role="status">
          后台扫描索引中，稍后可刷新获取最新列表。
        </p>
      )}
      <div
        ref={layoutRef}
        className={`library-layout${resizingTree ? " resizing" : ""}`}
        style={
          treeWidth
            ? ({
                "--library-tree-width": `${treeWidth}px`,
              } as React.CSSProperties)
            : undefined
        }
      >
        <aside className="library-folders" aria-label="音乐文件夹">
          <div className="library-folder-toolbar">
            <button
              type="button"
              className="secondary library-folder-action"
              disabled={busy || folderBusy}
              title={
                activeNode && activeNode.folder !== "."
                  ? `在 ${activeNode.label} 下新建文件夹`
                  : "在根目录新建文件夹"
              }
              onClick={() => {
                const parent =
                  activeNode && activeNode.folder !== "."
                    ? activeNode.folder
                    : "";
                const parentLabel =
                  activeNode && activeNode.folder !== "."
                    ? activeNode.label
                    : "根目录";
                openMkdirModal(defaultRoot, parent, parentLabel);
              }}
            >
              <FolderPlus />
              <span>新建</span>
            </button>
            <button
              type="button"
              className={`secondary library-folder-action ${folderManageMode ? "active" : ""}`}
              aria-pressed={folderManageMode}
              disabled={busy || folderBusy || manageableNodes.length === 0}
              title="批量勾选与删除文件夹"
              onClick={() => {
                setFolderManageMode((prev) => !prev);
                setSelectedFolderKeys([]);
              }}
            >
              <CheckSquare />
              <span>{folderManageMode ? "完成" : "多选"}</span>
            </button>
          </div>
          {folderManageMode && (
            <div className="library-folder-batch-bar">
              <button
                type="button"
                className="library-folder-select-all"
                onClick={() => {
                  const allSelected =
                    visibleManageableKeys.length > 0 &&
                    visibleManageableKeys.every((k) =>
                      selectedFolderKeys.includes(k),
                    );
                  setSelectedFolderKeys(
                    allSelected ? [] : visibleManageableKeys,
                  );
                }}
              >
                {visibleManageableKeys.length > 0 &&
                visibleManageableKeys.every((k) =>
                  selectedFolderKeys.includes(k),
                )
                  ? "取消全选"
                  : "全选当前"}
              </button>
              <button
                type="button"
                className="secondary danger-action"
                disabled={!selectedFolderNodes.length || busy || folderBusy}
                onClick={() => openDeleteFoldersModal(selectedFolderNodes)}
              >
                <Trash2 />
                <span>删除所选 ({selectedFolderNodes.length})</span>
              </button>
            </div>
          )}
          {folders.length > 6 && (
            <input
              className="library-folder-filter"
              aria-label="筛选文件夹"
              placeholder="筛选文件夹…"
              value={folderQuery}
              onChange={(e) => setFolderQuery(e.target.value)}
            />
          )}
          <div className="library-folder-list">
            <button
              className={`library-tree-root-btn ${folder === "all" ? "active" : ""}`}
              aria-pressed={folder === "all"}
              onClick={() => selectFolder("all")}
            >
              <Music2 />
              <span>全部文件夹</span>
            </button>
            {renderTreeNodes(visibleTreeRoots)}
          </div>
          <div
            className="library-resizer"
            role="separator"
            aria-orientation="vertical"
            aria-label="拖动调整文件夹栏宽度"
            title="拖动调整文件夹栏宽度，双击恢复默认"
            onPointerDown={startTreeResize}
            onDoubleClick={() => {
              setTreeWidth(null);
              try {
                localStorage.removeItem(TREE_WIDTH_STORAGE_KEY);
              } catch {
                /* Local preferences are optional. */
              }
              scheduleSavePreferences({ library_tree_width: 0 });
            }}
          />
        </aside>
        <div className="library-content">
          {(breadcrumbs.length > 0 || currentChildFolders.length > 0) && (
            <div className="library-browser-bar">
              <nav className="library-breadcrumbs" aria-label="目录位置">
                <button
                  type="button"
                  className={folder === "all" ? "active" : ""}
                  onClick={() => selectFolder("all")}
                >
                  全部文件
                </button>
                {breadcrumbs.map((node, index) => (
                  <span key={node.key} className="library-breadcrumb-seg">
                    <span aria-hidden="true">/</span>
                    <button
                      type="button"
                      className={
                        index === breadcrumbs.length - 1 ? "active" : ""
                      }
                      onClick={() => selectFolder(node.key)}
                    >
                      {node.label}
                    </button>
                  </span>
                ))}
              </nav>
              {currentChildFolders.length > 0 && (
                <div className="library-subfolders" aria-label="子文件夹">
                  {currentChildFolders.slice(0, 24).map((child) => (
                    <button
                      key={child.key}
                      type="button"
                      className="library-subfolder-chip"
                      onClick={() => selectFolder(child.key)}
                    >
                      <Folder />
                      <span>{child.label}</span>
                    </button>
                  ))}
                  {currentChildFolders.length > 24 && (
                    <span className="library-subfolder-more">
                      共 {currentChildFolders.length} 个子目录
                    </span>
                  )}
                </div>
              )}
            </div>
          )}
          <div className="library-filters">
            <input
              className="management-search"
              aria-label="搜索音乐文件"
              placeholder="搜索歌曲、歌手或专辑"
              value={query}
              onChange={(e) => {
                const next = e.target.value;
                setQuery(next);
                setSelected([]);
                notifyFilters(next, folder, sort);
              }}
            />
            <select
              aria-label="音乐库排序"
              value={sort}
              onChange={(e) => {
                const next = e.target.value;
                setSort(next);
                notifyFilters(query, folder, next);
              }}
            >
              <option value="title">歌曲名称</option>
              <option value="artist">歌手</option>
              <option value="album">专辑</option>
              <option value="duration">时长</option>
            </select>
            <select
              className="library-folder-select"
              aria-label="选择音乐文件夹"
              value={folder}
              onChange={(e) => selectFolder(e.target.value)}
            >
              <option value="all">全部文件夹</option>
              {folders.map(([key, value]) => (
                <option key={key} value={key}>
                  {formatFlatFolderName(
                    value.root,
                    value.name,
                    hasMultipleRoots,
                  )}
                </option>
              ))}
            </select>
          </div>
          <div className="management-toolbar">
            <label className="check-label">
              <input
                type="checkbox"
                aria-label="全选音乐文件"
                checked={visible.length > 0 && chosen.length === visible.length}
                disabled={busy || !visible.length}
                onChange={(e) =>
                  setSelected(
                    e.target.checked ? visible.map((file) => file.path) : [],
                  )
                }
              />
              {hasMore
                ? `全选已加载 (${visible.length} 首)`
                : `全选当前结果 (${visible.length} 首)`}
            </label>
            <span>已选 {chosen.length} 项</span>
            {!processing && (
              <button
                className="primary"
                disabled={!playable.length || busy}
                onClick={() =>
                  playAll(selectedTracks.length ? selectedTracks : playable)
                }
              >
                <Play />
                {selectedTracks.length ? "播放选中" : "播放全部"}
              </button>
            )}
            {!processing && process && (
              <button
                className="secondary"
                disabled={!selectedTracks.length || busy}
                onClick={() => process(chosen)}
              >
                <WandSparkles />
                送往刮削整理
              </button>
            )}
            {processDirectory && (
              <button
                type="button"
                className="secondary"
                disabled={busy || (!songs && !hasMore && folders.length === 0)}
                title={
                  currentScopeDir
                    ? `将目录「${currentScopeDir}」全部歌曲送往刮削工作台（单次最多 1000 首）`
                    : "将整个音乐根目录送往刮削工作台（单次最多 1000 首）"
                }
                onClick={() => processDirectory(currentScopeDir)}
              >
                <FolderInput />
                整目录送往刮削
              </button>
            )}
            <details className="library-batch-actions">
              <summary className="secondary">更多操作</summary>
              <div className="library-batch-options">
                {!processing && (
                  <button
                    className="secondary"
                    disabled={!selectedTracks.length || busy}
                    onClick={() => queue(selectedTracks)}
                  >
                    加入待播
                  </button>
                )}
                {!processing && savePlaylist && (
                  <button
                    className="secondary"
                    disabled={!selectedTracks.length || busy}
                    onClick={() => savePlaylist(selectedTracks)}
                  >
                    <ListMusic />
                    加入歌单
                  </button>
                )}
                {!processing && process && (
                  <button
                    className="secondary"
                    disabled={!selectedTracks.length || busy}
                    onClick={() => process(chosen)}
                  >
                    <WandSparkles />
                    送往刮削整理
                  </button>
                )}
                {!processing && processDirectory && (
                  <button
                    type="button"
                    className="secondary"
                    disabled={
                      busy || (!songs && !hasMore && folders.length === 0)
                    }
                    onClick={() => processDirectory(currentScopeDir)}
                  >
                    <FolderInput />
                    整目录送往刮削
                  </button>
                )}
                {manage && (
                  <>
                    <select
                      className="library-tool-select"
                      aria-label="更多音乐管理操作"
                      value=""
                      disabled={!chosen.some((file) => file.track) || busy}
                      onChange={(e) => {
                        if (e.target.value)
                          manage(e.target.value as LibraryAction, chosen);
                      }}
                    >
                      <option value="">更多管理操作</option>
                      <option value="scrape">只刮削标签</option>
                      <option value="edit">编辑标签、歌词与封面</option>
                      <option value="extract">从文件名提取标签</option>
                      <option value="clean">清理与替换标签文本</option>
                      <option value="rename">按标签批量重命名</option>
                      <option value="export_lyrics">导出内嵌歌词到 LRC</option>
                      <option value="import_lyrics">嵌入同名 LRC 歌词</option>
                      <option value="export_cover">导出内嵌专辑封面</option>
                      <option value="import_cover">嵌入同名或目录封面</option>
                      <option value="artist_artwork">刮削艺术家图片</option>
                      <option value="duplicates">检查重复歌曲</option>
                      <option value="quality">检查音频完整性与参数</option>
                      <option value="convert">转换音频格式</option>
                      <option value="split">CUE 分轨</option>
                      <option value="clear">清空内嵌标签</option>
                    </select>
                  </>
                )}
                <button
                  className="secondary"
                  disabled={busy || !chosen.length}
                  onClick={() => openMoveModal({ files: chosen })}
                >
                  <FolderInput />
                  移动到文件夹…
                </button>
                <button
                  className="secondary"
                  disabled={busy || !chosen.length}
                  onClick={() => remove(chosen)}
                >
                  删除选中文件
                </button>
                {chosen.length === 1 && (
                  <a
                    className="secondary"
                    href={`/api/files/content?path=${encodeURIComponent(chosen[0].path)}`}
                    download
                  >
                    下载到本机
                  </a>
                )}
              </div>
            </details>
          </div>
          {hasMore && (
            <p
              className="library-inline-status library-partial-hint"
              role="status"
            >
              当前列表仅展示已加载的前 {visible.length}{" "}
              首，可点击底部「加载更多」或直接使用「整目录送往刮削」（单次最多
              1000 首）。
            </p>
          )}
          {error && <p role="alert">{error}</p>}
          <div className="music-table-wrap">
            <table className="music-table">
              <thead>
                <tr>
                  <th className="library-selection" aria-label="选择" />
                  <th>歌曲</th>
                  <th className="library-artist">歌手</th>
                  <th className="library-album">专辑</th>
                  <th className="library-duration">时长 / 格式</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((file) => (
                  <tr
                    key={file.path}
                    className={current?.id === file.track?.id ? "current" : ""}
                  >
                    <td className="library-selection">
                      <input
                        type="checkbox"
                        aria-label={`选择文件 ${file.name}`}
                        disabled={busy}
                        checked={selected.includes(file.path)}
                        onChange={(e) =>
                          setSelected(
                            e.target.checked
                              ? [...selected, file.path]
                              : selected.filter((path) => path !== file.path),
                          )
                        }
                      />
                    </td>
                    <td className="library-song-cell">
                      <div className="library-song" title={file.name}>
                        <Cover
                          track={file.track}
                          onPlay={
                            file.track
                              ? () => play(file.track!, playable)
                              : undefined
                          }
                        />
                        <span>
                          <strong>
                            {file.track ? (
                              <SongTitle
                                track={file.track}
                                onPlay={() => play(file.track!, playable)}
                              />
                            ) : (
                              file.name
                            )}
                          </strong>
                          {file.track ? (
                            <small className="mobile-meta-sub">
                              {`${file.track.artist || "未知歌手"} · ${file.track.album || "未知专辑"}`}
                            </small>
                          ) : (
                            <small>{file.folder || "."}</small>
                          )}
                        </span>
                      </div>
                    </td>
                    <td className="library-artist">
                      {file.track?.artist || "—"}
                    </td>
                    <td className="library-album">
                      {file.track?.album || "—"}
                    </td>
                    <td className="library-duration">
                      {file.track ? (
                        <span className="library-meta-inline">
                          {file.track.playback.codec
                            ? `${file.track.playback.codec.toUpperCase()} · `
                            : ""}
                          {clock(file.track.duration)}
                        </span>
                      ) : (
                        <span className="library-meta-inline">
                          {(file.size / 1024).toFixed(1)} KB
                        </span>
                      )}
                    </td>
                    <td className="library-action-cell">
                      {file.track ? (
                        <SongActions
                          track={file.track}
                          play={() => play(file.track!, playable)}
                          queue={() => queue([file.track!])}
                          savePlaylist={
                            savePlaylist
                              ? () => savePlaylist([file.track!])
                              : undefined
                          }
                        >
                          {(processing || process) && (
                            <Button
                              label={
                                processing
                                  ? `编辑歌曲 ${file.track.title}`
                                  : `整理歌曲 ${file.track.title}`
                              }
                              text={processing ? "编辑歌曲" : "刮削整理"}
                              disabled={busy || (processing && !file.editable)}
                              onClick={() =>
                                processing ? edit(file) : process?.([file])
                              }
                            >
                              {processing ? <Pencil /> : <WandSparkles />}
                            </Button>
                          )}
                          <a
                            className="icon-button library-download"
                            aria-label={`下载到本机 ${file.name}`}
                            href={`/api/files/content?path=${encodeURIComponent(file.path)}`}
                            download
                          >
                            <Download />
                            <span>下载到本机</span>
                          </a>
                          <Button
                            label={`删除文件 ${file.name}`}
                            text="删除文件"
                            disabled={busy}
                            onClick={() => remove([file])}
                          >
                            <Trash2 />
                          </Button>
                        </SongActions>
                      ) : (
                        <div className="library-actions file-actions">
                          <a
                            className="icon-button"
                            aria-label={`下载到本机 ${file.name}`}
                            href={`/api/files/content?path=${encodeURIComponent(file.path)}`}
                            download
                          >
                            <Download />
                            <span>下载</span>
                          </a>
                          <Button
                            label={`删除文件 ${file.name}`}
                            text="删除"
                            disabled={busy}
                            onClick={() => remove([file])}
                          >
                            <Trash2 />
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!visible.length && (
            <div className="empty">
              {!files.length
                ? "音乐库为空。下载完成后，歌曲会自动出现在这里，也可以将已有音频放入挂载的音乐目录。"
                : "此范围没有匹配的歌曲。可切换文件夹或调整搜索条件。"}
            </div>
          )}
          {hasMore && loadMore && (
            <div className="management-toolbar">
              <button
                className="secondary"
                onClick={loadMore}
                disabled={busy || loadingMore}
              >
                {loadingMore ? "正在加载…" : "加载更多"}
              </button>
              <span className="management-help">
                已加载 {visible.length}{" "}
                首（可继续加载或点击上方「整目录送往刮削」）
              </span>
            </div>
          )}
        </div>
      </div>
      {!processing && chosen.length > 0 && (
        <div className="selection-bar" role="region" aria-label="已选音乐操作">
          <span>
            已选 <b>{chosen.length}</b> 首
          </span>
          <div>
            <button type="button" onClick={() => setSelected([])}>
              清空
            </button>
            {selectedTracks.length > 0 && (
              <>
                <button
                  type="button"
                  className="secondary"
                  disabled={busy}
                  onClick={() => playAll(selectedTracks)}
                >
                  播放选中
                </button>
                <button
                  type="button"
                  className="secondary"
                  disabled={busy}
                  onClick={() => queue(selectedTracks)}
                >
                  加入待播
                </button>
                {savePlaylist && (
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() => savePlaylist(selectedTracks)}
                  >
                    加入歌单
                  </button>
                )}
                {process && (
                  <button
                    type="button"
                    className="primary"
                    disabled={busy}
                    onClick={() => process(chosen)}
                  >
                    送往刮削整理
                  </button>
                )}
              </>
            )}
            <button
              type="button"
              className="secondary"
              disabled={busy}
              onClick={() => openMoveModal({ files: chosen })}
            >
              移动到…
            </button>
            <button
              type="button"
              className="secondary"
              disabled={busy}
              onClick={() => remove(chosen)}
            >
              删除
            </button>
          </div>
        </div>
      )}
      {editing && (
        <Modal
          title="编辑歌曲信息"
          close={saving ? undefined : () => setEditing(null)}
        >
          <form
            className="library-edit"
            onSubmit={async (event) => {
              event.preventDefault();
              const changedFields: Record<string, string> = {};
              for (const [key, value] of Object.entries(draft)) {
                if ((initialDraft[key] ?? "") !== value) {
                  changedFields[key] = value;
                }
              }
              if (!Object.keys(changedFields).length) {
                setEditing(null);
                return;
              }
              setSaving(true);
              setEditError("");
              try {
                const updated = await api<LibraryFile>(
                  `/files/tags?path=${encodeURIComponent(editing.path)}`,
                  "PATCH",
                  changedFields,
                );
                changed(updated);
                setEditing(null);
              } catch (error) {
                setEditError((error as Error).message);
              } finally {
                setSaving(false);
              }
            }}
          >
            <p>{editing.name}</p>
            <p className="management-help">
              标签写回音频文件，修改的字段会锁定以保留人工整理结果。
            </p>
            {!!editing.locked_fields?.length && (
              <div className="management-help">
                <p>
                  已锁定：
                  {editing.locked_fields
                    .map((key) => tagLabels[key] || key)
                    .join("、")}
                </p>
                <button
                  type="button"
                  className="secondary"
                  disabled={saving}
                  onClick={async () => {
                    setSaving(true);
                    setEditError("");
                    try {
                      await api(
                        `/library/files/locks?path=${encodeURIComponent(editing.path)}`,
                        "DELETE",
                      );
                      const updated = { ...editing, locked_fields: [] };
                      setEditing(updated);
                      changed(updated);
                    } catch (e) {
                      setEditError((e as Error).message);
                    } finally {
                      setSaving(false);
                    }
                  }}
                >
                  解除标签锁定
                </button>
              </div>
            )}
            {Object.entries(tagLabels).map(([key, label]) => (
              <label key={key}>
                {label}
                <input
                  maxLength={300}
                  value={draft[key] || ""}
                  onChange={(e) =>
                    setDraft({ ...draft, [key]: e.target.value })
                  }
                />
              </label>
            ))}
            {editError && <p role="alert">{editError}</p>}
            <div className="management-toolbar">
              <button
                type="button"
                className="secondary"
                disabled={saving}
                onClick={() => setEditing(null)}
              >
                取消
              </button>
              <button className="primary" disabled={saving}>
                {saving ? "正在保存…" : "保存歌曲信息"}
              </button>
            </div>
          </form>
        </Modal>
      )}
      {folderModal && (
        <Modal
          title={
            folderModal.type === "mkdir"
              ? "新建文件夹"
              : folderModal.type === "rename"
                ? "重命名文件夹"
                : folderModal.type === "move"
                  ? "移动到文件夹"
                  : "删除音乐文件夹"
          }
          close={folderBusy ? undefined : () => setFolderModal(null)}
        >
          {folderModal.type === "delete" ? (
            <div className="library-folder-dialog">
              <p>
                将从磁盘永久删除所选 {folderModal.folders.length}{" "}
                个文件夹及其内部的全部音频与附属文件，无法撤销：
              </p>
              <ul className="library-folder-delete-list">
                {folderModal.folders.map((item) => (
                  <li key={item.key}>
                    <Folder aria-hidden="true" />
                    <span>{item.folder}</span>
                  </li>
                ))}
              </ul>
              {folderModalError && <p role="alert">{folderModalError}</p>}
              <div className="dialog-actions">
                <button
                  type="button"
                  className="secondary"
                  disabled={folderBusy}
                  onClick={() => setFolderModal(null)}
                >
                  取消
                </button>
                <button
                  type="button"
                  className="primary danger"
                  disabled={folderBusy}
                  onClick={() => void submitFolderModal()}
                >
                  {folderBusy ? "正在删除…" : "确认删除"}
                </button>
              </div>
            </div>
          ) : (
            <form
              className="library-folder-dialog"
              onSubmit={(event) => {
                event.preventDefault();
                void submitFolderModal();
              }}
            >
              {(folderModal.type === "mkdir" ||
                folderModal.type === "rename") && (
                <>
                  <p className="management-help">
                    {folderModal.type === "mkdir"
                      ? `位置：${folderModal.parent || "根目录"}`
                      : `原文件夹：${folderModal.node.folder}`}
                  </p>
                  <label>
                    {folderModal.type === "mkdir"
                      ? "文件夹名称"
                      : "新文件夹名称"}
                    <input
                      autoFocus
                      maxLength={120}
                      placeholder="输入文件夹名称"
                      value={folderInput}
                      disabled={folderBusy}
                      onChange={(e) => setFolderInput(e.target.value)}
                      required
                    />
                  </label>
                </>
              )}
              {folderModal.type === "move" && (
                <>
                  <p className="management-help">
                    {folderModal.folders?.length
                      ? `将移动文件夹：${folderModal.folders.map((n) => n.label).join("、")}`
                      : `将移动已选的 ${folderModal.files?.length || 0} 个文件`}
                  </p>
                  {rootCandidates.length > 1 && (
                    <label>
                      目标音乐根目录
                      <select
                        value={moveTargetRoot}
                        disabled={folderBusy}
                        onChange={(e) => {
                          setMoveTargetRoot(e.target.value);
                          setMoveTargetFolder("");
                        }}
                      >
                        {rootCandidates.map((r) => (
                          <option key={r} value={r}>
                            {rootDisplayName(r)} ({r})
                          </option>
                        ))}
                      </select>
                    </label>
                  )}
                  <label>
                    目标文件夹
                    <select
                      value={moveTargetFolder}
                      disabled={folderBusy}
                      onChange={(e) => setMoveTargetFolder(e.target.value)}
                    >
                      <option value="">根目录</option>
                      {manageableNodes
                        .filter((n) => {
                          if (moveTargetRoot && n.root !== moveTargetRoot)
                            return false;
                          if (
                            folderModal.folders?.some(
                              (src) =>
                                src.root === n.root &&
                                (n.folder === src.folder ||
                                  n.folder.startsWith(src.folder + "/")),
                            )
                          ) {
                            return false;
                          }
                          return true;
                        })
                        .sort((a, b) =>
                          a.folder.localeCompare(b.folder, "zh-CN"),
                        )
                        .map((n) => (
                          <option key={n.key} value={n.folder}>
                            {n.folder}
                          </option>
                        ))}
                    </select>
                  </label>
                  <div className="library-inline-mkdir">
                    <button
                      type="button"
                      className="secondary"
                      disabled={folderBusy}
                      onClick={() => setShowInlineMkdir((prev) => !prev)}
                    >
                      <FolderPlus />
                      <span>
                        {showInlineMkdir
                          ? "取消新建子文件夹"
                          : "在目标位置新建子文件夹"}
                      </span>
                    </button>
                    {showInlineMkdir && (
                      <input
                        maxLength={120}
                        placeholder="新子文件夹名称"
                        value={inlineNewDir}
                        disabled={folderBusy}
                        onChange={(e) => setInlineNewDir(e.target.value)}
                      />
                    )}
                  </div>
                </>
              )}
              {folderModalError && <p role="alert">{folderModalError}</p>}
              <div className="dialog-actions">
                <button
                  type="button"
                  className="secondary"
                  disabled={folderBusy}
                  onClick={() => setFolderModal(null)}
                >
                  取消
                </button>
                <button
                  type="submit"
                  className="primary"
                  disabled={
                    folderBusy ||
                    ((folderModal.type === "mkdir" ||
                      folderModal.type === "rename") &&
                      !folderInput.trim())
                  }
                >
                  {folderBusy
                    ? "正在处理…"
                    : folderModal.type === "move"
                      ? "确认移动"
                      : "确认"}
                </button>
              </div>
            </form>
          )}
        </Modal>
      )}
    </section>
  );
}
