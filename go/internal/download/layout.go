package download

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/model"
)

var (
	invalidFilenameRe = regexp.MustCompile(`[<>:"/\\|?*\x00-\x1f\x7f]`)
	reservedWinNameRe = regexp.MustCompile(`(?i)^(CON|PRN|AUX|NUL|COM[1-9\x{00b9}\x{00b2}\x{00b3}]|LPT[1-9\x{00b9}\x{00b2}\x{00b3}])$`)
)

// SanitizeSegment mirrors local_library.download_stem segment cleaning with a 100-byte UTF-8 cap.
func SanitizeSegment(raw, fallback string) string {
	cleaned := invalidFilenameRe.ReplaceAllString(raw, "_")
	cleaned = strings.Trim(cleaned, " .")
	cleaned = truncateUTF8Bytes(cleaned, 100)
	cleaned = strings.TrimRight(cleaned, " .")
	if cleaned == "" {
		cleaned = fallback
	}
	first := strings.SplitN(cleaned, ".", 2)[0]
	if reservedWinNameRe.MatchString(first) {
		cleaned = "_" + cleaned
	}
	return cleaned
}

// DownloadStem builds "<title> - <artist>" safely for target filenames.
func DownloadStem(title, artist string) string {
	t := SanitizeSegment(title, "未知歌曲")
	a := SanitizeSegment(artist, "未知歌手")
	stem := t + " - " + a
	first := strings.SplitN(stem, ".", 2)[0]
	if reservedWinNameRe.MatchString(first) {
		stem = "_" + stem
	}
	return stem
}

// PathOptions carries optional naming metadata (such as audiobook directory and chapter numbering).
type PathOptions struct {
	BookTitle    string
	ChapterIndex int
	ChapterTotal int
}

// TargetAudioPath resolves a non-colliding absolute target path inside rootPath
// according to DownloadLayout (source | flat | artist | artist_album).
func TargetAudioPath(rootPath, layout string, track model.ProviderTrack, ext, downloadID string) string {
	return TargetAudioPathWithOptions(rootPath, layout, track, ext, downloadID, PathOptions{})
}

// TargetAudioPathWithOptions resolves a non-colliding absolute target path inside rootPath
// according to DownloadLayout (source | flat | artist | artist_album | audiobook) and PathOptions.
func TargetAudioPathWithOptions(rootPath, layout string, track model.ProviderTrack, ext, downloadID string, opts PathOptions) string {
	if ext == "" {
		ext = ".mp3"
	}
	if !strings.HasPrefix(ext, ".") {
		ext = "." + ext
	}
	ext = strings.ToLower(ext)

	var dir string
	var stem string
	switch strings.ToLower(strings.TrimSpace(layout)) {
	case "audiobook":
		bookName := strings.TrimSpace(opts.BookTitle)
		if bookName == "" {
			bookName = strings.TrimSpace(track.Album)
		}
		if bookName == "" {
			bookName = strings.TrimSpace(track.Artist)
		}
		dir = filepath.Join(rootPath, SanitizeSegment(bookName, "未知有声书"))

		idx := opts.ChapterIndex
		if idx <= 0 && track.PlaylistIndex != nil {
			idx = *track.PlaylistIndex
		}
		chapTitle := SanitizeSegment(track.Title, "未知章节")
		if idx > 0 {
			width := 3
			if opts.ChapterTotal >= 1000 || idx >= 1000 {
				width = 4
			}
			stem = fmt.Sprintf("%0*d - %s", width, idx, chapTitle)
		} else {
			stem = chapTitle
		}
		first := strings.SplitN(stem, ".", 2)[0]
		if reservedWinNameRe.MatchString(first) {
			stem = "_" + stem
		}
	case "flat":
		dir = rootPath
		stem = DownloadStem(track.Title, track.Artist)
	case "artist":
		dir = filepath.Join(rootPath, SanitizeSegment(track.Artist, "未知歌手"))
		stem = DownloadStem(track.Title, track.Artist)
	case "artist_album":
		artist := SanitizeSegment(track.Artist, "未知歌手")
		album := SanitizeSegment(track.Album, "未知专辑")
		dir = filepath.Join(rootPath, artist, album)
		stem = DownloadStem(track.Title, track.Artist)
	default: // "source"
		src := SanitizeSegment(track.Source, "unknown")
		dir = filepath.Join(rootPath, src)
		stem = DownloadStem(track.Title, track.Artist)
	}

	shortID := downloadID
	if len(shortID) > 8 {
		shortID = shortID[:8]
	}
	if shortID == "" {
		shortID = "dl"
	}

	candidate := stem
	for collision := 0; ; collision++ {
		if collision == 1 {
			candidate = fmt.Sprintf("%s-%s", stem, shortID)
		} else if collision > 1 {
			candidate = fmt.Sprintf("%s-%s-%d", stem, shortID, collision)
		}
		audioPath := filepath.Join(dir, candidate+ext)
		if !pathOrSidecarExists(audioPath) {
			return audioPath
		}
	}
}

func pathOrSidecarExists(audioPath string) bool {
	if _, err := os.Lstat(audioPath); err == nil {
		return true
	}
	base := strings.TrimSuffix(audioPath, filepath.Ext(audioPath))
	for _, ext := range files.SidecarExtensions {
		if _, err := os.Lstat(base + ext); err == nil {
			return true
		}
	}
	return false
}

// BuildSidecars creates optional .lrc and cover sidecar outputs matching lyricsMode.
func BuildSidecars(targetAudioPath, lyricsMode, lyrics string, coverData []byte, coverMime string) []files.SidecarOutput {
	var out []files.SidecarOutput
	base := strings.TrimSuffix(targetAudioPath, filepath.Ext(targetAudioPath))
	mode := strings.ToLower(strings.TrimSpace(lyricsMode))
	if mode == "" {
		mode = "both"
	}
	if (mode == "both" || mode == "lrc") && strings.TrimSpace(lyrics) != "" {
		out = append(out, files.SidecarOutput{
			Role:       "sidecar_lrc",
			TargetPath: base + ".lrc",
			Data:       []byte(lyrics),
		})
	}
	if len(coverData) > 0 {
		ext := ".jpg"
		if strings.Contains(strings.ToLower(coverMime), "png") {
			ext = ".png"
		} else if strings.Contains(strings.ToLower(coverMime), "webp") {
			ext = ".webp"
		}
		out = append(out, files.SidecarOutput{
			Role:       "sidecar_cover",
			TargetPath: base + ext,
			Data:       coverData,
		})
	}
	return out
}

func truncateUTF8Bytes(s string, maxBytes int) string {
	if len(s) <= maxBytes {
		return s
	}
	for i := range s {
		if i > maxBytes {
			return s[:prevRuneStart(s, i)]
		}
	}
	runes := []rune(s)
	for len(runes) > 0 && len(string(runes)) > maxBytes {
		runes = runes[:len(runes)-1]
	}
	return string(runes)
}

func prevRuneStart(s string, idx int) int {
	best := 0
	for i := range s {
		if i >= idx {
			break
		}
		best = i
	}
	return best
}
