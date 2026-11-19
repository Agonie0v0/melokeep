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
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

type RecoveryReport struct {
	RecoveredOps         int
	PausedOps            int
	CleanedCommittedOps  int
	NeedsAttentionOps    int
	PausedDownloads      int
	Details              []string
}

// RecoverOnStartup executes the storage-and-recovery.md §6.3 crash recovery matrix
// before task execution, directory watching, or external messaging starts.
func (c *Coordinator) RecoverOnStartup(ctx context.Context) (RecoveryReport, error) {
	work, err := c.store.LoadRecoveryWork(ctx)
	if err != nil {
		return RecoveryReport{}, err
	}

	var report RecoveryReport
	for _, recOp := range work.FileOperations {
		switch recOp.Operation.State {
		case "planned":
			st, msg := c.recoverPlanned(ctx, recOp)
			report.RecoveredOps++
			if st == "rolled_back" || st == "paused" {
				report.PausedOps++
			} else if st == "needs_attention" {
				report.NeedsAttentionOps++
			}
			if msg != "" {
				report.Details = append(report.Details, msg)
			}
		case "prepared":
			st, msg := c.recoverPrepared(ctx, recOp)
			report.RecoveredOps++
			if st == "rolled_back" || st == "paused" {
				report.PausedOps++
			} else if st == "needs_attention" {
				report.NeedsAttentionOps++
			}
			if msg != "" {
				report.Details = append(report.Details, msg)
			}
		case "publishing", "rolling_back":
			st, msg := c.rollbackOperation(ctx, recOp)
			report.RecoveredOps++
			if st == "rolled_back" || st == "paused" || st == "restored" {
				report.PausedOps++
			} else if st == "needs_attention" {
				report.NeedsAttentionOps++
			}
			if msg != "" {
				report.Details = append(report.Details, msg)
			}
		case "committed":
			st, msg := c.recoverCommitted(ctx, recOp)
			report.RecoveredOps++
			if st == "cleaned" {
				report.CleanedCommittedOps++
			} else if st == "needs_attention" {
				report.NeedsAttentionOps++
			}
			if msg != "" {
				report.Details = append(report.Details, msg)
			}
		case "needs_attention":
			report.NeedsAttentionOps++
			report.Details = append(report.Details, fmt.Sprintf("op %s 等待人工处理 (needs_attention)", recOp.Operation.ID))
		}
	}

	for _, dl := range work.InterruptedDownloads {
		if dl.Download.Status == model.DownloadPaused {
			continue
		}
		pauseMsg := "服务重启，任务已安全暂停，可手动继续"
		_, err := c.store.CompareAndSetDownload(ctx, store.CASDownloadParams{
			ID:               dl.Download.ID,
			ExpectedRevision: dl.Download.Revision,
			AllowedStates: []model.DownloadStatus{
				model.DownloadQueued,
				model.DownloadResolving,
				model.DownloadDownloading,
				model.DownloadVerifying,
				model.DownloadPublishing,
				model.DownloadPauseRequested,
				model.DownloadCancelRequested,
			},
			NewState: model.DownloadPaused,
			Detail:   &pauseMsg,
		})
		if err == nil {
			report.PausedDownloads++
		}
	}

	return report, nil
}

func (c *Coordinator) recoverPlanned(ctx context.Context, op store.RecoveryOperation) (string, string) {
	for _, f := range op.Files {
		srcPath, ok := c.resolveRefPath(f.SourceRefJSON, true)
		if ok && f.BeforeHash != "" {
			curHash, _, err := HashFileSHA256(srcPath)
			if err != nil || curHash != f.BeforeHash {
				_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("planned 恢复时源文件已变化或不可读"))
				return "needs_attention", fmt.Sprintf("op %s: planned 源文件已变化，转 needs_attention", op.Operation.ID)
			}
		}
		if stagePath, ok := c.resolveRefPath(f.StageRefJSON, true); ok {
			_ = os.Remove(stagePath)
		}
		if backupPath, ok := c.resolveRefPath(f.BackupRefJSON, true); ok {
			_ = os.Remove(backupPath)
			SafeRemoveEmptyParents(filepath.Dir(backupPath), c.Roots())
		}
	}
	if err := c.store.SetFileOperationState(ctx, op.Operation.ID, "rolled_back", "{}"); err != nil {
		return "needs_attention", fmt.Sprintf("op %s: 更新回滚状态失败: %v", op.Operation.ID, err)
	}
	return "rolled_back", fmt.Sprintf("op %s: planned 已安全清理临时文件并回滚", op.Operation.ID)
}

func (c *Coordinator) recoverPrepared(ctx context.Context, op store.RecoveryOperation) (string, string) {
	for _, f := range op.Files {
		if srcPath, ok := c.resolveRefPath(f.SourceRefJSON, true); ok && f.BeforeHash != "" {
			curHash, _, err := HashFileSHA256(srcPath)
			if err != nil || curHash != f.BeforeHash {
				_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("prepared 恢复时源文件已变化"))
				return "needs_attention", fmt.Sprintf("op %s: prepared 源文件已变化，转 needs_attention", op.Operation.ID)
			}
		}
		if stagePath, ok := c.resolveRefPath(f.StageRefJSON, true); ok {
			_ = os.Remove(stagePath)
		}
	}
	if err := c.store.SetFileOperationState(ctx, op.Operation.ID, "rolled_back", "{}"); err != nil {
		return "needs_attention", fmt.Sprintf("op %s: 更新回滚状态失败: %v", op.Operation.ID, err)
	}
	return "rolled_back", fmt.Sprintf("op %s: prepared 已清理暂存并安全回滚", op.Operation.ID)
}

func (c *Coordinator) recoverCommitted(ctx context.Context, op store.RecoveryOperation) (string, string) {
	for _, f := range op.Files {
		tgtPath, hasTgt := c.resolveRefPath(f.TargetRefJSON, false)
		if !hasTgt {
			continue
		}
		if f.AfterHash != "" {
			h, _, err := HashFileSHA256(tgtPath)
			if err != nil || h != f.AfterHash {
				_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("已提交目标文件缺失或校验不匹配"))
				return "needs_attention", fmt.Sprintf("op %s: committed 目标校验失败，转 needs_attention", op.Operation.ID)
			}
		}
	}
	var warnings []string
	for _, f := range op.Files {
		if stagePath, ok := c.resolveRefPath(f.StageRefJSON, true); ok {
			_ = os.Remove(stagePath)
		}
		srcPath, hasSrc := c.resolveRefPath(f.SourceRefJSON, false)
		tgtPath, hasTgt := c.resolveRefPath(f.TargetRefJSON, false)
		if hasSrc && hasTgt && srcPath != tgtPath && f.BeforeHash != "" {
			if h, _, err := HashFileSHA256(srcPath); err == nil && h == f.BeforeHash {
				if rmErr := os.Remove(srcPath); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
					warnings = append(warnings, rmErr.Error())
				}
				SafeRemoveEmptyParents(filepath.Dir(srcPath), c.Roots())
			}
		}
	}
	if len(warnings) > 0 {
		_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "committed", errJSON(fmt.Sprintf("清理警告: %v", warnings)))
		return "committed", fmt.Sprintf("op %s: committed 前滚清理存在警告", op.Operation.ID)
	}
	_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "cleaned", "{}")
	return "cleaned", fmt.Sprintf("op %s: committed 已前滚清理完成", op.Operation.ID)
}

func (c *Coordinator) rollbackByID(ctx context.Context, opID string) error {
	op, ok, err := c.loadOperationByID(ctx, opID)
	if err != nil || !ok {
		return err
	}
	st, _ := c.rollbackOperation(ctx, op)
	if st == "needs_attention" {
		return model.NewError(model.ErrConflict, http.StatusConflict, "文件回滚遇到冲突，已标记为 needs_attention 保护现场")
	}
	return nil
}

func (c *Coordinator) rollbackOperation(ctx context.Context, op store.RecoveryOperation) (string, string) {
	_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "rolling_back", "{}")

	if op.Operation.Kind == "download_publish" || op.Operation.Kind == "derive" {
		return c.rollbackGenerated(ctx, op)
	}

	// Step 1: Verify backups and ownership before making any changes.
	for _, f := range op.Files {
		srcPath, hasSrc := c.resolveRefPath(f.SourceRefJSON, false)
		tgtPath, hasTgt := c.resolveRefPath(f.TargetRefJSON, false)
		stagePath, _ := c.resolveRefPath(f.StageRefJSON, true)
		backupPath, hasBackup := c.resolveRefPath(f.BackupRefJSON, true)

		var pair FileIdentityPair
		_ = json.Unmarshal([]byte(f.IdentityJSON), &pair)

		if hasSrc && f.BeforeHash != "" {
			if !hasBackup {
				if hasTgt && srcPath != tgtPath && fileHashEquals(srcPath, f.BeforeHash) {
					goto checkTarget
				}
				_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("原件备份缺失，已保留当前现场"))
				return "needs_attention", fmt.Sprintf("op %s: 备份缺失，转 needs_attention", op.Operation.ID)
			}
			bHash, _, bErr := HashFileSHA256(backupPath)
			if bErr != nil || bHash != f.BeforeHash {
				_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("原件备份校验失败，已保留当前现场"))
				return "needs_attention", fmt.Sprintf("op %s: 备份损坏，转 needs_attention", op.Operation.ID)
			}
		}

	checkTarget:
		if hasTgt {
			if _, stErr := os.Lstat(tgtPath); stErr == nil {
				tgtIdent, _ := StatIdentity(tgtPath)
				sameAsStage := stagePath != "" && SameIdentity(pair.Stage, tgtIdent)
				samePath := hasSrc && srcPath == tgtPath
				expectedTargetHash := f.AfterHash
				if op.Operation.Kind == "restore" && samePath && f.Phase == "published" {
					expectedTargetHash = f.BeforeHash
				}
				owned := f.Phase == "published" || sameAsStage || (samePath && expectedTargetHash != "" && fileHashEquals(tgtPath, expectedTargetHash))
				if !samePath && !owned {
					// Even if hash matches, do not delete an unowned target path.
					_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("目标路径存在无法确认归属的文件，已保留双方"))
					return "needs_attention", fmt.Sprintf("op %s: 目标归属不明，转 needs_attention", op.Operation.ID)
				}
				if owned && expectedTargetHash != "" && !fileHashEquals(tgtPath, expectedTargetHash) {
					_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("目标文件在发布后被外部修改，已保留现场"))
					return "needs_attention", fmt.Sprintf("op %s: 目标被外部修改，转 needs_attention", op.Operation.ID)
				}
				if samePath && !owned && !fileHashEquals(tgtPath, f.BeforeHash) {
					_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("原位文件被外部修改，已保留现场"))
					return "needs_attention", fmt.Sprintf("op %s: 原位文件被外部修改，转 needs_attention", op.Operation.ID)
				}
			}
			if hasSrc && srcPath != tgtPath {
				if _, sErr := os.Lstat(srcPath); sErr == nil && f.BeforeHash != "" && !fileHashEquals(srcPath, f.BeforeHash) {
					_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("原路径已有其他修改后的文件，已保留现场"))
					return "needs_attention", fmt.Sprintf("op %s: 原路径内容不匹配，转 needs_attention", op.Operation.ID)
				}
			}
		}
	}

	// Step 2: Restore source files safely from verified backups and remove owned published targets.
	for _, f := range op.Files {
		srcPath, hasSrc := c.resolveRefPath(f.SourceRefJSON, false)
		tgtPath, hasTgt := c.resolveRefPath(f.TargetRefJSON, false)
		stagePath, hasStage := c.resolveRefPath(f.StageRefJSON, true)
		backupPath, hasBackup := c.resolveRefPath(f.BackupRefJSON, true)

		var pair FileIdentityPair
		_ = json.Unmarshal([]byte(f.IdentityJSON), &pair)

		if hasSrc && hasBackup {
			restoreTmp := filepath.Join(filepath.Dir(srcPath), ".melokeep-restore-"+op.Operation.ID+"-"+fmt.Sprintf("%d", f.Ordinal)+filepath.Ext(srcPath))
			if _, _, cErr := CopyFileDurable(backupPath, restoreTmp); cErr != nil {
				_ = os.Remove(restoreTmp)
				_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("从备份恢复失败: "+cErr.Error()))
				return "needs_attention", fmt.Sprintf("op %s: 备份复制失败", op.Operation.ID)
			}
			if rErr := ReplaceSamePath(ctx, restoreTmp, srcPath, c.maxRetries); rErr != nil {
				_ = os.Remove(restoreTmp)
				_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("替换恢复原文件失败: "+rErr.Error()))
				return "needs_attention", fmt.Sprintf("op %s: 原文件被占用导致回滚停顿", op.Operation.ID)
			}
		}

		if hasTgt && (!hasSrc || srcPath != tgtPath) {
			if _, stErr := os.Lstat(tgtPath); stErr == nil {
				tgtIdent, _ := StatIdentity(tgtPath)
				if f.Phase == "published" || (hasStage && SameIdentity(pair.Stage, tgtIdent)) {
					_ = os.Remove(tgtPath)
					SafeRemoveEmptyParents(filepath.Dir(tgtPath), c.Roots())
				}
			}
		}
		if hasStage {
			_ = os.Remove(stagePath)
		}
	}

	if err := c.store.SetFileOperationState(ctx, op.Operation.ID, "rolled_back", "{}"); err != nil {
		return "needs_attention", fmt.Sprintf("op %s: 更新回滚状态失败: %v", op.Operation.ID, err)
	}
	return "rolled_back", fmt.Sprintf("op %s: 已从备份安全回滚", op.Operation.ID)
}

func (c *Coordinator) rollbackGenerated(ctx context.Context, op store.RecoveryOperation) (string, string) {
	for _, f := range op.Files {
		tgtPath, hasTgt := c.resolveRefPath(f.TargetRefJSON, false)
		_, hasStage := c.resolveRefPath(f.StageRefJSON, true)
		var pair FileIdentityPair
		_ = json.Unmarshal([]byte(f.IdentityJSON), &pair)
		if hasTgt {
			if _, stErr := os.Lstat(tgtPath); stErr == nil {
				tgtIdent, _ := StatIdentity(tgtPath)
				owned := f.Phase == "published" || (hasStage && SameIdentity(pair.Stage, tgtIdent))
				if !owned {
					_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("未确认归属的目标文件已存在"))
					return "needs_attention", fmt.Sprintf("op %s: 生成目标归属不明", op.Operation.ID)
				}
				if f.AfterHash != "" && !fileHashEquals(tgtPath, f.AfterHash) {
					_ = c.store.SetFileOperationState(ctx, op.Operation.ID, "needs_attention", errJSON("生成文件已被外部修改"))
					return "needs_attention", fmt.Sprintf("op %s: 生成目标被修改", op.Operation.ID)
				}
			}
		}
	}
	for _, f := range op.Files {
		tgtPath, hasTgt := c.resolveRefPath(f.TargetRefJSON, false)
		stagePath, hasStage := c.resolveRefPath(f.StageRefJSON, true)
		var pair FileIdentityPair
		_ = json.Unmarshal([]byte(f.IdentityJSON), &pair)
		if hasTgt {
			if _, stErr := os.Lstat(tgtPath); stErr == nil {
				tgtIdent, _ := StatIdentity(tgtPath)
				if f.Phase == "published" || (hasStage && SameIdentity(pair.Stage, tgtIdent)) {
					_ = os.Remove(tgtPath)
				}
			}
		}
		if hasStage {
			_ = os.Remove(stagePath)
		}
	}
	if err := c.store.SetFileOperationState(ctx, op.Operation.ID, "rolled_back", "{}"); err != nil {
		return "needs_attention", fmt.Sprintf("op %s: 更新回滚状态失败: %v", op.Operation.ID, err)
	}
	return "rolled_back", fmt.Sprintf("op %s: 已清理未提交的生成文件", op.Operation.ID)
}

// Restore creates a new restore FileOperation reverting an earlier committed/cleaned operation
// only if current targets still match after_hash and backups still match before_hash.
func (c *Coordinator) Restore(ctx context.Context, prevOpID string) (OperationResult, error) {
	prev, ok, err := c.loadOperationByID(ctx, prevOpID)
	if err != nil {
		return OperationResult{}, err
	}
	if !ok {
		return OperationResult{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "历史操作记录不存在")
	}
	if prev.Operation.State != "cleaned" && prev.Operation.State != "committed" {
		return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "仅支持恢复已完成的文件操作")
	}
	if prev.Operation.Kind != "edit" && prev.Operation.Kind != "move" {
		return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "仅支持恢复编辑或移动类型的文件操作")
	}
	if prev.AssetID != "" {
		release, err := c.locks.AcquireWrite(ctx, prev.AssetID)
		if err != nil {
			return OperationResult{}, err
		}
		defer release()
	}

	for _, f := range prev.Files {
		_, hasSrc := c.resolveRefPath(f.SourceRefJSON, false)
		tgtPath, hasTgt := c.resolveRefPath(f.TargetRefJSON, false)
		backupPath, hasBackup := c.resolveRefPath(f.BackupRefJSON, true)
		var pair FileIdentityPair
		_ = json.Unmarshal([]byte(f.IdentityJSON), &pair)
		if hasSrc || hasBackup {
			if !hasBackup || f.BeforeHash == "" {
				return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "原件备份已不存在或已过期，无法恢复")
			}
			if !fileHashEquals(backupPath, f.BeforeHash) {
				return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "原件备份校验失败，已取消恢复")
			}
		}
		if hasTgt {
			if f.AfterHash != "" && !fileHashEquals(tgtPath, f.AfterHash) {
				return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "文件在整理后已被外部修改，请先处理后续修改")
			}
			if pair.Target.Ino != 0 {
				tgtIdent, idErr := StatIdentity(tgtPath)
				if idErr != nil || !SameIdentity(pair.Target, tgtIdent) {
					return OperationResult{}, model.NewError(model.ErrConflict, http.StatusConflict, "目标文件身份与原操作记录不匹配，已取消恢复")
				}
			}
		}
	}

	restoreID := model.NewUUID()
	restoreFiles := make([]store.OperationFileSpec, 0, len(prev.Files))
	for _, f := range prev.Files {
		rf := f
		rf.Phase = "planned"
		restoreFiles = append(restoreFiles, rf)
	}
	if _, err := c.store.CreateFileOperation(ctx, store.CreateFileOperationParams{
		Operation: model.FileOperation{ID: restoreID, Kind: "restore", State: "publishing"},
		AssetID:   prev.AssetID,
		Files:     restoreFiles,
	}); err != nil {
		return OperationResult{}, err
	}

	var restoredAudioPath string
	for _, f := range prev.Files {
		srcPath, hasSrc := c.resolveRefPath(f.SourceRefJSON, false)
		tgtPath, hasTgt := c.resolveRefPath(f.TargetRefJSON, false)
		backupPath, _ := c.resolveRefPath(f.BackupRefJSON, true)
		if hasSrc {
			restoreTmp := filepath.Join(filepath.Dir(srcPath), ".melokeep-restore-"+restoreID+"-"+fmt.Sprintf("%d", f.Ordinal)+filepath.Ext(srcPath))
			if _, _, cErr := CopyFileDurable(backupPath, restoreTmp); cErr != nil {
				_ = os.Remove(restoreTmp)
				_ = c.store.SetFileOperationState(ctx, restoreID, "needs_attention", errJSON(cErr.Error()))
				return OperationResult{}, cErr
			}
			if rErr := ReplaceSamePath(ctx, restoreTmp, srcPath, c.maxRetries); rErr != nil {
				_ = os.Remove(restoreTmp)
				_ = c.store.SetFileOperationState(ctx, restoreID, "needs_attention", errJSON(rErr.Error()))
				return OperationResult{}, rErr
			}
			restIdent, _ := StatIdentity(srcPath)
			_ = c.store.RecordOperationFilePhase(ctx, restoreID, f.Ordinal, "published", f.BeforeHash, marshalPair(FileIdentityPair{Target: restIdent}))
			if f.Ordinal == 0 {
				restoredAudioPath = srcPath
			}
			if hasTgt && tgtPath != srcPath {
				if rmErr := os.Remove(tgtPath); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
					_ = c.store.SetFileOperationState(ctx, restoreID, "needs_attention", errJSON(rmErr.Error()))
					return OperationResult{}, model.WrapError(model.ErrConflict, http.StatusConflict, "清理已移动目标文件失败（可能正在被占用）", rmErr)
				}
				SafeRemoveEmptyParents(filepath.Dir(tgtPath), c.Roots())
			}
		} else if hasTgt {
			if rmErr := os.Remove(tgtPath); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
				_ = c.store.SetFileOperationState(ctx, restoreID, "needs_attention", errJSON(rmErr.Error()))
				return OperationResult{}, model.WrapError(model.ErrConflict, http.StatusConflict, "清理新增附属文件失败（可能正在被占用）", rmErr)
			}
			_ = c.store.RecordOperationFilePhase(ctx, restoreID, f.Ordinal, "published", "", "{}")
			SafeRemoveEmptyParents(filepath.Dir(tgtPath), c.Roots())
		}
	}

	var updatedAsset *store.AssetRecord
	var expectedRev int64
	if prev.BeforeAssetJSON != "" && prev.BeforeAssetJSON != "{}" {
		var oldAsset store.AssetRecord
		if jsonErr := json.Unmarshal([]byte(prev.BeforeAssetJSON), &oldAsset); jsonErr == nil && oldAsset.ID != "" {
			if curAsset, ok, _ := c.store.GetAsset(ctx, oldAsset.ID); ok {
				oldAsset.Revision = curAsset.Revision
				updatedAsset = &oldAsset
				expectedRev = curAsset.Revision
			}
		}
	}
	if err := c.store.CommitFileOperation(ctx, store.CommitFileOperationParams{
		OperationID:      restoreID,
		UpdatedAsset:     updatedAsset,
		ExpectedRevision: expectedRev,
	}); err != nil {
		return OperationResult{}, err
	}
	_ = c.store.SetFileOperationState(ctx, restoreID, "cleaned", "{}")
	return OperationResult{
		OperationID: restoreID,
		Asset:       updatedAsset,
		TargetPath:  restoredAudioPath,
		State:       "cleaned",
	}, nil
}

// ExpireBackups safely deletes expired backup directories strictly inside historyDir and
// legacy .melokeep-history directories, transitioning operations to backup_expired while keeping audit rows.
func (c *Coordinator) ExpireBackups(ctx context.Context, now time.Time) (int, error) {
	nowMS := now.UTC().UnixMilli()
	rows, err := c.store.Reader().QueryContext(ctx, `
		SELECT id FROM file_operations
		WHERE state IN ('cleaned', 'committed') AND expires_at IS NOT NULL AND expires_at < ?
	`, nowMS)
	if err != nil {
		return 0, err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			_ = rows.Close()
			return 0, err
		}
		ids = append(ids, id)
	}
	_ = rows.Close()

	expired := 0
	for _, id := range ids {
		c.removeOpBackupDir(id)
		if err := c.store.SetFileOperationState(ctx, id, "backup_expired", "{}"); err == nil {
			expired++
		}
	}
	c.CleanupEmptyLegacyDirs()
	return expired, nil
}

// PurgeAllBackups removes all retained operation backups (in both historyDir and legacy .melokeep-history)
// and empty legacy temp folders, marking eligible operations as backup_expired.
func (c *Coordinator) PurgeAllBackups(ctx context.Context) (int, int64, error) {
	var freed int64
	if historyDir := c.HistoryDir(); historyDir != "" {
		freed += removeBackupBaseEntries(historyDir)
	}
	for _, r := range c.Roots() {
		freed += removeBackupBaseEntries(filepath.Join(r.CanonicalPath, ".melokeep-history"))
	}
	c.CleanupEmptyLegacyDirs()

	rows, err := c.store.Reader().QueryContext(ctx, `
		SELECT id FROM file_operations
		WHERE state IN ('cleaned', 'committed') AND expires_at IS NOT NULL
	`)
	if err != nil {
		return 0, freed, err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err == nil {
			ids = append(ids, id)
		}
	}
	_ = rows.Close()

	marked := 0
	for _, id := range ids {
		if err := c.store.SetFileOperationState(ctx, id, "backup_expired", "{}"); err == nil {
			marked++
		}
	}
	return marked, freed, nil
}

// CleanupEmptyLegacyDirs removes empty legacy internal directories (.melokeep-history, .processing, .partial)
// inside managed roots without touching non-empty folders.
func (c *Coordinator) CleanupEmptyLegacyDirs() {
	for _, r := range c.Roots() {
		for _, name := range []string{".melokeep-history", ".processing", ".partial"} {
			pruneEmptyTree(filepath.Join(r.CanonicalPath, name))
		}
	}
}

func (c *Coordinator) removeOpBackupDir(opID string) {
	if opID == "" || strings.ContainsAny(opID, `/\`) {
		return
	}
	if historyDir := c.HistoryDir(); historyDir != "" {
		removeDirIfNotLink(filepath.Join(historyDir, opID))
	}
	for _, r := range c.Roots() {
		removeDirIfNotLink(filepath.Join(r.CanonicalPath, ".melokeep-history", opID))
	}
}

func removeDirIfNotLink(dir string) {
	if st, err := os.Lstat(dir); err == nil && st.IsDir() {
		if isLink, lerr := IsReparseOrSymlink(dir, st); lerr == nil && !isLink {
			_ = os.RemoveAll(dir)
		}
	}
}

func removeBackupBaseEntries(base string) int64 {
	st, err := os.Lstat(base)
	if err != nil || !st.IsDir() {
		return 0
	}
	if isLink, lerr := IsReparseOrSymlink(base, st); lerr != nil || isLink {
		return 0
	}
	entries, err := os.ReadDir(base)
	if err != nil {
		return 0
	}
	var freed int64
	for _, ent := range entries {
		p := filepath.Join(base, ent.Name())
		est, sErr := os.Lstat(p)
		if sErr != nil {
			continue
		}
		if isLink, lerr := IsReparseOrSymlink(p, est); lerr == nil && isLink {
			continue
		}
		if ent.IsDir() {
			freed += dirSizeBytes(p)
			_ = os.RemoveAll(p)
		} else {
			freed += est.Size()
			_ = os.Remove(p)
		}
	}
	return freed
}

func dirSizeBytes(root string) int64 {
	var total int64
	_ = filepath.WalkDir(root, func(_ string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if info, iErr := d.Info(); iErr == nil {
			total += info.Size()
		}
		return nil
	})
	return total
}

func pruneEmptyTree(root string) bool {
	st, err := os.Lstat(root)
	if err != nil || !st.IsDir() {
		return false
	}
	if isLink, lerr := IsReparseOrSymlink(root, st); lerr != nil || isLink {
		return false
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		return false
	}
	empty := true
	for _, ent := range entries {
		p := filepath.Join(root, ent.Name())
		if ent.IsDir() {
			if !pruneEmptyTree(p) {
				empty = false
			}
		} else {
			empty = false
		}
	}
	if empty {
		return os.Remove(root) == nil
	}
	return false
}

func (c *Coordinator) resolveRefPath(refJSON string, allowInternal bool) (string, bool) {
	if refJSON == "" || refJSON == "{}" {
		return "", false
	}
	var ref FileRef
	if err := json.Unmarshal([]byte(refJSON), &ref); err != nil || ref.RelPath == "" {
		return "", false
	}
	if ref.HistoryRoot {
		historyDir := c.HistoryDir()
		if historyDir == "" {
			return "", false
		}
		cleanRel := filepath.Clean(filepath.FromSlash(ref.RelPath))
		if cleanRel == "." || cleanRel == ".." || strings.HasPrefix(cleanRel, ".."+string(filepath.Separator)) || filepath.IsAbs(cleanRel) {
			return "", false
		}
		candidate := filepath.Join(historyDir, cleanRel)
		relCheck, err := filepath.Rel(historyDir, candidate)
		if err != nil || relCheck == "." || relCheck == ".." || strings.HasPrefix(relCheck, ".."+string(filepath.Separator)) || filepath.IsAbs(relCheck) {
			return "", false
		}
		return candidate, true
	}
	if ref.RootID == "" {
		return "", false
	}
	for _, r := range c.Roots() {
		if r.ID == ref.RootID {
			candidate := filepath.Join(r.CanonicalPath, filepath.FromSlash(ref.RelPath))
			vp, err := ValidatePath([]Root{r}, candidate, ValidateOptions{AllowInternal: allowInternal, MustExist: false})
			if err != nil {
				return "", false
			}
			return vp.AbsPath, true
		}
	}
	return "", false
}

func (c *Coordinator) loadOperationByID(ctx context.Context, opID string) (store.RecoveryOperation, bool, error) {
	var op store.RecoveryOperation
	err := c.store.Reader().QueryRowContext(ctx, `
		SELECT id, COALESCE(job_id,''), COALESCE(asset_id,''), kind, state,
		       before_asset_json, after_asset_json, created_at, updated_at
		FROM file_operations WHERE id = ?
	`, opID).Scan(
		&op.Operation.ID, &op.JobID, &op.AssetID, &op.Operation.Kind, &op.Operation.State,
		&op.BeforeAssetJSON, &op.AfterAssetJSON, &op.Operation.CreatedAt, &op.Operation.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return store.RecoveryOperation{}, false, nil
	}
	if err != nil {
		return store.RecoveryOperation{}, false, err
	}
	fRows, err := c.store.Reader().QueryContext(ctx, `
		SELECT ordinal, role, source_ref_json, target_ref_json, stage_ref_json, backup_ref_json,
		       before_hash, after_hash, phase, identity_json
		FROM operation_files WHERE operation_id = ? ORDER BY ordinal ASC
	`, opID)
	if err != nil {
		return store.RecoveryOperation{}, false, err
	}
	defer fRows.Close()
	for fRows.Next() {
		var f store.OperationFileSpec
		if err := fRows.Scan(
			&f.Ordinal, &f.Role, &f.SourceRefJSON, &f.TargetRefJSON, &f.StageRefJSON, &f.BackupRefJSON,
			&f.BeforeHash, &f.AfterHash, &f.Phase, &f.IdentityJSON,
		); err != nil {
			return store.RecoveryOperation{}, false, err
		}
		op.Files = append(op.Files, f)
	}
	return op, true, fRows.Err()
}

func fileHashEquals(path, expectedHash string) bool {
	if expectedHash == "" {
		return false
	}
	h, _, err := HashFileSHA256(path)
	return err == nil && h == expectedHash
}
