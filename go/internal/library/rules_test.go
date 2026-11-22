package library

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type rulesGolden struct {
	Expressions []struct {
		Expr     string `json:"expr"`
		Expected string `json:"expected"`
	} `json:"expressions"`
	Renders []struct {
		Template string `json:"template"`
		Expected string `json:"expected"`
	} `json:"renders"`
	Extracts []struct {
		Template string            `json:"template"`
		Stem     string            `json:"stem"`
		Expected map[string]string `json:"expected"`
	} `json:"extracts"`
	Transforms []struct {
		Value       string `json:"value"`
		Find        string `json:"find"`
		Replacement string `json:"replacement"`
		Chinese     string `json:"chinese"`
		Repair      bool   `json:"repair"`
		Expected    string `json:"expected"`
	} `json:"transforms"`
	Variables []struct {
		Path     string            `json:"path"`
		Artist   string            `json:"artist"`
		Date     string            `json:"date"`
		Expected map[string]string `json:"expected"`
	} `json:"variables"`
	Errors []struct {
		Kind       string `json:"kind"`
		Input      string `json:"input"`
		Error      string `json:"error"`
		Unexpected string `json:"unexpected"`
	} `json:"errors"`
}

func TestRulesGoldenParity(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("testdata", "rules_golden.json"))
	if err != nil {
		t.Fatalf("read golden: %v", err)
	}
	var g rulesGolden
	if err := json.Unmarshal(raw, &g); err != nil {
		t.Fatalf("unmarshal golden: %v", err)
	}

	vals := Variables(
		"music/Artist - Album/01 - Track.flac",
		map[string]string{
			"title":        "晨风序曲",
			"artist":       "自由乐手, 合声乐手",
			"album":        "晨风集",
			"album_artist": "自由乐手",
			"date":         "2008-10-15",
			"track_number": "01",
			"disc_number":  "1",
			"genre":        "Pop",
			"composer":     "自由乐手",
			"comment":      "live",
			"lyricist":     "自由乐手",
			"language":     "Mandarin",
			"copyright":    "CC-BY",
			"publisher":    "Open Sound",
			"grouping":     "Mandopop",
			"bpm":          "90",
			"isrc":         "XXA000801501",
		},
		3,
		"[00:01.00]晨风拂过山谷与清澈湖面",
	)

	for _, tc := range g.Expressions {
		got, err := Expression(tc.Expr, vals)
		if err != nil {
			t.Fatalf("Expression(%q) failed: %v", tc.Expr, err)
		}
		if got != tc.Expected {
			t.Fatalf("Expression(%q) = %q, want %q", tc.Expr, got, tc.Expected)
		}
	}

	for _, tc := range g.Renders {
		got, err := Render(tc.Template, vals, nil)
		if err != nil {
			t.Fatalf("Render(%q) failed: %v", tc.Template, err)
		}
		if got != tc.Expected {
			t.Fatalf("Render(%q) = %q, want %q", tc.Template, got, tc.Expected)
		}
	}

	for _, tc := range g.Extracts {
		got, err := Extract(tc.Template, tc.Stem)
		if err != nil {
			t.Fatalf("Extract(%q, %q) failed: %v", tc.Template, tc.Stem, err)
		}
		for k, want := range tc.Expected {
			if got[k] != want {
				t.Fatalf("Extract(%q, %q)[%s] = %q, want %q", tc.Template, tc.Stem, k, got[k], want)
			}
		}
	}

	for _, tc := range g.Transforms {
		got := Transform(tc.Value, tc.Find, tc.Replacement, tc.Chinese, tc.Repair)
		if got != tc.Expected {
			t.Fatalf("Transform(%q, chinese=%s, repair=%v) = %q, want %q", tc.Value, tc.Chinese, tc.Repair, got, tc.Expected)
		}
	}

	for _, tc := range g.Variables {
		got := Variables(tc.Path, map[string]string{"artist": tc.Artist, "date": tc.Date}, 2, "lrc")
		for k, want := range tc.Expected {
			if got[k] != want {
				t.Fatalf("Variables(%q, artist=%q)[%s] = %q, want %q", tc.Path, tc.Artist, k, got[k], want)
			}
		}
	}

	for _, tc := range g.Errors {
		if tc.Unexpected != "" {
			t.Fatalf("golden error case had unexpected value: %+v", tc)
		}
		switch tc.Kind {
		case "expr":
			if got, err := Expression(tc.Input, vals); err == nil {
				t.Fatalf("Expression(%q) expected error, got %q", tc.Input, got)
			}
		case "render":
			if got, err := Render(tc.Input, vals, nil); err == nil {
				t.Fatalf("Render(%q) expected error, got %q", tc.Input, got)
			}
		case "extract":
			parts := strings.SplitN(tc.Input, "|", 2)
			if got, err := Extract(parts[0], parts[1]); err == nil {
				t.Fatalf("Extract(%q, %q) expected error, got %v", parts[0], parts[1], got)
			}
		}
	}

	// Template validation & target path safety
	if err := ValidateTemplate("../escape", false); err == nil {
		t.Fatal("expected traversal template error")
	}
	if err := ValidateTemplate("a/b", true); err == nil {
		t.Fatal("expected slash in filename template error")
	}
	if got := SafeSegment("CON.flac", "x"); got != "_CON.flac" {
		t.Fatalf("SafeSegment reserved = %q", got)
	}
	root := t.TempDir()
	dst, err := BuildTargetPath(root, filepath.Join(root, "in.flac"), map[string]string{"title": "Song", "artist": "Art", "album": "Alb", "track_number": "1"}, "{album_artist}/{album}", "{track_number} - {title}", 1, nil, nil)
	if err != nil {
		t.Fatalf("BuildTargetPath failed: %v", err)
	}
	if !strings.HasSuffix(dst, filepath.Join("Art", "Alb", "01 - Song.flac")) {
		t.Fatalf("unexpected BuildTargetPath: %s", dst)
	}
}

func toTestLatin1Mojibake(utf8Str string) string {
	var b strings.Builder
	for _, by := range []byte(utf8Str) {
		b.WriteRune(rune(by))
	}
	return b.String()
}

func TestTransformLyrics(t *testing.T) {
	if got := TransformLyrics("", "simplified", true); got != "" {
		t.Fatalf("empty lyrics should stay empty, got %q", got)
	}

	// Traditional -> Simplified with CRLF, [mm:ss.xx] timestamps, and blank lines preserved.
	tradLRC := "[ti:晨風序曲]\r\n[00:01.20]樂團 風景 髮 裡\r\n\r\n[00:08.50]歌聲在山谷迴盪\r\n"
	wantSimp := "[ti:晨风序曲]\n[00:01.20]乐团 风景 发 里\n\n[00:08.50]歌声在山谷回荡\n"
	if got := TransformLyrics(tradLRC, "simplified", false); got != wantSimp {
		t.Fatalf("TransformLyrics(simplified) =\n%q\nwant:\n%q", got, wantSimp)
	}

	// Simplified -> Traditional with multi-line timestamps and blank lines preserved.
	simpLRC := "[00:01.20]乐团 风景 发 里\n\n[00:08.50]歌声飘过树林"
	wantTrad := "[00:01.20]樂團 風景 發 裏\n\n[00:08.50]歌聲飄過樹林"
	if got := TransformLyrics(simpLRC, "traditional", false); got != wantTrad {
		t.Fatalf("TransformLyrics(traditional) =\n%q\nwant:\n%q", got, wantTrad)
	}

	// "none" mode preserves original text, timestamps, spaces, and blank lines.
	origLRC := "[00:01.00]繁體 歌詞  保留\n\n[00:05.00]简体 歌词"
	if got := TransformLyrics(origLRC, "none", false); got != origLRC {
		t.Fatalf("TransformLyrics(none) = %q, want %q", got, origLRC)
	}

	// Latin1 mojibake repair line-by-line + simplified conversion + NFC composition.
	mojibakeLine := toTestLatin1Mojibake("[00:02.00]自由樂手")
	mixedLRC := mojibakeLine + "\r\n\r\n[00:05.00]Cafe\u0301 晨風序曲"
	wantRepairedSimp := "[00:02.00]自由乐手\n\n[00:05.00]Café 晨风序曲"
	if got := TransformLyrics(mixedLRC, "simplified", true); got != wantRepairedSimp {
		t.Fatalf("TransformLyrics(repair+simplified) = %q, want %q", got, wantRepairedSimp)
	}

	wantRepairedOnly := "[00:02.00]自由樂手\n\n[00:05.00]Café 晨風序曲"
	if got := TransformLyrics(mixedLRC, "none", true); got != wantRepairedOnly {
		t.Fatalf("TransformLyrics(repair+none) = %q, want %q", got, wantRepairedOnly)
	}
}
