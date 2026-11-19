package media

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf16"

	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/model"
)

var TagFields = []string{
	"title", "artist", "album", "album_artist", "date", "track_number", "disc_number",
	"genre", "composer", "comment", "lyricist", "language", "copyright", "publisher",
	"grouping", "bpm", "isrc",
}

var EditableExtensions = map[string]bool{
	".mp3": true, ".flac": true, ".m4a": true, ".mp4": true,
	".ogg": true, ".opus": true, ".wav": true, ".wma": true,
}

type TagReaderWriter interface {
	ReadTags(ctx context.Context, path string) (helper.AudioTagData, error)
	BatchReadTags(ctx context.Context, paths []string) ([]helper.BatchTagItem, error)
	WriteTags(ctx context.Context, req helper.WriteTagsRequest) (helper.AudioTagData, error)
}

type TagService struct {
	tagger    TagReaderWriter
	runner    *Runner
	VerifyPCM bool
}

func NewTagService(tagger TagReaderWriter, runner *Runner) *TagService {
	return &TagService{
		tagger: tagger,
		runner: runner,
	}
}

func (s *TagService) ReadMetadata(ctx context.Context, path string) (helper.AudioTagData, error) {
	if s.tagger != nil {
		data, err := s.tagger.ReadTags(ctx, path)
		if err == nil {
			return data, nil
		}
	}
	if data, ok := ProbeBasicTags(path); ok {
		return data, nil
	}
	fb := fallbackTagData(path)
	if _, _, ok := ExtractEmbeddedCover(path); ok {
		fb.HasCover = true
	}
	return fb, nil
}

// BatchReadMetadata reads metadata in bounded chunks (<= 200 files/call) via TaggerClient,
// falling back to native Go probing if the helper is unavailable.
func (s *TagService) BatchReadMetadata(ctx context.Context, paths []string) []helper.BatchTagItem {
	if len(paths) == 0 {
		return nil
	}
	out := make([]helper.BatchTagItem, 0, len(paths))
	for start := 0; start < len(paths); start += helper.MaxBatchReadTags {
		end := start + helper.MaxBatchReadTags
		if end > len(paths) {
			end = len(paths)
		}
		chunk := paths[start:end]
		var handled bool
		if s.tagger != nil {
			items, err := s.tagger.BatchReadTags(ctx, chunk)
			if err == nil && len(items) == len(chunk) {
				out = append(out, items...)
				handled = true
			}
		}
		if !handled {
			for _, p := range chunk {
				if data, ok := ProbeBasicTags(p); ok {
					d := data
					out = append(out, helper.BatchTagItem{Path: p, OK: true, Data: &d})
				} else {
					d := fallbackTagData(p)
					if _, _, ok := ExtractEmbeddedCover(p); ok {
						d.HasCover = true
					}
					out = append(out, helper.BatchTagItem{Path: p, OK: true, Data: &d})
				}
			}
		}
	}
	return out
}

// WriteStagedTags delegates single-file tag writing strictly within stageRoot to TaggerClient
// and optionally verifies that decoded PCM audio remains bit-identical.
func (s *TagService) WriteStagedTags(ctx context.Context, req helper.WriteTagsRequest) (helper.AudioTagData, error) {
	if s.tagger == nil {
		return helper.AudioTagData{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "标签写入 helper 未配置")
	}
	var beforePCM string
	if s.VerifyPCM && s.runner != nil && s.runner.Available("ffmpeg") {
		var err error
		beforePCM, err = s.runner.AudioHash(ctx, req.StagePath)
		if err != nil {
			return helper.AudioTagData{}, err
		}
	}
	data, err := s.tagger.WriteTags(ctx, req)
	if err != nil {
		return helper.AudioTagData{}, err
	}
	if beforePCM != "" {
		afterPCM, hErr := s.runner.AudioHash(ctx, req.StagePath)
		if hErr != nil {
			return helper.AudioTagData{}, hErr
		}
		if afterPCM != beforePCM {
			return helper.AudioTagData{}, model.NewError(model.ErrInternal, http.StatusInternalServerError, "写标签后音频流校验不一致，已阻止发布")
		}
	}
	return data, nil
}

func fallbackTagData(path string) helper.AudioTagData {
	stem := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	ext := strings.ToLower(filepath.Ext(path))
	return helper.AudioTagData{
		Path:     path,
		Tags:     map[string]string{"title": stem},
		Editable: EditableExtensions[ext],
	}
}

// ProbeBasicTags performs fast read-only native Go container inspection for FLAC and WAV headers
// to avoid spawning Python for basic metadata when sufficient.
func ProbeBasicTags(path string) (helper.AudioTagData, bool) {
	f, err := os.Open(path)
	if err != nil {
		return helper.AudioTagData{}, false
	}
	defer f.Close()

	head := make([]byte, 64)
	n, err := f.Read(head)
	if err != nil || n < 12 {
		return helper.AudioTagData{}, false
	}
	if bytes.Equal(head[:4], []byte("fLaC")) {
		return parseFLACBasic(path, f)
	}
	if bytes.Equal(head[:4], []byte("RIFF")) && bytes.Equal(head[8:12], []byte("WAVE")) {
		return parseWAVBasic(path, f)
	}
	return helper.AudioTagData{}, false
}

func parseFLACBasic(path string, f *os.File) (helper.AudioTagData, bool) {
	if _, err := f.Seek(4, 0); err != nil {
		return helper.AudioTagData{}, false
	}
	data := fallbackTagData(path)
	data.Editable = true
	hdr := make([]byte, 4)
	for blocks := 0; blocks < 32; blocks++ {
		if _, err := f.Read(hdr); err != nil {
			break
		}
		isLast := (hdr[0] & 0x80) != 0
		bType := hdr[0] & 0x7f
		bLen := int(hdr[1])<<16 | int(hdr[2])<<8 | int(hdr[3])
		if bLen < 0 || bLen > 4<<20 {
			break
		}
		payload := make([]byte, bLen)
		if _, err := f.Read(payload); err != nil {
			break
		}
		if bType == 0 && len(payload) >= 18 {
			sr := int(payload[10])<<12 | int(payload[11])<<4 | int(payload[12]>>4)
			ch := int((payload[12]>>1)&0x07) + 1
			bps := int((payload[12]&0x01)<<4|(payload[13]>>4)) + 1
			totalSamples := uint64(payload[13]&0x0f)<<32 | uint64(binary.BigEndian.Uint32(payload[14:18]))
			if sr > 0 {
				data.SampleRate = &sr
				data.Channels = &ch
				data.BitsPerSample = &bps
				data.Duration = float64(totalSamples) / float64(sr)
			}
		} else if bType == 4 {
			parseVorbisComment(payload, &data)
		} else if bType == 6 {
			data.HasCover = true
		}
		if isLast {
			break
		}
	}
	return data, true
}

func parseVorbisComment(payload []byte, out *helper.AudioTagData) {
	if len(payload) < 8 {
		return
	}
	off := 0
	vLen := int(binary.LittleEndian.Uint32(payload[off : off+4]))
	off += 4
	if off+vLen+4 > len(payload) {
		return
	}
	off += vLen
	cnt := int(binary.LittleEndian.Uint32(payload[off : off+4]))
	off += 4
	if cnt > 512 {
		cnt = 512
	}
	for i := 0; i < cnt && off+4 <= len(payload); i++ {
		l := int(binary.LittleEndian.Uint32(payload[off : off+4]))
		off += 4
		if l < 0 || off+l > len(payload) {
			break
		}
		kv := string(payload[off : off+l])
		off += l
		if eq := strings.IndexByte(kv, '='); eq > 0 {
			k := strings.ToLower(kv[:eq])
			v := kv[eq+1:]
			switch k {
			case "albumartist":
				k = "album_artist"
			case "tracknumber":
				k = "track_number"
			case "discnumber":
				k = "disc_number"
			case "organization":
				k = "publisher"
			case "lyrics", "unsyncedlyrics":
				out.Lyrics = v
				continue
			}
			if v != "" {
				out.Tags[k] = v
				out.TaggedFields = append(out.TaggedFields, k)
			}
		}
	}
}

func parseWAVBasic(path string, f *os.File) (helper.AudioTagData, bool) {
	if _, err := f.Seek(12, 0); err != nil {
		return helper.AudioTagData{}, false
	}
	data := fallbackTagData(path)
	data.Editable = true
	var byteRate uint32
	var dataSize uint32
	hdr := make([]byte, 8)
	for chunks := 0; chunks < 32; chunks++ {
		if _, err := f.Read(hdr); err != nil {
			break
		}
		id := string(hdr[:4])
		sz := binary.LittleEndian.Uint32(hdr[4:8])
		if id == "ID3 " || id == "id3 " {
			return helper.AudioTagData{}, false
		}
		if id == "fmt " && sz >= 16 {
			buf := make([]byte, 16)
			if _, err := f.Read(buf); err == nil {
				ch := int(binary.LittleEndian.Uint16(buf[2:4]))
				sr := int(binary.LittleEndian.Uint32(buf[4:8]))
				byteRate = binary.LittleEndian.Uint32(buf[8:12])
				bps := int(binary.LittleEndian.Uint16(buf[14:16]))
				data.Channels = &ch
				data.SampleRate = &sr
				data.BitsPerSample = &bps
			}
			if sz > 16 {
				_, _ = f.Seek(int64(sz-16), 1)
			}
		} else if id == "data" {
			dataSize = sz
			if _, sErr := f.Seek(int64(sz), 1); sErr != nil {
				break
			}
		} else {
			if _, sErr := f.Seek(int64(sz), 1); sErr != nil {
				break
			}
		}
		if sz%2 == 1 {
			_, _ = f.Seek(1, 1)
		}
	}
	if byteRate > 0 && dataSize > 0 {
		data.Duration = float64(dataSize) / float64(byteRate)
	}
	return data, true
}

const maxEmbeddedCoverBytes = 16 << 20

var (
	asfHeaderGUID  = []byte{0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11, 0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C}
	asfExtContent  = []byte{0x40, 0xA4, 0xD0, 0xD2, 0x07, 0xE3, 0xD2, 0x11, 0x97, 0xF0, 0x00, 0xA0, 0xC9, 0x5E, 0xA8, 0x50}
	asfMetadataLib = []byte{0x94, 0x1C, 0x23, 0x44, 0x98, 0x94, 0xD1, 0x49, 0xA1, 0x41, 0x1D, 0x13, 0x4E, 0x45, 0x70, 0x54}
)

// ExtractEmbeddedCover extracts embedded artwork (JPEG/PNG/WebP) from FLAC, MP3 (ID3v2),
// M4A/MP4 (covr atom), Ogg/Opus (METADATA_BLOCK_PICTURE), or WMA/ASF (WM/Picture) without external processes.
func ExtractEmbeddedCover(path string) ([]byte, string, bool) {
	f, err := os.Open(path)
	if err != nil {
		return nil, "", false
	}
	defer f.Close()

	head := make([]byte, 16)
	n, err := f.Read(head)
	if err != nil || n < 4 {
		return nil, "", false
	}
	switch {
	case bytes.Equal(head[:4], []byte("fLaC")):
		return extractFLACCoverFile(f)
	case n >= 3 && bytes.Equal(head[:3], []byte("ID3")):
		return extractID3v2Cover(f)
	case n >= 4 && bytes.Equal(head[:4], []byte("OggS")):
		return extractOggCover(f)
	case n >= 16 && bytes.Equal(head[:16], asfHeaderGUID):
		return extractASFCover(f)
	case n >= 8 && bytes.Equal(head[4:8], []byte("ftyp")):
		return extractMP4Cover(f)
	}
	// Fallback by extension if header differed (e.g. MP4 without leading ftyp or trailing ID3).
	switch strings.ToLower(filepath.Ext(path)) {
	case ".m4a", ".mp4":
		return extractMP4Cover(f)
	case ".ogg", ".opus":
		return extractOggCover(f)
	case ".wma":
		return extractASFCover(f)
	case ".mp3":
		return extractID3v2Cover(f)
	case ".flac":
		return extractFLACCoverFile(f)
	}
	return nil, "", false
}

const maxSidecarCoverBytes = 16 << 20

func isValidImageMagic(mime string, data []byte) bool {
	switch mime {
	case "image/jpeg":
		return len(data) >= 3 && data[0] == 0xff && data[1] == 0xd8 && data[2] == 0xff
	case "image/png":
		return len(data) >= 8 && bytes.HasPrefix(data, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
	case "image/webp":
		return len(data) >= 12 && bytes.HasPrefix(data, []byte("RIFF")) && bytes.Equal(data[8:12], []byte("WEBP"))
	default:
		return false
	}
}

func findSidecarCoverPath(audioPath string) (string, string, bool) {
	dir := filepath.Dir(audioPath)
	baseStem := strings.TrimSuffix(filepath.Base(audioPath), filepath.Ext(audioPath))
	candidates := []string{
		filepath.Join(dir, baseStem),
		filepath.Join(dir, "cover"),
		filepath.Join(dir, "folder"),
		filepath.Join(dir, "front"),
	}
	for _, prefix := range candidates {
		for _, ext := range []string{".jpg", ".jpeg", ".png", ".webp"} {
			p := prefix + ext
			st, err := os.Lstat(p)
			if err != nil || st.IsDir() || st.Mode()&os.ModeSymlink != 0 || st.Size() <= 0 || st.Size() > maxSidecarCoverBytes {
				continue
			}
			mime := "image/jpeg"
			if ext == ".png" {
				mime = "image/png"
			} else if ext == ".webp" {
				mime = "image/webp"
			}
			return p, mime, true
		}
	}
	return "", "", false
}

// FindSidecarCover reads a matching companion cover image beside audioPath.
func FindSidecarCover(audioPath string) ([]byte, string, bool) {
	p, mime, ok := findSidecarCoverPath(audioPath)
	if !ok {
		return nil, "", false
	}
	f, err := os.Open(p)
	if err != nil {
		return nil, "", false
	}
	defer f.Close()
	if st, sErr := f.Stat(); sErr != nil || !st.Mode().IsRegular() || st.Size() > maxSidecarCoverBytes {
		return nil, "", false
	}
	raw, err := io.ReadAll(io.LimitReader(f, maxSidecarCoverBytes+1))
	if err != nil || len(raw) == 0 || len(raw) > maxSidecarCoverBytes || !isValidImageMagic(mime, raw) {
		return nil, "", false
	}
	return raw, mime, true
}

// HasSidecarCover checks whether a matching companion cover file exists beside audioPath.
func HasSidecarCover(audioPath string) bool {
	_, _, ok := findSidecarCoverPath(audioPath)
	return ok
}

// HasLocalCover reports whether audioPath has an embedded cover or companion sidecar cover.
func HasLocalCover(audioPath string) bool {
	if _, _, ok := ExtractEmbeddedCover(audioPath); ok {
		return true
	}
	return HasSidecarCover(audioPath)
}

func normalizeDeclaredMIME(declared string) string {
	d := strings.ToLower(strings.TrimSpace(strings.Split(declared, ";")[0]))
	switch d {
	case "image/jpeg", "image/jpg", "jpg", "jpeg":
		return "image/jpeg"
	case "image/png", "png":
		return "image/png"
	case "image/webp", "webp":
		return "image/webp"
	default:
		return ""
	}
}

func detectImageMIME(raw []byte, declared string) (string, bool) {
	if len(raw) < 12 || len(raw) > maxEmbeddedCoverBytes {
		return "", false
	}
	if bytes.HasPrefix(raw, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}) {
		return "image/png", true
	}
	if len(raw) >= 3 && raw[0] == 0xff && raw[1] == 0xd8 && raw[2] == 0xff {
		return "image/jpeg", true
	}
	if bytes.Equal(raw[:4], []byte("RIFF")) && bytes.Equal(raw[8:12], []byte("WEBP")) {
		return "image/webp", true
	}
	if norm := normalizeDeclaredMIME(declared); norm != "" {
		return norm, true
	}
	return "", false
}

// ParseFLACPictureBlock parses a standard FLAC METADATA_BLOCK_PICTURE payload.
func ParseFLACPictureBlock(buf []byte) ([]byte, string, bool) {
	if len(buf) < 32 {
		return nil, "", false
	}
	off := 4 // skip picture type
	mimeLen := int(binary.BigEndian.Uint32(buf[off : off+4]))
	off += 4
	if mimeLen < 0 || off+mimeLen+4 > len(buf) {
		return nil, "", false
	}
	declared := string(buf[off : off+mimeLen])
	off += mimeLen
	descLen := int(binary.BigEndian.Uint32(buf[off : off+4]))
	off += 4
	if descLen < 0 || off+descLen+20 > len(buf) {
		return nil, "", false
	}
	off += descLen + 16 // skip description + width/height/depth/colors
	dataLen := int(binary.BigEndian.Uint32(buf[off : off+4]))
	off += 4
	if dataLen <= 0 || dataLen > maxEmbeddedCoverBytes || off+dataLen > len(buf) {
		return nil, "", false
	}
	img := buf[off : off+dataLen]
	mime, ok := detectImageMIME(img, declared)
	if !ok {
		return nil, "", false
	}
	return img, mime, true
}

func extractFLACCoverFile(f *os.File) ([]byte, string, bool) {
	if _, err := f.Seek(4, 0); err != nil {
		return nil, "", false
	}
	hdr := make([]byte, 4)
	for i := 0; i < 64; i++ {
		if _, err := io.ReadFull(f, hdr); err != nil {
			break
		}
		isLast := (hdr[0] & 0x80) != 0
		bType := hdr[0] & 0x7f
		bLen := int(hdr[1])<<16 | int(hdr[2])<<8 | int(hdr[3])
		if bLen < 0 || bLen > maxEmbeddedCoverBytes {
			break
		}
		if bType != 6 {
			if _, err := f.Seek(int64(bLen), io.SeekCurrent); err != nil {
				break
			}
			if isLast {
				break
			}
			continue
		}
		buf := make([]byte, bLen)
		if _, err := io.ReadFull(f, buf); err != nil {
			break
		}
		if img, mime, ok := ParseFLACPictureBlock(buf); ok {
			return img, mime, true
		}
		if isLast {
			break
		}
	}
	return nil, "", false
}

func synchsafe32(b []byte) int {
	return int(b[0]&0x7f)<<21 | int(b[1]&0x7f)<<14 | int(b[2]&0x7f)<<7 | int(b[3]&0x7f)
}

func skipID3TerminatedString(buf []byte, off int, wide bool) (string, int, bool) {
	if off >= len(buf) {
		return "", off, false
	}
	if !wide {
		idx := bytes.IndexByte(buf[off:], 0)
		if idx < 0 {
			return "", len(buf), false
		}
		return string(buf[off : off+idx]), off + idx + 1, true
	}
	for i := off; i+1 < len(buf); i += 2 {
		if buf[i] == 0 && buf[i+1] == 0 {
			return "", i + 2, true
		}
	}
	return "", len(buf), false
}

func extractID3v2Cover(f *os.File) ([]byte, string, bool) {
	if _, err := f.Seek(0, 0); err != nil {
		return nil, "", false
	}
	hdr := make([]byte, 10)
	if _, err := io.ReadFull(f, hdr); err != nil || string(hdr[:3]) != "ID3" {
		return nil, "", false
	}
	major := hdr[3]
	if major < 2 || major > 4 {
		return nil, "", false
	}
	tagSize := synchsafe32(hdr[6:10])
	if tagSize <= 0 || tagSize > maxEmbeddedCoverBytes {
		return nil, "", false
	}
	buf := make([]byte, tagSize)
	n, err := io.ReadFull(f, buf)
	if err != nil && err != io.ErrUnexpectedEOF {
		return nil, "", false
	}
	buf = buf[:n]

	off := 0
	// Skip optional extended header in v2.3/v2.4.
	if (hdr[5]&0x40) != 0 && len(buf) >= 4 {
		var extSize int
		if major == 4 {
			extSize = synchsafe32(buf[:4])
		} else {
			extSize = int(binary.BigEndian.Uint32(buf[:4])) + 4
		}
		if extSize > 0 && extSize < len(buf) {
			off = extSize
		}
	}

	for off < len(buf) {
		if buf[off] == 0 {
			break // padding
		}
		if major == 2 {
			if off+6 > len(buf) {
				break
			}
			frameID := string(buf[off : off+3])
			frameSize := int(buf[off+3])<<16 | int(buf[off+4])<<8 | int(buf[off+5])
			off += 6
			if frameSize <= 0 || off+frameSize > len(buf) {
				break
			}
			frame := buf[off : off+frameSize]
			off += frameSize
			if frameID == "PIC" && len(frame) > 6 {
				enc := frame[0]
				declared := strings.ToLower(string(frame[1:4]))
				_, nextOff, ok := skipID3TerminatedString(frame, 5, enc == 1 || enc == 2)
				if ok && nextOff < len(frame) {
					if mime, ok := detectImageMIME(frame[nextOff:], declared); ok {
						return frame[nextOff:], mime, true
					}
				}
			}
			continue
		}
		if off+10 > len(buf) {
			break
		}
		frameID := string(buf[off : off+4])
		var frameSize int
		if major == 4 {
			frameSize = synchsafe32(buf[off+4 : off+8])
		} else {
			frameSize = int(binary.BigEndian.Uint32(buf[off+4 : off+8]))
		}
		flags1 := buf[off+9]
		off += 10
		if frameSize <= 0 || off+frameSize > len(buf) {
			break
		}
		frame := buf[off : off+frameSize]
		off += frameSize
		if frameID != "APIC" {
			continue
		}
		pOff := 0
		if major == 4 && (flags1&0x01) != 0 && len(frame) >= 4 {
			pOff += 4 // data length indicator
		}
		if pOff+2 >= len(frame) {
			continue
		}
		enc := frame[pOff]
		declared, nextOff, ok := skipID3TerminatedString(frame, pOff+1, false)
		if !ok || nextOff+1 >= len(frame) {
			continue
		}
		// nextOff is picture type (1 byte), then description.
		_, imgOff, ok := skipID3TerminatedString(frame, nextOff+1, enc == 1 || enc == 2)
		if !ok || imgOff >= len(frame) {
			continue
		}
		if mime, ok := detectImageMIME(frame[imgOff:], declared); ok {
			return frame[imgOff:], mime, true
		}
	}
	return nil, "", false
}

func findMP4ChildAtom(r io.ReadSeeker, start, end int64, target string) (int64, int64, bool) {
	pos := start
	hdr := make([]byte, 16)
	for pos+8 <= end {
		if _, err := r.Seek(pos, io.SeekStart); err != nil {
			return 0, 0, false
		}
		if _, err := io.ReadFull(r, hdr[:8]); err != nil {
			return 0, 0, false
		}
		size32 := binary.BigEndian.Uint32(hdr[:4])
		atomType := string(hdr[4:8])
		headerLen := int64(8)
		atomSize := int64(size32)
		if size32 == 1 {
			if pos+16 > end {
				return 0, 0, false
			}
			if _, err := io.ReadFull(r, hdr[8:16]); err != nil {
				return 0, 0, false
			}
			atomSize = int64(binary.BigEndian.Uint64(hdr[8:16]))
			headerLen = 16
		} else if size32 == 0 {
			atomSize = end - pos
		}
		if atomSize < headerLen || pos+atomSize > end {
			return 0, 0, false
		}
		if atomType == target {
			return pos + headerLen, pos + atomSize, true
		}
		pos += atomSize
	}
	return 0, 0, false
}

func extractMP4Cover(f *os.File) ([]byte, string, bool) {
	st, err := f.Stat()
	if err != nil {
		return nil, "", false
	}
	fileSize := st.Size()
	s, e, ok := findMP4ChildAtom(f, 0, fileSize, "moov")
	if !ok {
		return nil, "", false
	}
	s, e, ok = findMP4ChildAtom(f, s, e, "udta")
	if !ok {
		return nil, "", false
	}
	s, e, ok = findMP4ChildAtom(f, s, e, "meta")
	if !ok || s+4 > e {
		return nil, "", false
	}
	// meta is a full box with 4 bytes version+flags before children.
	s, e, ok = findMP4ChildAtom(f, s+4, e, "ilst")
	if !ok {
		return nil, "", false
	}
	s, e, ok = findMP4ChildAtom(f, s, e, "covr")
	if !ok {
		return nil, "", false
	}
	ds, de, ok := findMP4ChildAtom(f, s, e, "data")
	if !ok || ds+8 >= de {
		return nil, "", false
	}
	dataLen := de - (ds + 8)
	if dataLen <= 0 || dataLen > maxEmbeddedCoverBytes {
		return nil, "", false
	}
	if _, err := f.Seek(ds+8, io.SeekStart); err != nil {
		return nil, "", false
	}
	img := make([]byte, dataLen)
	if _, err := io.ReadFull(f, img); err != nil {
		return nil, "", false
	}
	if mime, ok := detectImageMIME(img, ""); ok {
		return img, mime, true
	}
	return nil, "", false
}

func extractOggCover(f *os.File) ([]byte, string, bool) {
	if _, err := f.Seek(0, 0); err != nil {
		return nil, "", false
	}
	var packetBuf bytes.Buffer
	var pageHdr [27]byte
	const maxPacketBytes = 16 << 20
	// Scan up to 512 Ogg pages looking for the Vorbis/Opus comment packet (typically page 1..N).
	for pages := 0; pages < 512; pages++ {
		if _, err := io.ReadFull(f, pageHdr[:]); err != nil {
			break
		}
		if !bytes.Equal(pageHdr[:4], []byte("OggS")) {
			break
		}
		nSegs := int(pageHdr[26])
		segTable := make([]byte, nSegs)
		if _, err := io.ReadFull(f, segTable); err != nil {
			break
		}
		for _, segLen := range segTable {
			if segLen > 0 {
				if packetBuf.Len()+int(segLen) > maxPacketBytes {
					return nil, "", false
				}
				if _, err := io.CopyN(&packetBuf, f, int64(segLen)); err != nil {
					return nil, "", false
				}
			}
			if segLen < 255 {
				pkt := packetBuf.Bytes()
				var commentPayload []byte
				switch {
				case len(pkt) > 7 && pkt[0] == 3 && bytes.Equal(pkt[1:7], []byte("vorbis")):
					commentPayload = pkt[7:]
				case len(pkt) > 8 && bytes.Equal(pkt[:8], []byte("OpusTags")):
					commentPayload = pkt[8:]
				}
				if len(commentPayload) >= 8 {
					if img, mime, ok := extractOggCommentPicture(commentPayload); ok {
						return img, mime, true
					}
					return nil, "", false
				}
				packetBuf.Reset()
			}
		}
	}
	return nil, "", false
}

func extractOggCommentPicture(payload []byte) ([]byte, string, bool) {
	if len(payload) < 8 {
		return nil, "", false
	}
	off := 0
	vLen := int(binary.LittleEndian.Uint32(payload[off : off+4]))
	off += 4
	if vLen < 0 || off+vLen+4 > len(payload) {
		return nil, "", false
	}
	off += vLen
	cnt := int(binary.LittleEndian.Uint32(payload[off : off+4]))
	off += 4
	if cnt > 1024 {
		cnt = 1024
	}
	const prefix = "metadata_block_picture="
	for i := 0; i < cnt && off+4 <= len(payload); i++ {
		l := int(binary.LittleEndian.Uint32(payload[off : off+4]))
		off += 4
		if l < 0 || off+l > len(payload) {
			break
		}
		entry := payload[off : off+l]
		off += l
		if len(entry) > len(prefix) && strings.EqualFold(string(entry[:len(prefix)]), prefix) {
			b64 := strings.TrimSpace(string(entry[len(prefix):]))
			raw, err := base64.StdEncoding.DecodeString(b64)
			if err != nil {
				raw, err = base64.RawStdEncoding.DecodeString(strings.TrimRight(b64, "="))
			}
			if err == nil {
				if img, mime, ok := ParseFLACPictureBlock(raw); ok {
					return img, mime, true
				}
			}
		}
	}
	return nil, "", false
}

func decodeUTF16LENullTerminated(b []byte) (string, int) {
	u16 := make([]uint16, 0, len(b)/2)
	consumed := 0
	for i := 0; i+1 < len(b); i += 2 {
		v := binary.LittleEndian.Uint16(b[i : i+2])
		consumed = i + 2
		if v == 0 {
			break
		}
		u16 = append(u16, v)
	}
	return string(utf16.Decode(u16)), consumed
}

func parseASFPicturePayload(buf []byte) ([]byte, string, bool) {
	// WMA/ASF WM/Picture payload: <B picture_type, <I data_len, UTF-16LE mime\0, UTF-16LE desc\0, bytes data.
	if len(buf) < 12 {
		return nil, "", false
	}
	dataLen := int(binary.LittleEndian.Uint32(buf[1:5]))
	if dataLen <= 0 || dataLen > maxEmbeddedCoverBytes {
		return nil, "", false
	}
	mimeStr, c1 := decodeUTF16LENullTerminated(buf[5:])
	_, c2 := decodeUTF16LENullTerminated(buf[5+c1:])
	imgStart := 5 + c1 + c2
	if imgStart+dataLen > len(buf) {
		return nil, "", false
	}
	img := buf[imgStart : imgStart+dataLen]
	mime, ok := detectImageMIME(img, mimeStr)
	if !ok {
		return nil, "", false
	}
	return img, mime, true
}

func extractASFCover(f *os.File) ([]byte, string, bool) {
	if _, err := f.Seek(0, 0); err != nil {
		return nil, "", false
	}
	var hdr [30]byte
	if _, err := io.ReadFull(f, hdr[:]); err != nil || !bytes.Equal(hdr[:16], asfHeaderGUID) {
		return nil, "", false
	}
	headerSize := binary.LittleEndian.Uint64(hdr[16:24])
	numObjs := binary.LittleEndian.Uint32(hdr[24:28])
	if headerSize < 30 || headerSize > maxEmbeddedCoverBytes*2 {
		return nil, "", false
	}
	if numObjs > 512 {
		numObjs = 512
	}
	pos := int64(30)
	objHdr := make([]byte, 24)
	for i := uint32(0); i < numObjs && uint64(pos+24) <= headerSize; i++ {
		if _, err := f.Seek(pos, io.SeekStart); err != nil {
			break
		}
		if _, err := io.ReadFull(f, objHdr); err != nil {
			break
		}
		guid := objHdr[:16]
		objSize := binary.LittleEndian.Uint64(objHdr[16:24])
		if objSize < 24 || uint64(pos)+objSize > headerSize {
			break
		}
		pos += int64(objSize)
		if !bytes.Equal(guid, asfExtContent) && !bytes.Equal(guid, asfMetadataLib) {
			continue
		}
		payloadLen := objSize - 24
		if payloadLen > maxEmbeddedCoverBytes {
			continue
		}
		buf := make([]byte, payloadLen)
		if _, err := io.ReadFull(f, buf); err != nil || len(buf) < 2 {
			continue
		}
		cnt := int(binary.LittleEndian.Uint16(buf[:2]))
		off := 2
		if bytes.Equal(guid, asfExtContent) {
			for j := 0; j < cnt && off+4 <= len(buf); j++ {
				nLen := int(binary.LittleEndian.Uint16(buf[off : off+2]))
				off += 2
				if off+nLen+4 > len(buf) {
					break
				}
				name, _ := decodeUTF16LENullTerminated(buf[off : off+nLen])
				off += nLen
				vType := binary.LittleEndian.Uint16(buf[off : off+2])
				vLen := int(binary.LittleEndian.Uint16(buf[off+2 : off+4]))
				off += 4
				if off+vLen > len(buf) {
					break
				}
				val := buf[off : off+vLen]
				off += vLen
				if strings.EqualFold(name, "WM/Picture") && vType == 1 {
					if img, mime, ok := parseASFPicturePayload(val); ok {
						return img, mime, true
					}
				}
			}
		} else {
			for j := 0; j < cnt && off+12 <= len(buf); j++ {
				nLen := int(binary.LittleEndian.Uint16(buf[off+4 : off+6]))
				vType := binary.LittleEndian.Uint16(buf[off+6 : off+8])
				vLen := int(binary.LittleEndian.Uint32(buf[off+8 : off+12]))
				off += 12
				if off+nLen+vLen > len(buf) {
					break
				}
				name, _ := decodeUTF16LENullTerminated(buf[off : off+nLen])
				val := buf[off+nLen : off+nLen+vLen]
				off += nLen + vLen
				if strings.EqualFold(name, "WM/Picture") && vType == 1 {
					if img, mime, ok := parseASFPicturePayload(val); ok {
						return img, mime, true
					}
				}
			}
		}
	}
	return nil, "", false
}
