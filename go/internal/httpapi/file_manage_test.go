package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/library/index"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func TestFolderManagementMkdirRenameMoveAndBatchDelete(t *testing.T) {
	srv, st, musicRoot := newTestServer(t, nil)
	ctx := context.Background()

	// Seed two active audio assets inside Singer/Album and a loose folder.
	albumDir := filepath.Join(musicRoot, "Singer", "Album")
	if err := os.MkdirAll(albumDir, 0o755); err != nil {
		t.Fatalf("mkdir seed: %v", err)
	}
	song1Path := filepath.Join(albumDir, "song1.wav")
	song2Path := filepath.Join(albumDir, "song2.wav")
	if err := os.WriteFile(song1Path, []byte("RIFF-1"), 0o644); err != nil {
		t.Fatalf("write song1: %v", err)
	}
	if err := os.WriteFile(song2Path, []byte("RIFF-2"), 0o644); err != nil {
		t.Fatalf("write song2: %v", err)
	}
	if err := os.WriteFile(filepath.Join(albumDir, "song1.lrc"), []byte("[00:00.00]hi"), 0o644); err != nil {
		t.Fatalf("write lrc: %v", err)
	}

	rootID := "root-1"
	_, caseMode, err := files.ProbeCapabilities(musicRoot)
	if err != nil {
		t.Fatalf("probe capabilities: %v", err)
	}
	a1, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID: rootID, RelPath: "Singer/Album/song1.wav",
		PathKey: files.ComputePathKey(caseMode, "Singer/Album/song1.wav"),
		Folder: "Singer/Album", Name: "song1.wav", Status: "active", Editable: true,
		Size: 6, MTimeNS: 1, Title: "Song 1", Artist: "Singer", Album: "Album",
	}, 0, true)
	if err != nil {
		t.Fatalf("upsert a1: %v", err)
	}
	a2, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID: rootID, RelPath: "Singer/Album/song2.wav",
		PathKey: files.ComputePathKey(caseMode, "Singer/Album/song2.wav"),
		Folder: "Singer/Album", Name: "song2.wav", Status: "active", Editable: true,
		Size: 6, MTimeNS: 2, Title: "Song 2", Artist: "Singer", Album: "Album",
	}, 0, true)
	if err != nil {
		t.Fatalf("upsert a2: %v", err)
	}

	postJSON := func(path string, body any) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(body)
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-MeloKeep-Request", "1")
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec
	}

	// 1. mkdir creates empty folder and /api/library/files lists it.
	rec := postJSON("/api/files/mkdir", map[string]any{
		"root":   musicRoot,
		"parent": "",
		"name":   "TrashBox",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("mkdir failed: %d %s", rec.Code, rec.Body.String())
	}
	if _, err := os.Stat(filepath.Join(musicRoot, "TrashBox")); err != nil {
		t.Fatalf("expected TrashBox dir on disk: %v", err)
	}

	recList := httptest.NewRecorder()
	srv.ServeHTTP(recList, httptest.NewRequest(http.MethodGet, "/api/library/files", nil))
	var page model.LibraryFilesPageDTO
	if err := json.Unmarshal(recList.Body.Bytes(), &page); err != nil {
		t.Fatalf("decode page: %v", err)
	}
	hasTrashBox := false
	for _, f := range page.Folders {
		if f.Folder == "TrashBox" {
			hasTrashBox = true
		}
	}
	if !hasTrashBox {
		t.Fatalf("expected empty folder TrashBox in folders list: %+v", page.Folders)
	}

	// 2. Rename folder Singer/Album -> Singer/AlbumRenamed and verify assets relocated.
	recRename := postJSON("/api/files/rename", map[string]any{
		"root":     musicRoot,
		"folder":   "Singer/Album",
		"new_name": "AlbumRenamed",
	})
	if recRename.Code != http.StatusOK {
		t.Fatalf("rename failed: %d %s", recRename.Code, recRename.Body.String())
	}
	gotA1, ok, err := st.GetAsset(ctx, a1.ID)
	if err != nil || !ok || gotA1.Folder != "Singer/AlbumRenamed" || gotA1.RelPath != "Singer/AlbumRenamed/song1.wav" {
		t.Fatalf("expected relocated asset a1, got %+v ok=%v err=%v", gotA1, ok, err)
	}

	// 3. Move song1 into TrashBox (audio + sidecar).
	recMove := postJSON("/api/files/move", map[string]any{
		"files":         []string{"asset:" + a1.ID},
		"target_root":   musicRoot,
		"target_folder": "TrashBox",
	})
	if recMove.Code != http.StatusOK {
		t.Fatalf("move failed: %d %s", recMove.Code, recMove.Body.String())
	}
	if _, err := os.Stat(filepath.Join(musicRoot, "TrashBox", "song1.wav")); err != nil {
		t.Fatalf("expected moved song1 in TrashBox: %v", err)
	}
	if _, err := os.Stat(filepath.Join(musicRoot, "TrashBox", "song1.lrc")); err != nil {
		t.Fatalf("expected moved sidecar in TrashBox: %v", err)
	}

	// 4. Forbid deleting root folder itself.
	recRootDel := postJSON("/api/files/batch-delete", map[string]any{
		"folders": []map[string]string{{"root": musicRoot, "folder": "."}},
	})
	if recRootDel.Code == http.StatusOK {
		t.Fatalf("expected root deletion rejected")
	}

	// 5. Batch delete both TrashBox (folder) and Singer (folder containing a2).
	recBatchDel := postJSON("/api/files/batch-delete", map[string]any{
		"folders": []map[string]string{
			{"root": musicRoot, "folder": "TrashBox"},
			{"root": musicRoot, "folder": "Singer"},
		},
	})
	if recBatchDel.Code != http.StatusOK {
		t.Fatalf("batch-delete failed: %d %s", recBatchDel.Code, recBatchDel.Body.String())
	}
	if _, err := os.Stat(filepath.Join(musicRoot, "TrashBox")); !os.IsNotExist(err) {
		t.Fatalf("expected TrashBox removed")
	}
	if _, err := os.Stat(filepath.Join(musicRoot, "Singer")); !os.IsNotExist(err) {
		t.Fatalf("expected Singer removed")
	}
	gotA2, ok, _ := st.GetAsset(ctx, a2.ID)
	if !ok || gotA2.Status != "deleted" {
		t.Fatalf("expected a2 soft deleted, got %+v", gotA2)
	}
}

func TestLibraryRefreshRescanAndGhostFolderDelete(t *testing.T) {
	srv, st, musicRoot := newTestServer(t, func(d *Dependencies, _ string) {
		locks := files.NewAssetLockManager(2 * time.Second)
		d.Indexer = index.NewIndexer(d.Store, locks, media.NewTagService(nil, nil))
	})
	ctx := context.Background()

	// 1. Manually place an audio file into disk and call POST /api/library/refresh.
	manualDir := filepath.Join(musicRoot, "ManualArtist")
	if err := os.MkdirAll(manualDir, 0o755); err != nil {
		t.Fatalf("mkdir ManualArtist: %v", err)
	}
	manualSong := filepath.Join(manualDir, "manual.wav")
	if err := os.WriteFile(manualSong, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644); err != nil {
		t.Fatalf("write manual.wav: %v", err)
	}

	recRefresh := httptest.NewRecorder()
	reqRefresh := httptest.NewRequest(http.MethodPost, "/api/library/refresh", nil)
	reqRefresh.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(recRefresh, reqRefresh)
	if recRefresh.Code != http.StatusOK {
		t.Fatalf("POST /api/library/refresh failed: %d %s", recRefresh.Code, recRefresh.Body.String())
	}
	var refreshOut map[string]any
	if err := json.Unmarshal(recRefresh.Body.Bytes(), &refreshOut); err != nil {
		t.Fatalf("decode refresh: %v", err)
	}
	if refreshOut["roots_scanned"] != float64(1) || refreshOut["updated_or_new"] != float64(1) {
		t.Fatalf("unexpected refresh stats: %+v", refreshOut)
	}

	// Verify GET /api/library/files lists the newly indexed file.
	recFiles := httptest.NewRecorder()
	srv.ServeHTTP(recFiles, httptest.NewRequest(http.MethodGet, "/api/library/files", nil))
	if recFiles.Code != http.StatusOK {
		t.Fatalf("GET /api/library/files failed: %d", recFiles.Code)
	}
	var page model.LibraryFilesPageDTO
	if err := json.Unmarshal(recFiles.Body.Bytes(), &page); err != nil {
		t.Fatalf("decode files: %v", err)
	}
	if len(page.Items) != 1 || page.Items[0].Name != "manual.wav" {
		t.Fatalf("expected manual.wav in library files, got %+v", page.Items)
	}

	// 2. Delete manual.wav externally and create GhostBox/ghost.wav, then call GET /api/library/files?rescan=1.
	if err := os.Remove(manualSong); err != nil {
		t.Fatalf("remove manualSong: %v", err)
	}
	ghostDir := filepath.Join(musicRoot, "GhostBox")
	if err := os.MkdirAll(ghostDir, 0o755); err != nil {
		t.Fatalf("mkdir GhostBox: %v", err)
	}
	ghostSong := filepath.Join(ghostDir, "ghost.wav")
	if err := os.WriteFile(ghostSong, []byte("RIFF\x00\x00\x00\x00WAVEfmt "), 0o644); err != nil {
		t.Fatalf("write ghost.wav: %v", err)
	}

	recRescan := httptest.NewRecorder()
	srv.ServeHTTP(recRescan, httptest.NewRequest(http.MethodGet, "/api/library/files?rescan=1", nil))
	if recRescan.Code != http.StatusOK {
		t.Fatalf("GET /api/library/files?rescan=1 failed: %d %s", recRescan.Code, recRescan.Body.String())
	}
	var page2 model.LibraryFilesPageDTO
	if err := json.Unmarshal(recRescan.Body.Bytes(), &page2); err != nil {
		t.Fatalf("decode rescan page: %v", err)
	}
	if len(page2.Items) != 1 || page2.Items[0].Name != "ghost.wav" {
		t.Fatalf("expected only ghost.wav after rescan=1, got %+v", page2.Items)
	}

	// 3. Externally delete GhostBox folder on disk, leaving a stale ghost asset in SQLite.
	// POST /api/files/batch-delete must still succeed and soft-delete stale SQLite records.
	rootRec, _, _ := st.GetRoot(ctx, "root-1")
	ghostAssetBefore, ok, _ := st.GetAssetByRootAndPathKey(ctx, "root-1", files.ComputePathKey(rootRec.PathCaseMode, "GhostBox/ghost.wav"))
	if !ok || ghostAssetBefore.ID == "" {
		t.Fatalf("expected GhostBox/ghost.wav indexed before external delete")
	}
	if err := os.RemoveAll(ghostDir); err != nil {
		t.Fatalf("remove GhostBox externally: %v", err)
	}
	bodyGhost, _ := json.Marshal(map[string]any{
		"folders": []map[string]string{{"root": musicRoot, "folder": "GhostBox"}},
	})
	recGhostDel := httptest.NewRecorder()
	reqGhostDel := httptest.NewRequest(http.MethodPost, "/api/files/batch-delete", bytes.NewReader(bodyGhost))
	reqGhostDel.Header.Set("Content-Type", "application/json")
	reqGhostDel.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(recGhostDel, reqGhostDel)
	if recGhostDel.Code != http.StatusOK {
		t.Fatalf("expected 200 deleting ghost folder, got %d %s", recGhostDel.Code, recGhostDel.Body.String())
	}
	var delOut map[string]any
	if err := json.Unmarshal(recGhostDel.Body.Bytes(), &delOut); err != nil {
		t.Fatalf("decode ghost delete response: %v", err)
	}
	if delOut["deleted_folders"] != float64(1) {
		t.Fatalf("expected deleted_folders=1, got %+v", delOut)
	}

	// Ghost folder's assets must be soft deleted and invisible in GET /api/library/files.
	ghostAsset, ok, _ := st.GetAsset(ctx, ghostAssetBefore.ID)
	if !ok || ghostAsset.Status != "deleted" {
		t.Fatalf("expected ghost asset soft-deleted, got ok=%v status=%s", ok, ghostAsset.Status)
	}
	recFilesAfter := httptest.NewRecorder()
	srv.ServeHTTP(recFilesAfter, httptest.NewRequest(http.MethodGet, "/api/library/files", nil))
	var pageAfter model.LibraryFilesPageDTO
	_ = json.Unmarshal(recFilesAfter.Body.Bytes(), &pageAfter)
	if len(pageAfter.Items) != 0 {
		t.Fatalf("expected 0 active files after ghost folder delete, got %+v", pageAfter.Items)
	}

	// 4. Deleting a folder that exists neither on disk nor in SQLite must return 404.
	bodyMissing, _ := json.Marshal(map[string]any{
		"folders": []map[string]string{{"root": musicRoot, "folder": "NeverExisted"}},
	})
	recMissing := httptest.NewRecorder()
	reqMissing := httptest.NewRequest(http.MethodPost, "/api/files/batch-delete", bytes.NewReader(bodyMissing))
	reqMissing.Header.Set("Content-Type", "application/json")
	reqMissing.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(recMissing, reqMissing)
	if recMissing.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for completely absent folder, got %d %s", recMissing.Code, recMissing.Body.String())
	}
}

func TestLibraryRefreshUnhealthyRootReadableLabelAndLog(t *testing.T) {
	var logBuf *LogBuffer
	srv, st, _ := newTestServer(t, func(d *Dependencies, _ string) {
		locks := files.NewAssetLockManager(2 * time.Second)
		d.Indexer = index.NewIndexer(d.Store, locks, media.NewTagService(nil, nil))
		logBuf = NewLogBuffer(100)
		d.Logs = logBuf
	})
	ctx := context.Background()

	missingRootID := "11111111-2222-3333-4444-555555555555"
	missingPath := filepath.Join(t.TempDir(), "absent-library-root")
	if err := st.UpsertRoot(ctx, store.RootRecord{
		ID:            missingRootID,
		CanonicalPath: missingPath,
		PathCaseMode:  "sensitive",
		Enabled:       true,
		Healthy:       true,
	}); err != nil {
		t.Fatalf("upsert missing root: %v", err)
	}

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/library/refresh", nil)
	req.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("POST /api/library/refresh failed: %d %s", rec.Code, rec.Body.String())
	}

	var out libraryRefreshResult
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode refresh output: %v", err)
	}
	if out.RootsScanned != 2 || len(out.UnhealthyRoots) != 1 {
		t.Fatalf("expected 2 scanned roots and 1 unhealthy root, got %+v", out)
	}
	label := out.UnhealthyRoots[0]
	if !strings.Contains(label, missingPath) || !strings.Contains(label, "目录不存在") {
		t.Fatalf("expected readable path and reason in unhealthy_roots, got %q", label)
	}
	if strings.Contains(label, missingRootID) {
		t.Fatalf("unhealthy_roots must not expose raw UUID when CanonicalPath exists, got %q", label)
	}

	foundWarn := false
	for _, entry := range logBuf.List() {
		if entry.Category == "library" && entry.Level == "warning" && entry.TaskID == missingRootID {
			if strings.Contains(entry.Message, missingPath) && strings.Contains(entry.Message, missingRootID) {
				foundWarn = true
			}
		}
	}
	if !foundWarn {
		t.Fatalf("expected library warning log with path and UUID, got %+v", logBuf.List())
	}

	// Verify formatUnhealthyRootLabel formatting across error variants.
	cases := []struct {
		name   string
		root   store.RootRecord
		reason string
		want   string
	}{
		{
			name:   "posix not exist",
			root:   store.RootRecord{ID: "uuid-1", CanonicalPath: "/mnt/music"},
			reason: "open /mnt/music: no such file or directory",
			want:   "/mnt/music (目录不存在)",
		},
		{
			name:   "windows cannot find path",
			root:   store.RootRecord{ID: "uuid-2", CanonicalPath: `D:\Music\Old`},
			reason: `GetFileAttributesEx D:\Music\Old: The system cannot find the path specified.`,
			want:   `D:\Music\Old (目录不存在)`,
		},
		{
			name:   "permission denied",
			root:   store.RootRecord{ID: "uuid-3", CanonicalPath: "/mnt/locked"},
			reason: "open /mnt/locked: permission denied",
			want:   "/mnt/locked (无访问权限)",
		},
		{
			name:   "empty canonical path fallback to id",
			root:   store.RootRecord{ID: "uuid-fallback", CanonicalPath: ""},
			reason: "disk read failed",
			want:   "uuid-fallback (disk read failed)",
		},
		{
			name:   "walk subdir permission denied",
			root:   store.RootRecord{ID: "uuid-sub", CanonicalPath: "/music"},
			reason: "目录遍历异常(/music/待刮削): open /music/待刮削: permission denied",
			want:   "/music (子目录 /music/待刮削 无访问权限)",
		},
		{
			name:   "walk subdir multi-error suffix",
			root:   store.RootRecord{ID: "uuid-multi", CanonicalPath: "/music"},
			reason: "目录遍历异常(/music/待刮削): open /music/待刮削: permission denied（另有 2 处目录遍历异常）",
			want:   "/music (子目录 /music/待刮削 无访问权限)",
		},
		{
			name:   "walk root itself permission denied",
			root:   store.RootRecord{ID: "uuid-root", CanonicalPath: "/music"},
			reason: "目录遍历异常(/music): open /music: permission denied",
			want:   "/music (无访问权限)",
		},
		{
			name:   "lstat subdir permission denied",
			root:   store.RootRecord{ID: "uuid-lstat", CanonicalPath: "/music"},
			reason: "读取文件属性失败(/music/locked-dir): lstat /music/locked-dir: permission denied",
			want:   "/music (子目录 /music/locked-dir 无访问权限)",
		},
	}
	for _, tc := range cases {
		if got := formatUnhealthyRootLabel(tc.root, tc.reason); got != tc.want {
			t.Fatalf("%s: got %q, want %q", tc.name, got, tc.want)
		}
	}
}

func TestStoreCountActiveAssetsAndDisableEmptyRemovedRootPolicy(t *testing.T) {
	srv, st, _ := newTestServer(t, func(d *Dependencies, _ string) {
		locks := files.NewAssetLockManager(2 * time.Second)
		d.Indexer = index.NewIndexer(d.Store, locks, media.NewTagService(nil, nil))
	})
	ctx := context.Background()

	emptyRemovedID := "root-empty-removed"
	emptyRemovedPath := filepath.Join(t.TempDir(), "old-removed-root")
	offlineWithTrackID := "root-offline-with-track"
	offlineWithTrackPath := filepath.Join(t.TempDir(), "offline-nas-root")

	for _, r := range []store.RootRecord{
		{ID: emptyRemovedID, CanonicalPath: emptyRemovedPath, PathCaseMode: "sensitive", Enabled: true, Healthy: true},
		{ID: offlineWithTrackID, CanonicalPath: offlineWithTrackPath, PathCaseMode: "sensitive", Enabled: true, Healthy: true},
	} {
		if err := st.UpsertRoot(ctx, r); err != nil {
			t.Fatalf("UpsertRoot %s: %v", r.ID, err)
		}
	}

	// Non-active rows (missing / deleted) in emptyRemovedID must not prevent auto-disable.
	for idx, status := range []string{"missing", "deleted"} {
		rel := "old/" + status + ".wav"
		if _, err := st.UpsertAsset(ctx, store.AssetRecord{
			RootID: emptyRemovedID, RelPath: rel, PathKey: rel, Folder: "old", Name: status + ".wav",
			Status: status, Size: int64(idx + 1), Title: status,
		}, 0, false); err != nil {
			t.Fatalf("seed non-active asset: %v", err)
		}
	}
	// Active asset in offlineWithTrackID must protect it from auto-disable.
	if _, err := st.UpsertAsset(ctx, store.AssetRecord{
		RootID: offlineWithTrackID, RelPath: "Artist/keep.wav", PathKey: "Artist/keep.wav", Folder: "Artist", Name: "keep.wav",
		Status: "active", Size: 100, Title: "Keep Track",
	}, 0, false); err != nil {
		t.Fatalf("seed active asset: %v", err)
	}

	cntEmpty, err := st.CountActiveAssetsByRoot(ctx, emptyRemovedID)
	if err != nil || cntEmpty != 0 {
		t.Fatalf("expected 0 active assets in emptyRemovedID, got count=%d err=%v", cntEmpty, err)
	}
	cntOffline, err := st.CountActiveAssetsByRoot(ctx, offlineWithTrackID)
	if err != nil || cntOffline != 1 {
		t.Fatalf("expected 1 active asset in offlineWithTrackID, got count=%d err=%v", cntOffline, err)
	}

	// Simulate startup cleanup rule: only disable missing root when active count == 0.
	roots, err := st.ListRoots(ctx)
	if err != nil {
		t.Fatalf("ListRoots: %v", err)
	}
	const autoDisableMsg = "目录已不存在且无有效曲目，已自动停用"
	for _, rt := range roots {
		if !rt.Enabled {
			continue
		}
		if _, statErr := os.Stat(rt.CanonicalPath); os.IsNotExist(statErr) {
			n, cErr := st.CountActiveAssetsByRoot(ctx, rt.ID)
			if cErr != nil {
				t.Fatalf("CountActiveAssetsByRoot(%s): %v", rt.ID, cErr)
			}
			if n == 0 {
				if err := st.SetRootEnabled(ctx, rt.ID, false, autoDisableMsg); err != nil {
					t.Fatalf("SetRootEnabled(%s): %v", rt.ID, err)
				}
			}
		}
	}

	emptyRec, ok, err := st.GetRoot(ctx, emptyRemovedID)
	if err != nil || !ok || emptyRec.Enabled || emptyRec.LastError != autoDisableMsg {
		t.Fatalf("expected emptyRemovedID disabled with reason, got ok=%v rec=%+v err=%v", ok, emptyRec, err)
	}
	offlineRec, ok, err := st.GetRoot(ctx, offlineWithTrackID)
	if err != nil || !ok || !offlineRec.Enabled {
		t.Fatalf("expected offlineWithTrackID preserved as enabled, got ok=%v rec=%+v err=%v", ok, offlineRec, err)
	}

	// Subsequent library refresh skips disabled emptyRemovedID and only flags offlineWithTrackID.
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/library/refresh", nil)
	req.Header.Set("X-MeloKeep-Request", "1")
	srv.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("POST /api/library/refresh failed: %d %s", rec.Code, rec.Body.String())
	}
	var out libraryRefreshResult
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode refresh output: %v", err)
	}
	if out.RootsScanned != 2 || len(out.UnhealthyRoots) != 1 || !strings.Contains(out.UnhealthyRoots[0], offlineWithTrackPath) {
		t.Fatalf("expected disabled empty root skipped and only offlineWithTrackPath flagged, got %+v", out)
	}
}
