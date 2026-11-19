package operations

import "strings"

type coreSpecSeed struct {
	Method string
	Path   string
	Write  bool
	Desc   string
}

var coreSpecs = []coreSpecSeed{
	{"POST", "/api/playback/events", true, " · playback_event"},
	{"GET", "/api/health", false, " · health"},
	{"GET", "/api/config", false, "音源代理下载配置 · read_config"},
	{"GET", "/api/engine/config", false, "音源引擎配置更新 · read_engine_config"},
	{"PUT", "/api/engine/config", true, "音源引擎配置更新 · write_engine_config"},
	{"PUT", "/api/config", true, "音源代理下载配置 · write_config"},
	{"POST", "/api/search/jobs", true, " · search_start"},
	{"DELETE", "/api/search/jobs/{ident}", true, " · search_stop"},
	{"POST", "/api/tracks/{sid}/playback", true, " · playback"},
	{"POST", "/api/tracks/{sid}/versions", true, " · versions"},
	{"GET", "/api/tracks/{sid}/lyrics", false, " · lyrics"},
	{"GET", "/api/tracks/{sid}/episodes", false, " · episodes"},
	{"GET", "/api/favorites", false, "收藏 · favorites"},
	{"PUT", "/api/favorites/{sid}", true, "收藏 · favorite_add"},
	{"DELETE", "/api/favorites/{sid}", true, "收藏 · favorite_remove"},
	{"GET", "/api/playlist", false, "播放队列与歌单导入 · playlist_read"},
	{"PUT", "/api/playlist", true, "播放队列与歌单导入 · playlist_write"},
	{"POST", "/api/playlist/import", true, "播放队列与歌单导入 · playlist_import"},
	{"GET", "/api/playlists", false, "歌单 · collections"},
	{"POST", "/api/playlists", true, "歌单 · collection_create"},
	{"GET", "/api/playlists/{ident}", false, "歌单 · collection_read"},
	{"POST", "/api/playlists/{ident}/tracks", true, "歌单 · collection_add_tracks"},
	{"PATCH", "/api/playlists/{ident}", true, "歌单 · collection_rename"},
	{"DELETE", "/api/playlists/{ident}", true, "歌单 · collection_delete"},
	{"DELETE", "/api/playlists/{ident}/tracks/{sid}", true, "歌单 · collection_remove_track"},
	{"GET", "/api/downloads", false, "下载任务 · downloads"},
	{"POST", "/api/downloads", true, "下载任务 · enqueue"},
	{"POST", "/api/downloads/{ident}/{action}", true, "下载任务 · control_download"},
	{"DELETE", "/api/downloads/{ident}", true, "下载任务 · remove_download"},
	{"GET", "/api/files", false, "本地音乐文件与标签 · files"},
	{"PATCH", "/api/files/tags", true, "本地音乐文件与标签 · edit_file_tags"},
	{"DELETE", "/api/files", true, "本地音乐文件与标签 · delete_library_file"},
	{"GET", "/api/navidrome/capabilities", false, "Navidrome 曲库歌单扫描 · navidrome_capabilities"},
	{"GET", "/api/navidrome/songs/{ident}/deletion", false, "Navidrome 曲库歌单扫描 · navidrome_delete_preview"},
	{"DELETE", "/api/navidrome/songs/{ident}", true, "Navidrome 曲库歌单扫描 · navidrome_delete_song"},
	{"GET", "/api/library/settings", false, "音乐库刮削整理归档监控设置 · settings"},
	{"PUT", "/api/library/settings", true, "音乐库刮削整理归档监控设置 · save_settings"},
	{"PUT", "/api/library/credentials", true, "音乐库刮削整理归档监控设置 · save_credentials"},
	{"GET", "/api/library/watch", false, "音乐库刮削整理归档监控设置 · watch_status"},
	{"POST", "/api/library/watch/scan", true, "音乐库刮削整理归档监控设置 · watch_scan"},
	{"PUT", "/api/library/navidrome", true, "音乐库刮削整理归档监控设置 · save_navidrome"},
	{"GET", "/api/library/jobs", false, "音乐库刮削整理归档监控设置 · jobs"},
	{"GET", "/api/library/versions", false, "音乐库刮削整理归档监控设置 · versions"},
	{"GET", "/api/library/jobs/{ident}", false, "音乐库刮削整理归档监控设置 · job"},
	{"POST", "/api/library/jobs", true, "音乐库刮削整理归档监控设置 · process"},
	{"POST", "/api/library/jobs/{ident}/apply", true, "音乐库刮削整理归档监控设置 · apply"},
	{"POST", "/api/library/jobs/{ident}/search", true, "音乐库刮削整理归档监控设置 · search_candidates"},
	{"POST", "/api/library/jobs/{ident}/preview", true, "音乐库刮削整理归档监控设置 · preview"},
	{"POST", "/api/library/jobs/{ident}/cancel", true, "音乐库刮削整理归档监控设置 · cancel"},
	{"POST", "/api/library/jobs/{ident}/retry", true, "音乐库刮削整理归档监控设置 · retry"},
	{"POST", "/api/library/jobs/{ident}/restore", true, "音乐库刮削整理归档监控设置 · restore"},
	{"DELETE", "/api/library/files/locks", true, "音乐库刮削整理归档监控设置 · unlock"},
	{"GET", "/api/navidrome/playlists", false, "Navidrome 曲库歌单扫描 · playlists"},
	{"GET", "/api/navidrome/playlists/{ident}", false, "Navidrome 曲库歌单扫描 · playlist"},
	{"POST", "/api/navidrome/playlists", true, "Navidrome 曲库歌单扫描 · create_playlist"},
	{"PATCH", "/api/navidrome/playlists/{ident}", true, "Navidrome 曲库歌单扫描 · rename_playlist"},
	{"DELETE", "/api/navidrome/playlists/{ident}", true, "Navidrome 曲库歌单扫描 · delete_playlist"},
	{"POST", "/api/navidrome/playlists/{ident}/tracks", true, "Navidrome 曲库歌单扫描 · add_songs"},
	{"POST", "/api/navidrome/playlists/{ident}/tracks/remove", true, "Navidrome 曲库歌单扫描 · remove_songs"},
	{"GET", "/api/navidrome/songs", false, "Navidrome 曲库歌单扫描 · songs"},
	{"GET", "/api/navidrome/albums", false, "Navidrome 曲库歌单扫描 · albums"},
	{"GET", "/api/navidrome/albums/{ident}", false, "Navidrome 曲库歌单扫描 · album"},
	{"GET", "/api/navidrome/artists", false, "Navidrome 曲库歌单扫描 · artists"},
	{"GET", "/api/navidrome/artists/{ident}", false, "Navidrome 曲库歌单扫描 · artist"},
	{"GET", "/api/navidrome/search", false, "Navidrome 曲库歌单扫描 · search"},
	{"POST", "/api/navidrome/test", true, "Navidrome 曲库歌单扫描 · test"},
	{"GET", "/api/navidrome/scan", false, "Navidrome 曲库歌单扫描 · scan_status"},
	{"POST", "/api/navidrome/scan", true, "Navidrome 曲库歌单扫描 · scan"},
	{"GET", "/api/meta", false, " · meta"},
	{"GET", "/api/engine/latest", false, "音源引擎配置更新 · enginelatest"},
	{"POST", "/api/engine/update", true, "音源引擎配置更新 · engineupdate"},
	{"GET", "/api/engine/update", false, "音源引擎配置更新 · engineupdatestatus"},
	{"POST", "/api/restart", true, "重启服务 · restartserver"},
	{"POST", "/api/search", true, " · search"},
	{"GET", "/api/song/{sid}", false, " · songdetail"},
	{"POST", "/api/resolve", true, " · resolve"},
	{"GET", "/api/song/{sid}/episodes", false, " · episodes"},
	{"POST", "/api/playlist", true, "播放队列与歌单导入 · playlist"},
	{"POST", "/api/songs/validate", true, " · validatesongs"},
	{"POST", "/api/download", true, " · download"},
	{"POST", "/api/tasks/{task_id}/retry", true, " · retrytask"},
	{"POST", "/api/tasks/delete", true, " · deletetasks"},
	{"POST", "/api/tasks/clear-finished", true, " · clearfinishedtasks"},
	{"POST", "/api/open-dir", true, " · opendir"},
	{"POST", "/api/cache/clear", true, "缓存清理 · clearcache"},
	{"GET", "/api/tasks", false, " · tasks"},
	{"DELETE", "/api/file", true, " · deletefile"},
	{"GET", "/api/logs", false, "运行日志 · logs"},
	{"POST", "/api/logs/clear", true, "运行日志 · clearlogs"},
	{"GET", "/api/agent/settings", false, "AI 助手配置 · settings_read"},
	{"PUT", "/api/agent/settings", true, "AI 助手配置 · settings_write"},
	{"POST", "/api/agent/settings/test", true, "AI 助手配置 · test"},
	{"POST", "/api/agent/settings/test-capability/{capability}", true, "AI 助手配置 · test_capability"},
	{"POST", "/api/agent/settings/models", true, "AI 助手配置 · models"},
	{"GET", "/api/bots/config", false, "通知渠道与机器人配置 · read_config"},
	{"PUT", "/api/bots/config", true, "通知渠道与机器人配置 · write_config"},
	{"POST", "/api/bots/test", true, "通知渠道与机器人配置 · test_connection"},
	{"POST", "/api/bots/discord/commands", true, "通知渠道与机器人配置 · register_discord"},
}

// RegisterCoreSpecs seeds the registry with the 98 operation specs matching contracts/v1/operations.json.
func RegisterCoreSpecs(r *Registry) error {
	for _, seed := range coreSpecs {
		alias := seed.Method + " " + seed.Path
		effect := classifyEffect(seed.Path, seed.Write)
		perm := defaultPermissionForEffect(effect)
		idemp := IdempotencyNotApplicable
		if seed.Write {
			idemp = IdempotencyOptional
		}
		if err := r.RegisterSpec(Spec{
			ID:                 alias,
			Alias:              alias,
			Method:             seed.Method,
			Path:               seed.Path,
			Description:        seed.Desc,
			Write:              seed.Write,
			Effect:             effect,
			RequiredPermission: perm,
			Idempotency:        idemp,
			SensitiveFields:    []string{"cookie", "password", "token", "api_key", "secret"},
		}); err != nil {
			return err
		}
	}
	return nil
}

func classifyEffect(path string, write bool) Effect {
	if !write {
		return EffectRead
	}
	switch {
	case path == "/api/config",
		path == "/api/engine/config",
		path == "/api/agent/settings",
		path == "/api/library/settings",
		path == "/api/library/credentials",
		path == "/api/library/navidrome",
		path == "/api/bots/config",
		path == "/api/bots/test",
		path == "/api/bots/discord/commands":
		return EffectAdmin
	case strings.HasPrefix(path, "/api/navidrome/"),
		strings.HasPrefix(path, "/api/bots/"),
		strings.HasPrefix(path, "/api/agent/settings/test"),
		strings.HasPrefix(path, "/api/agent/settings/models"),
		path == "/api/resolve":
		return EffectExternalWrite
	case strings.HasPrefix(path, "/api/engine/update"),
		path == "/api/restart",
		path == "/api/cache/clear",
		path == "/api/logs/clear",
		path == "/api/open-dir":
		return EffectMaintenance
	default:
		return EffectLocalWrite
	}
}
