UPDATE assets
SET path_key = path_key || '#deleted:' || id
WHERE status = 'deleted' AND instr(path_key, '#deleted:') = 0;

CREATE INDEX IF NOT EXISTS assets_status_album_id ON assets(status, album COLLATE NOCASE, id);
CREATE INDEX IF NOT EXISTS assets_status_duration_id ON assets(status, duration_ms, id);
CREATE INDEX IF NOT EXISTS assets_root_status_folder_id ON assets(root_id, status, folder, id);
