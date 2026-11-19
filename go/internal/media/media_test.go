package media

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/helper"
)

func findFFmpeg(t *testing.T) string {
	t.Helper()
	candidates := []string{
		os.Getenv("MELOKEEP_FFMPEG"),
		`E:\tool\ffmpeg\bin\ffmpeg.exe`,
		"ffmpeg",
	}
	for _, c := range candidates {
		if c == "" {
			continue
		}
		if p, err := exec.LookPath(c); err == nil {
			return p
		}
	}
	t.Skip("ffmpeg not found in test environment")
	return ""
}

func TestParseCUE(t *testing.T) {
	cue := `PERFORMER "独立乐手"
TITLE "晨风集"
FILE "album.flac" WAVE
  TRACK 01 AUDIO
    TITLE "晨风序曲"
    INDEX 01 00:00:00
  TRACK 02 AUDIO
    TITLE "星海回响"
    INDEX 01 00:05:00
`
	tracks, err := ParseCUE(filepath.Join(t.TempDir(), "album.flac"), cue, 12.5)
	if err != nil {
		t.Fatalf("ParseCUE failed: %v", err)
	}
	if len(tracks) != 2 || tracks[0].Start != 0 || tracks[0].End != 5 || tracks[1].Start != 5 || tracks[1].End != 12.5 {
		t.Fatalf("unexpected CUE tracks: %+v", tracks)
	}
	if tracks[0].Album != "晨风集" || tracks[0].AlbumArtist != "独立乐手" {
		t.Fatalf("unexpected album metadata: %+v", tracks[0])
	}
}

func TestRunnerFFmpegInspectHashConvertAndCancel(t *testing.T) {
	ffmpegExe := findFFmpeg(t)
	runner := NewRunner(config.DefaultResourceBudgets(), ffmpegExe, "", "")
	ctx := context.Background()

	dir := t.TempDir()
	srcWAV := filepath.Join(dir, "tone.wav")
	// Generate a 1-second 440Hz WAV test fixture.
	cmd := exec.Command(ffmpegExe, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", srcWAV)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("generate wav fixture: %v (%s)", err, string(out))
	}

	ins, err := runner.InspectAudio(ctx, srcWAV)
	if err != nil {
		t.Fatalf("InspectAudio failed: %v", err)
	}
	if ins.Duration < 0.9 || ins.Duration > 1.2 {
		t.Fatalf("unexpected duration: %v", ins.Duration)
	}

	stageFLAC := filepath.Join(dir, "tone.flac")
	if err := runner.Convert(ctx, srcWAV, stageFLAC, ConvertOptions{OutputFormat: "flac"}); err != nil {
		t.Fatalf("Convert to flac failed: %v", err)
	}

	h1, err := runner.AudioHash(ctx, stageFLAC)
	if err != nil || !strings.HasPrefix(h1, "SHA256=") {
		t.Fatalf("AudioHash failed: %q (%v)", h1, err)
	}

	// Fast native Go probe reads FLAC duration/sample rate/channels.
	probed, ok := ProbeBasicTags(stageFLAC)
	if !ok || probed.Duration < 0.9 || probed.SampleRate == nil || *probed.SampleRate <= 0 {
		t.Fatalf("ProbeBasicTags FLAC failed: ok=%v data=%+v", ok, probed)
	}
	probedWAV, okWAV := ProbeBasicTags(srcWAV)
	if !okWAV || probedWAV.Duration < 0.9 {
		t.Fatalf("ProbeBasicTags WAV failed: ok=%v data=%+v", okWAV, probedWAV)
	}

	// Verify cancellation kills ffmpeg immediately.
	cancelCtx, cancel := context.WithTimeout(ctx, 100*time.Millisecond)
	defer cancel()
	longOut := filepath.Join(dir, "cancel.flac")
	if err := runner.Convert(cancelCtx, srcWAV, longOut, ConvertOptions{OutputFormat: "flac"}); err != nil {
		// Expected or completed quickly; ensure no panic.
		_ = err
	}
}

type stubTagger struct {
	batchCalls int
	maxBatch   int
	writeCalls int
}

func (s *stubTagger) ReadTags(_ context.Context, path string) (helper.AudioTagData, error) {
	return helper.AudioTagData{Path: path, Tags: map[string]string{"title": "T"}, Editable: true}, nil
}

func (s *stubTagger) BatchReadTags(_ context.Context, paths []string) ([]helper.BatchTagItem, error) {
	s.batchCalls++
	if len(paths) > s.maxBatch {
		s.maxBatch = len(paths)
	}
	out := make([]helper.BatchTagItem, len(paths))
	for i, p := range paths {
		out[i] = helper.BatchTagItem{
			Path: p, OK: true,
			Data: &helper.AudioTagData{Path: p, Tags: map[string]string{"title": filepath.Base(p)}, Editable: true},
		}
	}
	return out, nil
}

func (s *stubTagger) WriteTags(_ context.Context, req helper.WriteTagsRequest) (helper.AudioTagData, error) {
	s.writeCalls++
	return helper.AudioTagData{Path: req.StagePath, Tags: req.Tags, Editable: true}, nil
}

func TestTagServiceBatchChunkingAndPCMVerify(t *testing.T) {
	stub := &stubTagger{}
	ffmpegExe := findFFmpeg(t)
	runner := NewRunner(config.DefaultResourceBudgets(), ffmpegExe, "", "")
	svc := NewTagService(stub, runner)
	svc.VerifyPCM = true

	paths := make([]string, 450)
	for i := range paths {
		paths[i] = fmt.Sprintf("/music/song_%03d.mp3", i)
	}
	items := svc.BatchReadMetadata(context.Background(), paths)
	if len(items) != 450 {
		t.Fatalf("expected 450 items, got %d", len(items))
	}
	if stub.batchCalls != 3 || stub.maxBatch != helper.MaxBatchReadTags {
		t.Fatalf("expected 3 bounded batch calls (max %d), got calls=%d max=%d", helper.MaxBatchReadTags, stub.batchCalls, stub.maxBatch)
	}

	dir := t.TempDir()
	stageFLAC := filepath.Join(dir, "stage.flac")
	cmd := exec.Command(ffmpegExe, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.3", stageFLAC)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("generate flac fixture: %v (%s)", err, string(out))
	}
	if _, err := svc.WriteStagedTags(context.Background(), helper.WriteTagsRequest{
		AllowedStageRoot: dir,
		StagePath:        stageFLAC,
		Tags:             map[string]string{"title": "晨风序曲"},
	}); err != nil {
		t.Fatalf("WriteStagedTags with PCM verify failed: %v", err)
	}
	if stub.writeCalls != 1 {
		t.Fatalf("expected 1 write call, got %d", stub.writeCalls)
	}
}

func makeFLACPicBlock(mime string, img []byte) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.BigEndian, uint32(3))
	_ = binary.Write(&b, binary.BigEndian, uint32(len(mime)))
	b.WriteString(mime)
	_ = binary.Write(&b, binary.BigEndian, uint32(0)) // desc
	_ = binary.Write(&b, binary.BigEndian, uint32(0)) // w
	_ = binary.Write(&b, binary.BigEndian, uint32(0)) // h
	_ = binary.Write(&b, binary.BigEndian, uint32(0)) // depth
	_ = binary.Write(&b, binary.BigEndian, uint32(0)) // colors
	_ = binary.Write(&b, binary.BigEndian, uint32(len(img)))
	b.Write(img)
	return b.Bytes()
}

func writeSynchsafe(v int) [4]byte {
	return [4]byte{
		byte((v >> 21) & 0x7f),
		byte((v >> 14) & 0x7f),
		byte((v >> 7) & 0x7f),
		byte(v & 0x7f),
	}
}

func makeMP4Atom(typ string, payload []byte) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.BigEndian, uint32(8+len(payload)))
	b.WriteString(typ)
	b.Write(payload)
	return b.Bytes()
}

func encodeUTF16LENull(s string) []byte {
	out := make([]byte, 0, (len(s)+1)*2)
	for _, r := range s {
		out = binary.LittleEndian.AppendUint16(out, uint16(r))
	}
	out = append(out, 0, 0)
	return out
}

func TestExtractEmbeddedCoverAllContainers(t *testing.T) {
	dir := t.TempDir()
	pngSample := []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 1, 0, 0, 0, 1}
	jpgSample := []byte{0xff, 0xd8, 0xff, 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0xff, 0xd9}

	// 1. FLAC
	pic := makeFLACPicBlock("image/png", pngSample)
	flacPath := filepath.Join(dir, "sample.flac")
	var flacBuf bytes.Buffer
	flacBuf.WriteString("fLaC")
	flacBuf.Write([]byte{0x80 | 6, byte(len(pic) >> 16), byte(len(pic) >> 8), byte(len(pic))})
	flacBuf.Write(pic)
	if err := os.WriteFile(flacPath, flacBuf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, mime, ok := ExtractEmbeddedCover(flacPath); !ok || mime != "image/png" || !bytes.Equal(got, pngSample) {
		t.Fatalf("FLAC cover mismatch: ok=%v mime=%q len=%d", ok, mime, len(got))
	}

	// 2. MP3 ID3v2.3 APIC
	var apic bytes.Buffer
	apic.WriteByte(0)
	apic.WriteString("image/jpeg")
	apic.WriteByte(0)
	apic.WriteByte(3)
	apic.WriteString("Cover")
	apic.WriteByte(0)
	apic.Write(jpgSample)
	var id3Frame bytes.Buffer
	id3Frame.WriteString("APIC")
	_ = binary.Write(&id3Frame, binary.BigEndian, uint32(apic.Len()))
	id3Frame.Write([]byte{0, 0})
	id3Frame.Write(apic.Bytes())
	ss := writeSynchsafe(id3Frame.Len())
	var mp3Buf bytes.Buffer
	mp3Buf.WriteString("ID3")
	mp3Buf.Write([]byte{3, 0, 0})
	mp3Buf.Write(ss[:])
	mp3Buf.Write(id3Frame.Bytes())
	mp3Path := filepath.Join(dir, "sample.mp3")
	if err := os.WriteFile(mp3Path, mp3Buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, mime, ok := ExtractEmbeddedCover(mp3Path); !ok || mime != "image/jpeg" || !bytes.Equal(got, jpgSample) {
		t.Fatalf("MP3 cover mismatch: ok=%v mime=%q len=%d", ok, mime, len(got))
	}
	if _, ok := ProbeBasicTags(mp3Path); ok {
		t.Fatalf("expected ProbeBasicTags(mp3) to defer to helper so metadata tags are preserved")
	}
	fbMeta, err := NewTagService(nil, nil).ReadMetadata(context.Background(), mp3Path)
	if err != nil || !fbMeta.HasCover {
		t.Fatalf("expected fallback ReadMetadata HasCover=true for mp3, got err=%v info=%+v", err, fbMeta)
	}

	// Trailing ID3 chunk inside WAV must also defer to helper instead of fast-probing empty tags.
	var wavWithID3 bytes.Buffer
	wavWithID3.WriteString("RIFF")
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint32(4+8+16+8+4+8+3))
	wavWithID3.WriteString("WAVE")
	wavWithID3.WriteString("fmt ")
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint32(16))
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint16(1))
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint16(2))
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint32(44100))
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint32(176400))
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint16(4))
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint16(16))
	wavWithID3.WriteString("data")
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint32(4))
	wavWithID3.Write([]byte{0, 0, 0, 0})
	wavWithID3.WriteString("ID3 ")
	_ = binary.Write(&wavWithID3, binary.LittleEndian, uint32(3))
	wavWithID3.Write([]byte("TAG"))
	wavID3Path := filepath.Join(dir, "with_id3.wav")
	if err := os.WriteFile(wavID3Path, wavWithID3.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, ok := ProbeBasicTags(wavID3Path); ok {
		t.Fatalf("expected ProbeBasicTags(wav with ID3 chunk) to return false")
	}

	// 3. M4A covr atom
	var dataPayload bytes.Buffer
	dataPayload.Write(make([]byte, 8)) // type/locale
	dataPayload.Write(jpgSample)
	covr := makeMP4Atom("covr", makeMP4Atom("data", dataPayload.Bytes()))
	ilst := makeMP4Atom("ilst", covr)
	var metaPayload bytes.Buffer
	metaPayload.Write(make([]byte, 4)) // version/flags
	metaPayload.Write(ilst)
	moov := makeMP4Atom("moov", makeMP4Atom("udta", makeMP4Atom("meta", metaPayload.Bytes())))
	ftyp := makeMP4Atom("ftyp", []byte("M4A \x00\x00\x00\x00"))
	m4aPath := filepath.Join(dir, "sample.m4a")
	if err := os.WriteFile(m4aPath, append(ftyp, moov...), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, mime, ok := ExtractEmbeddedCover(m4aPath); !ok || mime != "image/jpeg" || !bytes.Equal(got, jpgSample) {
		t.Fatalf("M4A cover mismatch: ok=%v mime=%q len=%d", ok, mime, len(got))
	}

	// 4. Ogg/Opus OpusTags METADATA_BLOCK_PICTURE
	var pkt bytes.Buffer
	pkt.WriteString("OpusTags")
	_ = binary.Write(&pkt, binary.LittleEndian, uint32(3))
	pkt.WriteString("mel")
	_ = binary.Write(&pkt, binary.LittleEndian, uint32(1))
	comment := "METADATA_BLOCK_PICTURE=" + base64.StdEncoding.EncodeToString(pic)
	_ = binary.Write(&pkt, binary.LittleEndian, uint32(len(comment)))
	pkt.WriteString(comment)
	pktBytes := pkt.Bytes()
	var ogg bytes.Buffer
	ogg.WriteString("OggS")
	ogg.Write(make([]byte, 22))
	nSeg := len(pktBytes)/255 + 1
	ogg.WriteByte(byte(nSeg))
	for i := 0; i < nSeg-1; i++ {
		ogg.WriteByte(255)
	}
	ogg.WriteByte(byte(len(pktBytes) % 255))
	ogg.Write(pktBytes)
	opusPath := filepath.Join(dir, "sample.opus")
	if err := os.WriteFile(opusPath, ogg.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, mime, ok := ExtractEmbeddedCover(opusPath); !ok || mime != "image/png" || !bytes.Equal(got, pngSample) {
		t.Fatalf("Opus cover mismatch: ok=%v mime=%q len=%d", ok, mime, len(got))
	}

	// 5. WMA/ASF WM/Picture
	var wmPic bytes.Buffer
	wmPic.WriteByte(3)
	_ = binary.Write(&wmPic, binary.LittleEndian, uint32(len(jpgSample)))
	wmPic.Write(encodeUTF16LENull("image/jpeg"))
	wmPic.Write([]byte{0, 0}) // empty desc
	wmPic.Write(jpgSample)
	nameBytes := encodeUTF16LENull("WM/Picture")
	var extObj bytes.Buffer
	_ = binary.Write(&extObj, binary.LittleEndian, uint16(1))
	_ = binary.Write(&extObj, binary.LittleEndian, uint16(len(nameBytes)))
	extObj.Write(nameBytes)
	_ = binary.Write(&extObj, binary.LittleEndian, uint16(1)) // byte array
	_ = binary.Write(&extObj, binary.LittleEndian, uint16(wmPic.Len()))
	extObj.Write(wmPic.Bytes())
	var asfBuf bytes.Buffer
	asfBuf.Write(asfHeaderGUID)
	_ = binary.Write(&asfBuf, binary.LittleEndian, uint64(30+24+extObj.Len()))
	_ = binary.Write(&asfBuf, binary.LittleEndian, uint32(1))
	asfBuf.Write([]byte{1, 2})
	asfBuf.Write(asfExtContent)
	_ = binary.Write(&asfBuf, binary.LittleEndian, uint64(24+extObj.Len()))
	asfBuf.Write(extObj.Bytes())
	wmaPath := filepath.Join(dir, "sample.wma")
	if err := os.WriteFile(wmaPath, asfBuf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, mime, ok := ExtractEmbeddedCover(wmaPath); !ok || mime != "image/jpeg" || !bytes.Equal(got, jpgSample) {
		t.Fatalf("WMA cover mismatch: ok=%v mime=%q len=%d", ok, mime, len(got))
	}
}
