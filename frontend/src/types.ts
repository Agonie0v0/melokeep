export interface Quality {
  id: string;
  label: string;
  codec: string;
  bitrate: number | null;
  lossless: boolean;
  availability: string;
  evidence: string;
  size_bytes?: number | null;
  size_label?: string | null;
}
export interface Track {
  playlist_index?: number;
  id: string;
  source: string;
  source_id: string;
  title: string;
  artist: string;
  album: string;
  duration: number;
  cover: string | null;
  size_bytes?: number | null;
  size_label?: string | null;
  episodes?: number;
  root_source?: string;
  qualities: Quality[];
  playback: {
    state: string;
    url: string | null;
    codec: string | null;
    bitrate: number | null;
    detail: string | null;
    preview_only: boolean;
  };
}
export interface EpisodePage {
  items: Track[];
  page: number;
  page_size: number;
  total: number;
  has_more: boolean;
}
export type BatchQualityPolicy = "highest" | "standard";
export interface SourceCapability {
  tier?: string;
  note?: string;
  search: boolean;
  playlist: boolean;
  lyrics?: boolean;
  metadata?: boolean;
  episodes?: boolean;
  partial?: boolean;
}
export interface AvailableSource {
  id: string;
  label: string;
  category?: string;
  engine_name?: string;
  origin?: "builtin" | "plugin" | string;
  plugin_id?: string;
  version?: string;
  description?: string;
  proxy_recommended?: boolean;
  init_error?: string | null;
  capability?: SourceCapability;
}
export interface PluginSettingField {
  key: string;
  label: string;
  type: string;
  placeholder?: string;
  required?: boolean;
  default_value?: string;
}
export interface PluginManifest {
  api_version: number;
  id: string;
  name: string;
  version: string;
  description?: string;
  category?: string;
  capabilities: SourceCapability;
  settings?: PluginSettingField[];
}
export interface ProviderPlugin {
  id: string;
  enabled: boolean;
  source_url?: string;
  sha256?: string;
  manifest: PluginManifest;
  settings: Record<string, string>;
  allowed_origins: string[];
  last_error?: string;
  created_at: number;
  updated_at: number;
}
export interface PluginInspectResult {
  sha256: string;
  manifest: PluginManifest;
  permissions: string[];
  warnings: string[];
}
export interface PluginArchiveEntryInspect {
  entry_path: string;
  ok: boolean;
  error?: string;
  sha256?: string;
  manifest?: PluginManifest;
  permissions?: string[];
  warnings?: string[];
}
export interface PluginArchiveInspectResult {
  total: number;
  valid_count: number;
  invalid_count: number;
  entries: PluginArchiveEntryInspect[];
}
export interface PluginArchiveInstallItem {
  entry_path: string;
  id?: string;
  ok: boolean;
  error?: string;
  plugin?: ProviderPlugin;
}
export interface PluginArchiveInstallResult {
  total: number;
  installed_count: number;
  failed_count: number;
  installed: ProviderPlugin[];
  results: PluginArchiveInstallItem[];
}
export interface PluginLogEntry {
  level: string;
  message: string;
  time: string;
}
export type PluginTestAction =
  "search" | "resolve" | "playlist" | "episodes" | "lyrics" | "metadata";
export interface PluginTestResult {
  ok: boolean;
  action: string;
  duration_ms: number;
  result?: unknown;
  logs?: PluginLogEntry[];
  error?: string;
}
export interface Config {
  available_sources?: AvailableSource[];
  engine?: string;
  sources: string[];
  results_per_source: number;
  search_timeout_seconds: number;
  playback_timeout_seconds: number;
  download_concurrency: number;
  download_layout: string;
  lyrics_mode: string;
  embed_metadata: boolean;
  source_settings: Record<string, { cookie: string; proxy: string }>;
}
export interface DownloadJob {
  id: string;
  track: Track;
  quality_id: string;
  quality_label: string;
  download_source?: string;
  status: string;
  bytes: number;
  total_bytes: number | null;
  detail: string;
  file: string | null;
  processing?: { id: string; status: string; detail: string };
}
export interface LibraryFile {
  path: string;
  name: string;
  size: number;
  root?: string;
  folder?: string;
  track?: Track;
  editable?: boolean;
  metadata?: Record<string, string>;
  locked_fields?: string[];
}

export interface LibraryFilesPage {
  items: LibraryFile[];
  next_cursor: string | null;
  index_revision: number;
  indexing: boolean;
  folders?: { root: string; folder: string }[];
}

export interface LibraryRefreshResult {
  roots_scanned: number;
  scanned_files: number;
  unchanged_files: number;
  updated_or_new: number;
  marked_missing: number;
  unhealthy_roots?: string[];
}

export interface NavidromeConfig {
  enabled: boolean;
  url: string;
  username: string;
  auto_scan: boolean;
  delete_enabled?: boolean;
  music_root?: string;
  server_music_root?: string;
  has_password: boolean;
  has_api_key: boolean;
}
export interface LibraryConfig {
  auto_scrape: boolean;
  auto_organize: boolean;
  library_dir: string;
  file_root: string;
  folder_template: string;
  filename_template: string;
  sources: string[];
  overwrite: boolean;
  lyrics_mode: string;
  cover_mode: string;
  fields: string[];
  backup_days: number;
  navidrome: NavidromeConfig;
  metadata_sources?: MetadataSource[];
  chinese?: string;
  repair_encoding?: boolean;
  watch_enabled?: boolean;
  watch_dirs?: string[];
  watch_interval?: number;
  watch_stable_seconds?: number;
  watch_action?: string;
  watch_apply?: boolean;
  watch_failure_dir?: string;
  has_acoustid_key?: boolean;
  min_match_score?: number;
  tools?: Record<string, boolean>;
}
export interface MetadataSource {
  id: string;
  label: string;
  description: string;
  fields: string[];
  note: string;
  url: string;
  builtin?: boolean;
  recommended?: boolean;
  recommend_reason?: string;
}
export interface MetadataCandidate {
  id: string;
  source: string;
  title: string;
  artist: string;
  album: string;
  score: number;
  reasons: string[];
  conflicts: string[];
  field_sources?: Record<string, string>;
  [key: string]: unknown;
}
export interface LibraryJobItem {
  id: string;
  path: string;
  name: string;
  original_path: string;
  target: string;
  status: string;
  before: Record<string, string>;
  after: Record<string, string>;
  candidates: MetadataCandidate[];
  chosen: string | null;
  locks: string[];
  warnings: string[];
  error?: string;
  operation_id?: string;
  quality?: Record<string, number | null>;
  outputs?: { path: string; start?: number; end?: number }[];
  artwork?: { artist: string; source: string; url: string };
}
export interface LibraryJob {
  id: string;
  action: string;
  status: string;
  created_at: number;
  updated_at: number;
  total: number;
  processed: number;
  detail: string;
  items?: LibraryJobItem[];
  warnings: string[];
  settings?: Pick<LibraryConfig, "sources" | "overwrite" | "fields">;
  duplicates?: { id: string; name: string; path: string }[][];
}
export interface SourceStatus {
  state: string;
  count?: number;
  detail?: string;
}
export interface PlaylistIssue {
  source_id: string;
  title: string;
  artist: string;
  reason: string;
}
export interface SavedPlaylist {
  read_only?: boolean;
  can_manage?: boolean;
  revision?: string;
  owner?: string;
  id: string;
  name: string;
  source: string;
  source_url: string;
  created_at: number;
  tracks: Track[];
  issues: PlaylistIssue[];
  detail: string;
  task_id: string;
}
export interface PlaylistSummary extends Omit<
  SavedPlaylist,
  "tracks" | "issues"
> {
  track_count: number;
  issue_count: number;
  cover: string | null;
}

export interface BuildInfo {
  version: string;
  commit?: string;
  short_commit?: string;
  ref?: string;
  build_time?: string;
  dirty?: boolean;
  in_container: boolean;
}

export interface UpdateAsset {
  name: string;
  download_url: string;
  size: number;
  content_type?: string;
}

export interface UpdateTarget {
  version?: string;
  tag?: string;
  commit?: string;
  short_commit?: string;
  title?: string;
  published_at?: string;
  url?: string;
  assets?: UpdateAsset[];
}

export type SelfUpdateStage =
  "idle" | "pulling" | "recreating" | "restarting" | "up_to_date" | "failed";

export interface SelfUpdateStatus {
  supported: boolean;
  mode: "docker_socket" | "webhook" | "none" | string;
  reason?: string;
  in_progress: boolean;
  stage?: SelfUpdateStage | string;
  message?: string;
  progress?: number;
  target_image?: string;
  started_at?: string;
  error?: string;
}

export interface UpdateCommitItem {
  sha: string;
  short: string;
  title: string;
  author?: string;
  date?: string;
  url?: string;
}

export interface SystemUpdateInfo {
  repo: string;
  mode: "release" | "compare" | "commits" | "error";
  current: BuildInfo;
  latest?: UpdateTarget;
  has_update: boolean;
  comparable: boolean;
  ahead_by?: number;
  behind_by?: number;
  release_notes?: string;
  commits: UpdateCommitItem[];
  checked_at: string;
  cached: boolean;
  error?: string;
  self_update?: SelfUpdateStatus;
}
