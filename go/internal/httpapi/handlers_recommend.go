package httpapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/recommend"
)

func (s *Server) handleRecommendRecordHistory(w http.ResponseWriter, r *http.Request) {
	if s.deps.Recommend == nil {
		writeError(w, http.StatusServiceUnavailable, "推荐服务未初始化")
		return
	}
	var req recommend.HistoryReportRequest
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	count, err := s.deps.Recommend.RecordHistory(r.Context(), req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":    true,
		"count": count,
	})
}

func (s *Server) handleRecommendListHistory(w http.ResponseWriter, r *http.Request) {
	if s.deps.Recommend == nil {
		writeError(w, http.StatusServiceUnavailable, "推荐服务未初始化")
		return
	}
	limit := 50
	if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
		if v, err := strconv.Atoi(raw); err == nil && v > 0 {
			limit = v
		}
	}
	items, err := s.deps.Recommend.ListHistory(r.Context(), limit)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	if items == nil {
		items = []recommend.HistoryEntry{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (s *Server) handleRecommendDailyGet(w http.ResponseWriter, r *http.Request) {
	if s.deps.Recommend == nil {
		writeError(w, http.StatusServiceUnavailable, "推荐服务未初始化")
		return
	}
	dto, err := s.deps.Recommend.GetDaily(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, dto)
}

func (s *Server) handleRecommendDailyStart(w http.ResponseWriter, r *http.Request) {
	if s.deps.Recommend == nil {
		writeError(w, http.StatusServiceUnavailable, "推荐服务未初始化")
		return
	}
	var req recommend.DailyStartRequest
	if _, ok := decodeJSONOptional(w, r, s.defaultJSONLimit(), &req); !ok {
		return
	}
	dto, err := s.deps.Recommend.StartDaily(r.Context(), s.currentSnapshot(), req)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, dto)
}

func (s *Server) handleRecommendDailyEvents(w http.ResponseWriter, r *http.Request) {
	if s.deps.Recommend == nil {
		writeError(w, http.StatusServiceUnavailable, "推荐服务未初始化")
		return
	}
	ident := r.PathValue("ident")
	var after int64
	if rawAfter := strings.TrimSpace(r.URL.Query().Get("after")); rawAfter != "" {
		v, err := strconv.ParseInt(rawAfter, 10, 64)
		if err != nil || v < 0 {
			writeError(w, http.StatusBadRequest, "事件序号无效")
			return
		}
		after = v
	}
	lastEventID := strings.TrimSpace(r.Header.Get("Last-Event-ID"))
	sub, err := s.deps.Recommend.Subscribe(r.Context(), ident, after, lastEventID)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	defer sub.Close()

	flusher, _ := w.(http.Flusher)
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	if flusher != nil {
		flusher.Flush()
	}

	for {
		ev, nextErr := sub.Next(r.Context())
		if nextErr != nil {
			if errors.Is(nextErr, io.EOF) || errors.Is(nextErr, recommend.ErrSlowSubscriber) || errors.Is(nextErr, context.Canceled) || r.Context().Err() != nil {
				return
			}
			return
		}
		if _, wErr := w.Write(ev.SSEBytes()); wErr != nil {
			return
		}
		if flusher != nil {
			flusher.Flush()
		}
	}
}

func (s *Server) handleRecommendDailyStop(w http.ResponseWriter, r *http.Request) {
	if s.deps.Recommend == nil {
		writeJSON(w, http.StatusOK, map[string]any{"cancelled": true})
		return
	}
	ident := r.PathValue("ident")
	if err := s.deps.Recommend.Cancel(ident); err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"cancelled": true})
}
