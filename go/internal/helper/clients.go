package helper

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
)

const MaxBatchReadTags = 200

type AudioTagData struct {
	Path          string            `json:"path"`
	Tags          map[string]string `json:"tags"`
	TaggedFields  []string          `json:"tagged_fields,omitempty"`
	Lyrics        string            `json:"lyrics"`
	HasCover      bool              `json:"has_cover"`
	Editable      bool              `json:"editable"`
	Duration      float64           `json:"duration"`
	Bitrate       *int              `json:"bitrate,omitempty"`
	SampleRate    *int              `json:"sample_rate,omitempty"`
	Channels      *int              `json:"channels,omitempty"`
	BitsPerSample *int              `json:"bits_per_sample,omitempty"`
}

type BatchTagItem struct {
	Path  string        `json:"path"`
	OK    bool          `json:"ok"`
	Data  *AudioTagData `json:"data,omitempty"`
	Error string        `json:"error,omitempty"`
}

type CoverSpec struct {
	Path string `json:"path"`
	Mime string `json:"mime"`
}

type WriteTagsRequest struct {
	AllowedStageRoot string            // Required boundary: StagePath must stay inside this directory.
	StagePath        string            // Single staged copy inside task directory.
	Tags             map[string]string // Tag fields to set/clear.
	Lyrics           *string           // Optional lyrics update.
	Cover            *CoverSpec        // Optional cover file inside task directory.
	ClearCover       bool
	ClearAll         bool
}

// TaggerClient wraps the controlled Mutagen NDJSON v1 helper.
// Write operations are strictly single-file against a staged copy; batch_read_tags is read-only <= 200 files.
type TaggerClient struct {
	sup *Supervisor
}

func NewTaggerClient(sup *Supervisor) *TaggerClient {
	return &TaggerClient{sup: sup}
}

func (c *TaggerClient) ReadTags(ctx context.Context, path string) (AudioTagData, error) {
	if strings.TrimSpace(path) == "" {
		return AudioTagData{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "音频路径不能为空")
	}
	raw, err := c.sup.Run(ctx, HelperTagger, "read_tags", DefaultTaskTimeout, map[string]any{
		"path": path,
	}, nil, nil)
	if err != nil {
		return AudioTagData{}, err
	}
	var out AudioTagData
	if err := json.Unmarshal(raw, &out); err != nil {
		return AudioTagData{}, model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "解析标签读取结果失败", err)
	}
	return out, nil
}

func (c *TaggerClient) BatchReadTags(ctx context.Context, paths []string) ([]BatchTagItem, error) {
	if len(paths) == 0 {
		return nil, nil
	}
	if len(paths) > MaxBatchReadTags {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest,
			fmt.Sprintf("batch_read_tags 单批最多支持 %d 个文件", MaxBatchReadTags))
	}
	raw, err := c.sup.Run(ctx, HelperTagger, "batch_read_tags", DefaultLongTimeout, map[string]any{
		"paths": paths,
	}, nil, nil)
	if err != nil {
		return nil, err
	}
	var out struct {
		Items []BatchTagItem `json:"items"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "解析批量标签结果失败", err)
	}
	return out.Items, nil
}

func (c *TaggerClient) WriteTags(ctx context.Context, req WriteTagsRequest) (AudioTagData, error) {
	if strings.TrimSpace(req.AllowedStageRoot) == "" || strings.TrimSpace(req.StagePath) == "" {
		return AudioTagData{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "写标签必须指定受限暂存目录与目标暂存文件")
	}
	if err := ensureInsideRoot(req.AllowedStageRoot, req.StagePath); err != nil {
		return AudioTagData{}, err
	}
	if req.Cover != nil && req.Cover.Path != "" {
		if err := ensureInsideRoot(req.AllowedStageRoot, req.Cover.Path); err != nil {
			return AudioTagData{}, err
		}
	}
	payload := map[string]any{
		"stage_path":  req.StagePath,
		"tags":        req.Tags,
		"clear_cover": req.ClearCover,
		"clear_all":   req.ClearAll,
	}
	if req.Lyrics != nil {
		payload["lyrics"] = *req.Lyrics
	}
	if req.Cover != nil {
		payload["cover"] = req.Cover
	}
	raw, err := c.sup.Run(ctx, HelperTagger, "write_tags", DefaultLongTimeout, payload, nil, nil)
	if err != nil {
		return AudioTagData{}, err
	}
	var out AudioTagData
	if err := json.Unmarshal(raw, &out); err != nil {
		return AudioTagData{}, model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "解析写标签结果失败", err)
	}
	return out, nil
}

func ensureInsideRoot(root, target string) error {
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的暂存根目录", err)
	}
	realRoot, err := filepath.EvalSymlinks(absRoot)
	if err != nil {
		return model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "暂存根目录不可用", err)
	}
	absTarget, err := filepath.Abs(target)
	if err != nil {
		return model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "无效的目标文件路径", err)
	}
	info, err := os.Lstat(absTarget)
	if err != nil {
		return model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "目标暂存文件不存在", err)
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "helper 禁止写入符号链接")
	}
	realTarget, err := filepath.EvalSymlinks(absTarget)
	if err != nil {
		return model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "解析目标暂存文件路径失败", err)
	}
	rel, err := filepath.Rel(realRoot, realTarget)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "helper 仅允许写入任务暂存目录内的文件")
	}
	return nil
}
