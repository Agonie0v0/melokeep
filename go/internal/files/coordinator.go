package files

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func withRefreshedCoverMetadata(dstAbsPath string, incoming, existing map[string]string) map[string]string {
	out := make(map[string]string)
	src := incoming
	if src == nil {
		src = existing
	}
	for k, v := range src {
		out[k] = v
	}
	if incoming != nil && existing != nil {
		if br := strings.TrimSpace(existing["_bitrate"]); br != "" && strings.TrimSpace(out["_bitrate"]) == "" {
			out["_bitrate"] = br
		}
	}
	if media.HasLocalCover(dstAbsPath) {
		out["_has_cover"] = "1"
	} else {
		delete(out, "_has_cover")
	}
	return out
}

type FileRef struct {
	RootID      string `json:"root_id,omitempty"`
	RelPath     string `json:"rel_path"`
	HistoryRoot bool   `json:"history_root,omitempty"`
}

type FileIdentityPair struct {
	Source      FileIdentity `json:"source,omitempty"`
	Stage       FileIdentity `json:"stage,omitempty"`
	Target      FileIdentity `json:"target,omitempty"`
	Backup      FileIdentity `json:"backup,omitempty"`
	Preexisting bool         `json:"preexisting,omitempty"`
}

type SidecarOutput struct {
	Role       string // sidecar_lrc | sidecar_cover
	TargetPath string
	Data       []byte
}

// StageMutator applies tags, transcode, or verification strictly to the staged audio copy.
type StageMutator func(ctx context.Context, stageAudioPath string) ([]SidecarOutput, error)

type OperationRequest struct {
	Kind             string // edit | move | derive | download_publish | delete
	AssetID          string
	ExpectedRevision int64
	SourcePath       string
	TargetPath       string
	BackupDays       int
	DisableBackup    bool
	JobID            string
	ItemID           string
	ItemOrdinal      *int
	JobStatus        model.LibraryJobStatus
	ItemStatus       model.LibraryItemStatus
	NewMetadata      map[string]string
	NewTitle         string
	NewArtist        string
	NewAlbum         string
	AllowEmptyMeta   bool
	NewDurationMS    int64
	NewLockedFields  []string
	Editable         bool
	Outbox           *store.OutboxEnqueueParams
	Mutator          StageMutator
	DownloadPublish  *DownloadPublishSpec
	HookAfterPhase   func(phase string) error
}

type DownloadPublishSpec struct {
	DownloadID       string
	ExpectedRevision int64
	TrackID          string
	PublicID         string
	Source         string
	SourceID       string
	ActorKey       string
	AutoOrganize   *store.AutoOrganizeJobSpec
	SidecarOutputs []SidecarOutput
}

type OperationResult struct {
	OperationID string
	Asset       *store.AssetRecord
	TargetPath  string
	State       string
	Warnings    []string
}

type companionPlan struct {
	ordinal    int
	srcPath    string
	dstPath    string
	backupPath string
	stagePath  string
	beforeHash string
	ident      FileIdentity
	role       string
}

type Coordinator struct {
	mu         sync.RWMutex
	store      *store.Store
	locks      *AssetLockManager
	roots      []Root
	historyDir string
	maxRetries int
}

func NewCoordinator(st *store.Store, locks *AssetLockManager, roots []Root) *Coordinator {
	if locks == nil {
		locks = NewAssetLockManager(5 * time.Second)
	}
	return &Coordinator{
		store:      st,
		locks:      locks,
		roots:      append([]Root(nil), roots...),
		maxRetries: 5,
	}
}

// SetHistoryDir configures an out-of-library directory (e.g. data/go/history) for operation backups.
func (c *Coordinator) SetHistoryDir(dir string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	trimmed := strings.TrimSpace(dir)
	if trimmed == "" {
		c.historyDir = ""
		return
	}
	if abs, err := filepath.Abs(trimmed); err == nil {
		c.historyDir = filepath.Clean(abs)
		return
	}
	c.historyDir = filepath.Clean(trimmed)
}

func (c *Coordinator) HistoryDir() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.historyDir
}

func (c *Coordinator) resolveBackupTarget(srcRoot Root, opID, fileName string) (dir, abs, refJSON string) {
	if historyDir := c.HistoryDir(); historyDir != "" {
		dir = filepath.Join(historyDir, opID)
		abs = filepath.Join(dir, fileName)
		b, _ := json.Marshal(FileRef{
			RelPath:     filepath.ToSlash(filepath.Join(opID, fileName)),
			HistoryRoot: true,
		})
		return dir, abs, string(b)
	}
	dir = filepath.Join(srcRoot.CanonicalPath, ".melokeep-history", opID)
	abs = filepath.Join(dir, fileName)
	return dir, abs, marshalRelRef(srcRoot.ID, srcRoot.CanonicalPath, abs)
}

func (c *Coordinator) Roots() []Root {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return append([]Root(nil), c.roots...)
}

func (c *Coordinator) UpdateRoots(roots []Root) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.roots = append([]Root(nil), roots...)
}

func (c *Coordinator) Locks() *AssetLockManager {
	return c.locks
}

func (c *Coordinator) Execute(ctx context.Context, req OperationRequest) (OperationResult, error) {
	switch req.Kind {
	case "download_publish":
		return c.executeDownloadPublish(ctx, req)
	case "edit", "move":
		return c.executeEditOrMove(ctx, req)
	case "derive":
		return c.executeDerive(ctx, req)
	case "delete":
		return c.executeDelete(ctx, req)
	default:
		return OperationResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的文件操作类型")
	}
}

func (c *Coordinator) executeEditOrMove(ctx context.Context, req OperationRequest) (OperationResult, error) {
	if req.AssetID != "" {
		release, err := c.locks.AcquireWrite(ctx, req.AssetID)
		if err != nil {
			return OperationResult{}, err
		}
		defer release()
	}

	roots := c.Roots()
	srcVal, err := ValidatePath(roots, req.SourcePath, ValidateOptions{MustExist: true, AudioOnly: true})
	if err != nil {
		return OperationResult{}, err
	}
	dstVal, err := ValidatePath(roots, req.TargetPath, ValidateOptions{MustExist: false, AudioOnly: true})
	if err != nil {
		return OperationResult{}, err
	}
	samePath := srcVal.AbsPath == dstVal.AbsPath
	sameSlot := srcVal.Root.ID == dstVal.Root.ID && srcVal.PathKey == dstVal.PathKey
	if !sameSlot {
		if _, stErr := os.Lstat(dstVal.AbsPath); stErr == nil {
			return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "目标文件已存在，请重新预览整理路径")
		}
	}

	var existingAsset store.AssetRecord
	var hasAsset bool
	if req.AssetID != "" {
		existingAsset, hasAsset, err = c.store.GetAsset(ctx, req.AssetID)
		if err != nil {
			return OperationResult{}, err
		}
		if !hasAsset {
			return OperationResult{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "资产记录不存在")
		}
		if req.ExpectedRevision > 0 && existingAsset.Revision != req.ExpectedRevision {
			return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "资产版本已变化，请重新预览")
		}
	}

	opID := model.NewUUID()
	skipBackupCopy := req.DisableBackup && !samePath
	transientBackup := req.DisableBackup && samePath
	backupDir, backupAudio, backupAudioRef := c.resolveBackupTarget(srcVal.Root, opID, srcVal.Name)
	if skipBackupCopy {
		backupDir = ""
		backupAudio = ""
		backupAudioRef = "{}"
	}
	stageAudio := filepath.Join(filepath.Dir(dstVal.AbsPath), ".melokeep-stage-"+opID+filepath.Ext(dstVal.AbsPath))

	srcHash, _, err := HashFileSHA256(srcVal.AbsPath)
	if err != nil {
		return OperationResult{}, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取源文件摘要失败", err)
	}
	srcIdent, err := StatIdentity(srcVal.AbsPath)
	if err != nil {
		return OperationResult{}, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取源文件身份失败", err)
	}

	companions := DiscoverSidecars(srcVal.AbsPath)

	specs := make([]store.OperationFileSpec, 0, 1+len(companions))
	specs = append(specs, store.OperationFileSpec{
		Ordinal:       0,
		Role:          "audio",
		SourceRefJSON: marshalRef(srcVal.Root.ID, srcVal.RelPath),
		TargetRefJSON: marshalRef(dstVal.Root.ID, dstVal.RelPath),
		StageRefJSON:  marshalRelRef(dstVal.Root.ID, dstVal.Root.CanonicalPath, stageAudio),
		BackupRefJSON: backupAudioRef,
		BeforeHash:    srcHash,
		Phase:         "planned",
		IdentityJSON:  marshalPair(FileIdentityPair{Source: srcIdent, Preexisting: samePath}),
	})

	var compPlans []companionPlan
	for i, compPath := range companions {
		ext := filepath.Ext(compPath)
		cHash, _, err := HashFileSHA256(compPath)
		if err != nil {
			return OperationResult{}, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取附属文件摘要失败", err)
		}
		cIdent, err := StatIdentity(compPath)
		if err != nil {
			return OperationResult{}, model.WrapError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取附属文件身份失败", err)
		}
		dstComp := strings.TrimSuffix(dstVal.AbsPath, filepath.Ext(dstVal.AbsPath)) + ext
		if !sameSlot {
			if _, stErr := os.Lstat(dstComp); stErr == nil {
				return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "目标附属文件已存在，请重新预览")
			}
		}
		var bComp, bCompRef string
		if skipBackupCopy {
			bCompRef = "{}"
		} else {
			_, bComp, bCompRef = c.resolveBackupTarget(srcVal.Root, opID, filepath.Base(compPath))
		}
		sComp := filepath.Join(filepath.Dir(dstComp), ".melokeep-stage-"+opID+"-"+fmt.Sprintf("%d", i+1)+ext)
		role := "sidecar_cover"
		if strings.EqualFold(ext, ".lrc") {
			role = "sidecar_lrc"
		}
		srcCompRel, _ := filepath.Rel(srcVal.Root.CanonicalPath, compPath)
		dstCompRel, _ := filepath.Rel(dstVal.Root.CanonicalPath, dstComp)
		specs = append(specs, store.OperationFileSpec{
			Ordinal:       i + 1,
			Role:          role,
			SourceRefJSON: marshalRef(srcVal.Root.ID, filepath.ToSlash(srcCompRel)),
			TargetRefJSON: marshalRef(dstVal.Root.ID, filepath.ToSlash(dstCompRel)),
			StageRefJSON:  marshalRelRef(dstVal.Root.ID, dstVal.Root.CanonicalPath, sComp),
			BackupRefJSON: bCompRef,
			BeforeHash:    cHash,
			Phase:         "planned",
			IdentityJSON:  marshalPair(FileIdentityPair{Source: cIdent, Preexisting: samePath}),
		})
		compPlans = append(compPlans, companionPlan{
			ordinal:    i + 1,
			srcPath:    compPath,
			dstPath:    dstComp,
			backupPath: bComp,
			stagePath:  sComp,
			beforeHash: cHash,
			ident:      cIdent,
			role:       role,
		})
	}

	var expiresAtPtr *int64
	if !req.DisableBackup {
		backupDays := req.BackupDays
		if backupDays <= 0 {
			backupDays = 7
		}
		exp := time.Now().UTC().Add(time.Duration(backupDays) * 24 * time.Hour).UnixMilli()
		expiresAtPtr = &exp
	}
	beforeAssetJSON, _ := json.Marshal(existingAsset)

	if _, err := c.store.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation:       model.FileOperation{ID: opID, Kind: req.Kind, State: "planned"},
		JobID:           req.JobID,
		ItemOrdinal:     req.ItemOrdinal,
		AssetID:         req.AssetID,
		BeforeAssetJSON: string(beforeAssetJSON),
		ExpiresAt:       expiresAtPtr,
		Files:           specs,
	}); err != nil {
		return OperationResult{}, err
	}
	if err := callHook(req.HookAfterPhase, "planned"); err != nil {
		return OperationResult{}, err
	}

	if !skipBackupCopy {
		if bHash, _, err := CopyFileDurable(srcVal.AbsPath, backupAudio); err != nil || bHash != srcHash {
			_ = os.RemoveAll(backupDir)
			_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON("backup failed"))
			if err != nil {
				return OperationResult{}, err
			}
			return OperationResult{}, model.NewError(model.ErrInternal, http.StatusInternalServerError, "备份校验失败，已保留原文件")
		}
		for _, cp := range compPlans {
			if bHash, _, err := CopyFileDurable(cp.srcPath, cp.backupPath); err != nil || bHash != cp.beforeHash {
				_ = os.RemoveAll(backupDir)
				_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON("companion backup failed"))
				if err != nil {
					return OperationResult{}, err
				}
				return OperationResult{}, model.NewError(model.ErrInternal, http.StatusInternalServerError, "附属文件备份校验失败，已保留原文件")
			}
		}
		if err := callHook(req.HookAfterPhase, "backed_up"); err != nil {
			return OperationResult{}, err
		}
	}

	defer func() {
		_ = os.Remove(stageAudio)
		for _, cp := range compPlans {
			_ = os.Remove(cp.stagePath)
		}
	}()
	if _, _, err := CopyFileDurable(srcVal.AbsPath, stageAudio); err != nil {
		_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(err.Error()))
		return OperationResult{}, err
	}
	var mutatorSidecars []SidecarOutput
	if req.Mutator != nil {
		mutatorSidecars, err = req.Mutator(ctx, stageAudio)
		if err != nil {
			_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(err.Error()))
			return OperationResult{}, err
		}
	}
	afterHash, afterSize, err := HashFileSHA256(stageAudio)
	if err != nil {
		_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(err.Error()))
		return OperationResult{}, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "计算暂存音频摘要失败", err)
	}
	stageIdent, _ := StatIdentity(stageAudio)
	audioPair := FileIdentityPair{Source: srcIdent, Stage: stageIdent, Preexisting: samePath}
	if err := c.store.RecordOperationFilePhase(ctx, opID, 0, "prepared", afterHash, marshalPair(audioPair)); err != nil {
		return OperationResult{}, err
	}
	for idx := range compPlans {
		cp := &compPlans[idx]
		data, hasOverride := findSidecarOverride(mutatorSidecars, cp.dstPath)
		if hasOverride {
			aHash, wErr := WriteFileDurable(cp.stagePath, data)
			if wErr != nil {
				_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(wErr.Error()))
				return OperationResult{}, wErr
			}
			cp.beforeHash = aHash
		} else {
			if _, _, cErr := CopyFileDurable(cp.srcPath, cp.stagePath); cErr != nil {
				_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(cErr.Error()))
				return OperationResult{}, cErr
			}
		}
		sIdent, _ := StatIdentity(cp.stagePath)
		pair := FileIdentityPair{Source: cp.ident, Stage: sIdent, Preexisting: samePath}
		if err := c.store.RecordOperationFilePhase(ctx, opID, cp.ordinal, "prepared", cp.beforeHash, marshalPair(pair)); err != nil {
			return OperationResult{}, err
		}
	}
	nextOrd := 1 + len(compPlans)
	for _, sc := range mutatorSidecars {
		if containsCompanionDst(compPlans, sc.TargetPath) {
			continue
		}
		scVal, vErr := ValidatePath(roots, sc.TargetPath, ValidateOptions{MustExist: false})
		if vErr != nil {
			_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(vErr.Error()))
			return OperationResult{}, vErr
		}
		if _, stErr := os.Lstat(scVal.AbsPath); stErr == nil {
			_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON("new sidecar exists"))
			return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "目标附属文件已存在，请重新预览")
		}
		sPath := filepath.Join(filepath.Dir(scVal.AbsPath), ".melokeep-stage-"+opID+"-"+fmt.Sprintf("%d", nextOrd)+filepath.Ext(scVal.AbsPath))
		aHash, wErr := WriteFileDurable(sPath, sc.Data)
		if wErr != nil {
			_ = os.Remove(sPath)
			_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(wErr.Error()))
			return OperationResult{}, wErr
		}
		sIdent, _ := StatIdentity(sPath)
		pair := FileIdentityPair{Stage: sIdent, Preexisting: false}
		if addErr := c.addOperationFile(ctx, opID, store.OperationFileSpec{
			Ordinal:       nextOrd,
			Role:          sc.Role,
			SourceRefJSON: "{}",
			TargetRefJSON: marshalRef(scVal.Root.ID, scVal.RelPath),
			StageRefJSON:  marshalRelRef(scVal.Root.ID, scVal.Root.CanonicalPath, sPath),
			BackupRefJSON: "{}",
			BeforeHash:    "",
			AfterHash:     aHash,
			Phase:         "prepared",
			IdentityJSON:  marshalPair(pair),
		}); addErr != nil {
			_ = os.Remove(sPath)
			return OperationResult{}, addErr
		}
		compPlans = append(compPlans, companionPlan{
			ordinal:    nextOrd,
			dstPath:    scVal.AbsPath,
			stagePath:  sPath,
			beforeHash: aHash,
			role:       sc.Role,
		})
		nextOrd++
	}

	if err := c.store.SetFileOperationState(ctx, opID, "prepared", "{}"); err != nil {
		return OperationResult{}, err
	}
	if err := callHook(req.HookAfterPhase, "prepared"); err != nil {
		return OperationResult{}, err
	}

	if err := c.store.SetFileOperationState(ctx, opID, "publishing", "{}"); err != nil {
		return OperationResult{}, err
	}
	if err := callHook(req.HookAfterPhase, "publishing"); err != nil {
		return OperationResult{}, err
	}

	var pubErr error
	var caseTmpAudio string
	caseTmpComps := make(map[int]string)
	restoreCaseTmps := func() {
		for idx, tmp := range caseTmpComps {
			if tmp != "" && idx < len(compPlans) {
				_ = os.Rename(tmp, compPlans[idx].srcPath)
			}
		}
		if caseTmpAudio != "" {
			_ = os.Rename(caseTmpAudio, srcVal.AbsPath)
		}
	}
	if samePath {
		pubErr = ReplaceSamePath(ctx, stageAudio, dstVal.AbsPath, c.maxRetries)
	} else if sameSlot {
		caseTmpAudio = srcVal.AbsPath + ".melokeep-case-" + opID
		if rErr := os.Rename(srcVal.AbsPath, caseTmpAudio); rErr != nil {
			pubErr = model.WrapError(model.ErrConflict, http.StatusConflict, "重命名文件失败（可能正在被占用）", rErr)
			caseTmpAudio = ""
		} else {
			pubErr = LinkNoReplace(stageAudio, dstVal.AbsPath)
		}
	} else {
		pubErr = LinkNoReplace(stageAudio, dstVal.AbsPath)
	}
	if pubErr != nil {
		restoreCaseTmps()
		_ = c.rollbackByID(ctx, opID)
		return OperationResult{}, pubErr
	}
	tgtIdent, _ := StatIdentity(dstVal.AbsPath)
	audioPair.Target = tgtIdent
	_ = c.store.RecordOperationFilePhase(ctx, opID, 0, "published", afterHash, marshalPair(audioPair))

	if err := callHook(req.HookAfterPhase, "file_published"); err != nil {
		return OperationResult{}, err
	}

	for idx, cp := range compPlans {
		var cErr error
		if cp.srcPath != "" && samePath {
			cErr = ReplaceSamePath(ctx, cp.stagePath, cp.dstPath, c.maxRetries)
		} else if cp.srcPath != "" && sameSlot {
			tmpC := cp.srcPath + ".melokeep-case-" + opID
			if rErr := os.Rename(cp.srcPath, tmpC); rErr != nil {
				cErr = model.WrapError(model.ErrConflict, http.StatusConflict, "重命名附属文件失败（可能正在被占用）", rErr)
			} else {
				caseTmpComps[idx] = tmpC
				cErr = LinkNoReplace(cp.stagePath, cp.dstPath)
			}
		} else {
			cErr = LinkNoReplace(cp.stagePath, cp.dstPath)
		}
		if cErr != nil {
			restoreCaseTmps()
			_ = c.rollbackByID(ctx, opID)
			return OperationResult{}, cErr
		}
		cTgtIdent, _ := StatIdentity(cp.dstPath)
		_ = c.store.RecordOperationFilePhase(ctx, opID, cp.ordinal, "published", cp.beforeHash, marshalPair(FileIdentityPair{
			Source: cp.ident, Target: cTgtIdent, Preexisting: cp.srcPath != "" && samePath,
		}))
	}

	verifyHash, _, vErr := HashFileSHA256(dstVal.AbsPath)
	if vErr != nil || verifyHash != afterHash {
		restoreCaseTmps()
		_ = c.rollbackByID(ctx, opID)
		return OperationResult{}, model.NewError(model.ErrInternal, http.StatusInternalServerError, "发布后目标文件校验失败，已回滚")
	}

	dstSt, _ := os.Lstat(dstVal.AbsPath)
	var updatedAsset *store.AssetRecord
	if req.AssetID != "" && hasAsset {
		a := existingAsset
		a.RootID = dstVal.Root.ID
		a.RelPath = dstVal.RelPath
		a.PathKey = dstVal.PathKey
		a.Folder = dstVal.Folder
		a.Name = dstVal.Name
		a.Size = afterSize
		if dstSt != nil {
			a.MTimeNS = dstSt.ModTime().UnixNano()
		}
		if req.AllowEmptyMeta || req.NewTitle != "" {
			a.Title = req.NewTitle
		}
		if req.AllowEmptyMeta || req.NewArtist != "" {
			a.Artist = req.NewArtist
		}
		if req.AllowEmptyMeta || req.NewAlbum != "" {
			a.Album = req.NewAlbum
		}
		if req.NewDurationMS > 0 {
			a.DurationMS = req.NewDurationMS
		}
		a.Metadata = withRefreshedCoverMetadata(dstVal.AbsPath, req.NewMetadata, existingAsset.Metadata)
		if req.NewLockedFields != nil {
			a.LockedFields = req.NewLockedFields
		}
		a.Editable = req.Editable || a.Editable
		updatedAsset = &a
	}

	if err := c.store.CommitFileOperation(ctx, store.CommitFileOperationParams{
		OperationID:      opID,
		UpdatedAsset:     updatedAsset,
		ExpectedRevision: req.ExpectedRevision,
		JobID:            req.JobID,
		ItemID:           req.ItemID,
		ItemStatus:       req.ItemStatus,
		JobStatus:        req.JobStatus,
		Outbox:           req.Outbox,
	}); err != nil {
		restoreCaseTmps()
		_ = c.rollbackByID(ctx, opID)
		return OperationResult{}, err
	}
	if err := callHook(req.HookAfterPhase, "committed"); err != nil {
		return OperationResult{}, err
	}

	var warnings []string
	if sameSlot && !samePath {
		if caseTmpAudio != "" {
			if rmErr := os.Remove(caseTmpAudio); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
				warnings = append(warnings, fmt.Sprintf("旧源文件清理失败(%s): %v", srcVal.Name, rmErr))
			}
		}
		for _, tmpC := range caseTmpComps {
			if tmpC != "" {
				if rmErr := os.Remove(tmpC); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
					warnings = append(warnings, fmt.Sprintf("旧附属文件清理失败(%s): %v", filepath.Base(tmpC), rmErr))
				}
			}
		}
	} else if !samePath {
		if curSrcHash, _, err := HashFileSHA256(srcVal.AbsPath); err == nil && curSrcHash == srcHash {
			if rmErr := os.Remove(srcVal.AbsPath); rmErr != nil {
				warnings = append(warnings, fmt.Sprintf("旧源文件清理失败(%s): %v", srcVal.Name, rmErr))
			}
		}
		for _, cp := range compPlans {
			if cp.srcPath != "" {
				if rmErr := os.Remove(cp.srcPath); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
					warnings = append(warnings, fmt.Sprintf("旧附属文件清理失败(%s): %v", filepath.Base(cp.srcPath), rmErr))
				}
			}
		}
		SafeRemoveEmptyParents(filepath.Dir(srcVal.AbsPath), roots)
	}
	_ = os.Remove(stageAudio)
	for _, cp := range compPlans {
		_ = os.Remove(cp.stagePath)
	}
	if transientBackup && backupDir != "" {
		_ = os.RemoveAll(backupDir)
	}
	if len(warnings) == 0 {
		_ = c.store.SetFileOperationState(ctx, opID, "cleaned", "{}")
		return OperationResult{OperationID: opID, Asset: updatedAsset, TargetPath: dstVal.AbsPath, State: "cleaned"}, nil
	}
	_ = c.store.SetFileOperationState(ctx, opID, "committed", errJSON(strings.Join(warnings, "; ")))
	return OperationResult{OperationID: opID, Asset: updatedAsset, TargetPath: dstVal.AbsPath, State: "committed", Warnings: warnings}, nil
}

func (c *Coordinator) executeDownloadPublish(ctx context.Context, req OperationRequest) (OperationResult, error) {
	if req.DownloadPublish == nil {
		return OperationResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "缺少下载发布参数")
	}
	dp := req.DownloadPublish
	if _, err := os.Stat(req.SourcePath); err != nil {
		return OperationResult{}, model.WrapError(model.ErrNotFound, http.StatusNotFound, "待发布下载临时文件不存在", err)
	}
	roots := c.Roots()
	dstVal, err := ValidatePath(roots, req.TargetPath, ValidateOptions{MustExist: false, AudioOnly: true})
	if err != nil {
		return OperationResult{}, err
	}
	if _, err := os.Lstat(dstVal.AbsPath); err == nil {
		return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "目标文件已存在，请先处理冲突")
	}

	opID := model.NewUUID()
	stageAudio := filepath.Join(filepath.Dir(dstVal.AbsPath), ".melokeep-dlstage-"+opID+filepath.Ext(dstVal.AbsPath))
	defer os.Remove(stageAudio)

	afterHash, sizeBytes, err := CopyFileDurable(req.SourcePath, stageAudio)
	if err != nil {
		return OperationResult{}, err
	}
	if req.Mutator != nil {
		extraSidecars, mErr := req.Mutator(ctx, stageAudio)
		if mErr != nil {
			return OperationResult{}, mErr
		}
		dp.SidecarOutputs = append(dp.SidecarOutputs, extraSidecars...)
		afterHash, sizeBytes, err = HashFileSHA256(stageAudio)
		if err != nil {
			return OperationResult{}, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "校验暂存音频失败", err)
		}
	}
	stageIdent, _ := StatIdentity(stageAudio)

	specs := []store.OperationFileSpec{
		{
			Ordinal:       0,
			Role:          "audio",
			SourceRefJSON: "{}",
			TargetRefJSON: marshalRef(dstVal.Root.ID, dstVal.RelPath),
			StageRefJSON:  marshalRelRef(dstVal.Root.ID, dstVal.Root.CanonicalPath, stageAudio),
			AfterHash:     afterHash,
			Phase:         "prepared",
			IdentityJSON:  marshalPair(FileIdentityPair{Stage: stageIdent}),
		},
	}

	type dlSidecar struct {
		ord        int
		dst        string
		stage      string
		stageIdent FileIdentity
		hash       string
		role       string
		rel        string
	}
	var sidecars []dlSidecar
	for i, sc := range dp.SidecarOutputs {
		scVal, vErr := ValidatePath(roots, sc.TargetPath, ValidateOptions{MustExist: false})
		if vErr != nil {
			return OperationResult{}, vErr
		}
		sPath := filepath.Join(filepath.Dir(scVal.AbsPath), ".melokeep-dlstage-"+opID+"-"+fmt.Sprintf("%d", i+1)+filepath.Ext(scVal.AbsPath))
		sHash, wErr := WriteFileDurable(sPath, sc.Data)
		if wErr != nil {
			_ = os.Remove(sPath)
			return OperationResult{}, wErr
		}
		defer os.Remove(sPath)
		scStageIdent, _ := StatIdentity(sPath)
		specs = append(specs, store.OperationFileSpec{
			Ordinal:       i + 1,
			Role:          sc.Role,
			SourceRefJSON: "{}",
			TargetRefJSON: marshalRef(scVal.Root.ID, scVal.RelPath),
			StageRefJSON:  marshalRelRef(scVal.Root.ID, scVal.Root.CanonicalPath, sPath),
			AfterHash:     sHash,
			Phase:         "prepared",
			IdentityJSON:  marshalPair(FileIdentityPair{Stage: scStageIdent}),
		})
		sidecars = append(sidecars, dlSidecar{ord: i + 1, dst: scVal.AbsPath, stage: sPath, stageIdent: scStageIdent, hash: sHash, role: sc.Role, rel: scVal.RelPath})
	}

	if _, err := c.store.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation: model.FileOperation{ID: opID, Kind: "download_publish", State: "publishing"},
		Files:     specs,
	}); err != nil {
		return OperationResult{}, err
	}
	if err := callHook(req.HookAfterPhase, "publishing"); err != nil {
		return OperationResult{}, err
	}

	if err := LinkNoReplace(stageAudio, dstVal.AbsPath); err != nil {
		_ = c.rollbackByID(ctx, opID)
		return OperationResult{}, err
	}
	tgtIdent, _ := StatIdentity(dstVal.AbsPath)
	_ = c.store.RecordOperationFilePhase(ctx, opID, 0, "published", afterHash, marshalPair(FileIdentityPair{Stage: stageIdent, Target: tgtIdent}))
	for _, sc := range sidecars {
		if err := LinkNoReplace(sc.stage, sc.dst); err != nil {
			_ = c.rollbackByID(ctx, opID)
			return OperationResult{}, err
		}
		scIdent, _ := StatIdentity(sc.dst)
		_ = c.store.RecordOperationFilePhase(ctx, opID, sc.ord, "published", sc.hash, marshalPair(FileIdentityPair{Stage: sc.stageIdent, Target: scIdent}))
	}
	if err := callHook(req.HookAfterPhase, "file_published"); err != nil {
		return OperationResult{}, err
	}

	st, _ := os.Lstat(dstVal.AbsPath)
	var mtimeNS int64
	if st != nil {
		mtimeNS = st.ModTime().UnixNano()
	}
	assetID := model.NewUUID()
	var lyricsRel, coverRel string
	for _, sc := range sidecars {
		if sc.role == "sidecar_lrc" {
			lyricsRel = sc.rel
		} else if sc.role == "sidecar_cover" {
			coverRel = sc.rel
		}
	}
	originBytes, _ := json.Marshal(map[string]any{
		"kind":        "download",
		"download_id": dp.DownloadID,
		"public_id":   dp.PublicID,
		"source":      dp.Source,
		"source_id":   dp.SourceID,
	})
	_, err = c.store.CommitPublishedOutputs(ctx, store.CommitPublishedOutputsParams{
		DownloadID:       dp.DownloadID,
		ExpectedRevision: dp.ExpectedRevision,
		FileToken:        "asset:" + assetID,
		FileOperationID:  opID,
		BytesDone:        sizeBytes,
		BytesTotal:       &sizeBytes,
		AutoOrganize:     dp.AutoOrganize,
		Outputs: []store.PublishedOutputSpec{
			{
				Asset: store.AssetRecord{
					ID:         assetID,
					RootID:     dstVal.Root.ID,
					RelPath:    dstVal.RelPath,
					PathKey:    dstVal.PathKey,
					Folder:     dstVal.Folder,
					Name:       dstVal.Name,
					Status:     "active",
					Editable:   req.Editable,
					Size:       sizeBytes,
					MTimeNS:    mtimeNS,
					Title:      req.NewTitle,
					Artist:     req.NewArtist,
					Album:      req.NewAlbum,
					DurationMS: req.NewDurationMS,
					Metadata:   withRefreshedCoverMetadata(dstVal.AbsPath, req.NewMetadata, nil),
					OriginJSON: string(originBytes),
				},
				Role:       "primary",
				LyricsPath: lyricsRel,
				CoverPath:  coverRel,
			},
		},
	})
	if err != nil {
		_ = c.rollbackByID(ctx, opID)
		return OperationResult{}, err
	}
	if err := callHook(req.HookAfterPhase, "committed"); err != nil {
		return OperationResult{}, err
	}
	_ = os.Remove(stageAudio)
	_ = os.Remove(req.SourcePath)
	for _, sc := range sidecars {
		_ = os.Remove(sc.stage)
	}
	_ = c.store.SetFileOperationState(ctx, opID, "cleaned", "{}")

	return OperationResult{
		OperationID: opID,
		TargetPath:  dstVal.AbsPath,
		State:       "cleaned",
		Asset: &store.AssetRecord{
			ID:      assetID,
			RootID:  dstVal.Root.ID,
			RelPath: dstVal.RelPath,
			PathKey: dstVal.PathKey,
		},
	}, nil
}

func (c *Coordinator) executeDerive(ctx context.Context, req OperationRequest) (OperationResult, error) {
	if req.AssetID != "" {
		release, err := c.locks.AcquireRead(req.AssetID)
		if err != nil {
			return OperationResult{}, err
		}
		defer release()
	}
	roots := c.Roots()
	srcVal, err := ValidatePath(roots, req.SourcePath, ValidateOptions{MustExist: true, AudioOnly: true})
	if err != nil {
		return OperationResult{}, err
	}
	dstVal, err := ValidatePath(roots, req.TargetPath, ValidateOptions{MustExist: false})
	if err != nil {
		return OperationResult{}, err
	}
	if _, err := os.Lstat(dstVal.AbsPath); err == nil {
		return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "派生目标文件已存在")
	}

	opID := model.NewUUID()
	stagePath := filepath.Join(filepath.Dir(dstVal.AbsPath), ".melokeep-derive-"+opID+filepath.Ext(dstVal.AbsPath))
	defer os.Remove(stagePath)

	if _, err := c.store.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation: model.FileOperation{ID: opID, Kind: "derive", State: "planned"},
		JobID:     req.JobID,
		AssetID:   req.AssetID,
		Files: []store.OperationFileSpec{
			{
				Ordinal:       0,
				Role:          "derived",
				SourceRefJSON: marshalRef(srcVal.Root.ID, srcVal.RelPath),
				TargetRefJSON: marshalRef(dstVal.Root.ID, dstVal.RelPath),
				StageRefJSON:  marshalRelRef(dstVal.Root.ID, dstVal.Root.CanonicalPath, stagePath),
				Phase:         "planned",
			},
		},
	}); err != nil {
		return OperationResult{}, err
	}

	if req.Mutator == nil {
		return OperationResult{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "派生操作缺少生成处理器")
	}
	if _, err := req.Mutator(ctx, stagePath); err != nil {
		_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(err.Error()))
		return OperationResult{}, err
	}
	afterHash, sizeBytes, err := HashFileSHA256(stagePath)
	if err != nil {
		_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(err.Error()))
		return OperationResult{}, err
	}
	sIdent, _ := StatIdentity(stagePath)
	_ = c.store.RecordOperationFilePhase(ctx, opID, 0, "prepared", afterHash, marshalPair(FileIdentityPair{Stage: sIdent}))
	_ = c.store.SetFileOperationState(ctx, opID, "publishing", "{}")

	if err := LinkNoReplace(stagePath, dstVal.AbsPath); err != nil {
		_ = c.rollbackByID(ctx, opID)
		return OperationResult{}, err
	}
	tIdent, _ := StatIdentity(dstVal.AbsPath)
	_ = c.store.RecordOperationFilePhase(ctx, opID, 0, "published", afterHash, marshalPair(FileIdentityPair{Stage: sIdent, Target: tIdent}))

	var newAsset *store.AssetRecord
	if AudioExtensions[strings.ToLower(filepath.Ext(dstVal.AbsPath))] {
		st, _ := os.Lstat(dstVal.AbsPath)
		var mtimeNS int64
		if st != nil {
			mtimeNS = st.ModTime().UnixNano()
		}
		durMS := req.NewDurationMS
		if durMS <= 0 {
			if td, ok := media.ProbeBasicTags(dstVal.AbsPath); ok && td.Duration > 0 {
				durMS = int64(td.Duration * 1000)
			}
		}
		meta := withRefreshedCoverMetadata(dstVal.AbsPath, req.NewMetadata, nil)
		created, upErr := c.store.UpsertAsset(ctx, store.AssetRecord{
			RootID:     dstVal.Root.ID,
			RelPath:    dstVal.RelPath,
			PathKey:    dstVal.PathKey,
			Folder:     dstVal.Folder,
			Name:       dstVal.Name,
			Status:     "active",
			Editable:   req.Editable,
			Size:       sizeBytes,
			MTimeNS:    mtimeNS,
			Title:      req.NewTitle,
			Artist:     req.NewArtist,
			Album:      req.NewAlbum,
			DurationMS: durMS,
			Metadata:   meta,
		}, 0, true)
		if upErr != nil {
			_ = c.rollbackByID(ctx, opID)
			return OperationResult{}, upErr
		}
		if created.ID != "" {
			_ = c.store.PutPublicRef(ctx, model.PublicRef{
				PublicID: "asset:" + created.ID,
				Kind:     "asset",
				TargetID: created.ID,
				Source:   "local",
				SourceID: created.ID,
			}, "")
		}
		newAsset = &created
	}
	if err := c.store.CommitFileOperation(ctx, store.CommitFileOperationParams{
		OperationID: opID,
		JobID:       req.JobID,
		ItemID:      req.ItemID,
		ItemStatus:  req.ItemStatus,
		JobStatus:   req.JobStatus,
	}); err != nil {
		return OperationResult{}, err
	}
	_ = os.Remove(stagePath)
	_ = c.store.SetFileOperationState(ctx, opID, "cleaned", "{}")
	return OperationResult{OperationID: opID, Asset: newAsset, TargetPath: dstVal.AbsPath, State: "cleaned"}, nil
}

func (c *Coordinator) executeDelete(ctx context.Context, req OperationRequest) (OperationResult, error) {
	if req.AssetID != "" {
		release, err := c.locks.AcquireWrite(ctx, req.AssetID)
		if err != nil {
			return OperationResult{}, err
		}
		defer release()
	}
	roots := c.Roots()
	srcVal, err := ValidatePath(roots, req.SourcePath, ValidateOptions{MustExist: true, AudioOnly: true})
	if err != nil {
		return OperationResult{}, err
	}
	srcHash, _, err := HashFileSHA256(srcVal.AbsPath)
	if err != nil {
		return OperationResult{}, err
	}
	srcIdent, err := StatIdentity(srcVal.AbsPath)
	if err != nil {
		return OperationResult{}, err
	}
	sidecars := DiscoverSidecars(srcVal.AbsPath)

	opID := model.NewUUID()
	if _, err := c.store.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation: model.FileOperation{ID: opID, Kind: "delete", State: "planned"},
		AssetID:   req.AssetID,
		Files: []store.OperationFileSpec{
			{
				Ordinal:       0,
				Role:          "audio",
				SourceRefJSON: marshalRef(srcVal.Root.ID, srcVal.RelPath),
				BackupRefJSON: "{}",
				BeforeHash:    srcHash,
				Phase:         "planned",
				IdentityJSON:  marshalPair(FileIdentityPair{Source: srcIdent}),
			},
		},
	}); err != nil {
		return OperationResult{}, err
	}
	_ = c.store.SetFileOperationState(ctx, opID, "publishing", "{}")
	if err := os.Remove(srcVal.AbsPath); err != nil {
		_ = c.store.SetFileOperationState(ctx, opID, "rolled_back", errJSON(err.Error()))
		return OperationResult{}, model.WrapError(model.ErrConflict, http.StatusConflict, "删除文件失败（可能正在被占用）", err)
	}
	if len(sidecars) > 0 && !hasSiblingAudioWithSameStem(srcVal.AbsPath, srcVal.Root.CaseMode == "insensitive") {
		for _, sc := range sidecars {
			_ = os.Remove(sc)
		}
	}
	if req.AssetID != "" {
		if err := c.store.SoftDeleteAsset(ctx, req.AssetID); err != nil {
			return OperationResult{}, err
		}
	}
	_ = c.store.CommitFileOperation(ctx, store.CommitFileOperationParams{OperationID: opID})
	SafeRemoveEmptyParents(filepath.Dir(srcVal.AbsPath), roots)
	_ = c.store.SetFileOperationState(ctx, opID, "cleaned", "{}")
	return OperationResult{OperationID: opID, State: "cleaned"}, nil
}

func hasSiblingAudioWithSameStem(deletedAudioPath string, caseInsensitive bool) bool {
	dir := filepath.Dir(deletedAudioPath)
	base := filepath.Base(deletedAudioPath)
	targetStem := strings.TrimSuffix(base, filepath.Ext(base))
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	for _, ent := range entries {
		if ent.IsDir() {
			continue
		}
		name := ent.Name()
		ext := strings.ToLower(filepath.Ext(name))
		if !AudioExtensions[ext] {
			continue
		}
		if caseInsensitive {
			if strings.EqualFold(name, base) {
				continue
			}
		} else if name == base {
			continue
		}
		stem := strings.TrimSuffix(name, filepath.Ext(name))
		if caseInsensitive {
			if strings.EqualFold(stem, targetStem) {
				return true
			}
		} else if stem == targetStem {
			return true
		}
	}
	return false
}

func (c *Coordinator) addOperationFile(ctx context.Context, opID string, f store.OperationFileSpec) error {
	return c.store.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO operation_files(
				operation_id, ordinal, role, source_ref_json, target_ref_json, stage_ref_json, backup_ref_json,
				before_hash, after_hash, phase, identity_json
			) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, opID, f.Ordinal, f.Role, f.SourceRefJSON, f.TargetRefJSON, f.StageRefJSON, f.BackupRefJSON,
			f.BeforeHash, f.AfterHash, f.Phase, f.IdentityJSON)
		return err
	})
}

func marshalRef(rootID, relPath string) string {
	b, _ := json.Marshal(FileRef{RootID: rootID, RelPath: filepath.ToSlash(relPath)})
	return string(b)
}

func marshalRelRef(rootID, rootCanon, absPath string) string {
	rel, err := filepath.Rel(rootCanon, absPath)
	if err != nil {
		return "{}"
	}
	return marshalRef(rootID, filepath.ToSlash(rel))
}

func marshalPair(p FileIdentityPair) string {
	b, _ := json.Marshal(p)
	return string(b)
}

func errJSON(msg string) string {
	b, _ := json.Marshal(map[string]string{"error": msg})
	return string(b)
}

func callHook(fn func(string) error, phase string) error {
	if fn != nil {
		return fn(phase)
	}
	return nil
}

func findSidecarOverride(list []SidecarOutput, dst string) ([]byte, bool) {
	for _, sc := range list {
		if filepath.Clean(sc.TargetPath) == filepath.Clean(dst) {
			return sc.Data, true
		}
	}
	return nil, false
}

func containsCompanionDst(list []companionPlan, dst string) bool {
	for _, item := range list {
		if filepath.Clean(item.dstPath) == filepath.Clean(dst) {
			return true
		}
	}
	return false
}
