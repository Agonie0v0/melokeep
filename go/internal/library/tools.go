package library

import (
	"bytes"
	"context"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
)

var nonWordRe = regexp.MustCompile(`[^\p{L}\p{N}_]+`)

type DuplicateInput struct {
	AssetID  string
	Name     string
	AbsPath  string
	Title    string
	Artist   string
	Duration float64
	Size     int64
}

type DerivedOutputPlan struct {
	Path  string            `json:"path"`
	Tags  map[string]string `json:"tags,omitempty"`
	Start *float64          `json:"start,omitempty"`
	End   *float64          `json:"end,omitempty"`
}

type ConvertSplitOptions struct {
	OutputFormat string
	Bitrate      int
	SampleRate   int
	CUEText      string
}

// AnalyzeDuplicates groups duplicate candidates according to mode ("file", "audio", "identity").
// It never deletes or modifies files on disk.
func AnalyzeDuplicates(ctx context.Context, runner *media.Runner, mode string, inputs []DuplicateInput) (map[string]string, [][]model.LibraryDuplicateDTO, error) {
	switch mode {
	case "", "file", "audio", "identity":
	default:
		return nil, nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的查重模式")
	}
	if mode == "" {
		mode = "file"
	}

	itemHashes := make(map[string]string, len(inputs))
	groups := make(map[string][]model.LibraryDuplicateDTO)
	var order []string

	for _, in := range inputs {
		if ctx.Err() != nil {
			return nil, nil, model.WrapError(model.ErrCancelled, 499, "查重已取消", ctx.Err())
		}
		var key string
		switch mode {
		case "audio":
			if runner == nil {
				return nil, nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "音频查重需要配置 ffmpeg")
			}
			h, err := runner.AudioHash(ctx, in.AbsPath)
			if err != nil {
				return nil, nil, err
			}
			key = h
		case "identity":
			normTitle := nonWordRe.ReplaceAllString(strings.ToLower(strings.TrimSpace(in.Title)), "")
			normArtist := nonWordRe.ReplaceAllString(strings.ToLower(strings.TrimSpace(in.Artist)), "")
			if normTitle != "" && normArtist != "" && in.Duration > 0 {
				key = fmt.Sprintf("%s|%s|%d", normTitle, normArtist, int(math.Round(in.Duration)))
			} else {
				h, _, err := files.HashFileSHA256(in.AbsPath)
				if err != nil {
					return nil, nil, err
				}
				key = h
			}
		default: // "file"
			h, _, err := files.HashFileSHA256(in.AbsPath)
			if err != nil {
				return nil, nil, err
			}
			key = h
		}
		itemHashes[in.AssetID] = key
		if _, exists := groups[key]; !exists {
			order = append(order, key)
		}
		groups[key] = append(groups[key], model.LibraryDuplicateDTO{
			ID:   in.AssetID,
			Name: in.Name,
			Path: in.AbsPath,
		})
	}

	var dupGroups [][]model.LibraryDuplicateDTO
	for _, k := range order {
		if len(groups[k]) > 1 {
			dupGroups = append(dupGroups, groups[k])
		}
	}
	return itemHashes, dupGroups, nil
}

// InspectQuality decodes and inspects audio stream parameters via media.Runner.
func InspectQuality(ctx context.Context, runner *media.Runner, absPath string, before map[string]string) (map[string]*float64, []string, error) {
	if runner == nil {
		return nil, nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "音质检测需要配置 ffmpeg")
	}
	ins, err := runner.InspectAudio(ctx, absPath)
	if err != nil {
		return nil, nil, err
	}
	dur := ins.Duration
	q := map[string]*float64{
		"duration": &dur,
	}
	if ins.Bitrate != nil {
		v := float64(*ins.Bitrate)
		q["bitrate"] = &v
	}
	if ins.SampleRate != nil {
		v := float64(*ins.SampleRate)
		q["sample_rate"] = &v
	}
	if ins.Channels != nil {
		v := float64(*ins.Channels)
		q["channels"] = &v
	}
	if ins.BitsPerSample != nil {
		v := float64(*ins.BitsPerSample)
		q["bits_per_sample"] = &v
	}
	var warns []string
	if strings.TrimSpace(before["artist"]) == "" {
		warns = append(warns, "缺少artist")
	}
	if strings.TrimSpace(before["album"]) == "" {
		warns = append(warns, "缺少album")
	}
	return q, warns, nil
}

func ValidateConvertOptions(opts ConvertSplitOptions) error {
	fmtName := strings.ToLower(strings.TrimSpace(opts.OutputFormat))
	if fmtName == "" {
		fmtName = "flac"
	}
	if _, ok := media.FormatArgs[fmtName]; !ok {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的输出音频格式")
	}
	switch opts.SampleRate {
	case 0, 8000, 16000, 22050, 24000, 44100, 48000, 88200, 96000, 192000:
	default:
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的目标采样率")
	}
	if opts.Bitrate != 0 && (opts.Bitrate < 32 || opts.Bitrate > 512) {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "目标码率需在 32 到 512 kbps 之间")
	}
	if fmtName == "opus" {
		switch opts.SampleRate {
		case 0, 8000, 16000, 24000, 48000:
		default:
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Opus 请选择 8/16/24/48 kHz 或自动采样率")
		}
	}
	if fmtName == "mp3" {
		if opts.SampleRate > 48000 {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "MP3 采样率不能超过 48 kHz")
		}
		if opts.Bitrate > 320 {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "MP3 码率不能超过 320 kbps")
		}
	}
	return nil
}

// PlanDerivedOutputs computes non-colliding output targets for convert or CUE split actions.
func PlanDerivedOutputs(
	action string,
	srcAbsPath string,
	meta helper.AudioTagData,
	baseTags map[string]string,
	opts ConvertSplitOptions,
	reserved map[string]struct{},
	roots []files.Root,
) ([]DerivedOutputPlan, error) {
	if err := ValidateConvertOptions(opts); err != nil {
		return nil, err
	}
	fmtName := strings.ToLower(strings.TrimSpace(opts.OutputFormat))
	if fmtName == "" {
		fmtName = "flac"
	}
	if fmtName == "opus" && meta.Channels != nil && *meta.Channels == 1 {
		br := opts.Bitrate
		if br <= 0 {
			br = 128
		}
		if br > 256 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Opus 单声道码率不能超过 256 kbps；请降低码率后重试")
		}
	}

	type trackSpec struct {
		tags  map[string]string
		name  string
		start *float64
		end   *float64
	}
	var specs []trackSpec
	stem := strings.TrimSuffix(filepath.Base(srcAbsPath), filepath.Ext(srcAbsPath))

	if action == "split" {
		cueText := opts.CUEText
		if strings.TrimSpace(cueText) == "" {
			cuePath := strings.TrimSuffix(srcAbsPath, filepath.Ext(srcAbsPath)) + ".cue"
			if _, err := files.ValidatePath(roots, cuePath, files.ValidateOptions{MustExist: true}); err != nil {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请提供 CUE 文本或同名 .cue 文件")
			}
			st, err := os.Lstat(cuePath)
			if err != nil || st.Size() > 1_000_000 {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "CUE 文件无效或超过 1 MB")
			}
			raw, err := os.ReadFile(cuePath)
			if err != nil {
				return nil, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取 CUE 文件失败", err)
			}
			cueText = decodeCUEText(raw)
		}
		tracks, err := media.ParseCUE(srcAbsPath, cueText, meta.Duration)
		if err != nil {
			return nil, err
		}
		for _, tr := range tracks {
			tMap := cloneStringMap(baseTags)
			if tr.TrackNumber != "" {
				tMap["track_number"] = tr.TrackNumber
			}
			if tr.Title != "" {
				tMap["title"] = tr.Title
			}
			if tr.Artist != "" {
				tMap["artist"] = tr.Artist
			}
			if tr.Album != "" {
				tMap["album"] = tr.Album
			}
			if tr.AlbumArtist != "" {
				tMap["album_artist"] = tr.AlbumArtist
			}
			num, _ := strconv.Atoi(tr.TrackNumber)
			sVal := tr.Start
			eVal := tr.End
			specs = append(specs, trackSpec{
				tags:  tMap,
				name:  fmt.Sprintf("%02d - %s", num, SafeSegment(tr.Title, "Track")),
				start: &sVal,
				end:   &eVal,
			})
		}
	} else {
		specs = append(specs, trackSpec{
			tags: cloneStringMap(baseTags),
			name: stem,
		})
	}

	dirSuffix := " - converted"
	if action == "split" {
		dirSuffix = " - tracks"
	}
	outDir := filepath.Join(filepath.Dir(srcAbsPath), stem+dirSuffix)

	var plans []DerivedOutputPlan
	for _, sp := range specs {
		baseName := SafeSegment(sp.name, stem)
		cand := filepath.Join(outDir, baseName+"."+fmtName)
		for n := 1; ; n++ {
			_, inRes := reserved[cand]
			_, stErr := os.Lstat(cand)
			if !inRes && os.IsNotExist(stErr) {
				break
			}
			cand = filepath.Join(outDir, fmt.Sprintf("%s (%d).%s", baseName, n, fmtName))
		}
		if _, err := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: false, AudioOnly: true}); err != nil {
			return nil, err
		}
		if reserved != nil {
			reserved[cand] = struct{}{}
		}
		plans = append(plans, DerivedOutputPlan{
			Path:  cand,
			Tags:  sp.tags,
			Start: sp.start,
			End:   sp.end,
		})
	}
	return plans, nil
}

// ExecuteDerived converts/splits srcAbsPath into each derived output via files.Coordinator (Kind: "derive").
func ExecuteDerived(
	ctx context.Context,
	coord *files.Coordinator,
	tagSvc *media.TagService,
	runner *media.Runner,
	jobID string,
	itemID string,
	itemOrdinal int,
	assetID string,
	action string,
	srcAbsPath string,
	outputs []DerivedOutputPlan,
	opts ConvertSplitOptions,
	lyrics string,
	coverData []byte,
	coverMIME string,
) ([]string, error) {
	if runner == nil {
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "转码与分轨需要配置 ffmpeg")
	}
	fmtName := strings.ToLower(strings.TrimSpace(opts.OutputFormat))
	if fmtName == "" {
		fmtName = "flac"
	}
	var opIDs []string
	for idx, out := range outputs {
		if err := os.MkdirAll(filepath.Dir(out.Path), 0o755); err != nil {
			return opIDs, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "创建输出目录失败", err)
		}
		ord := itemOrdinal*1000 + idx
		var estDurMS int64
		if out.Start != nil && out.End != nil && *out.End > *out.Start {
			estDurMS = int64((*out.End - *out.Start) * 1000)
		}
		res, err := coord.Execute(ctx, files.OperationRequest{
			Kind:          "derive",
			AssetID:       assetID,
			SourcePath:    srcAbsPath,
			TargetPath:    out.Path,
			JobID:         jobID,
			ItemID:        itemID,
			ItemOrdinal:   &ord,
			NewTitle:      out.Tags["title"],
			NewArtist:     out.Tags["artist"],
			NewAlbum:      out.Tags["album"],
			NewDurationMS: estDurMS,
			NewMetadata:   out.Tags,
			Editable:      true,
			Mutator: func(mctx context.Context, stagePath string) ([]files.SidecarOutput, error) {
				if err := runner.Convert(mctx, srcAbsPath, stagePath, media.ConvertOptions{
					OutputFormat: fmtName,
					Bitrate:      opts.Bitrate,
					SampleRate:   opts.SampleRate,
					Start:        out.Start,
					End:          out.End,
				}); err != nil {
					return nil, err
				}
				if tagSvc != nil {
					var lrcPtr *string
					if action == "convert" && lyrics != "" {
						l := lyrics
						lrcPtr = &l
					}
					var coverSpec *helper.CoverSpec
					var coverTmp string
					if len(coverData) > 0 && coverMIME != "" {
						ext := ".jpg"
						if coverMIME == "image/png" {
							ext = ".png"
						}
						coverTmp = filepath.Join(filepath.Dir(stagePath), ".melokeep-cov-"+model.NewUUID()+ext)
						if _, wErr := files.WriteFileDurable(coverTmp, coverData); wErr == nil {
							coverSpec = &helper.CoverSpec{Path: coverTmp, Mime: coverMIME}
							defer os.Remove(coverTmp)
						}
					}
					_, _ = tagSvc.WriteStagedTags(mctx, helper.WriteTagsRequest{
						AllowedStageRoot: filepath.Dir(stagePath),
						StagePath:        stagePath,
						Tags:             out.Tags,
						Lyrics:           lrcPtr,
						Cover:            coverSpec,
					})
				}
				return nil, nil
			},
		})
		if err != nil {
			return opIDs, err
		}
		opIDs = append(opIDs, res.OperationID)
	}
	return opIDs, nil
}

func decodeCUEText(raw []byte) string {
	raw = bytes.TrimPrefix(raw, []byte{0xEF, 0xBB, 0xBF})
	if utf8.Valid(raw) {
		return string(raw)
	}
	return strings.ToValidUTF8(string(raw), "")
}

func cloneStringMap(m map[string]string) map[string]string {
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

var _ = sort.Strings
