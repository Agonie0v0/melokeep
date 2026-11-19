package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/agonie0v0/melokeep/internal/files"
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
