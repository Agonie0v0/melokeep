package acceptance_integrity_test

import (
	"encoding/json"
	"net/http"
	"path/filepath"
	"strings"
	"testing"

	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/legacyimport"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/operations"
)

func FuzzValidatePath(f *testing.F) {
	rootDir := filepath.Join(f.TempDir(), "music")
	for _, seed := range []string{
		"",
		"Artist/song.flac",
		"../music_evil/song.flac",
		"../../etc/passwd",
		".melokeep-history/op1/song.flac",
		".melokeep-stage-1.mp3",
		"sub\x00/song.flac",
		`Artist\..\..\\escape.mp3`,
		"CON.flac",
		"..",
		".",
		"folder/song.txt",
	} {
		f.Add(seed)
	}
	root := files.Root{ID: "r1", CanonicalPath: rootDir, CaseMode: "insensitive"}
	roots := []files.Root{root}

	f.Fuzz(func(t *testing.T, rel string) {
		cand := rel
		if !filepath.IsAbs(rel) {
			cand = filepath.Join(rootDir, rel)
		}
		vp, err := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: false, AudioOnly: true})
		if err != nil {
			return
		}
		r, relErr := filepath.Rel(rootDir, vp.AbsPath)
		if relErr != nil || r == "." || r == ".." || strings.HasPrefix(r, ".."+string(filepath.Separator)) || filepath.IsAbs(r) {
			t.Fatalf("ValidatePath escaped root: cand=%q abs=%q rel=%q", cand, vp.AbsPath, r)
		}
		for _, seg := range strings.Split(vp.RelPath, "/") {
			if files.InternalDirs[seg] || strings.HasPrefix(seg, ".melokeep-") {
				t.Fatalf("ValidatePath allowed internal segment %q in %q", seg, vp.RelPath)
			}
		}
	})
}

func FuzzDecodePaginationCursorAndPayloadHash(f *testing.F) {
	fh := model.FiltersHash("ambient", "electronic")
	validTok, _ := (model.PaginationCursor{
		SortField:     "title",
		LastID:        "asset-1",
		FiltersHash:   fh,
		IndexRevision: 3,
	}).Encode()
	for _, seed := range []string{
		"",
		validTok,
		"!!!not-base64",
		"e30", // {}
		`{"track_id":"t1","nested":{"a":1,"b":[2,3]}}`,
	} {
		f.Add(seed)
	}

	f.Fuzz(func(t *testing.T, input string) {
		if cur, err := model.DecodePaginationCursor(input, "title", fh, 3); err == nil {
			if cur.SortField != "title" || cur.FiltersHash != fh || cur.IndexRevision != 3 || cur.LastID == "" {
				t.Fatalf("invalid cursor accepted: %+v", cur)
			}
		} else if de, ok := model.AsDomainError(err); !ok || (de.HTTPStatus() != http.StatusBadRequest && de.HTTPStatus() != http.StatusConflict) {
			t.Fatalf("unexpected error status for cursor %q: %v", input, err)
		}

		var payload map[string]any
		if json.Unmarshal([]byte(input), &payload) == nil && payload != nil {
			h1 := operations.CanonicalPayloadHash("POST /api/downloads", false, "", payload)
			h2 := operations.CanonicalPayloadHash("POST /api/downloads", false, "", payload)
			if h1 != h2 || len(h1) != 64 {
				t.Fatalf("non-deterministic CanonicalPayloadHash: %q vs %q", h1, h2)
			}
		}
	})
}

func FuzzDecodeJSONSong(f *testing.F) {
	for _, seed := range [][]byte{
		[]byte(`{}`),
		[]byte(`{"source":"AudiusMusicClient","identifier":"1","download_url":"https://x/y","episodes":[{"download_url":"https://secret"}]}`),
		[]byte(`{"default_download_headers":{"Cookie":"a"},"default_download_cookies":{"uin":"1"},"downloaded_contents":"raw","downloaded_contents_b64":"YQ=="}`),
		[]byte(`not json`),
		[]byte(`[]`),
	} {
		f.Add(seed)
	}

	f.Fuzz(func(t *testing.T, raw []byte) {
		m, err := legacyimport.DecodeJSONSong(raw)
		if err != nil || m == nil {
			return
		}
		assertNoSecretKeys(t, m)
	})
}

func assertNoSecretKeys(t *testing.T, v any) {
	t.Helper()
	switch x := v.(type) {
	case map[string]any:
		for k, val := range x {
			switch k {
			case "download_url", "default_download_headers", "default_download_cookies", "downloaded_contents", "downloaded_contents_b64":
				t.Fatalf("secret key %q survived DecodeJSONSong", k)
			}
			assertNoSecretKeys(t, val)
		}
	case []any:
		for _, item := range x {
			assertNoSecretKeys(t, item)
		}
	}
}
