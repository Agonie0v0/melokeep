package model

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
)

// PaginationCursor binds keyset pagination to sort order, filter hash, and index revision.
type PaginationCursor struct {
	SortField     string `json:"s"`
	LastID        string `json:"l"`
	FiltersHash   string `json:"f"`
	IndexRevision int64  `json:"r"`
}

// FiltersHash computes a deterministic SHA-256 hex digest for filter parameters.
func FiltersHash(query, folder string) string {
	sum := sha256.Sum256([]byte(query + "\x00" + folder))
	return hex.EncodeToString(sum[:16])
}

func (c PaginationCursor) Encode() (string, error) {
	raw, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

// DecodePaginationCursor validates a cursor against expected sort, filters, and index revision.
// Malformed cursors or filter/sort mismatches return 400; stale index_revision returns 409.
func DecodePaginationCursor(token, expectedSort, expectedFiltersHash string, currentIndexRevision int64) (PaginationCursor, error) {
	if token == "" {
		return PaginationCursor{}, NewError(ErrInvalidInput, http.StatusBadRequest, "无效分页游标")
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return PaginationCursor{}, NewError(ErrInvalidInput, http.StatusBadRequest, "分页游标格式错误")
	}
	var c PaginationCursor
	if err := json.Unmarshal(raw, &c); err != nil {
		return PaginationCursor{}, NewError(ErrInvalidInput, http.StatusBadRequest, "分页游标解析失败")
	}
	if c.SortField == "" || c.LastID == "" || c.FiltersHash == "" || c.IndexRevision <= 0 {
		return PaginationCursor{}, NewError(ErrInvalidInput, http.StatusBadRequest, "分页游标字段缺失")
	}
	if c.SortField != expectedSort || c.FiltersHash != expectedFiltersHash {
		return PaginationCursor{}, NewError(ErrInvalidInput, http.StatusBadRequest, "分页游标与当前查询条件不匹配")
	}
	if c.IndexRevision != currentIndexRevision {
		return PaginationCursor{}, NewError(ErrConflict, http.StatusConflict, "曲库索引已更新，请重新加载列表")
	}
	return c, nil
}
