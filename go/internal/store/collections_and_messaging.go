package store

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
)

type CreateCollectionParams struct {
	ID        string
	Name      string
	Kind      string // playlist | favorites | play_queue
	OwnerKey  string
	Source    string
	SourceURL string
	Detail    string
	TaskID    string
}

func (s *Store) CreateCollection(ctx context.Context, p CreateCollectionParams) (model.Collection, error) {
	if p.Name == "" || p.OwnerKey == "" {
		return model.Collection{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "集合名称和所有者不能为空")
	}
	if p.Kind == "" {
		p.Kind = "playlist"
	}
	if p.Source == "" {
		p.Source = "local"
	}
	if p.ID == "" {
		p.ID = model.NewUUID()
	}
	now := time.Now().UTC().UnixMilli()
	var out model.Collection
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO collections(id, name, kind, owner_key, source, source_url, detail, task_id, revision, created_at, updated_at)
			VALUES(?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
		`, p.ID, p.Name, p.Kind, p.OwnerKey, p.Source, p.SourceURL, p.Detail, p.TaskID, now, now)
		if err != nil {
			if isUniqueConstraintErr(err) {
				return model.NewError(model.ErrConflict, http.StatusConflict, "集合已存在")
			}
			return err
		}
		out = model.Collection{
			ID:        p.ID,
			Owner:     p.OwnerKey,
			Name:      p.Name,
			Source:    p.Source,
			SourceURL: p.SourceURL,
			Revision:  "1",
			Detail:    p.Detail,
			TaskID:    p.TaskID,
			CreatedAt: now,
		}
		return nil
	})
	return out, err
}

func (s *Store) GetCollection(ctx context.Context, id string) (model.Collection, []model.CollectionItem, bool, error) {
	var c model.Collection
	var rev int64
	err := s.reader.QueryRowContext(ctx, `
		SELECT id, owner_key, name, source, source_url, revision, detail, task_id, created_at
		FROM collections WHERE id = ?
	`, id).Scan(&c.ID, &c.Owner, &c.Name, &c.Source, &c.SourceURL, &rev, &c.Detail, &c.TaskID, &c.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return model.Collection{}, nil, false, nil
	}
	if err != nil {
		return model.Collection{}, nil, false, err
	}
	c.Revision = strconv.FormatInt(rev, 10)

	rows, err := s.reader.QueryContext(ctx, `
		SELECT collection_id, position, public_ref
		FROM collection_items WHERE collection_id = ? ORDER BY position ASC
	`, id)
	if err != nil {
		return model.Collection{}, nil, false, err
	}
	defer rows.Close()
	var items []model.CollectionItem
	for rows.Next() {
		var it model.CollectionItem
		if err := rows.Scan(&it.CollectionID, &it.Position, &it.PublicID); err != nil {
			return model.Collection{}, nil, false, err
		}
		items = append(items, it)
	}
	return c, items, true, rows.Err()
}

func (s *Store) ListCollections(ctx context.Context, ownerKey, kind string) ([]model.Collection, error) {
	rows, err := s.reader.QueryContext(ctx, `
		SELECT id, owner_key, name, source, source_url, revision, detail, task_id, created_at
		FROM collections
		WHERE (? = '' OR owner_key = ?) AND (? = '' OR kind = ?)
		ORDER BY created_at DESC, id DESC
	`, ownerKey, ownerKey, kind, kind)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.Collection
	for rows.Next() {
		var c model.Collection
		var rev int64
		if err := rows.Scan(&c.ID, &c.Owner, &c.Name, &c.Source, &c.SourceURL, &rev, &c.Detail, &c.TaskID, &c.CreatedAt); err != nil {
			return nil, err
		}
		c.Revision = strconv.FormatInt(rev, 10)
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Store) RenameCollection(ctx context.Context, id, newName string, expectedRev int64) (int64, error) {
	if newName == "" {
		return 0, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "集合名称不能为空")
	}
	now := time.Now().UTC().UnixMilli()
	var newRev int64
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		var res sql.Result
		var err error
		if expectedRev > 0 {
			res, err = tx.ExecContext(ctx, `
				UPDATE collections SET name = ?, revision = revision + 1, updated_at = ?
				WHERE id = ? AND revision = ?
			`, newName, now, id, expectedRev)
		} else {
			res, err = tx.ExecContext(ctx, `
				UPDATE collections SET name = ?, revision = revision + 1, updated_at = ?
				WHERE id = ?
			`, newName, now, id)
		}
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrConflict, http.StatusConflict, "集合不存在或版本冲突")
		}
		return tx.QueryRowContext(ctx, `SELECT revision FROM collections WHERE id = ?`, id).Scan(&newRev)
	})
	return newRev, err
}

func (s *Store) DeleteCollection(ctx context.Context, id string) error {
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM collection_items WHERE collection_id = ?`, id); err != nil {
			return err
		}
		res, err := tx.ExecContext(ctx, `DELETE FROM collections WHERE id = ?`, id)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "集合不存在")
		}
		return nil
	})
}

// AddCollectionItems appends public_refs to a collection while verifying revision and ref existence.
func (s *Store) AddCollectionItems(ctx context.Context, collectionID string, publicRefs []string, expectedRev int64) (int64, error) {
	now := time.Now().UTC().UnixMilli()
	var newRev int64
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		var curRev int64
		if err := tx.QueryRowContext(ctx, `SELECT revision FROM collections WHERE id = ?`, collectionID).Scan(&curRev); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return model.NewError(model.ErrNotFound, http.StatusNotFound, "集合不存在")
			}
			return err
		}
		if expectedRev > 0 && curRev != expectedRev {
			return model.NewError(model.ErrConflict, http.StatusConflict, "集合版本冲突")
		}
		var maxPos sql.NullInt64
		if err := tx.QueryRowContext(ctx, `SELECT MAX(position) FROM collection_items WHERE collection_id = ?`, collectionID).Scan(&maxPos); err != nil {
			return err
		}
		nextPos := 0
		if maxPos.Valid {
			nextPos = int(maxPos.Int64) + 1
		}
		for _, ref := range publicRefs {
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO collection_items(collection_id, position, public_ref, added_at)
				VALUES(?, ?, ?, ?)
			`, collectionID, nextPos, ref, now); err != nil {
				return model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "曲目引用不存在或无法加入集合", err)
			}
			nextPos++
		}
		if _, err := tx.ExecContext(ctx, `
			UPDATE collections SET revision = revision + 1, updated_at = ? WHERE id = ?
		`, now, collectionID); err != nil {
			return err
		}
		newRev = curRev + 1
		return nil
	})
	return newRev, err
}

// RemoveCollectionItems removes items matching publicRefs in a single transaction,
// compacts positions, and bumps revision when items were removed.
func (s *Store) RemoveCollectionItems(ctx context.Context, collectionID string, publicRefs []string, expectedRev int64) (int64, int, error) {
	clean := make([]string, 0, len(publicRefs))
	seen := make(map[string]bool, len(publicRefs))
	for _, r := range publicRefs {
		trimmed := strings.TrimSpace(r)
		if trimmed == "" || seen[trimmed] {
			continue
		}
		seen[trimmed] = true
		clean = append(clean, trimmed)
	}
	now := time.Now().UTC().UnixMilli()
	var newRev int64
	var removedCount int
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		var curRev int64
		if err := tx.QueryRowContext(ctx, `SELECT revision FROM collections WHERE id = ?`, collectionID).Scan(&curRev); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return model.NewError(model.ErrNotFound, http.StatusNotFound, "集合不存在")
			}
			return err
		}
		if expectedRev > 0 && curRev != expectedRev {
			return model.NewError(model.ErrConflict, http.StatusConflict, "集合版本冲突")
		}
		newRev = curRev
		if len(clean) == 0 {
			return nil
		}
		var removed int64
		const chunkSize = 200
		for start := 0; start < len(clean); start += chunkSize {
			end := start + chunkSize
			if end > len(clean) {
				end = len(clean)
			}
			chunk := clean[start:end]
			placeholders := make([]string, len(chunk))
			args := make([]any, 1+len(chunk))
			args[0] = collectionID
			for i, v := range chunk {
				placeholders[i] = "?"
				args[i+1] = v
			}
			res, err := tx.ExecContext(ctx, `DELETE FROM collection_items WHERE collection_id = ? AND public_ref IN (`+strings.Join(placeholders, ",")+`)`, args...)
			if err != nil {
				return err
			}
			n, err := res.RowsAffected()
			if err != nil {
				return err
			}
			removed += n
		}
		removedCount = int(removed)
		if removedCount > 0 {
			if err := compactCollectionItemsTx(ctx, tx, collectionID, now); err != nil {
				return err
			}
			newRev = curRev + 1
		}
		return nil
	})
	return newRev, removedCount, err
}

// RemoveCollectionItem removes items matching publicRef and compacts positions inside one short transaction.
func (s *Store) RemoveCollectionItem(ctx context.Context, collectionID, publicRef string, expectedRev int64) (int64, error) {
	now := time.Now().UTC().UnixMilli()
	var newRev int64
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		var curRev int64
		if err := tx.QueryRowContext(ctx, `SELECT revision FROM collections WHERE id = ?`, collectionID).Scan(&curRev); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return model.NewError(model.ErrNotFound, http.StatusNotFound, "集合不存在")
			}
			return err
		}
		if expectedRev > 0 && curRev != expectedRev {
			return model.NewError(model.ErrConflict, http.StatusConflict, "集合版本冲突")
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM collection_items WHERE collection_id = ? AND public_ref = ?`, collectionID, publicRef); err != nil {
			return err
		}
		if err := compactCollectionItemsTx(ctx, tx, collectionID, now); err != nil {
			return err
		}
		newRev = curRev + 1
		return nil
	})
	return newRev, err
}

func compactCollectionItemsTx(ctx context.Context, tx *sql.Tx, collectionID string, now int64) error {
	rows, err := tx.QueryContext(ctx, `SELECT public_ref, added_at FROM collection_items WHERE collection_id = ? ORDER BY position ASC`, collectionID)
	if err != nil {
		return err
	}
	type itemRow struct {
		ref   string
		added int64
	}
	var remaining []itemRow
	for rows.Next() {
		var r itemRow
		if err := rows.Scan(&r.ref, &r.added); err != nil {
			_ = rows.Close()
			return err
		}
		remaining = append(remaining, r)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return err
	}
	_ = rows.Close()
	if _, err := tx.ExecContext(ctx, `DELETE FROM collection_items WHERE collection_id = ?`, collectionID); err != nil {
		return err
	}
	for idx, r := range remaining {
		if _, err := tx.ExecContext(ctx, `INSERT INTO collection_items(collection_id, position, public_ref, added_at) VALUES(?, ?, ?, ?)`, collectionID, idx, r.ref, r.added); err != nil {
			return err
		}
	}
	_, err = tx.ExecContext(ctx, `UPDATE collections SET revision = revision + 1, updated_at = ? WHERE id = ?`, now, collectionID)
	return err
}

// RemoveCollectionRefs removes any collection_items referencing the given public_refs
// and compacts positions of all affected collections.
func (s *Store) RemoveCollectionRefs(ctx context.Context, refs []string) error {
	if len(refs) == 0 {
		return nil
	}
	clean := make([]string, 0, len(refs))
	seen := make(map[string]bool, len(refs))
	for _, r := range refs {
		if r == "" || seen[r] {
			continue
		}
		seen[r] = true
		clean = append(clean, r)
	}
	if len(clean) == 0 {
		return nil
	}
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		affected := make(map[string]bool)
		const chunkSize = 200
		for start := 0; start < len(clean); start += chunkSize {
			end := start + chunkSize
			if end > len(clean) {
				end = len(clean)
			}
			chunk := clean[start:end]
			placeholders := make([]string, len(chunk))
			args := make([]any, len(chunk))
			for i, v := range chunk {
				placeholders[i] = "?"
				args[i] = v
			}
			inClause := strings.Join(placeholders, ",")
			rows, err := tx.QueryContext(ctx, `SELECT DISTINCT collection_id FROM collection_items WHERE public_ref IN (`+inClause+`)`, args...)
			if err != nil {
				return err
			}
			for rows.Next() {
				var cid string
				if err := rows.Scan(&cid); err != nil {
					_ = rows.Close()
					return err
				}
				affected[cid] = true
			}
			if err := rows.Err(); err != nil {
				_ = rows.Close()
				return err
			}
			_ = rows.Close()
			if _, err := tx.ExecContext(ctx, `DELETE FROM collection_items WHERE public_ref IN (`+inClause+`)`, args...); err != nil {
				return err
			}
		}
		for cid := range affected {
			if err := compactCollectionItemsTx(ctx, tx, cid, now); err != nil {
				return err
			}
		}
		return nil
	})
}

type DurableRecord struct {
	Namespace string
	ID        string
	BodyJSON  string
	Revision  int64
	UpdatedAt int64
}

func (s *Store) PutDurableRecord(ctx context.Context, namespace, id, bodyJSON string, expectedRev int64) (int64, error) {
	now := time.Now().UTC().UnixMilli()
	var newRev int64
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		if expectedRev > 0 {
			res, err := tx.ExecContext(ctx, `
				UPDATE durable_records SET body_json = ?, revision = revision + 1, updated_at = ?
				WHERE namespace = ? AND id = ? AND revision = ?
			`, bodyJSON, now, namespace, id, expectedRev)
			if err != nil {
				return err
			}
			if n, _ := res.RowsAffected(); n == 0 {
				return model.NewError(model.ErrConflict, http.StatusConflict, "持久记录版本冲突")
			}
			newRev = expectedRev + 1
			return nil
		}
		return tx.QueryRowContext(ctx, `
			INSERT INTO durable_records(namespace, id, body_json, revision, updated_at)
			VALUES(?, ?, ?, 1, ?)
			ON CONFLICT(namespace, id) DO UPDATE SET
				body_json = excluded.body_json,
				revision = durable_records.revision + 1,
				updated_at = excluded.updated_at
			RETURNING revision
		`, namespace, id, bodyJSON, now).Scan(&newRev)
	})
	return newRev, err
}

func (s *Store) GetDurableRecord(ctx context.Context, namespace, id string) (DurableRecord, bool, error) {
	var r DurableRecord
	r.Namespace = namespace
	r.ID = id
	err := s.reader.QueryRowContext(ctx, `
		SELECT body_json, revision, updated_at FROM durable_records WHERE namespace = ? AND id = ?
	`, namespace, id).Scan(&r.BodyJSON, &r.Revision, &r.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return DurableRecord{}, false, nil
	}
	if err != nil {
		return DurableRecord{}, false, err
	}
	return r, true, nil
}

func (s *Store) ListDurableRecords(ctx context.Context, namespace string, limit int) ([]DurableRecord, error) {
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	rows, err := s.reader.QueryContext(ctx, `
		SELECT id, body_json, revision, updated_at
		FROM durable_records WHERE namespace = ?
		ORDER BY updated_at DESC, id DESC LIMIT ?
	`, namespace, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DurableRecord
	for rows.Next() {
		r := DurableRecord{Namespace: namespace}
		if err := rows.Scan(&r.ID, &r.BodyJSON, &r.Revision, &r.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) DeleteDurableRecord(ctx context.Context, namespace, id string) error {
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `DELETE FROM durable_records WHERE namespace = ? AND id = ?`, namespace, id)
		return err
	})
}

type OutboxEnqueueParams struct {
	ID           string
	Channel      string
	RecipientKey string
	EventKey     string
	PayloadJSON  string
}

func enqueueOutboxTx(ctx context.Context, tx *sql.Tx, p OutboxEnqueueParams, now int64) error {
	id := p.ID
	if id == "" {
		id = model.NewUUID()
	}
	if p.PayloadJSON == "" {
		p.PayloadJSON = "{}"
	}
	_, err := tx.ExecContext(ctx, `
		INSERT INTO outbox(
			id, channel, recipient_key, event_key, payload_json, state, attempt, next_attempt_at, receipt_json, last_error, created_at, updated_at
		) VALUES(?, ?, ?, ?, ?, 'pending', 0, ?, '{}', '', ?, ?)
		ON CONFLICT(channel, recipient_key, event_key) DO NOTHING
	`, id, p.Channel, p.RecipientKey, p.EventKey, p.PayloadJSON, now, now, now)
	return err
}

func (s *Store) EnqueueOutbox(ctx context.Context, p OutboxEnqueueParams) error {
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		return enqueueOutboxTx(ctx, tx, p, now)
	})
}

// ReplacePendingOutboxByPrefix removes unsent pending outbox entries matching eventPrefix
// for the same channel and recipient before enqueuing the latest card update.
func (s *Store) ReplacePendingOutboxByPrefix(ctx context.Context, eventPrefix string, p OutboxEnqueueParams) error {
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		if eventPrefix != "" {
			if _, err := tx.ExecContext(ctx, `
				DELETE FROM outbox WHERE channel = ? AND recipient_key = ? AND state = 'pending' AND event_key LIKE ?
			`, p.Channel, p.RecipientKey, eventPrefix+"%"); err != nil {
				return err
			}
		}
		return enqueueOutboxTx(ctx, tx, p, now)
	})
}

func (s *Store) ClaimOutbox(ctx context.Context, limit int) ([]model.OutboxMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	now := time.Now().UTC().UnixMilli()
	var claimed []model.OutboxMessage
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `
			SELECT id, channel, recipient_key, payload_json, state, attempt, next_attempt_at, last_error, created_at, updated_at
			FROM outbox
			WHERE state IN ('pending', 'retry_wait') AND next_attempt_at <= ?
			ORDER BY next_attempt_at ASC, created_at ASC
			LIMIT ?
		`, now, limit)
		if err != nil {
			return err
		}
		for rows.Next() {
			var m model.OutboxMessage
			var st string
			if err := rows.Scan(&m.ID, &m.Channel, &m.Recipient, &m.PayloadJSON, &st, &m.Attempt, &m.NextAttempt, &m.LastError, &m.CreatedAt, &m.UpdatedAt); err != nil {
				_ = rows.Close()
				return err
			}
			m.Status = model.OutboxSending
			m.Attempt++
			claimed = append(claimed, m)
		}
		_ = rows.Close()
		for _, m := range claimed {
			if _, err := tx.ExecContext(ctx, `
				UPDATE outbox SET state = 'sending', attempt = ?, updated_at = ? WHERE id = ?
			`, m.Attempt, now, m.ID); err != nil {
				return err
			}
		}
		return nil
	})
	return claimed, err
}

func (s *Store) UpdateOutboxState(ctx context.Context, id string, state model.OutboxStatus, receiptJSON, lastError string, nextAttemptAt int64) error {
	if receiptJSON == "" {
		receiptJSON = "{}"
	}
	now := time.Now().UTC().UnixMilli()
	return s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			UPDATE outbox SET state = ?, receipt_json = ?, last_error = ?, next_attempt_at = ?, updated_at = ?
			WHERE id = ?
		`, string(state), receiptJSON, lastError, nextAttemptAt, now, id)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return model.NewError(model.ErrNotFound, http.StatusNotFound, "发件箱消息不存在")
		}
		return nil
	})
}

// RecoverSendingOutbox transitions any 'sending' outbox items left by a crash into 'delivery_unknown'
// rather than blindly re-sending external notifications.
func (s *Store) RecoverSendingOutbox(ctx context.Context) (int64, error) {
	now := time.Now().UTC().UnixMilli()
	var count int64
	err := s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			UPDATE outbox SET state = 'delivery_unknown', last_error = '服务异常退出时消息处于发送中，需人工或回执核对', updated_at = ?
			WHERE state = 'sending'
		`, now)
		if err != nil {
			return err
		}
		count, _ = res.RowsAffected()
		return nil
	})
	return count, err
}

// RecordInbox atomically records an inbound channel message if not seen within retention window.
// Returns duplicate=true if the event_id already exists.
func (s *Store) RecordInbox(ctx context.Context, ev model.InboxEvent, ttl time.Duration) (duplicate bool, err error) {
	if ttl <= 0 {
		ttl = 7 * 24 * time.Hour
	}
	now := ev.ReceivedAt
	if now <= 0 {
		now = time.Now().UTC().UnixMilli()
	}
	expires := now + ttl.Milliseconds()
	err = s.WithTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `
			INSERT INTO inbox(channel, event_id, sender_id, state, received_at, expires_at, result_ref)
			VALUES(?, ?, ?, 'received', ?, ?, '')
			ON CONFLICT(channel, event_id) DO NOTHING
		`, ev.Channel, ev.PlatformMessageID, ev.SenderID, now, expires)
		if err != nil {
			return err
		}
		n, _ := res.RowsAffected()
		duplicate = n == 0
		return nil
	})
	return duplicate, err
}

// HasInboxEvent checks read-only whether (channel, event_id) has already been recorded in inbox.
func (s *Store) HasInboxEvent(ctx context.Context, channel, eventID string) (bool, error) {
	var exists int
	err := s.reader.QueryRowContext(ctx, `
		SELECT 1 FROM inbox WHERE channel = ? AND event_id = ? LIMIT 1
	`, channel, eventID).Scan(&exists)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}
