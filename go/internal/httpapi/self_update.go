package httpapi

import (
	"context"
	"net/http"
	"strings"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/dockerupdate"
	"github.com/agonie0v0/melokeep/internal/model"
)

func (s *Server) ensureSelfUpdater() *dockerupdate.Manager {
	s.updateMu.Lock()
	defer s.updateMu.Unlock()
	if s.selfUpdater == nil {
		bi := s.currentBuildInfo()
		s.selfUpdater = dockerupdate.NewManager(dockerupdate.Options{
			InContainer: bi.InContainer,
			Logger: func(level, message string) {
				s.pushLog(level, message, "system", "")
			},
		})
	}
	return s.selfUpdater
}

func (s *Server) selfUpdateStatus(ctx context.Context) model.SelfUpdateStatusDTO {
	return s.ensureSelfUpdater().Status(ctx)
}

func (s *Server) handleSystemUpdateStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.selfUpdateStatus(r.Context()))
}

func (s *Server) handleSystemUpdateApply(w http.ResponseWriter, r *http.Request) {
	actor := actorFromRequest(r)
	if !actor.Authenticated || actor.ReadOnlyAI || !actor.Has(auth.PermAdmin) {
		writeError(w, http.StatusForbidden, "需要管理员权限执行系统自更新")
		return
	}
	var req struct {
		TargetImage string `json:"target_image"`
	}
	if _, ok := decodeJSONOptional(w, r, s.defaultJSONLimit(), &req); !ok {
		return
	}
	st, err := s.ensureSelfUpdater().Start(r.Context(), strings.TrimSpace(req.TargetImage))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusAccepted, st)
}
