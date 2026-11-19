CREATE INDEX IF NOT EXISTS assets_status_path_id ON assets(status, path_key, id);
CREATE INDEX IF NOT EXISTS assets_status_title_id ON assets(status, title, id);
CREATE INDEX IF NOT EXISTS assets_status_artist_id ON assets(status, artist, id);
CREATE INDEX IF NOT EXISTS assets_status_name_id ON assets(status, name, id);
CREATE INDEX IF NOT EXISTS assets_status_size_id ON assets(status, size, id);
CREATE INDEX IF NOT EXISTS assets_status_updated_id ON assets(status, updated_at, id);
