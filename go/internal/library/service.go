package library

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

const (
	settingsNamespace        = "library_settings"
	durableJobMetaNamespace  = "library_job_meta"
	defaultMaxBatchFiles     = 1000
	maxInlineLyricsBytes     = 2_000_000
	maxInlineCoverDecodedLen = 8 << 20
)

const maxScrapeSourcesPerTask = 12

var validActions = map[string]bool{
	"scrape": true, "organize": true, "both": true,
	"edit": true, "extract": true, "clean": true, "duplicates": true,
	"rename": true, "export_lyrics": true, "import_lyrics": true,
	"export_cover": true, "import_cover": true, "clear": true,
	"convert": true, "split": true, "quality": true, "artist_artwork": true,
}

// Settings mirrors library_pipeline.py LibrarySettings.
type Settings struct {
	AutoScrape         bool     `json:"auto_scrape"`
	AutoOrganize       bool     `json:"auto_organize"`
	LibraryDir         string   `json:"library_dir"`
	FolderTemplate     string   `json:"folder_template"`
	FilenameTemplate   string   `json:"filename_template"`
	Sources            []string `json:"sources"`
	Overwrite          bool     `json:"overwrite"`
	LyricsMode         string   `json:"lyrics_mode"`
	CoverMode          string   `json:"cover_mode"`
	Fields             []string `json:"fields"`
	BackupDays         int      `json:"backup_days"`
	Chinese            string   `json:"chinese"`
	RepairEncoding     bool     `json:"repair_encoding"`
	WatchEnabled       bool     `json:"watch_enabled"`
	WatchDirs          []string `json:"watch_dirs"`
	WatchInterval      int      `json:"watch_interval"`
	WatchStableSeconds int      `json:"watch_stable_seconds"`
	WatchAction        string   `json:"watch_action"`
	WatchApply         bool     `json:"watch_apply"`
	WatchFailureDir    string   `json:"watch_failure_dir"`
}

func DefaultSettings() Settings {
	fields := make([]string, len(media.TagFields))
	copy(fields, media.TagFields)
	return Settings{
		AutoScrape:         false,
		AutoOrganize:       false,
		LibraryDir:         "library",
		FolderTemplate:     "{album_artist}/{album}",
		FilenameTemplate:   "{track_number} - {title}",
		Sources:            []string{"itunes", "musicbrainz", "lrclib"},
		Overwrite:          false,
		LyricsMode:         "both",
		CoverMode:          "both",
		Fields:             fields,
		BackupDays:         7,
		Chinese:            "none",
		RepairEncoding:     false,
		WatchEnabled:       false,
		WatchDirs:          []string{"incoming"},
		WatchInterval:      180,
		WatchStableSeconds: 30,
		WatchAction:        "both",
		WatchApply:         true,
		WatchFailureDir:    "",
	}
}

func validateScrapeSourceList(sources []string, scraper *Scraper) ([]string, error) {
	if len(sources) == 0 || len(sources) > maxScrapeSourcesPerTask {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "刮削来源数量无效")
	}
	seen := make(map[string]bool, len(sources))
	out := make([]string, 0, len(sources))
	for _, raw := range sources {
		src := strings.TrimSpace(raw)
		if !scraper.ValidSourceID(src) {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的刮削来源: "+raw)
		}
		if !seen[src] {
			seen[src] = true
			out = append(out, src)
		}
	}
	return out, nil
}

func filterValidScrapeSources(sources, fallback []string, scraper *Scraper) []string {
	seen := make(map[string]bool, len(sources))
	filtered := make([]string, 0, len(sources))
	for _, raw := range sources {
		src := strings.TrimSpace(raw)
		if src == "" || seen[src] {
			continue
		}
		if scraper.ValidSourceID(src) {
			seen[src] = true
			filtered = append(filtered, src)
		}
	}
	if len(filtered) == 0 {
		return append([]string(nil), fallback...)
	}
	if len(filtered) > maxScrapeSourcesPerTask {
		filtered = filtered[:maxScrapeSourcesPerTask]
	}
	return filtered
}

func ValidateSettings(s Settings, roots []files.Root) (Settings, error) {
	return ValidateSettingsWithScraper(s, roots, nil)
}

func ValidateSettingsWithScraper(s Settings, roots []files.Root, scraper *Scraper) (Settings, error) {
	s.LibraryDir = strings.TrimSpace(s.LibraryDir)
	if s.LibraryDir == "" {
		return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请填写整理后的音乐库目录")
	}
	if err := ValidateTemplate(s.FolderTemplate, false); err != nil {
		return s, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, err.Error(), err)
	}
	if err := ValidateTemplate(s.FilenameTemplate, true); err != nil {
		return s, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, err.Error(), err)
	}
	validSources, err := validateScrapeSourceList(s.Sources, scraper)
	if err != nil {
		return s, err
	}
	s.Sources = validSources
	switch s.LyricsMode {
	case "both", "embed", "lrc", "none":
	default:
		return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的歌词模式")
	}
	switch s.CoverMode {
	case "both", "embed", "file", "none":
	default:
		return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的封面模式")
	}
	switch s.Chinese {
	case "", "none", "simplified", "traditional":
		if s.Chinese == "" {
			s.Chinese = "none"
		}
	default:
		return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的繁简转换模式")
	}
	allowedFields := make(map[string]bool, len(media.TagFields))
	for _, f := range media.TagFields {
		allowedFields[f] = true
	}
	dedupFields := make([]string, 0, len(s.Fields))
	seenField := make(map[string]bool, len(s.Fields))
	for _, f := range s.Fields {
		if !allowedFields[f] {
			return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的标签字段: "+f)
		}
		if !seenField[f] {
			seenField[f] = true
			dedupFields = append(dedupFields, f)
		}
	}
	s.Fields = dedupFields
	if s.BackupDays < 0 || s.BackupDays > 365 {
		return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "备份保留天数需在 0 到 365 之间")
	}
	if s.WatchInterval == 0 {
		s.WatchInterval = 180
	}
	if s.WatchInterval < 10 || s.WatchInterval > 86400 {
		return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "监控间隔需在 10 到 86400 秒之间")
	}
	if s.WatchStableSeconds == 0 {
		s.WatchStableSeconds = 30
	}
	if s.WatchStableSeconds < 2 || s.WatchStableSeconds > 3600 {
		return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件稳定等待需在 2 到 3600 秒之间")
	}
	switch s.WatchAction {
	case "", "scrape", "organize", "both":
		if s.WatchAction == "" {
			s.WatchAction = "both"
		}
	default:
		return s, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的监控动作")
	}
	if len(roots) > 0 {
		if _, _, _, err := ValidateWatchRoots(s, roots); err != nil {
			return s, err
		}
	}
	return s, nil
}

type SubmitRequest struct {
	Paths            []string          `json:"paths"`
	ScopeDir         *string           `json:"scope_dir,omitempty"`
	Action           string            `json:"action"`
	Changes          map[string]string `json:"changes,omitempty"`
	Lyrics           *string           `json:"lyrics,omitempty"`
	CoverData        string            `json:"cover_data,omitempty"`
	Overwrite        *bool             `json:"overwrite,omitempty"`
	Sources          []string          `json:"sources,omitempty"`
	FilenameOrder    string            `json:"filename_order,omitempty"`
	Find             string            `json:"find,omitempty"`
	Replacement      string            `json:"replacement,omitempty"`
	Chinese          string            `json:"chinese,omitempty"`
	RepairEncoding   *bool             `json:"repair_encoding,omitempty"`
	FilenameTemplate string            `json:"filename_template,omitempty"`
	ExtractTemplate  string            `json:"extract_template,omitempty"`
	DuplicateMode    string            `json:"duplicate_mode,omitempty"`
	OutputFormat     string            `json:"output_format,omitempty"`
	Bitrate          int               `json:"bitrate,omitempty"`
	SampleRate       int               `json:"sample_rate,omitempty"`
	CUE              string            `json:"cue,omitempty"`
	SourceDownloadID string            `json:"source_download_id,omitempty"`
	AutoApply        bool              `json:"auto_apply,omitempty"`
}

type submitFlags struct {
	Watch      bool
	WatchApply bool
}

type NavidromeRefreshHook func(ctx context.Context) error
type LogFunc func(level, message, category, taskID string)

type ServiceOptions struct {
	SnapshotProvider func() config.Snapshot
	NavidromeHook    NavidromeRefreshHook
	Logger           LogFunc
	TestAfterPhase   func(phase string) error
}

type persistedItemMeta struct {
	ID           string                      `json:"id"`
	Ordinal      int                         `json:"ordinal"`
	AssetID      string                      `json:"asset_id"`
	RootID       string                      `json:"root_id"`
	RelPath      string                      `json:"rel_path"`
	Name         string                      `json:"name"`
	OriginalPath string                      `json:"original_path"`
	Target       string                      `json:"target"`
	Status       model.LibraryItemStatus     `json:"status"`
	ExpectedRev  int64                       `json:"expected_revision"`
	Before       map[string]string           `json:"before"`
	After        map[string]string           `json:"after"`
	Candidates   []ScrapeCandidate           `json:"candidates,omitempty"`
	Chosen       *string                     `json:"chosen"`
	Locks        []string                    `json:"locks"`
	Warnings     []string                    `json:"warnings"`
	Error        string                      `json:"error,omitempty"`
	OperationID  string                      `json:"operation_id,omitempty"`
	Quality      map[string]*float64         `json:"quality,omitempty"`
	Outputs      []model.LibraryJobOutputDTO `json:"outputs,omitempty"`
	Artwork      *model.LibraryJobArtworkDTO `json:"artwork,omitempty"`
	CoverURL     string                      `json:"cover_url,omitempty"`
	LyricsText   *string                     `json:"lyrics_text,omitempty"`
	InlineCover  string                      `json:"inline_cover,omitempty"`
	ClearCover   bool                        `json:"clear_cover,omitempty"`
	ClearAll     bool                        `json:"clear_all,omitempty"`
	DerivedPlans []DerivedOutputPlan         `json:"derived_plans,omitempty"`
	SourceMeta   helper.AudioTagData         `json:"source_meta,omitempty"`
}

type persistedJobMeta struct {
	JobID      string                        `json:"job_id"`
	Action     string                        `json:"action"`
	OwnerKey   string                        `json:"owner_key"`
	Settings   Settings                      `json:"settings"`
	Request    SubmitRequest                 `json:"request"`
	Flags      submitFlags                   `json:"flags"`
	Duplicates [][]model.LibraryDuplicateDTO `json:"duplicates,omitempty"`
	Items      []persistedItemMeta           `json:"items"`
}

type jobControl struct {
	mu           sync.Mutex
	pauseWanted  bool
	cancelWanted bool
	applying     bool
}

type Service struct {
	store       *store.Store
	coordinator *files.Coordinator
	tagSvc      *media.TagService
	runner      *media.Runner
	scraper     *Scraper
	opts        ServiceOptions
	watcher     *Watcher

	ctrlMu   sync.Mutex
	controls map[string]*jobControl
	closed   bool
}

func NewService(
	st *store.Store,
	coord *files.Coordinator,
	tagSvc *media.TagService,
	runner *media.Runner,
	scraper *Scraper,
	opts ServiceOptions,
) *Service {
	if opts.SnapshotProvider == nil {
		opts.SnapshotProvider = func() config.Snapshot {
			return config.NewSnapshot(config.DefaultRuntimeConfig(), config.DefaultResourceBudgets())
		}
	}
	s := &Service{
		store:       st,
		coordinator: coord,
		tagSvc:      tagSvc,
		runner:      runner,
		scraper:     scraper,
		opts:        opts,
		controls:    make(map[string]*jobControl),
	}
	s.watcher = NewWatcher(s)
	return s
}

func (s *Service) log(level, taskID, message string) {
	if s != nil && s.opts.Logger != nil && strings.TrimSpace(message) != "" {
		s.opts.Logger(level, message, "library", taskID)
	}
}

func (s *Service) Watcher() *Watcher {
	return s.watcher
}

func (s *Service) Close() error {
	s.ctrlMu.Lock()
	s.closed = true
	s.ctrlMu.Unlock()
	if s.watcher != nil {
		s.watcher.Close()
	}
	return nil
}

func (s *Service) getControl(jobID string) *jobControl {
	s.ctrlMu.Lock()
	defer s.ctrlMu.Unlock()
	c, ok := s.controls[jobID]
	if !ok {
		c = &jobControl{}
		s.controls[jobID] = c
	}
	return c
}

// SourceCatalog returns available built-in and enabled plugin metadata sources.
func (s *Service) SourceCatalog() []model.MetadataSourceDTO {
	if s == nil {
		return SourceCatalog()
	}
	return s.scraper.SourceCatalog()
}

func (s *Service) Settings(ctx context.Context) (Settings, error) {
	def := DefaultSettings()
	if s.store == nil {
		return def, nil
	}
	rec, ok, err := s.store.GetSetting(ctx, settingsNamespace)
	if err != nil {
		return def, err
	}
	if !ok || strings.TrimSpace(rec.BodyJSON) == "" || rec.BodyJSON == "{}" {
		return def, nil
	}
	out := DefaultSettings()
	out.Sources = nil
	if err := json.Unmarshal([]byte(rec.BodyJSON), &out); err != nil {
		return def, nil
	}
	out.Sources = filterValidScrapeSources(out.Sources, def.Sources, s.scraper)
	return out, nil
}

func (s *Service) SaveSettings(ctx context.Context, actor auth.Actor, next Settings) (Settings, error) {
	if !actor.Authenticated {
		return Settings{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return Settings{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少修改曲库设置的权限")
	}
	var roots []files.Root
	if s.coordinator != nil {
		roots = s.coordinator.Roots()
	}
	validated, err := ValidateSettingsWithScraper(next, roots, s.scraper)
	if err != nil {
		return Settings{}, err
	}
	raw, err := json.Marshal(validated)
	if err != nil {
		return Settings{}, err
	}
	if _, err := s.store.PutSetting(ctx, settingsNamespace, string(raw), 0); err != nil {
		return Settings{}, err
	}
	return validated, nil
}

func (s *Service) Submit(ctx context.Context, actor auth.Actor, req SubmitRequest) (model.LibraryJobDTO, error) {
	return s.submitInternal(ctx, actor, req, submitFlags{})
}

func (s *Service) submitInternal(ctx context.Context, actor auth.Actor, req SubmitRequest, flags submitFlags) (model.LibraryJobDTO, error) {
	if !actor.Authenticated {
		return model.LibraryJobDTO{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if !validActions[req.Action] {
		return model.LibraryJobDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的曲库操作")
	}
	isReport := req.Action == "duplicates" || req.Action == "quality"
	if isReport {
		if !actor.Has(auth.PermRead) {
			return model.LibraryJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少读取权限")
		}
	} else if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return model.LibraryJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少曲库写入权限")
	}

	if err := validateSubmitRequest(req); err != nil {
		return model.LibraryJobDTO{}, err
	}
	if (req.Action == "scrape" || req.Action == "both") && len(req.Sources) > 0 {
		validatedSources, err := validateScrapeSourceList(req.Sources, s.scraper)
		if err != nil {
			return model.LibraryJobDTO{}, err
		}
		req.Sources = validatedSources
	}

	settings, err := s.Settings(ctx)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	roots := s.coordinator.Roots()
	if len(roots) == 0 {
		return model.LibraryJobDTO{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "未配置音乐根目录")
	}

	resolvedPaths, err := s.resolveRequestPaths(ctx, roots, req)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	if len(resolvedPaths) == 0 {
		return model.LibraryJobDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请选择至少一个音频文件")
	}

	libRoot := ""
	if req.Action == "organize" || req.Action == "both" {
		libRoot = resolveDirAgainstRoots(settings.LibraryDir, roots)
		if _, lErr := files.ValidatePath(roots, libRoot, files.ValidateOptions{MustExist: false, AudioOnly: false}); lErr != nil {
			return model.LibraryJobDTO{}, lErr
		}
	}

	absPaths := make([]string, len(resolvedPaths))
	for i, vp := range resolvedPaths {
		absPaths[i] = vp.AbsPath
	}
	var tagResults []helper.BatchTagItem
	if s.tagSvc != nil {
		tagResults = s.tagSvc.BatchReadMetadata(ctx, absPaths)
	}

	tagByPath := make(map[string]helper.AudioTagData, len(tagResults))
	for _, r := range tagResults {
		if r.OK && r.Data != nil {
			tagByPath[r.Path] = *r.Data
		}
	}

	if req.Action == "duplicates" {
		return s.submitDuplicatesJob(ctx, actor, settings, req, resolvedPaths, tagByPath)
	}

	reserved := make(map[string]struct{})
	for _, vp := range resolvedPaths {
		reserved[vp.AbsPath] = struct{}{}
	}
	metaItems := make([]persistedItemMeta, 0, len(resolvedPaths))
	storeItems := make([]store.LibraryItemInput, 0, len(resolvedPaths))
	snap := s.opts.SnapshotProvider()

	for idx, vp := range resolvedPaths {
		asset, err := s.ensureAssetForPath(ctx, vp, tagByPath[vp.AbsPath])
		if err != nil {
			return model.LibraryJobDTO{}, err
		}
		itemMeta := s.buildPreviewItem(ctx, snap, settings, req, flags, roots, libRoot, vp, asset, tagByPath[vp.AbsPath], idx+1, reserved)
		metaItems = append(metaItems, itemMeta)
		storeItems = append(storeItems, toStoreLibraryItemInput(itemMeta))
	}

	if !req.AutoApply && req.Action != "quality" && req.Action != "duplicates" && !flags.Watch {
		if err := s.cleanupDuplicatePreviewJobs(ctx, actor, req, resolvedPaths); err != nil {
			return model.LibraryJobDTO{}, err
		}
	}

	jobID := model.NewUUID()
	jobStatus := deriveInitialJobStatus(req.Action, metaItems)
	detail := buildJobDetail(req.Action, jobStatus, metaItems)
	settingsJSON, _ := json.Marshal(settings)
	optionsJSON, _ := json.Marshal(req)

	if _, err := s.store.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID:            jobID,
		SourceDownloadID: req.SourceDownloadID,
		Action:           req.Action,
		State:            jobStatus,
		SettingsJSON:     string(settingsJSON),
		OptionsJSON:      string(optionsJSON),
		WarningsJSON:     "[]",
		Detail:           detail,
		OwnerKey:         actor.Key(),
		Items:            storeItems,
	}); err != nil {
		return model.LibraryJobDTO{}, err
	}

	jobMeta := persistedJobMeta{
		JobID:    jobID,
		Action:   req.Action,
		OwnerKey: actor.Key(),
		Settings: settings,
		Request:  req,
		Flags:    flags,
		Items:    metaItems,
	}
	if err := s.saveJobMeta(ctx, jobMeta); err != nil {
		return model.LibraryJobDTO{}, err
	}

	if req.Action == "quality" {
		// Quality inspection is read-only and finishes immediately.
		if err := s.persistJobAndItems(ctx, jobMeta, model.LibraryJobComplete, "音质检测完成", len(metaItems)); err != nil {
			return model.LibraryJobDTO{}, err
		}
		s.log("info", jobID, fmt.Sprintf("音质完整性检查完成：共 %d 首", len(metaItems)))
		return s.ToDTO(ctx, jobID)
	}

	readyCnt, reviewCnt, failedCnt := countStatusBreakdown(metaItems)
	s.log("info", jobID, fmt.Sprintf("提交曲库任务 (%s)：共 %d 首（就绪=%d, 待确认=%d, 失败=%d, 自动应用=%v）",
		req.Action, len(metaItems), readyCnt, reviewCnt, failedCnt, req.AutoApply))

	if flags.Watch && flags.WatchApply {
		if jobStatus == model.LibraryJobPreviewReady {
			return s.Apply(ctx, actor, jobID)
		}
		if flags.Watch && settings.WatchFailureDir != "" {
			s.handleWatchFailures(ctx, actor, settings, roots, &jobMeta)
		}
	}
	if req.AutoApply && req.Action != "duplicates" && req.Action != "quality" {
		for _, it := range metaItems {
			if it.Status == model.LibraryItemPreviewReady {
				return s.Apply(ctx, actor, jobID)
			}
		}
	}

	return s.ToDTO(ctx, jobID)
}

// AutoOrganizeDownload implements download.AutoOrganizeHandler after download outputs are published.
func (s *Service) AutoOrganizeDownload(ctx context.Context, jobID string, dl model.Download) error {
	j, items, ok, err := s.store.GetLibraryJob(ctx, jobID)
	if err != nil {
		return err
	}
	if !ok {
		return model.NewError(model.ErrNotFound, http.StatusNotFound, "自动整理任务不存在")
	}
	if len(items) == 0 && dl.ID != "" {
		rows, qErr := s.store.Reader().QueryContext(ctx, `
			SELECT asset_id, rel_path FROM download_outputs WHERE download_id = ? ORDER BY ordinal ASC
		`, dl.ID)
		if qErr == nil {
			for rows.Next() {
				var assetID, relPath string
				if rows.Scan(&assetID, &relPath) == nil {
					items = append(items, model.LibraryItem{
						ID:           model.NewUUID(),
						JobID:        jobID,
						AssetID:      assetID,
						Name:         filepath.Base(relPath),
						OriginalPath: relPath,
						Status:       model.LibraryItemQueued,
					})
				}
			}
			_ = rows.Close()
			if len(items) > 0 {
				_ = s.store.WithTx(ctx, func(tx *sql.Tx) error {
					for ord, it := range items {
						_, iErr := tx.ExecContext(ctx, `
							INSERT INTO library_items(
								job_id, ordinal, item_id, asset_id, path_token, name, original_path, target_path,
								state, expected_revision, before_json, proposed_json, selected_candidate_json,
								locks_json, warnings_json, error_json, operation_id
							) VALUES(?, ?, ?, ?, ?, ?, ?, '', 'queued', 1, '{}', '{}', 'null', '[]', '[]', '{}', '')
							ON CONFLICT(job_id, ordinal) DO NOTHING
						`, jobID, ord, it.ID, it.AssetID, it.OriginalPath, it.Name, it.OriginalPath)
						if iErr != nil {
							return iErr
						}
					}
					return nil
				})
			}
		}
	}
	settings, err := s.Settings(ctx)
	if err != nil {
		return err
	}
	if !settings.AutoScrape && !settings.AutoOrganize && j.Action == "" {
		return nil
	}
	action := j.Action
	if action == "" {
		if settings.AutoScrape && settings.AutoOrganize {
			action = "both"
		} else if settings.AutoScrape {
			action = "scrape"
		} else {
			action = "organize"
		}
	}
	roots := s.coordinator.Roots()
	libRoot := ""
	if action == "organize" || action == "both" {
		libRoot = resolveDirAgainstRoots(settings.LibraryDir, roots)
	}
	snap := s.opts.SnapshotProvider()
	reserved := make(map[string]struct{})
	var metaItems []persistedItemMeta

	for idx, it := range items {
		var asset store.AssetRecord
		if it.AssetID != "" {
			if a, found, aErr := s.store.GetAsset(ctx, it.AssetID); aErr == nil && found {
				asset = a
			}
		}
		absPath := it.OriginalPath
		if asset.RootID != "" && asset.RelPath != "" {
			for _, r := range roots {
				if r.ID == asset.RootID {
					absPath = filepath.Join(r.CanonicalPath, filepath.FromSlash(asset.RelPath))
					break
				}
			}
		}
		vp, vErr := files.ValidatePath(roots, absPath, files.ValidateOptions{MustExist: true, AudioOnly: true})
		if vErr != nil {
			metaItems = append(metaItems, persistedItemMeta{
				ID:           it.ID,
				Ordinal:      idx,
				AssetID:      it.AssetID,
				Name:         it.Name,
				OriginalPath: absPath,
				Status:       model.LibraryItemFailed,
				Error:        vErr.Error(),
				Warnings:     []string{},
				Locks:        it.Locks,
			})
			continue
		}
		reserved[vp.AbsPath] = struct{}{}
		var tagData helper.AudioTagData
		if s.tagSvc != nil {
			if d, dErr := s.tagSvc.ReadMetadata(ctx, vp.AbsPath); dErr == nil {
				tagData = d
			}
		}
		req := SubmitRequest{Action: action, SourceDownloadID: dl.ID}
		built := s.buildPreviewItem(ctx, snap, settings, req, submitFlags{}, roots, libRoot, vp, asset, tagData, idx+1, reserved)
		built.ID = it.ID
		built.Ordinal = idx
		metaItems = append(metaItems, built)
	}

	jobMeta := persistedJobMeta{
		JobID:    jobID,
		Action:   action,
		OwnerKey: dl.ActorKey,
		Settings: settings,
		Request:  SubmitRequest{Action: action, SourceDownloadID: dl.ID},
		Items:    metaItems,
	}
	nextState := deriveInitialJobStatus(action, metaItems)
	if err := s.saveJobMeta(ctx, jobMeta); err != nil {
		return err
	}
	if err := s.persistJobAndItems(ctx, jobMeta, nextState, buildJobDetail(action, nextState, metaItems), 0); err != nil {
		return err
	}
	s.log("info", jobID, fmt.Sprintf("下载完成，开始自动刮削整理 (%s)：%d 个文件", action, len(metaItems)))
	if nextState == model.LibraryJobPreviewReady {
		_, err = s.Apply(ctx, auth.SystemActor(), jobID)
		if err != nil {
			s.log("error", jobID, "下载自动整理写入失败："+err.Error())
		}
		return err
	}
	if nextState == model.LibraryJobNeedsReview {
		s.log("warning", jobID, "下载自动整理未自动匹配到唯一版本，已转人工审核确认")
	} else if nextState == model.LibraryJobFailed {
		s.log("error", jobID, "下载自动整理失败，请查看任务详情")
	}
	return nil
}

func (s *Service) Review(ctx context.Context, actor auth.Actor, jobID string, choices map[string]string) (model.LibraryJobDTO, error) {
	if !actor.Authenticated {
		return model.LibraryJobDTO{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return model.LibraryJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少审核曲库任务权限")
	}
	ctrl := s.getControl(jobID)
	ctrl.mu.Lock()
	if ctrl.applying {
		ctrl.mu.Unlock()
		return model.LibraryJobDTO{}, model.NewError(model.ErrConflict, http.StatusConflict, "任务正在应用中，无法修改选择")
	}
	ctrl.mu.Unlock()

	jobMeta, err := s.loadJobMeta(ctx, jobID)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.ensureActorAccess(actor, jobMeta.OwnerKey); err != nil {
		return model.LibraryJobDTO{}, err
	}

	roots := s.coordinator.Roots()
	libRoot := ""
	if jobMeta.Action == "organize" || jobMeta.Action == "both" {
		libRoot = resolveDirAgainstRoots(jobMeta.Settings.LibraryDir, roots)
	}
	reserved := make(map[string]struct{})
	for _, it := range jobMeta.Items {
		reserved[it.OriginalPath] = struct{}{}
	}

	snap := s.opts.SnapshotProvider()
	for idx := range jobMeta.Items {
		it := &jobMeta.Items[idx]
		candID, ok := choices[it.ID]
		if !ok {
			candID, ok = choices[it.OriginalPath]
		}
		if !ok {
			continue
		}
		if strings.TrimSpace(candID) == "" {
			it.Chosen = nil
			it.Status = model.LibraryItemSkipped
			continue
		}
		var selected *ScrapeCandidate
		for i := range it.Candidates {
			if it.Candidates[i].ID == candID {
				c := it.Candidates[i]
				selected = &c
				break
			}
		}
		if selected == nil {
			return model.LibraryJobDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "所选候选不存在: "+candID)
		}
		if s.scraper != nil {
			enriched, warns := s.scraper.Enrich(ctx, snap, *selected, jobMeta.Settings.LyricsMode != "none", it.Candidates)
			selected = &enriched
			it.Warnings = mergeWarnings(it.Warnings, warns)
			for i := range it.Candidates {
				if it.Candidates[i].ID == enriched.ID {
					it.Candidates[i] = enriched
					break
				}
			}
		}
		cid := selected.ID
		it.Chosen = &cid
		it.CoverURL = selected.CoverURL
		if selected.Lyrics != "" && it.LyricsText == nil {
			l := selected.Lyrics
			it.LyricsText = &l
		}
		it.After = applyCandidateToTags(it.Before, *selected, jobMeta.Settings, it.Locks)
		if libRoot != "" {
			overwrite := jobMeta.Settings.Overwrite
			if jobMeta.Request.Overwrite != nil {
				overwrite = *jobMeta.Request.Overwrite
			}
			target, tErr := computeTargetPath(libRoot, it.OriginalPath, it.After, jobMeta.Settings, idx+1, reserved, overwrite)
			if tErr != nil {
				it.Status = model.LibraryItemNeedsReview
				it.Error = tErr.Error()
				continue
			}
			it.Target = target
		}
		it.Error = ""
		it.Status = model.LibraryItemPreviewReady
	}

	nextStatus := deriveInitialJobStatus(jobMeta.Action, jobMeta.Items)
	if err := s.saveJobMeta(ctx, jobMeta); err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.persistJobAndItems(ctx, jobMeta, nextStatus, buildJobDetail(jobMeta.Action, nextStatus, jobMeta.Items), countCompleteItems(jobMeta.Items)); err != nil {
		return model.LibraryJobDTO{}, err
	}
	s.log("info", jobID, fmt.Sprintf("已更新审核候选选择（%d 项）", len(choices)))
	return s.ToDTO(ctx, jobID)
}

func (s *Service) SearchItemCandidates(ctx context.Context, actor auth.Actor, jobID, itemID, query string, sources []string) (model.LibraryJobDTO, error) {
	if !actor.Authenticated {
		return model.LibraryJobDTO{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return model.LibraryJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少搜索曲库候选权限")
	}
	ctrl := s.getControl(jobID)
	ctrl.mu.Lock()
	if ctrl.applying {
		ctrl.mu.Unlock()
		return model.LibraryJobDTO{}, model.NewError(model.ErrConflict, http.StatusConflict, "任务正在应用中，暂不能搜索候选")
	}
	ctrl.mu.Unlock()

	jobMeta, err := s.loadJobMeta(ctx, jobID)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.ensureActorAccess(actor, jobMeta.OwnerKey); err != nil {
		return model.LibraryJobDTO{}, err
	}
	if jobMeta.Action != "scrape" && jobMeta.Action != "both" {
		return model.LibraryJobDTO{}, model.NewError(model.ErrConflict, http.StatusConflict, "任务不存在或暂不能搜索候选")
	}
	targetIdx := -1
	for i := range jobMeta.Items {
		it := &jobMeta.Items[i]
		if it.ID == itemID && (it.Status == model.LibraryItemPreviewReady || it.Status == model.LibraryItemNeedsReview || it.Status == model.LibraryItemFailed) {
			targetIdx = i
			break
		}
	}
	if targetIdx < 0 {
		return model.LibraryJobDTO{}, model.NewError(model.ErrConflict, http.StatusConflict, "文件已处理或不存在，请新建任务")
	}
	if s.scraper == nil {
		return model.LibraryJobDTO{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "元数据刮削器未初始化")
	}
	var useSources []string
	if len(sources) > 0 {
		validatedSources, vErr := validateScrapeSourceList(sources, s.scraper)
		if vErr != nil {
			return model.LibraryJobDTO{}, vErr
		}
		useSources = validatedSources
	} else {
		useSources = filterValidScrapeSources(jobMeta.Settings.Sources, DefaultSettings().Sources, s.scraper)
	}
	it := &jobMeta.Items[targetIdx]
	ref := ScrapeReference{
		Title:  it.Before["title"],
		Artist: it.Before["artist"],
		Album:  it.Before["album"],
		Path:   it.OriginalPath,
		Query:  query,
	}
	cands, warns := s.scraper.Search(ctx, s.opts.SnapshotProvider(), ref, useSources)
	ranked, _ := PickCandidates(ref, cands)
	it.Candidates = ranked
	it.Chosen = nil
	it.After = cloneStringMap(it.Before)
	it.Warnings = warns
	it.Error = ""
	it.Status = model.LibraryItemNeedsReview

	nextStatus := deriveInitialJobStatus(jobMeta.Action, jobMeta.Items)
	if err := s.saveJobMeta(ctx, jobMeta); err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.persistJobAndItems(ctx, jobMeta, nextStatus, "搜索结果已更新，请选择并预览候选", countCompleteItems(jobMeta.Items)); err != nil {
		return model.LibraryJobDTO{}, err
	}
	s.log("info", jobID, fmt.Sprintf("手动搜索曲目候选：%s -> 返回 %d 条结果", it.Name, len(ranked)))
	return s.ToDTO(ctx, jobID)
}

func (s *Service) Apply(ctx context.Context, actor auth.Actor, jobID string) (model.LibraryJobDTO, error) {
	if !actor.Authenticated {
		return model.LibraryJobDTO{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return model.LibraryJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少执行曲库写入权限")
	}

	ctrl := s.getControl(jobID)
	ctrl.mu.Lock()
	if ctrl.applying {
		ctrl.mu.Unlock()
		return model.LibraryJobDTO{}, model.NewError(model.ErrConflict, http.StatusConflict, "任务正在应用中")
	}
	ctrl.applying = true
	ctrl.pauseWanted = false
	ctrl.cancelWanted = false
	ctrl.mu.Unlock()

	defer func() {
		ctrl.mu.Lock()
		ctrl.applying = false
		ctrl.mu.Unlock()
	}()

	jobMeta, err := s.loadJobMeta(ctx, jobID)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.ensureActorAccess(actor, jobMeta.OwnerKey); err != nil {
		return model.LibraryJobDTO{}, err
	}

	if err := s.persistJobAndItems(ctx, jobMeta, model.LibraryJobApplying, "正在应用曲库更改", countCompleteItems(jobMeta.Items)); err != nil {
		return model.LibraryJobDTO{}, err
	}
	s.log("info", jobID, fmt.Sprintf("开始应用曲库整理 (%s)：共 %d 首", jobMeta.Action, len(jobMeta.Items)))

	committedCount := 0
	failedCount := 0
	stoppedByUser := ""

	for idx := range jobMeta.Items {
		ctrl.mu.Lock()
		paused := ctrl.pauseWanted
		cancelled := ctrl.cancelWanted
		ctrl.mu.Unlock()
		if cancelled || ctx.Err() != nil {
			stoppedByUser = "cancelled"
			break
		}
		if paused {
			stoppedByUser = "paused"
			break
		}

		it := &jobMeta.Items[idx]
		if it.Status == model.LibraryItemComplete || it.Status == model.LibraryItemSkipped {
			continue
		}
		if it.Status == model.LibraryItemNeedsReview && it.Chosen == nil {
			continue
		}

		it.Status = model.LibraryItemApplying
		_ = s.persistItemState(ctx, jobID, *it)

		if jobMeta.Action == "convert" || jobMeta.Action == "split" {
			if err := s.applyDerivedItem(ctx, jobMeta, it); err != nil {
				it.Status = model.LibraryItemFailed
				it.Error = err.Error()
				failedCount++
				s.log("error", jobID, fmt.Sprintf("曲目处理失败 (%s)：%v", it.Name, err))
			} else {
				it.Status = model.LibraryItemComplete
				it.Error = ""
				committedCount++
			}
			_ = s.persistJobMetaAndItem(ctx, jobMeta, *it)
			continue
		}

		if err := s.applyStandardItem(ctx, jobMeta, it); err != nil {
			var de *model.DomainError
			if errors.As(err, &de) && de.Code == model.ErrConflict {
				it.Status = model.LibraryItemFailed
				it.Warnings = mergeWarnings(it.Warnings, []string{de.Detail})
			} else {
				it.Status = model.LibraryItemFailed
			}
			it.Error = err.Error()
			failedCount++
			s.log("error", jobID, fmt.Sprintf("曲目处理失败 (%s)：%v", it.Name, err))
		} else {
			it.Status = model.LibraryItemComplete
			it.Error = ""
			committedCount++
		}
		_ = s.persistJobMetaAndItem(ctx, jobMeta, *it)
	}

	finalStatus := deriveFinalJobStatus(jobMeta.Items, stoppedByUser)
	finalDetail := buildJobDetail(jobMeta.Action, finalStatus, jobMeta.Items)
	if err := s.saveJobMeta(ctx, jobMeta); err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.persistJobAndItems(ctx, jobMeta, finalStatus, finalDetail, countCompleteItems(jobMeta.Items)); err != nil {
		return model.LibraryJobDTO{}, err
	}

	logLevel := "info"
	if failedCount > 0 && committedCount == 0 {
		logLevel = "error"
	} else if failedCount > 0 || finalStatus == model.LibraryJobNeedsReview || finalStatus == model.LibraryJobPartial {
		logLevel = "warning"
	}
	s.log(logLevel, jobID, fmt.Sprintf("曲库整理结束 (%s)：状态=%s，成功写入=%d，失败=%d（总计 %d 首）",
		jobMeta.Action, finalStatus, committedCount, failedCount, len(jobMeta.Items)))

	if committedCount > 0 && s.opts.NavidromeHook != nil {
		_ = s.opts.NavidromeHook(ctx)
	}
	return s.ToDTO(ctx, jobID)
}

func (s *Service) Pause(ctx context.Context, actor auth.Actor, jobID string) (model.LibraryJobDTO, error) {
	return s.stopJob(ctx, actor, jobID, "paused")
}

func (s *Service) Cancel(ctx context.Context, actor auth.Actor, jobID string) (model.LibraryJobDTO, error) {
	return s.stopJob(ctx, actor, jobID, "cancelled")
}

func (s *Service) stopJob(ctx context.Context, actor auth.Actor, jobID, targetState string) (model.LibraryJobDTO, error) {
	if !actor.Authenticated {
		return model.LibraryJobDTO{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return model.LibraryJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少操作曲库任务权限")
	}
	jobMeta, err := s.loadJobMeta(ctx, jobID)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.ensureActorAccess(actor, jobMeta.OwnerKey); err != nil {
		return model.LibraryJobDTO{}, err
	}

	ctrl := s.getControl(jobID)
	ctrl.mu.Lock()
	if targetState == "paused" {
		ctrl.pauseWanted = true
	} else {
		ctrl.cancelWanted = true
	}
	applying := ctrl.applying
	ctrl.mu.Unlock()

	if !applying {
		st := model.LibraryJobStatus(targetState)
		detail := "任务已暂停"
		if st == model.LibraryJobCancelled {
			detail = "任务已取消"
		}
		if err := s.persistJobAndItems(ctx, jobMeta, st, detail, countCompleteItems(jobMeta.Items)); err != nil {
			return model.LibraryJobDTO{}, err
		}
	}
	s.log("info", jobID, fmt.Sprintf("曲库任务状态变更为 %s", targetState))
	return s.ToDTO(ctx, jobID)
}

func (s *Service) Retry(ctx context.Context, actor auth.Actor, jobID string) (model.LibraryJobDTO, error) {
	if !actor.Authenticated {
		return model.LibraryJobDTO{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return model.LibraryJobDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少重试曲库任务权限")
	}
	ctrl := s.getControl(jobID)
	ctrl.mu.Lock()
	if ctrl.applying {
		ctrl.mu.Unlock()
		return model.LibraryJobDTO{}, model.NewError(model.ErrConflict, http.StatusConflict, "任务正在运行中")
	}
	ctrl.mu.Unlock()

	jobMeta, err := s.loadJobMeta(ctx, jobID)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.ensureActorAccess(actor, jobMeta.OwnerKey); err != nil {
		return model.LibraryJobDTO{}, err
	}

	requeued := 0
	for idx := range jobMeta.Items {
		it := &jobMeta.Items[idx]
		if it.Status == model.LibraryItemComplete || it.Status == model.LibraryItemSkipped {
			continue
		}
		if it.Status == model.LibraryItemFailed || it.Status == model.LibraryItemQueued {
			it.Status = model.LibraryItemPreviewReady
			it.Error = ""
			requeued++
		} else if it.Status == model.LibraryItemNeedsReview && it.Chosen != nil {
			it.Status = model.LibraryItemPreviewReady
			it.Error = ""
			requeued++
		}
	}
	if requeued == 0 {
		return s.ToDTO(ctx, jobID)
	}
	if err := s.saveJobMeta(ctx, jobMeta); err != nil {
		return model.LibraryJobDTO{}, err
	}
	return s.Apply(ctx, actor, jobID)
}

func (s *Service) Restore(ctx context.Context, actor auth.Actor, jobID, itemID string) (files.OperationResult, error) {
	if !actor.Authenticated {
		return files.OperationResult{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return files.OperationResult{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少恢复文件权限")
	}
	jobMeta, err := s.loadJobMeta(ctx, jobID)
	if err != nil {
		return files.OperationResult{}, err
	}
	if err := s.ensureActorAccess(actor, jobMeta.OwnerKey); err != nil {
		return files.OperationResult{}, err
	}
	var opID string
	for _, it := range jobMeta.Items {
		if it.ID == itemID || it.OriginalPath == itemID || it.OperationID == itemID {
			opID = it.OperationID
			break
		}
	}
	if opID == "" {
		return files.OperationResult{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "未找到可恢复的文件操作记录")
	}
	res, err := s.coordinator.Restore(ctx, opID)
	if err != nil {
		s.log("error", jobID, "恢复原文件失败："+err.Error())
		return files.OperationResult{}, err
	}
	s.log("info", jobID, fmt.Sprintf("已从历史备份恢复原文件：%s", res.TargetPath))
	if s.opts.NavidromeHook != nil {
		_ = s.opts.NavidromeHook(ctx)
	}
	return res, nil
}

func (s *Service) GetJob(ctx context.Context, jobID string) (model.LibraryJobDTO, bool, error) {
	j, items, ok, err := s.store.GetLibraryJob(ctx, jobID)
	if err != nil || !ok {
		return model.LibraryJobDTO{}, ok, err
	}
	dto, err := s.buildJobDTO(ctx, j, items)
	if err != nil {
		return model.LibraryJobDTO{}, false, err
	}
	return dto, true, nil
}

var finishedJobStates = []string{
	string(model.LibraryJobComplete),
	"completed",
	string(model.LibraryJobFailed),
	string(model.LibraryJobPartial),
	string(model.LibraryJobCancelled),
	"restored",
}

func isRunningJobState(state string) bool {
	switch strings.ToLower(strings.TrimSpace(state)) {
	case string(model.LibraryJobQueued), string(model.LibraryJobScanning), string(model.LibraryJobApplying), "restoring":
		return true
	default:
		return false
	}
}

func normalizeComparablePaths(paths []string) []string {
	if len(paths) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(paths))
	out := make([]string, 0, len(paths))
	for _, p := range paths {
		trimmed := strings.TrimSpace(p)
		if trimmed == "" {
			continue
		}
		norm := filepath.ToSlash(filepath.Clean(trimmed))
		if os.PathSeparator == '\\' {
			norm = strings.ToLower(norm)
		}
		if _, ok := seen[norm]; ok {
			continue
		}
		seen[norm] = struct{}{}
		out = append(out, norm)
	}
	slices.Sort(out)
	return out
}

func (s *Service) isJobApplying(jobID string) bool {
	s.ctrlMu.Lock()
	c, ok := s.controls[jobID]
	s.ctrlMu.Unlock()
	if !ok {
		return false
	}
	c.mu.Lock()
	applying := c.applying
	c.mu.Unlock()
	return applying
}

func (s *Service) releaseControlsIfIdle(jobIDs []string) (released []string, busy []string) {
	s.ctrlMu.Lock()
	defer s.ctrlMu.Unlock()
	for _, id := range jobIDs {
		if c, ok := s.controls[id]; ok {
			c.mu.Lock()
			applying := c.applying
			c.mu.Unlock()
			if applying {
				busy = append(busy, id)
				continue
			}
			delete(s.controls, id)
		}
		released = append(released, id)
	}
	return released, busy
}

func (s *Service) jobOwnerKey(ctx context.Context, rec store.LibraryJobRecord) string {
	if rec.OwnerKey != "" {
		return rec.OwnerKey
	}
	if meta, err := s.loadJobMeta(ctx, rec.ID); err == nil {
		return meta.OwnerKey
	}
	return ""
}

func (s *Service) cleanupDuplicatePreviewJobs(ctx context.Context, actor auth.Actor, req SubmitRequest, resolvedPaths []files.ValidatedPath) error {
	if s.store == nil {
		return nil
	}
	ownerKey := actor.Key()
	candidates, err := s.store.ListLibraryJobsByOwnerAndStates(ctx, ownerKey, []string{
		string(model.LibraryJobPreviewReady),
		string(model.LibraryJobNeedsReview),
	})
	if err != nil {
		return err
	}
	if len(candidates) == 0 {
		return nil
	}

	currentPaths := make([]string, len(resolvedPaths))
	for i, vp := range resolvedPaths {
		currentPaths[i] = vp.AbsPath
	}
	currentNormPaths := normalizeComparablePaths(currentPaths)
	reqSourceDL := strings.TrimSpace(req.SourceDownloadID)

	var staleIDs []string
	for _, cand := range candidates {
		if s.isJobApplying(cand.ID) {
			continue
		}
		var oldSourceDL string
		var oldOrigPaths []string
		if meta, mErr := s.loadJobMeta(ctx, cand.ID); mErr == nil {
			oldSourceDL = strings.TrimSpace(meta.Request.SourceDownloadID)
			oldOrigPaths = make([]string, 0, len(meta.Items))
			for _, it := range meta.Items {
				oldOrigPaths = append(oldOrigPaths, it.OriginalPath)
			}
		} else {
			oldSourceDL = strings.TrimSpace(cand.SourceDownloadID)
			if oldSourceDL == "" && strings.TrimSpace(cand.OptionsJSON) != "" {
				var oldReq SubmitRequest
				if json.Unmarshal([]byte(cand.OptionsJSON), &oldReq) == nil {
					oldSourceDL = strings.TrimSpace(oldReq.SourceDownloadID)
				}
			}
			if _, items, ok, gErr := s.store.GetLibraryJob(ctx, cand.ID); gErr == nil && ok {
				oldOrigPaths = make([]string, 0, len(items))
				for _, it := range items {
					oldOrigPaths = append(oldOrigPaths, it.OriginalPath)
				}
			}
		}
		matchSourceDL := reqSourceDL != "" && oldSourceDL == reqSourceDL
		matchPaths := cand.Action == req.Action && len(currentNormPaths) > 0 && slices.Equal(normalizeComparablePaths(oldOrigPaths), currentNormPaths)
		if matchSourceDL || matchPaths {
			staleIDs = append(staleIDs, cand.ID)
		}
	}
	if len(staleIDs) == 0 {
		return nil
	}
	released, _ := s.releaseControlsIfIdle(staleIDs)
	if len(released) == 0 {
		return nil
	}
	_, err = s.store.DeleteLibraryJobs(ctx, released)
	return err
}

func (s *Service) DeleteJob(ctx context.Context, actor auth.Actor, jobID string) error {
	if !actor.Authenticated {
		return model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少删除曲库任务权限")
	}
	if s.store == nil {
		return model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "存储服务未初始化")
	}
	jobID = strings.TrimSpace(jobID)
	if jobID == "" {
		return model.NewError(model.ErrNotFound, http.StatusNotFound, "整理任务不存在")
	}
	rec, ok, err := s.store.GetLibraryJobRecord(ctx, jobID)
	if err != nil {
		return err
	}
	if !ok {
		return model.NewError(model.ErrNotFound, http.StatusNotFound, "整理任务不存在")
	}
	if err := s.ensureActorAccess(actor, s.jobOwnerKey(ctx, rec)); err != nil {
		return err
	}
	if isRunningJobState(string(rec.Status)) || s.isJobApplying(jobID) {
		return model.NewError(model.ErrConflict, http.StatusConflict, "任务正在运行中，请先取消后再删除")
	}
	released, busy := s.releaseControlsIfIdle([]string{jobID})
	if len(busy) > 0 || len(released) == 0 {
		return model.NewError(model.ErrConflict, http.StatusConflict, "任务正在运行中，请先取消后再删除")
	}
	if _, err := s.store.DeleteLibraryJobs(ctx, released); err != nil {
		return err
	}
	s.log("info", jobID, "已删除整理任务记录")
	return nil
}

func (s *Service) BatchDeleteJobs(ctx context.Context, actor auth.Actor, ids []string, mode string) (deletedIDs []string, skippedRunning []string, err error) {
	if !actor.Authenticated {
		return nil, nil, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return nil, nil, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少删除曲库任务权限")
	}
	if s.store == nil {
		return nil, nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "存储服务未初始化")
	}
	mode = strings.TrimSpace(mode)
	if mode != "" && mode != "finished" {
		return nil, nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的批量删除模式")
	}

	deletedIDs = []string{}
	skippedRunning = []string{}
	var toDelete []string

	if mode == "finished" {
		recs, qErr := s.store.ListLibraryJobsByOwnerAndStates(ctx, "", finishedJobStates)
		if qErr != nil {
			return nil, nil, qErr
		}
		for _, rec := range recs {
			if s.ensureActorAccess(actor, s.jobOwnerKey(ctx, rec)) != nil {
				continue
			}
			if s.isJobApplying(rec.ID) {
				skippedRunning = append(skippedRunning, rec.ID)
				continue
			}
			toDelete = append(toDelete, rec.ID)
		}
	} else {
		uniqueIDs := make([]string, 0, len(ids))
		seen := make(map[string]struct{}, len(ids))
		for _, raw := range ids {
			id := strings.TrimSpace(raw)
			if id == "" {
				continue
			}
			if _, ok := seen[id]; ok {
				continue
			}
			seen[id] = struct{}{}
			uniqueIDs = append(uniqueIDs, id)
		}
		if len(uniqueIDs) == 0 {
			return deletedIDs, skippedRunning, nil
		}
		for _, id := range uniqueIDs {
			rec, ok, gErr := s.store.GetLibraryJobRecord(ctx, id)
			if gErr != nil {
				return nil, nil, gErr
			}
			if !ok {
				return nil, nil, model.NewError(model.ErrNotFound, http.StatusNotFound, "整理任务不存在: "+id)
			}
			if aErr := s.ensureActorAccess(actor, s.jobOwnerKey(ctx, rec)); aErr != nil {
				return nil, nil, aErr
			}
			if isRunningJobState(string(rec.Status)) || s.isJobApplying(id) {
				skippedRunning = append(skippedRunning, id)
				continue
			}
			toDelete = append(toDelete, id)
		}
	}

	if len(toDelete) == 0 {
		return deletedIDs, skippedRunning, nil
	}
	released, busy := s.releaseControlsIfIdle(toDelete)
	if len(busy) > 0 {
		skippedRunning = append(skippedRunning, busy...)
	}
	if len(released) == 0 {
		return deletedIDs, skippedRunning, nil
	}
	if _, dErr := s.store.DeleteLibraryJobs(ctx, released); dErr != nil {
		return nil, nil, dErr
	}
	deletedIDs = released
	s.log("info", "", fmt.Sprintf("批量删除整理任务记录：已移除 %d 条（跳过运行中 %d 条）", len(deletedIDs), len(skippedRunning)))
	return deletedIDs, skippedRunning, nil
}

func (s *Service) ListJobs(ctx context.Context, limit int) ([]model.LibraryJobDTO, error) {
	jobs, err := s.store.ListLibraryJobs(ctx, limit)
	if err != nil {
		return nil, err
	}
	out := make([]model.LibraryJobDTO, 0, len(jobs))
	for _, j := range jobs {
		out = append(out, model.LibraryJobDTO{
			ID:        j.ID,
			Action:    j.Action,
			Status:    string(j.Status),
			CreatedAt: j.CreatedAt,
			UpdatedAt: j.UpdatedAt,
			Total:     j.Total,
			Processed: j.Processed,
			Detail:    j.Detail,
			Warnings:  nonNilSlice(j.Warnings),
		})
	}
	return out, nil
}

func (s *Service) ToDTO(ctx context.Context, jobID string) (model.LibraryJobDTO, error) {
	dto, ok, err := s.GetJob(ctx, jobID)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	if !ok {
		return model.LibraryJobDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "整理任务不存在")
	}
	return dto, nil
}

func (s *Service) buildJobDTO(ctx context.Context, j model.LibraryJob, storeItems []model.LibraryItem) (model.LibraryJobDTO, error) {
	dto := model.LibraryJobDTO{
		ID:        j.ID,
		Action:    j.Action,
		Status:    string(j.Status),
		CreatedAt: j.CreatedAt,
		UpdatedAt: j.UpdatedAt,
		Total:     j.Total,
		Processed: j.Processed,
		Detail:    j.Detail,
		Warnings:  nonNilSlice(j.Warnings),
	}
	meta, err := s.loadJobMeta(ctx, j.ID)
	if err == nil {
		dto.Duplicates = meta.Duplicates
		dto.Settings = &model.LibraryJobSettingsDTO{
			Sources:   nonNilSlice(meta.Settings.Sources),
			Overwrite: meta.Settings.Overwrite,
			Fields:    nonNilSlice(meta.Settings.Fields),
		}
		itemsDTO := make([]model.LibraryJobItemDTO, 0, len(meta.Items))
		for _, it := range meta.Items {
			cands := make([]model.MetadataCandidateDTO, 0, len(it.Candidates))
			for _, c := range it.Candidates {
				cands = append(cands, c.ToDTO())
			}
			itemsDTO = append(itemsDTO, model.LibraryJobItemDTO{
				ID:           it.ID,
				Path:         it.RelPath,
				Name:         it.Name,
				OriginalPath: it.OriginalPath,
				Target:       it.Target,
				Status:       string(it.Status),
				Before:       nonNilStringMap(it.Before),
				After:        nonNilStringMap(it.After),
				Candidates:   cands,
				Chosen:       it.Chosen,
				Locks:        nonNilSlice(it.Locks),
				Warnings:     nonNilSlice(it.Warnings),
				Error:        it.Error,
				OperationID:  it.OperationID,
				Quality:      it.Quality,
				Outputs:      it.Outputs,
				Artwork:      it.Artwork,
			})
		}
		dto.Items = itemsDTO
		return dto, nil
	}

	itemsDTO := make([]model.LibraryJobItemDTO, 0, len(storeItems))
	for _, it := range storeItems {
		itemsDTO = append(itemsDTO, model.LibraryJobItemDTO{
			ID:           it.ID,
			Path:         it.Path,
			Name:         it.Name,
			OriginalPath: it.OriginalPath,
			Target:       it.Target,
			Status:       string(it.Status),
			Before:       map[string]string{},
			After:        map[string]string{},
			Candidates:   []model.MetadataCandidateDTO{},
			Chosen:       it.Chosen,
			Locks:        nonNilSlice(it.Locks),
			Warnings:     nonNilSlice(it.Warnings),
			Error:        it.Error,
			OperationID:  it.OperationID,
		})
	}
	dto.Items = itemsDTO
	return dto, nil
}

func (s *Service) applyStandardItem(ctx context.Context, jobMeta persistedJobMeta, it *persistedItemMeta) error {
	roots := s.coordinator.Roots()
	srcVP, sErr := files.ValidatePath(roots, it.OriginalPath, files.ValidateOptions{MustExist: true, AudioOnly: true})
	if sErr != nil {
		return sErr
	}
	if it.AssetID != "" {
		asset, ok, err := s.store.GetAsset(ctx, it.AssetID)
		if err != nil {
			return err
		}
		if !ok {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "资产记录不存在")
		}
		if it.ExpectedRev > 0 && asset.Revision != it.ExpectedRev {
			return model.NewError(model.ErrConflict, http.StatusConflict, "文件已被外部修改，请重新预览")
		}
	}

	targetPath := it.Target
	if targetPath == "" {
		targetPath = it.OriginalPath
	}
	dstVP, err := files.ValidatePath(roots, targetPath, files.ValidateOptions{MustExist: false, AudioOnly: true})
	if err != nil {
		return err
	}
	kind := "edit"
	if targetPath != it.OriginalPath {
		kind = "move"
		sameSlot := dstVP.Root.ID == srcVP.Root.ID && dstVP.PathKey == srcVP.PathKey
		if !sameSlot {
			if _, stErr := os.Lstat(targetPath); stErr == nil {
				return model.NewError(model.ErrConflict, http.StatusConflict, "目标文件已存在，请重新预览整理路径")
			}
		}
		if err := os.MkdirAll(filepath.Dir(targetPath), 0o755); err != nil {
			return model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "创建目标目录失败", err)
		}
	}

	snap := s.opts.SnapshotProvider()
	var coverData []byte
	var coverMIME string
	if jobMeta.Action == "export_cover" {
		cov, mime, ok := media.ExtractEmbeddedCover(it.OriginalPath)
		if !ok || len(cov) == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "音频中不包含内嵌封面")
		}
		coverData, coverMIME = cov, mime
	} else if it.InlineCover != "" {
		d, m, err := decodeInlineCover(it.InlineCover)
		if err != nil {
			return err
		}
		coverData, coverMIME = d, m
	} else if it.CoverURL != "" && s.scraper != nil && jobMeta.Settings.CoverMode != "none" {
		if d, m, err := s.scraper.FetchCover(ctx, snap, it.CoverURL); err == nil {
			coverData, coverMIME = d, m
		} else {
			it.Warnings = mergeWarnings(it.Warnings, []string{"下载封面失败：" + err.Error()})
		}
	}

	lyricsMode := jobMeta.Settings.LyricsMode
	coverMode := jobMeta.Settings.CoverMode
	isExportOnly := jobMeta.Action == "export_lyrics" || jobMeta.Action == "export_cover"
	if jobMeta.Action == "export_lyrics" {
		lyricsMode = "lrc"
		coverMode = "none"
	} else if jobMeta.Action == "export_cover" {
		coverMode = "file"
		lyricsMode = "none"
	}
	stem := strings.TrimSuffix(targetPath, filepath.Ext(targetPath))
	editable := it.SourceMeta.Editable || media.EditableExtensions[strings.ToLower(filepath.Ext(it.OriginalPath))]

	tagsChanged := !isExportOnly && (!mapsEqual(it.Before, it.After) || it.LyricsText != nil || len(coverData) > 0 || it.ClearCover || it.ClearAll)
	needMutator := tagsChanged || (lyricsMode == "lrc" || lyricsMode == "both") && it.LyricsText != nil || (coverMode == "file" || coverMode == "both") && len(coverData) > 0

	var mutator files.StageMutator
	if needMutator {
		mutator = func(mctx context.Context, stagePath string) ([]files.SidecarOutput, error) {
			var sidecars []files.SidecarOutput
			if it.LyricsText != nil && (lyricsMode == "lrc" || lyricsMode == "both") {
				sidecars = append(sidecars, files.SidecarOutput{
					Role:       "sidecar_lrc",
					TargetPath: stem + ".lrc",
					Data:       []byte(*it.LyricsText),
				})
			}
			if len(coverData) > 0 && (coverMode == "file" || coverMode == "both") {
				ext := ".jpg"
				switch strings.ToLower(coverMIME) {
				case "image/png":
					ext = ".png"
				case "image/webp":
					ext = ".webp"
				}
				sidecars = append(sidecars, files.SidecarOutput{
					Role:       "sidecar_cover",
					TargetPath: stem + ext,
					Data:       coverData,
				})
			}

			shouldWriteEmbed := !isExportOnly && s.tagSvc != nil && editable &&
				(tagsChanged || (it.LyricsText != nil && (lyricsMode == "embed" || lyricsMode == "both")) || (len(coverData) > 0 && (coverMode == "embed" || coverMode == "both")))
			if shouldWriteEmbed {
				var embedLyrics *string
				if it.LyricsText != nil && (lyricsMode == "embed" || lyricsMode == "both") {
					embedLyrics = it.LyricsText
				}
				var coverSpec *helper.CoverSpec
				if len(coverData) > 0 && (coverMode == "embed" || coverMode == "both") {
					ext := ".jpg"
					if coverMIME == "image/png" {
						ext = ".png"
					}
					coverTmp := filepath.Join(filepath.Dir(stagePath), ".melokeep-cov-"+model.NewUUID()+ext)
					if _, wErr := files.WriteFileDurable(coverTmp, coverData); wErr != nil {
						return nil, wErr
					}
					defer os.Remove(coverTmp)
					coverSpec = &helper.CoverSpec{Path: coverTmp, Mime: coverMIME}
				}
				if _, err := s.tagSvc.WriteStagedTags(mctx, helper.WriteTagsRequest{
					AllowedStageRoot: filepath.Dir(stagePath),
					StagePath:        stagePath,
					Tags:             it.After,
					Lyrics:           embedLyrics,
					Cover:            coverSpec,
					ClearCover:       it.ClearCover,
					ClearAll:         it.ClearAll,
				}); err != nil {
					return nil, err
				}
			}
			return sidecars, nil
		}
	}

	ord := it.Ordinal
	res, err := s.coordinator.Execute(ctx, files.OperationRequest{
		Kind:             kind,
		AssetID:          it.AssetID,
		ExpectedRevision: it.ExpectedRev,
		SourcePath:       it.OriginalPath,
		TargetPath:       targetPath,
		BackupDays:       jobMeta.Settings.BackupDays,
		DisableBackup:    jobMeta.Settings.BackupDays <= 0 || strings.TrimSpace(jobMeta.Request.SourceDownloadID) != "",
		JobID:            jobMeta.JobID,
		ItemID:           it.ID,
		ItemOrdinal:      &ord,
		ItemStatus:       model.LibraryItemComplete,
		JobStatus:        model.LibraryJobApplying,
		NewMetadata:      nonNilStringMap(it.After),
		NewTitle:         it.After["title"],
		NewArtist:        it.After["artist"],
		NewAlbum:         it.After["album"],
		AllowEmptyMeta:   jobMeta.Action == "edit" || jobMeta.Action == "clear",
		NewDurationMS:    int64(it.SourceMeta.Duration * 1000),
		NewLockedFields:  it.Locks,
		Editable:         editable,
		Mutator:          mutator,
		HookAfterPhase:   s.opts.TestAfterPhase,
	})
	if err != nil {
		return err
	}
	it.OperationID = res.OperationID
	if res.Asset != nil {
		it.ExpectedRev = res.Asset.Revision
		it.RelPath = res.Asset.RelPath
	}
	it.OriginalPath = targetPath
	it.Target = targetPath
	return nil
}

func (s *Service) applyDerivedItem(ctx context.Context, jobMeta persistedJobMeta, it *persistedItemMeta) error {
	var coverData []byte
	var coverMIME string
	var lyrics string
	if it.LyricsText != nil {
		lyrics = *it.LyricsText
	}
	if it.InlineCover != "" {
		if d, m, err := decodeInlineCover(it.InlineCover); err == nil {
			coverData, coverMIME = d, m
		}
	}
	opIDs, err := ExecuteDerived(
		ctx,
		s.coordinator,
		s.tagSvc,
		s.runner,
		jobMeta.JobID,
		it.ID,
		it.Ordinal,
		it.AssetID,
		jobMeta.Action,
		it.OriginalPath,
		it.DerivedPlans,
		ConvertSplitOptions{
			OutputFormat: jobMeta.Request.OutputFormat,
			Bitrate:      jobMeta.Request.Bitrate,
			SampleRate:   jobMeta.Request.SampleRate,
			CUEText:      jobMeta.Request.CUE,
		},
		lyrics,
		coverData,
		coverMIME,
	)
	if len(opIDs) > 0 {
		it.OperationID = opIDs[0]
	}
	if err == nil && s.store != nil && s.coordinator != nil {
		roots := s.coordinator.Roots()
		for _, plan := range it.DerivedPlans {
			vp, vErr := files.ValidatePath(roots, plan.Path, files.ValidateOptions{MustExist: true, AudioOnly: true})
			if vErr != nil {
				continue
			}
			asset, ok, aErr := s.store.GetAssetByRootAndPathKey(ctx, vp.Root.ID, vp.PathKey)
			if aErr != nil || !ok {
				continue
			}
			needsDuration := asset.DurationMS <= 0
			if needsDuration {
				if s.tagSvc != nil {
					if td, tErr := s.tagSvc.ReadMetadata(ctx, vp.AbsPath); tErr == nil && td.Duration > 0 {
						asset.DurationMS = int64(td.Duration * 1000)
					}
				}
				if asset.DurationMS <= 0 && jobMeta.Action == "convert" && it.SourceMeta.Duration > 0 {
					asset.DurationMS = int64(it.SourceMeta.Duration * 1000)
				}
				if asset.DurationMS > 0 {
					_, _ = s.store.UpsertAsset(ctx, asset, asset.Revision, false)
				}
			}
		}
	}
	return err
}

func (s *Service) submitDuplicatesJob(
	ctx context.Context,
	actor auth.Actor,
	settings Settings,
	req SubmitRequest,
	resolvedPaths []files.ValidatedPath,
	tagByPath map[string]helper.AudioTagData,
) (model.LibraryJobDTO, error) {
	inputs := make([]DuplicateInput, 0, len(resolvedPaths))
	items := make([]persistedItemMeta, 0, len(resolvedPaths))
	storeItems := make([]store.LibraryItemInput, 0, len(resolvedPaths))
	for idx, vp := range resolvedPaths {
		td := tagByPath[vp.AbsPath]
		asset, err := s.ensureAssetForPath(ctx, vp, td)
		if err != nil {
			return model.LibraryJobDTO{}, err
		}
		st, _ := os.Lstat(vp.AbsPath)
		var size int64
		if st != nil {
			size = st.Size()
		}
		inputs = append(inputs, DuplicateInput{
			AssetID:  asset.ID,
			Name:     vp.Name,
			AbsPath:  vp.AbsPath,
			Title:    td.Tags["title"],
			Artist:   td.Tags["artist"],
			Duration: td.Duration,
			Size:     size,
		})
		it := persistedItemMeta{
			ID:           model.NewUUID(),
			Ordinal:      idx,
			AssetID:      asset.ID,
			RootID:       vp.Root.ID,
			RelPath:      vp.RelPath,
			Name:         vp.Name,
			OriginalPath: vp.AbsPath,
			Target:       vp.AbsPath,
			Status:       model.LibraryItemComplete,
			ExpectedRev:  asset.Revision,
			Before:       nonNilStringMap(td.Tags),
			After:        nonNilStringMap(td.Tags),
			Locks:        nonNilSlice(asset.LockedFields),
			Warnings:     []string{},
		}
		items = append(items, it)
		storeItems = append(storeItems, toStoreLibraryItemInput(it))
	}

	_, groups, err := AnalyzeDuplicates(ctx, s.runner, req.DuplicateMode, inputs)
	if err != nil {
		return model.LibraryJobDTO{}, err
	}
	jobID := model.NewUUID()
	detail := fmt.Sprintf("查重完成，发现 %d 组重复候选", len(groups))
	settingsJSON, _ := json.Marshal(settings)
	optionsJSON, _ := json.Marshal(req)
	if _, err := s.store.CreateLibraryPreview(ctx, store.CreateLibraryPreviewParams{
		JobID:        jobID,
		Action:       req.Action,
		State:        model.LibraryJobComplete,
		SettingsJSON: string(settingsJSON),
		OptionsJSON:  string(optionsJSON),
		WarningsJSON: "[]",
		Detail:       detail,
		OwnerKey:     actor.Key(),
		Items:        storeItems,
	}); err != nil {
		return model.LibraryJobDTO{}, err
	}
	jobMeta := persistedJobMeta{
		JobID:      jobID,
		Action:     req.Action,
		OwnerKey:   actor.Key(),
		Settings:   settings,
		Request:    req,
		Duplicates: groups,
		Items:      items,
	}
	if err := s.saveJobMeta(ctx, jobMeta); err != nil {
		return model.LibraryJobDTO{}, err
	}
	if err := s.persistJobAndItems(ctx, jobMeta, model.LibraryJobComplete, detail, len(items)); err != nil {
		return model.LibraryJobDTO{}, err
	}
	return s.ToDTO(ctx, jobID)
}

func (s *Service) buildPreviewItem(
	ctx context.Context,
	snap config.Snapshot,
	settings Settings,
	req SubmitRequest,
	flags submitFlags,
	roots []files.Root,
	libRoot string,
	vp files.ValidatedPath,
	asset store.AssetRecord,
	tagData helper.AudioTagData,
	counter int,
	reserved map[string]struct{},
) persistedItemMeta {
	before := nonNilStringMap(tagData.Tags)
	if len(before) == 0 && len(asset.Metadata) > 0 {
		before = nonNilStringMap(asset.Metadata)
	}
	after := cloneStringMap(before)
	locks := nonNilSlice(asset.LockedFields)
	lockedSet := make(map[string]bool, len(locks))
	for _, l := range locks {
		lockedSet[l] = true
	}

	item := persistedItemMeta{
		ID:           model.NewUUID(),
		Ordinal:      counter - 1,
		AssetID:      asset.ID,
		RootID:       vp.Root.ID,
		RelPath:      vp.RelPath,
		Name:         vp.Name,
		OriginalPath: vp.AbsPath,
		Target:       vp.AbsPath,
		Status:       model.LibraryItemPreviewReady,
		ExpectedRev:  asset.Revision,
		Before:       before,
		After:        after,
		Locks:        locks,
		Warnings:     []string{},
		SourceMeta:   tagData,
	}

	switch req.Action {
	case "quality":
		q, warns, qErr := InspectQuality(ctx, s.runner, vp.AbsPath, before)
		if qErr != nil {
			item.Status = model.LibraryItemFailed
			item.Error = qErr.Error()
		} else {
			item.Status = model.LibraryItemComplete
			item.Quality = q
			item.Warnings = mergeWarnings(item.Warnings, warns)
		}
		return item

	case "convert", "split":
		plans, pErr := PlanDerivedOutputs(req.Action, vp.AbsPath, tagData, before, ConvertSplitOptions{
			OutputFormat: req.OutputFormat,
			Bitrate:      req.Bitrate,
			SampleRate:   req.SampleRate,
			CUEText:      req.CUE,
		}, reserved, roots)
		if pErr != nil {
			item.Status = model.LibraryItemFailed
			item.Error = pErr.Error()
			return item
		}
		item.DerivedPlans = plans
		outs := make([]model.LibraryJobOutputDTO, 0, len(plans))
		for _, p := range plans {
			outs = append(outs, model.LibraryJobOutputDTO{Path: p.Path, Start: p.Start, End: p.End})
		}
		item.Outputs = outs
		if len(plans) > 0 {
			item.Target = plans[0].Path
		}
		return item

	case "edit":
		for k, v := range req.Changes {
			after[k] = v
			if !lockedSet[k] {
				locks = append(locks, k)
				lockedSet[k] = true
			}
		}
		item.Locks = locks
		item.After = after
		if req.Lyrics != nil {
			l := *req.Lyrics
			item.LyricsText = &l
		}
		if req.CoverData != "" {
			item.InlineCover = req.CoverData
		}
		return item

	case "clear":
		item.After = map[string]string{}
		item.ClearAll = true
		item.ClearCover = true
		return item

	case "clean":
		chinese := settings.Chinese
		if req.Chinese != "" {
			chinese = req.Chinese
		}
		repair := settings.RepairEncoding
		if req.RepairEncoding != nil {
			repair = *req.RepairEncoding
		}
		for _, f := range media.TagFields {
			if lockedSet[f] {
				continue
			}
			if v, ok := after[f]; ok && v != "" {
				after[f] = Transform(v, req.Find, req.Replacement, chinese, repair)
			}
		}
		item.After = after
		return item

	case "extract":
		stem := strings.TrimSuffix(vp.Name, filepath.Ext(vp.Name))
		if strings.TrimSpace(req.ExtractTemplate) != "" {
			if extracted, err := Extract(req.ExtractTemplate, stem); err == nil {
				for k, v := range extracted {
					if !lockedSet[k] && v != "" {
						after[k] = v
					}
				}
			} else {
				item.Warnings = append(item.Warnings, err.Error())
			}
		} else {
			t, a := inferFromStem(stem, req.FilenameOrder)
			if t != "" && !lockedSet["title"] {
				after["title"] = t
			}
			if a != "" && !lockedSet["artist"] {
				after["artist"] = a
			}
		}
		item.After = after
		return item

	case "import_lyrics":
		if req.Lyrics != nil {
			l := *req.Lyrics
			item.LyricsText = &l
		}
		return item

	case "import_cover":
		item.InlineCover = req.CoverData
		return item

	case "export_lyrics":
		if strings.TrimSpace(tagData.Lyrics) == "" {
			item.Status = model.LibraryItemNeedsReview
			item.Warnings = mergeWarnings(item.Warnings, []string{"音频内未发现内嵌歌词，无法导出 .lrc"})
			return item
		}
		l := tagData.Lyrics
		item.LyricsText = &l
		return item

	case "export_cover":
		if cov, _, ok := media.ExtractEmbeddedCover(vp.AbsPath); !ok || len(cov) == 0 {
			item.Status = model.LibraryItemNeedsReview
			item.Warnings = mergeWarnings(item.Warnings, []string{"音频内未发现内嵌封面，无法导出图片"})
			return item
		}
		return item

	case "artist_artwork":
		item.Status = model.LibraryItemNeedsReview
		item.Warnings = mergeWarnings(item.Warnings, []string{"歌手头像自动抓取暂未启用，请使用封面导入或手动上传"})
		return item

	case "rename":
		tmpl := req.FilenameTemplate
		if strings.TrimSpace(tmpl) == "" {
			tmpl = settings.FilenameTemplate
		}
		overwrite := settings.Overwrite
		if req.Overwrite != nil {
			overwrite = *req.Overwrite
		}
		target, tErr := computeRenamePath(vp.AbsPath, after, tmpl, counter, reserved, overwrite)
		if tErr != nil {
			item.Status = model.LibraryItemNeedsReview
			item.Error = tErr.Error()
		} else {
			item.Target = target
		}
		return item

	case "organize":
		overwrite := settings.Overwrite
		if req.Overwrite != nil {
			overwrite = *req.Overwrite
		}
		target, tErr := computeTargetPath(libRoot, vp.AbsPath, after, settings, counter, reserved, overwrite)
		if tErr != nil {
			item.Status = model.LibraryItemNeedsReview
			item.Error = tErr.Error()
		} else {
			item.Target = target
		}
		return item

	case "scrape", "both":
		sources := settings.Sources
		if len(req.Sources) > 0 {
			sources = req.Sources
		}
		stem := strings.TrimSuffix(vp.Name, filepath.Ext(vp.Name))
		refTitle := before["title"]
		refArtist := before["artist"]
		if refTitle == "" {
			t, a := inferFromStem(stem, req.FilenameOrder)
			refTitle = t
			if refArtist == "" {
				refArtist = a
			}
		}
		ref := ScrapeReference{
			Title:    refTitle,
			Artist:   refArtist,
			Album:    before["album"],
			Duration: tagData.Duration,
			Path:     vp.AbsPath,
		}
		var cands []ScrapeCandidate
		var warns []string
		if s.scraper != nil {
			cands, warns = s.scraper.Search(ctx, snap, ref, sources)
		}
		ranked, best := PickCandidates(ref, cands)
		item.Candidates = ranked
		item.Warnings = mergeWarnings(item.Warnings, warns)
		if best == nil {
			item.Status = model.LibraryItemNeedsReview
			if len(ranked) == 0 {
				item.Warnings = mergeWarnings(item.Warnings, []string{"未找到匹配候选，请人工确认"})
			} else {
				item.Warnings = mergeWarnings(item.Warnings, []string{"匹配置信度不足或存在多候选冲突，请人工选择"})
			}
			return item
		}
		if s.scraper != nil {
			enriched, eWarns := s.scraper.Enrich(ctx, snap, *best, settings.LyricsMode != "none", ranked)
			best = &enriched
			item.Warnings = mergeWarnings(item.Warnings, eWarns)
			for i := range item.Candidates {
				if item.Candidates[i].ID == enriched.ID {
					item.Candidates[i] = enriched
					break
				}
			}
		}
		cid := best.ID
		item.Chosen = &cid
		item.CoverURL = best.CoverURL
		if best.Lyrics != "" {
			l := best.Lyrics
			item.LyricsText = &l
		}
		item.After = applyCandidateToTags(before, *best, settings, locks)
		if req.Action == "both" && libRoot != "" {
			overwrite := settings.Overwrite
			if req.Overwrite != nil {
				overwrite = *req.Overwrite
			}
			target, tErr := computeTargetPath(libRoot, vp.AbsPath, item.After, settings, counter, reserved, overwrite)
			if tErr != nil {
				item.Status = model.LibraryItemNeedsReview
				item.Error = tErr.Error()
				return item
			}
			item.Target = target
		}
		return item
	}
	_ = flags
	return item
}

func (s *Service) handleWatchFailures(ctx context.Context, actor auth.Actor, settings Settings, roots []files.Root, jobMeta *persistedJobMeta) {
	if settings.WatchFailureDir == "" {
		return
	}
	failRoot := resolveDirAgainstRoots(settings.WatchFailureDir, roots)
	for idx := range jobMeta.Items {
		it := &jobMeta.Items[idx]
		if it.Status != model.LibraryItemNeedsReview {
			continue
		}
		target := filepath.Join(failRoot, filepath.Base(it.OriginalPath))
		if _, err := files.ValidatePath(roots, target, files.ValidateOptions{MustExist: false, AudioOnly: true}); err != nil {
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			continue
		}
		if _, err := os.Lstat(target); err == nil {
			continue
		}
		ord := it.Ordinal
		res, err := s.coordinator.Execute(ctx, files.OperationRequest{
			Kind:             "move",
			AssetID:          it.AssetID,
			ExpectedRevision: it.ExpectedRev,
			SourcePath:       it.OriginalPath,
			TargetPath:       target,
			BackupDays:       settings.BackupDays,
			JobID:            jobMeta.JobID,
			ItemID:           it.ID,
			ItemOrdinal:      &ord,
			ItemStatus:       model.LibraryItemNeedsReview,
			JobStatus:        model.LibraryJobNeedsReview,
			NewMetadata:      nonNilStringMap(it.After),
			NewTitle:         it.After["title"],
			NewArtist:        it.After["artist"],
			NewAlbum:         it.After["album"],
			NewDurationMS:    int64(it.SourceMeta.Duration * 1000),
			NewLockedFields:  it.Locks,
			Editable:         it.SourceMeta.Editable,
		})
		if err == nil {
			it.OperationID = res.OperationID
			it.OriginalPath = target
			it.Target = target
			if res.Asset != nil {
				it.ExpectedRev = res.Asset.Revision
				it.RelPath = res.Asset.RelPath
			}
		}
	}
	_ = s.saveJobMeta(ctx, *jobMeta)
	_ = actor
}

func (s *Service) ensureAssetForPath(ctx context.Context, vp files.ValidatedPath, tagData helper.AudioTagData) (store.AssetRecord, error) {
	var asset store.AssetRecord
	var metaJSON, locksJSON string
	var editable int
	err := s.store.Reader().QueryRowContext(ctx, `
		SELECT id, root_id, rel_path, path_key, folder, name, status, editable,
		       size, mtime_ns, revision, title, artist, album, duration_ms,
		       metadata_json, locks_json, origin_json, indexed_generation, updated_at
		FROM assets WHERE root_id = ? AND path_key = ?
	`, vp.Root.ID, vp.PathKey).Scan(
		&asset.ID, &asset.RootID, &asset.RelPath, &asset.PathKey, &asset.Folder, &asset.Name, &asset.Status, &editable,
		&asset.Size, &asset.MTimeNS, &asset.Revision, &asset.Title, &asset.Artist, &asset.Album, &asset.DurationMS,
		&metaJSON, &locksJSON, &asset.OriginJSON, &asset.IndexedGeneration, &asset.UpdatedAt,
	)
	if err == nil {
		asset.Editable = editable == 1
		_ = json.Unmarshal([]byte(metaJSON), &asset.Metadata)
		_ = json.Unmarshal([]byte(locksJSON), &asset.LockedFields)
		return asset, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return store.AssetRecord{}, err
	}

	st, stErr := os.Lstat(vp.AbsPath)
	if stErr != nil {
		return store.AssetRecord{}, model.WrapError(model.ErrNotFound, http.StatusNotFound, "文件不存在", stErr)
	}
	if err := s.store.UpsertRoot(ctx, store.RootRecord{
		ID:            vp.Root.ID,
		CanonicalPath: vp.Root.CanonicalPath,
		PathCaseMode:  vp.Root.CaseMode,
		Enabled:       true,
	}); err != nil {
		return store.AssetRecord{}, err
	}
	created, uErr := s.store.UpsertAsset(ctx, store.AssetRecord{
		ID:         model.NewUUID(),
		RootID:     vp.Root.ID,
		RelPath:    vp.RelPath,
		PathKey:    vp.PathKey,
		Status:     "active",
		Editable:   media.EditableExtensions[strings.ToLower(filepath.Ext(vp.Name))] && (tagData.Editable || tagData.Path == ""),
		Size:       st.Size(),
		MTimeNS:    st.ModTime().UnixNano(),
		Title:      tagData.Tags["title"],
		Artist:     tagData.Tags["artist"],
		Album:      tagData.Tags["album"],
		DurationMS: int64(tagData.Duration * 1000),
		Metadata:   nonNilStringMap(tagData.Tags),
	}, 0, false)
	if uErr == nil && created.ID != "" {
		_ = s.store.PutPublicRef(ctx, model.PublicRef{
			PublicID: "asset:" + created.ID,
			Kind:     "asset",
			TargetID: created.ID,
			Source:   "local",
			SourceID: created.ID,
		}, "")
	}
	return created, uErr
}

func (s *Service) PurgeBackups(ctx context.Context, actor auth.Actor) (int, int64, error) {
	if !actor.Authenticated {
		return 0, 0, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	if actor.ReadOnlyAI || !actor.Has(auth.PermLocalWrite) {
		return 0, 0, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少清理曲库备份权限")
	}
	if s.coordinator == nil {
		return 0, 0, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "文件协调器未初始化")
	}
	removed, freed, err := s.coordinator.PurgeAllBackups(ctx)
	if err != nil {
		s.log("error", "", "清理历史备份失败："+err.Error())
		return removed, freed, err
	}
	s.log("info", "", fmt.Sprintf("已清空历史备份：%d 条记录，释放 %.2f MB", removed, float64(freed)/(1024*1024)))
	return removed, freed, nil
}

func (s *Service) resolveSingleAudioPath(ctx context.Context, roots []files.Root, raw string) (files.ValidatedPath, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return files.ValidatedPath{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件路径不能为空")
	}
	if s.store != nil {
		if strings.HasPrefix(raw, "asset:") {
			assetID := strings.TrimPrefix(raw, "asset:")
			asset, ok, err := s.store.GetAsset(ctx, assetID)
			if err != nil {
				return files.ValidatedPath{}, err
			}
			if !ok || asset.Status == "deleted" {
				return files.ValidatedPath{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "文件不存在")
			}
			rootRec, rOK, rErr := s.store.GetRoot(ctx, asset.RootID)
			if rErr != nil {
				return files.ValidatedPath{}, rErr
			}
			if !rOK {
				return files.ValidatedPath{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "文件根目录不存在")
			}
			cand := filepath.Join(rootRec.CanonicalPath, filepath.FromSlash(asset.RelPath))
			return files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: true, AudioOnly: true})
		}
		if asset, ok, err := s.store.ResolveAssetAlias(ctx, raw); err == nil && ok && asset.Status != "deleted" {
			if rootRec, rOK, rErr := s.store.GetRoot(ctx, asset.RootID); rErr == nil && rOK {
				cand := filepath.Join(rootRec.CanonicalPath, filepath.FromSlash(asset.RelPath))
				if vp, vErr := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: true, AudioOnly: true}); vErr == nil {
					return vp, nil
				}
			}
		}
	}
	if !filepath.IsAbs(raw) {
		var lastErr error
		for _, r := range roots {
			cand := filepath.Join(r.CanonicalPath, filepath.FromSlash(raw))
			vp, err := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: true, AudioOnly: true})
			if err == nil {
				return vp, nil
			}
			lastErr = err
			if de, ok := model.AsDomainError(err); ok && de.Code == model.ErrForbidden {
				return files.ValidatedPath{}, err
			}
		}
		if lastErr != nil {
			return files.ValidatedPath{}, lastErr
		}
	}
	return files.ValidatePath(roots, raw, files.ValidateOptions{MustExist: true, AudioOnly: true})
}

func resolveScopeDirectory(roots []files.Root, rawDir string) (string, error) {
	trimmed := strings.TrimSpace(rawDir)
	if trimmed == "" || trimmed == "." {
		st, err := os.Lstat(roots[0].CanonicalPath)
		if err != nil || !st.IsDir() {
			return "", model.NewError(model.ErrNotFound, http.StatusNotFound, "音乐根目录不可用")
		}
		return roots[0].CanonicalPath, nil
	}
	if !filepath.IsAbs(trimmed) {
		var lastErr error
		for _, r := range roots {
			cand := filepath.Join(r.CanonicalPath, filepath.FromSlash(trimmed))
			vp, err := files.ValidatePath(roots, cand, files.ValidateOptions{MustExist: false, AudioOnly: false})
			if err != nil {
				lastErr = err
				if de, ok := model.AsDomainError(err); ok && de.Code == model.ErrForbidden {
					return "", err
				}
				continue
			}
			st, stErr := os.Lstat(vp.AbsPath)
			if stErr != nil {
				lastErr = model.NewError(model.ErrNotFound, http.StatusNotFound, "目标目录不存在")
				continue
			}
			if isLink, lErr := files.IsReparseOrSymlink(vp.AbsPath, st); lErr != nil || isLink {
				return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止访问符号链接目录")
			}
			if !st.IsDir() {
				return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "目标路径是文件而非目录")
			}
			return vp.AbsPath, nil
		}
		if lastErr != nil {
			return "", lastErr
		}
	}
	if filepath.Clean(trimmed) == roots[0].CanonicalPath {
		return roots[0].CanonicalPath, nil
	}
	for _, r := range roots {
		if filepath.Clean(trimmed) == r.CanonicalPath {
			return r.CanonicalPath, nil
		}
	}
	vp, err := files.ValidatePath(roots, trimmed, files.ValidateOptions{MustExist: false, AudioOnly: false})
	if err != nil {
		return "", err
	}
	st, stErr := os.Lstat(vp.AbsPath)
	if stErr != nil {
		if errors.Is(stErr, os.ErrNotExist) {
			return "", model.NewError(model.ErrNotFound, http.StatusNotFound, "目标目录不存在")
		}
		return "", model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取目录失败", stErr)
	}
	if isLink, lErr := files.IsReparseOrSymlink(vp.AbsPath, st); lErr != nil || isLink {
		return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "禁止访问符号链接目录")
	}
	if !st.IsDir() {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "目标路径是文件而非目录")
	}
	return vp.AbsPath, nil
}

func (s *Service) resolveRequestPaths(ctx context.Context, roots []files.Root, req SubmitRequest) ([]files.ValidatedPath, error) {
	if len(req.Paths) > 0 {
		if len(req.Paths) > defaultMaxBatchFiles {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "单次批量处理最多支持 1000 首")
		}
		seen := make(map[string]bool, len(req.Paths))
		out := make([]files.ValidatedPath, 0, len(req.Paths))
		for _, raw := range req.Paths {
			vp, err := s.resolveSingleAudioPath(ctx, roots, raw)
			if err != nil {
				return nil, err
			}
			if !seen[vp.AbsPath] {
				seen[vp.AbsPath] = true
				out = append(out, vp)
			}
		}
		return out, nil
	}
	if req.ScopeDir == nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请提供待处理文件路径")
	}
	scopeDir, err := resolveScopeDirectory(roots, *req.ScopeDir)
	if err != nil {
		return nil, err
	}
	var out []files.ValidatedPath
	err = filepath.WalkDir(scopeDir, func(p string, d fs.DirEntry, wErr error) error {
		if wErr != nil {
			return wErr
		}
		name := d.Name()
		if d.IsDir() {
			if files.InternalDirs[name] || strings.HasPrefix(name, ".melokeep-") || strings.HasPrefix(name, ".") {
				return filepath.SkipDir
			}
			if st, stErr := os.Lstat(p); stErr == nil {
				if isLink, _ := files.IsReparseOrSymlink(p, st); isLink {
					return filepath.SkipDir
				}
			}
			return nil
		}
		if !files.AudioExtensions[strings.ToLower(filepath.Ext(name))] {
			return nil
		}
		vp, vErr := files.ValidatePath(roots, p, files.ValidateOptions{MustExist: true, AudioOnly: true})
		if vErr != nil {
			return nil
		}
		out = append(out, vp)
		if len(out) >= defaultMaxBatchFiles {
			return fs.SkipAll
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (s *Service) saveJobMeta(ctx context.Context, meta persistedJobMeta) error {
	raw, err := json.Marshal(meta)
	if err != nil {
		return err
	}
	_, err = s.store.PutDurableRecord(ctx, durableJobMetaNamespace, meta.JobID, string(raw), 0)
	return err
}

func (s *Service) loadJobMeta(ctx context.Context, jobID string) (persistedJobMeta, error) {
	rec, ok, err := s.store.GetDurableRecord(ctx, durableJobMetaNamespace, jobID)
	if err != nil {
		return persistedJobMeta{}, err
	}
	if !ok {
		return persistedJobMeta{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "整理任务不存在")
	}
	var meta persistedJobMeta
	if err := json.Unmarshal([]byte(rec.BodyJSON), &meta); err != nil {
		return persistedJobMeta{}, err
	}
	return meta, nil
}

func (s *Service) persistJobMetaAndItem(ctx context.Context, meta persistedJobMeta, it persistedItemMeta) error {
	if err := s.saveJobMeta(ctx, meta); err != nil {
		return err
	}
	return s.persistItemState(ctx, meta.JobID, it)
}

func (s *Service) persistItemState(ctx context.Context, jobID string, it persistedItemMeta) error {
	proposedJSON, _ := json.Marshal(it.After)
	var selJSON string
	if it.Chosen != nil {
		b, _ := json.Marshal(*it.Chosen)
		selJSON = string(b)
	} else {
		selJSON = "null"
	}
	locksJSON, _ := json.Marshal(nonNilSlice(it.Locks))
	warnsJSON, _ := json.Marshal(nonNilSlice(it.Warnings))
	errJSON := "{}"
	if it.Error != "" {
		b, _ := json.Marshal(map[string]string{"detail": it.Error})
		errJSON = string(b)
	}
	return s.store.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			UPDATE library_items
			SET original_path = ?, target_path = ?, state = ?, expected_revision = ?,
			    proposed_json = ?, selected_candidate_json = ?, locks_json = ?,
			    warnings_json = ?, error_json = ?, operation_id = ?
			WHERE job_id = ? AND item_id = ?
		`, it.OriginalPath, it.Target, string(it.Status), max64(1, it.ExpectedRev),
			string(proposedJSON), selJSON, string(locksJSON), string(warnsJSON), errJSON, it.OperationID,
			jobID, it.ID)
		return err
	})
}

func (s *Service) persistJobAndItems(ctx context.Context, meta persistedJobMeta, status model.LibraryJobStatus, detail string, processed int) error {
	now := time.Now().UTC().UnixMilli()
	return s.store.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `
			UPDATE library_jobs
			SET action = ?, state = ?, detail = ?, processed = ?, total = ?, revision = revision + 1, updated_at = ?
			WHERE id = ?
		`, meta.Action, string(status), detail, processed, len(meta.Items), now, meta.JobID); err != nil {
			return err
		}
		for _, it := range meta.Items {
			proposedJSON, _ := json.Marshal(it.After)
			selJSON := "null"
			if it.Chosen != nil {
				b, _ := json.Marshal(*it.Chosen)
				selJSON = string(b)
			}
			locksJSON, _ := json.Marshal(nonNilSlice(it.Locks))
			warnsJSON, _ := json.Marshal(nonNilSlice(it.Warnings))
			errJSON := "{}"
			if it.Error != "" {
				b, _ := json.Marshal(map[string]string{"detail": it.Error})
				errJSON = string(b)
			}
			if _, err := tx.ExecContext(ctx, `
				UPDATE library_items
				SET original_path = ?, target_path = ?, state = ?, expected_revision = ?,
				    proposed_json = ?, selected_candidate_json = ?, locks_json = ?,
				    warnings_json = ?, error_json = ?, operation_id = ?
				WHERE job_id = ? AND item_id = ?
			`, it.OriginalPath, it.Target, string(it.Status), max64(1, it.ExpectedRev),
				string(proposedJSON), selJSON, string(locksJSON), string(warnsJSON), errJSON, it.OperationID,
				meta.JobID, it.ID); err != nil {
				return err
			}
		}
		return nil
	})
}

func (s *Service) ensureActorAccess(actor auth.Actor, ownerKey string) error {
	if actor.Kind == auth.ActorSystem || actor.Kind == auth.ActorWebAdmin || actor.Has(auth.PermAdmin) {
		return nil
	}
	if ownerKey != "" && actor.Key() != ownerKey {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "无权操作其他用户创建的整理任务")
	}
	return nil
}

func validateSubmitRequest(req SubmitRequest) error {
	if len(req.Changes) > 0 {
		allowed := make(map[string]bool, len(media.TagFields))
		for _, f := range media.TagFields {
			allowed[f] = true
		}
		for k, v := range req.Changes {
			if !allowed[k] || len(v) > 2000 {
				return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "标签字段或长度无效")
			}
		}
	}
	if req.Lyrics != nil && len(*req.Lyrics) > maxInlineLyricsBytes {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌词文本超过 2 MB 上限")
	}
	if req.CoverData != "" {
		if _, _, err := decodeInlineCover(req.CoverData); err != nil {
			return err
		}
	}
	if req.FilenameTemplate != "" {
		if err := ValidateTemplate(req.FilenameTemplate, true); err != nil {
			return model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, err.Error(), err)
		}
	}
	if req.Action == "convert" || req.Action == "split" {
		if err := ValidateConvertOptions(ConvertSplitOptions{
			OutputFormat: req.OutputFormat,
			Bitrate:      req.Bitrate,
			SampleRate:   req.SampleRate,
			CUEText:      req.CUE,
		}); err != nil {
			return err
		}
	}
	return nil
}

func decodeInlineCover(raw string) ([]byte, string, error) {
	s := strings.TrimSpace(raw)
	if idx := strings.Index(s, ","); idx >= 0 && strings.HasPrefix(s[:idx], "data:") {
		s = s[idx+1:]
	}
	data, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return nil, "", model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "封面编码无效", err)
	}
	if len(data) > maxInlineCoverDecodedLen {
		return nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "封面图片超过 8 MB")
	}
	mime := DetectImageMIME(data)
	if mime == "" {
		return nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "封面必须是有效的 PNG 或 JPEG 图片")
	}
	return data, mime, nil
}

func applyCandidateToTags(before map[string]string, cand ScrapeCandidate, settings Settings, locks []string) map[string]string {
	after := cloneStringMap(before)
	lockedSet := make(map[string]bool, len(locks))
	for _, l := range locks {
		lockedSet[l] = true
	}
	allowedFields := make(map[string]bool, len(settings.Fields))
	for _, f := range settings.Fields {
		allowedFields[f] = true
	}
	if len(allowedFields) == 0 {
		for _, f := range media.TagFields {
			allowedFields[f] = true
		}
	}
	candFields := cand.FieldsMap()
	for field, val := range candFields {
		if val == "" || !allowedFields[field] || lockedSet[field] {
			continue
		}
		after[field] = Transform(val, "", "", settings.Chinese, settings.RepairEncoding)
	}
	return after
}

func computeTargetPath(libRoot, srcPath string, tags map[string]string, settings Settings, counter int, reserved map[string]struct{}, overwrite bool) (string, error) {
	sidecars := files.DiscoverSidecars(srcPath)
	target, err := BuildTargetPath(libRoot, srcPath, tags, settings.FolderTemplate, settings.FilenameTemplate, counter, nil, sidecars)
	if err != nil {
		return "", err
	}
	if target == srcPath {
		return target, nil
	}
	if !overwrite {
		if _, inRes := reserved[target]; inRes {
			target = ReserveTargetPath(srcPath, target, reserved, sidecars, settings.LyricsMode, settings.CoverMode)
		} else if _, stErr := os.Lstat(target); stErr == nil {
			target = ReserveTargetPath(srcPath, target, reserved, sidecars, settings.LyricsMode, settings.CoverMode)
		}
	}
	if reserved != nil {
		reserved[target] = struct{}{}
	}
	return target, nil
}

func computeRenamePath(srcPath string, tags map[string]string, tmpl string, counter int, reserved map[string]struct{}, overwrite bool) (string, error) {
	vals := Variables(srcPath, tags, counter, "")
	rendered, err := Render(tmpl, vals, func(s string) string { return SafeSegment(s, "") })
	if err != nil {
		return "", err
	}
	name := SafeSegment(rendered, strings.TrimSuffix(filepath.Base(srcPath), filepath.Ext(srcPath)))
	target := filepath.Join(filepath.Dir(srcPath), name+filepath.Ext(srcPath))
	if target == srcPath {
		return target, nil
	}
	if !overwrite {
		target = ReserveTargetPath(srcPath, target, reserved, files.DiscoverSidecars(srcPath), "both", "both")
	}
	if reserved != nil {
		reserved[target] = struct{}{}
	}
	return target, nil
}

var filenameSplitRe = regexp.MustCompile(`\s+-\s+`)

func inferFromStem(stem, order string) (string, string) {
	parts := filenameSplitRe.Split(strings.TrimSpace(stem), 2)
	if len(parts) == 2 {
		if order == "artist_title" {
			return strings.TrimSpace(parts[1]), strings.TrimSpace(parts[0])
		}
		return strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1])
	}
	return strings.TrimSpace(stem), ""
}

func toStoreLibraryItemInput(it persistedItemMeta) store.LibraryItemInput {
	beforeJSON, _ := json.Marshal(nonNilStringMap(it.Before))
	proposedJSON, _ := json.Marshal(nonNilStringMap(it.After))
	selJSON := "null"
	if it.Chosen != nil {
		b, _ := json.Marshal(*it.Chosen)
		selJSON = string(b)
	}
	locksJSON, _ := json.Marshal(nonNilSlice(it.Locks))
	warnsJSON, _ := json.Marshal(nonNilSlice(it.Warnings))
	return store.LibraryItemInput{
		ItemID:                it.ID,
		AssetID:               it.AssetID,
		PathToken:             it.RelPath,
		Name:                  it.Name,
		OriginalPath:          it.OriginalPath,
		TargetPath:            it.Target,
		State:                 it.Status,
		ExpectedRevision:      max64(1, it.ExpectedRev),
		BeforeJSON:            string(beforeJSON),
		ProposedJSON:          string(proposedJSON),
		SelectedCandidateJSON: selJSON,
		LocksJSON:             string(locksJSON),
		WarningsJSON:          string(warnsJSON),
	}
}

func isLibraryJobActive(st model.LibraryJobStatus) bool {
	switch st {
	case model.LibraryJobQueued, model.LibraryJobScanning, model.LibraryJobPreviewReady, model.LibraryJobNeedsReview, model.LibraryJobApplying, model.LibraryJobPaused:
		return true
	default:
		return false
	}
}

func deriveInitialJobStatus(action string, items []persistedItemMeta) model.LibraryJobStatus {
	if action == "quality" || action == "duplicates" {
		allFailed := len(items) > 0
		for _, it := range items {
			if it.Status != model.LibraryItemFailed {
				allFailed = false
				break
			}
		}
		if allFailed {
			return model.LibraryJobFailed
		}
		return model.LibraryJobComplete
	}
	hasReview := false
	hasReady := false
	hasFailed := false
	for _, it := range items {
		switch it.Status {
		case model.LibraryItemNeedsReview:
			hasReview = true
		case model.LibraryItemPreviewReady:
			hasReady = true
		case model.LibraryItemFailed:
			hasFailed = true
		}
	}
	if hasReview && !hasReady {
		return model.LibraryJobNeedsReview
	}
	if hasReady {
		return model.LibraryJobPreviewReady
	}
	if hasFailed {
		return model.LibraryJobFailed
	}
	return model.LibraryJobComplete
}

func deriveFinalJobStatus(items []persistedItemMeta, stopped string) model.LibraryJobStatus {
	if stopped == "cancelled" {
		return model.LibraryJobCancelled
	}
	if stopped == "paused" {
		return model.LibraryJobPaused
	}
	completeCnt := 0
	failedCnt := 0
	reviewCnt := 0
	for _, it := range items {
		switch it.Status {
		case model.LibraryItemComplete:
			completeCnt++
		case model.LibraryItemFailed:
			failedCnt++
		case model.LibraryItemNeedsReview:
			reviewCnt++
		}
	}
	if completeCnt == len(items) {
		return model.LibraryJobComplete
	}
	if completeCnt > 0 && (failedCnt > 0 || reviewCnt > 0) {
		return model.LibraryJobPartial
	}
	if reviewCnt > 0 && failedCnt == 0 {
		return model.LibraryJobNeedsReview
	}
	if failedCnt > 0 {
		return model.LibraryJobFailed
	}
	return model.LibraryJobPartial
}

func buildJobDetail(action string, status model.LibraryJobStatus, items []persistedItemMeta) string {
	switch status {
	case model.LibraryJobPreviewReady:
		return fmt.Sprintf("预览已就绪（共 %d 首）", len(items))
	case model.LibraryJobNeedsReview:
		return "部分歌曲需要人工审核确认"
	case model.LibraryJobComplete:
		if action == "quality" {
			return "音质检测完成"
		}
		return fmt.Sprintf("处理完成（共 %d 首）", len(items))
	case model.LibraryJobPartial:
		return fmt.Sprintf("部分完成（成功 %d / %d）", countCompleteItems(items), len(items))
	case model.LibraryJobCancelled:
		return "任务已取消"
	case model.LibraryJobPaused:
		return "任务已暂停"
	case model.LibraryJobFailed:
		return "处理失败"
	default:
		return ""
	}
}

func countCompleteItems(items []persistedItemMeta) int {
	n := 0
	for _, it := range items {
		if it.Status == model.LibraryItemComplete {
			n++
		}
	}
	return n
}

func countStatusBreakdown(items []persistedItemMeta) (ready, review, failed int) {
	for _, it := range items {
		switch it.Status {
		case model.LibraryItemPreviewReady:
			ready++
		case model.LibraryItemNeedsReview:
			review++
		case model.LibraryItemFailed:
			failed++
		}
	}
	return ready, review, failed
}

func mergeWarnings(existing, extra []string) []string {
	out := append([]string(nil), existing...)
	for _, w := range extra {
		if w != "" && !slices.Contains(out, w) {
			out = append(out, w)
		}
	}
	return out
}

func nonNilStringMap(m map[string]string) map[string]string {
	if m == nil {
		return map[string]string{}
	}
	return m
}

func nonNilSlice(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

func mapsEqual(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range a {
		if b[k] != v {
			return false
		}
	}
	return true
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

// RecoverOnStartup marks any library_jobs left stuck in 'scanning' or 'applying'
// during an unclean shutdown as 'needs_attention'.
func (s *Service) RecoverOnStartup(ctx context.Context) (int, error) {
	if s.store == nil {
		return 0, nil
	}
	now := time.Now().UTC().UnixMilli()
	var updated int64
	err := s.store.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			UPDATE library_jobs
			SET state = ?,
			    detail = CASE WHEN detail = '' THEN ? ELSE detail END,
			    revision = revision + 1,
			    updated_at = ?
			WHERE state IN ('scanning', 'applying')
		`, string(model.LibraryJobNeedsAttention), "服务重启时检测到未完成的整理任务，请检查后重试", now)
		if err != nil {
			return err
		}
		updated, _ = res.RowsAffected()
		return nil
	})
	if err != nil {
		return 0, err
	}
	if updated > 0 {
		s.log("warn", "", fmt.Sprintf("启动恢复：发现 %d 个未完成的整理任务，已标记为待处理", updated))
	}
	return int(updated), nil
}
