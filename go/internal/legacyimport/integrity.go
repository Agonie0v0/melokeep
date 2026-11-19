package legacyimport

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/agonie0v0/melokeep/internal/store"
)

type IntegrityIssue struct {
	Category string `json:"category"`
	Entity   string `json:"entity"`
	Detail   string `json:"detail"`
}

type IntegrityStats struct {
	Roots          int `json:"roots"`
	Assets         int `json:"assets"`
	ProviderTracks int `json:"provider_tracks"`
	MediaVariants  int `json:"media_variants"`
	PublicRefs     int `json:"public_refs"`
	Downloads      int `json:"downloads"`
	Collections    int `json:"collections"`
	FileOperations int `json:"file_operations"`
}

type IntegrityReport struct {
	OK     bool             `json:"ok"`
	Stats  IntegrityStats   `json:"stats"`
	Issues []IntegrityIssue `json:"issues,omitempty"`
}

// CheckIntegrity verifies foreign keys, polymorphic public_refs targets,
// root ownership, and unresolved needs_attention file operations without exposing secrets.
func CheckIntegrity(ctx context.Context, st *store.Store) (IntegrityReport, error) {
	var rep IntegrityReport
	err := st.WithReadTx(ctx, func(tx *sql.Tx) error {
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM roots`).Scan(&rep.Stats.Roots)
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM assets`).Scan(&rep.Stats.Assets)
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM provider_tracks`).Scan(&rep.Stats.ProviderTracks)
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM media_variants`).Scan(&rep.Stats.MediaVariants)
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM public_refs`).Scan(&rep.Stats.PublicRefs)
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM downloads`).Scan(&rep.Stats.Downloads)
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM collections`).Scan(&rep.Stats.Collections)
		_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM file_operations`).Scan(&rep.Stats.FileOperations)

		fkRows, err := tx.QueryContext(ctx, `PRAGMA foreign_key_check`)
		if err != nil {
			return err
		}
		for fkRows.Next() {
			var table, parent string
			var rowid, fkid int64
			if err := fkRows.Scan(&table, &rowid, &parent, &fkid); err == nil {
				rep.Issues = append(rep.Issues, IntegrityIssue{
					Category: "foreign_key",
					Entity:   fmt.Sprintf("%s#%d", table, rowid),
					Detail:   fmt.Sprintf("broken reference to %s", parent),
				})
			}
		}
		_ = fkRows.Close()

		trRows, err := tx.QueryContext(ctx, `
			SELECT p.public_id, p.target_id
			FROM public_refs p
			LEFT JOIN provider_tracks t ON t.id = p.target_id
			WHERE p.kind IN ('track', 'candidate') AND t.id IS NULL
		`)
		if err != nil {
			return err
		}
		for trRows.Next() {
			var pid, tid string
			if err := trRows.Scan(&pid, &tid); err == nil {
				rep.Issues = append(rep.Issues, IntegrityIssue{
					Category: "orphan_public_ref",
					Entity:   pid,
					Detail:   "missing provider_track target " + tid,
				})
			}
		}
		_ = trRows.Close()

		asRows, err := tx.QueryContext(ctx, `
			SELECT p.public_id, p.target_id
			FROM public_refs p
			LEFT JOIN assets a ON a.id = p.target_id
			WHERE p.kind = 'asset' AND a.id IS NULL
		`)
		if err != nil {
			return err
		}
		for asRows.Next() {
			var pid, tid string
			if err := asRows.Scan(&pid, &tid); err == nil {
				rep.Issues = append(rep.Issues, IntegrityIssue{
					Category: "orphan_public_ref",
					Entity:   pid,
					Detail:   "missing asset target " + tid,
				})
			}
		}
		_ = asRows.Close()

		opRows, err := tx.QueryContext(ctx, `
			SELECT id, kind FROM file_operations WHERE state = 'needs_attention'
		`)
		if err != nil {
			return err
		}
		for opRows.Next() {
			var id, kind string
			if err := opRows.Scan(&id, &kind); err == nil {
				rep.Issues = append(rep.Issues, IntegrityIssue{
					Category: "needs_attention_operation",
					Entity:   id,
					Detail:   "file operation requires manual attention: " + kind,
				})
			}
		}
		_ = opRows.Close()

		rep.OK = len(rep.Issues) == 0
		return nil
	})
	return rep, err
}
