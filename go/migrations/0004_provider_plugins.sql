CREATE TABLE IF NOT EXISTS provider_plugins (
  id TEXT PRIMARY KEY,
  enabled INTEGER NOT NULL DEFAULT 1,
  source_url TEXT NOT NULL DEFAULT '',
  sha256 TEXT NOT NULL DEFAULT '',
  manifest_json TEXT NOT NULL,
  settings_json TEXT NOT NULL DEFAULT '{}',
  allowed_origins_json TEXT NOT NULL DEFAULT '[]',
  last_error TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
