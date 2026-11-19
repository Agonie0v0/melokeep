CREATE TABLE IF NOT EXISTS settings (
    namespace TEXT PRIMARY KEY,
    body_json TEXT NOT NULL CHECK (json_valid(body_json)),
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS import_runs (
    id TEXT PRIMARY KEY,
    source_fingerprint TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (state IN ('running', 'completed', 'failed')),
    report_json TEXT NOT NULL CHECK (json_valid(report_json)),
    created_at INTEGER NOT NULL,
    completed_at INTEGER
);

CREATE TABLE IF NOT EXISTS legacy_records (
    kind TEXT NOT NULL,
    legacy_id TEXT NOT NULL,
    body_json TEXT NOT NULL CHECK (json_valid(body_json)),
    source_updated INTEGER NOT NULL DEFAULT 0,
    import_id TEXT NOT NULL REFERENCES import_runs(id) ON DELETE RESTRICT,
    PRIMARY KEY (kind, legacy_id)
);

CREATE TABLE IF NOT EXISTS roots (
    id TEXT PRIMARY KEY,
    canonical_path TEXT NOT NULL UNIQUE,
    path_case_mode TEXT NOT NULL CHECK (path_case_mode IN ('sensitive', 'insensitive')),
    capabilities_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(capabilities_json)),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    scan_generation INTEGER NOT NULL DEFAULT 0 CHECK (scan_generation >= 0),
    healthy INTEGER NOT NULL DEFAULT 1 CHECK (healthy IN (0, 1)),
    last_error TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS provider_tracks (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    locator TEXT NOT NULL,
    root_source TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT NOT NULL,
    duration_ms INTEGER NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    metadata_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(metadata_json)),
    provider_data_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(provider_data_json)),
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    deleted_at INTEGER,
    updated_at INTEGER NOT NULL,
    UNIQUE (source, locator)
);
CREATE INDEX IF NOT EXISTS provider_tracks_source_updated ON provider_tracks(source, updated_at DESC, id);

CREATE TABLE IF NOT EXISTS media_variants (
    id TEXT PRIMARY KEY,
    track_id TEXT NOT NULL REFERENCES provider_tracks(id) ON DELETE RESTRICT,
    variant_key TEXT NOT NULL,
    upstream_id TEXT NOT NULL DEFAULT '',
    label TEXT NOT NULL DEFAULT '',
    codec TEXT NOT NULL DEFAULT '',
    container TEXT NOT NULL DEFAULT '',
    bitrate INTEGER CHECK (bitrate IS NULL OR bitrate >= 0),
    sample_rate INTEGER CHECK (sample_rate IS NULL OR sample_rate >= 0),
    bit_depth INTEGER CHECK (bit_depth IS NULL OR bit_depth >= 0),
    lossless INTEGER NOT NULL DEFAULT 0 CHECK (lossless IN (0, 1)),
    availability TEXT NOT NULL DEFAULT '',
    evidence TEXT NOT NULL DEFAULT '',
    protocol TEXT NOT NULL DEFAULT '',
    size_bytes INTEGER CHECK (size_bytes IS NULL OR size_bytes >= 0),
    duration_ms INTEGER NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    preview_only INTEGER NOT NULL DEFAULT 0 CHECK (preview_only IN (0, 1)),
    metadata_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(metadata_json)),
    updated_at INTEGER NOT NULL,
    UNIQUE (track_id, variant_key)
);

CREATE TABLE IF NOT EXISTS public_refs (
    public_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('candidate', 'track', 'asset', 'navidrome')),
    target_id TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT '',
    source_id TEXT NOT NULL DEFAULT '',
    quality_hint TEXT NOT NULL DEFAULT '',
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS public_refs_target ON public_refs(kind, target_id);

CREATE TABLE IF NOT EXISTS assets (
    id TEXT PRIMARY KEY,
    root_id TEXT NOT NULL REFERENCES roots(id) ON DELETE RESTRICT,
    rel_path TEXT NOT NULL,
    path_key TEXT NOT NULL,
    folder TEXT NOT NULL DEFAULT '',
    name TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL CHECK (status IN ('active', 'missing', 'deleted')),
    editable INTEGER NOT NULL DEFAULT 1 CHECK (editable IN (0, 1)),
    size INTEGER NOT NULL DEFAULT 0 CHECK (size >= 0),
    mtime_ns INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    title TEXT NOT NULL DEFAULT '',
    artist TEXT NOT NULL DEFAULT '',
    album TEXT NOT NULL DEFAULT '',
    duration_ms INTEGER NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    metadata_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(metadata_json)),
    locks_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(locks_json)),
    origin_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(origin_json)),
    indexed_generation INTEGER NOT NULL DEFAULT 0 CHECK (indexed_generation >= 0),
    updated_at INTEGER NOT NULL,
    UNIQUE (root_id, path_key)
);
CREATE INDEX IF NOT EXISTS assets_folder ON assets(root_id, status, path_key, id);
CREATE INDEX IF NOT EXISTS assets_artist_album ON assets(root_id, status, artist, album, id);
CREATE INDEX IF NOT EXISTS assets_status_path_id ON assets(status, path_key, id);

CREATE TABLE IF NOT EXISTS asset_aliases (
    alias_token TEXT PRIMARY KEY,
    asset_id TEXT NOT NULL REFERENCES assets(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS asset_aliases_asset ON asset_aliases(asset_id);

CREATE TABLE IF NOT EXISTS downloads (
    id TEXT PRIMARY KEY,
    track_id TEXT REFERENCES provider_tracks(id) ON DELETE RESTRICT,
    variant_id TEXT REFERENCES media_variants(id) ON DELETE RESTRICT,
    public_id TEXT NOT NULL DEFAULT '',
    quality_id TEXT NOT NULL DEFAULT '',
    quality_label TEXT NOT NULL DEFAULT '',
    download_source TEXT NOT NULL DEFAULT '',
    owner_key TEXT NOT NULL,
    dedupe_key TEXT NOT NULL,
    intent_scope TEXT NOT NULL DEFAULT 'default',
    state TEXT NOT NULL CHECK (state IN ('queued', 'resolving', 'downloading', 'verifying', 'publishing', 'pause_requested', 'paused', 'cancel_requested', 'cancelled', 'retry_wait', 'complete', 'failed', 'needs_attention')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    bytes_done INTEGER NOT NULL DEFAULT 0 CHECK (bytes_done >= 0),
    bytes_total INTEGER CHECK (bytes_total IS NULL OR bytes_total >= 0),
    cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK (cancel_requested IN (0, 1)),
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    detail TEXT NOT NULL DEFAULT '',
    file_token TEXT,
    intent_nonce TEXT NOT NULL DEFAULT '',
    next_attempt_at INTEGER NOT NULL DEFAULT 0,
    error_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(error_json)),
    settings_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(settings_json)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS downloads_state_created ON downloads(state, created_at DESC, id);
CREATE UNIQUE INDEX IF NOT EXISTS downloads_active_dedupe ON downloads(dedupe_key, intent_scope) WHERE state IN ('queued', 'resolving', 'downloading', 'verifying', 'publishing', 'pause_requested', 'paused', 'retry_wait');

CREATE TABLE IF NOT EXISTS download_outputs (
    download_id TEXT NOT NULL REFERENCES downloads(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    asset_id TEXT NOT NULL REFERENCES assets(id) ON DELETE RESTRICT,
    role TEXT NOT NULL DEFAULT 'audio',
    rel_path TEXT NOT NULL DEFAULT '',
    lyrics_path TEXT NOT NULL DEFAULT '',
    cover_path TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    PRIMARY KEY (download_id, ordinal),
    UNIQUE (download_id, asset_id)
);

CREATE TABLE IF NOT EXISTS transfer_checkpoints (
    download_id TEXT PRIMARY KEY REFERENCES downloads(id) ON DELETE RESTRICT,
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    part_rel_path TEXT NOT NULL,
    url_fingerprint TEXT NOT NULL DEFAULT '',
    validator_type TEXT NOT NULL DEFAULT '',
    validator TEXT NOT NULL DEFAULT '',
    expected_length INTEGER CHECK (expected_length IS NULL OR expected_length >= 0),
    durable_bytes INTEGER NOT NULL DEFAULT 0 CHECK (durable_bytes >= 0),
    complete INTEGER NOT NULL DEFAULT 0 CHECK (complete IN (0, 1)),
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS library_jobs (
    id TEXT PRIMARY KEY,
    source_download_id TEXT REFERENCES downloads(id) ON DELETE RESTRICT,
    action TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('queued', 'scanning', 'preview_ready', 'needs_review', 'applying', 'complete', 'partial', 'paused', 'cancelled', 'failed', 'needs_attention')),
    settings_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(settings_json)),
    options_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(options_json)),
    warnings_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(warnings_json)),
    detail TEXT NOT NULL DEFAULT '',
    total INTEGER NOT NULL DEFAULT 0 CHECK (total >= 0),
    processed INTEGER NOT NULL DEFAULT 0 CHECK (processed >= 0),
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    owner_key TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS library_jobs_source_download ON library_jobs(source_download_id) WHERE source_download_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS library_jobs_state_created ON library_jobs(state, created_at DESC, id);
CREATE INDEX IF NOT EXISTS library_jobs_recent ON library_jobs(created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS library_items (
    job_id TEXT NOT NULL REFERENCES library_jobs(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    item_id TEXT NOT NULL,
    asset_id TEXT REFERENCES assets(id) ON DELETE RESTRICT,
    path_token TEXT NOT NULL DEFAULT '',
    name TEXT NOT NULL DEFAULT '',
    original_path TEXT NOT NULL DEFAULT '',
    target_path TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL CHECK (state IN ('queued', 'preview_ready', 'needs_review', 'applying', 'complete', 'skipped', 'failed')),
    expected_revision INTEGER NOT NULL DEFAULT 1 CHECK (expected_revision >= 1),
    before_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(before_json)),
    proposed_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(proposed_json)),
    selected_candidate_json TEXT NOT NULL DEFAULT 'null' CHECK (json_valid(selected_candidate_json)),
    locks_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(locks_json)),
    warnings_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(warnings_json)),
    error_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(error_json)),
    operation_id TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (job_id, ordinal),
    UNIQUE (job_id, item_id)
);
CREATE INDEX IF NOT EXISTS library_items_asset ON library_items(asset_id, job_id);

CREATE TABLE IF NOT EXISTS file_operations (
    id TEXT PRIMARY KEY,
    job_id TEXT REFERENCES library_jobs(id) ON DELETE RESTRICT,
    item_ordinal INTEGER,
    asset_id TEXT REFERENCES assets(id) ON DELETE RESTRICT,
    kind TEXT NOT NULL CHECK (kind IN ('download_publish', 'edit', 'move', 'derive', 'restore', 'delete')),
    state TEXT NOT NULL CHECK (state IN ('planned', 'prepared', 'publishing', 'committed', 'cleaned', 'rolling_back', 'rolled_back', 'backup_expired', 'needs_attention')),
    before_asset_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(before_asset_json)),
    after_asset_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(after_asset_json)),
    error_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(error_json)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    committed_at INTEGER,
    expires_at INTEGER
);
CREATE INDEX IF NOT EXISTS file_operations_state_created ON file_operations(state, created_at, id);
CREATE INDEX IF NOT EXISTS file_operations_asset_created ON file_operations(asset_id, created_at, id);

CREATE TABLE IF NOT EXISTS operation_files (
    operation_id TEXT NOT NULL REFERENCES file_operations(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    role TEXT NOT NULL,
    source_ref_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(source_ref_json)),
    target_ref_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(target_ref_json)),
    stage_ref_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(stage_ref_json)),
    backup_ref_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(backup_ref_json)),
    before_hash TEXT NOT NULL DEFAULT '',
    after_hash TEXT NOT NULL DEFAULT '',
    phase TEXT NOT NULL,
    identity_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(identity_json)),
    PRIMARY KEY (operation_id, ordinal)
);

CREATE TABLE IF NOT EXISTS collections (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('playlist', 'favorites', 'play_queue')),
    owner_key TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'local',
    source_url TEXT NOT NULL DEFAULT '',
    detail TEXT NOT NULL DEFAULT '',
    task_id TEXT NOT NULL DEFAULT '',
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS collections_singleton_kind ON collections(owner_key, kind) WHERE kind IN ('favorites', 'play_queue');

CREATE TABLE IF NOT EXISTS collection_items (
    collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE RESTRICT,
    position INTEGER NOT NULL CHECK (position >= 0),
    public_ref TEXT NOT NULL REFERENCES public_refs(public_id) ON DELETE RESTRICT,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (collection_id, position)
);
CREATE INDEX IF NOT EXISTS collection_items_ref ON collection_items(public_ref);

CREATE TABLE IF NOT EXISTS durable_records (
    namespace TEXT NOT NULL,
    id TEXT NOT NULL,
    body_json TEXT NOT NULL CHECK (json_valid(body_json)),
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (namespace, id)
);

CREATE TABLE IF NOT EXISTS outbox (
    id TEXT PRIMARY KEY,
    channel TEXT NOT NULL,
    recipient_key TEXT NOT NULL,
    event_key TEXT NOT NULL,
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    state TEXT NOT NULL CHECK (state IN ('pending', 'sending', 'sent', 'retry_wait', 'delivery_unknown', 'failed', 'cancelled')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    next_attempt_at INTEGER NOT NULL DEFAULT 0,
    receipt_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(receipt_json)),
    last_error TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (channel, recipient_key, event_key)
);
CREATE INDEX IF NOT EXISTS outbox_state_next ON outbox(state, next_attempt_at, id);

CREATE TABLE IF NOT EXISTS inbox (
    channel TEXT NOT NULL,
    event_id TEXT NOT NULL,
    sender_id TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL CHECK (state IN ('received', 'processed', 'rejected')),
    received_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    result_ref TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (channel, event_id)
);
CREATE INDEX IF NOT EXISTS inbox_expires ON inbox(expires_at);

CREATE TABLE IF NOT EXISTS operation_requests (
    owner_key TEXT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    result_ref TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_key, operation, idempotency_key)
);
CREATE INDEX IF NOT EXISTS operation_requests_expires ON operation_requests(expires_at);
