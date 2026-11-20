package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	stdpath "path"
	"regexp"
	"strconv"
	"strings"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

type actorCtxKey struct{}

func withActor(ctx context.Context, actor auth.Actor) context.Context {
	return context.WithValue(ctx, actorCtxKey{}, actor)
}

func actorFromRequest(r *http.Request) auth.Actor {
	if r != nil {
		if a, ok := r.Context().Value(actorCtxKey{}).(auth.Actor); ok {
			return a
		}
	}
	return auth.UnauthenticatedActor()
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	data, err := json.Marshal(payload)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"detail":"响应编码失败"}`))
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.WriteHeader(status)
	_, _ = w.Write(data)
}

func writeError(w http.ResponseWriter, status int, detail any) {
	writeJSON(w, status, model.ErrorDTO{Detail: detail})
}

var inlineURLRe = regexp.MustCompile(`https?://[^\s"'<>]+`)

func redactTextURLs(text string) string {
	if text == "" || (!strings.Contains(text, "http://") && !strings.Contains(text, "https://")) {
		return text
	}
	return inlineURLRe.ReplaceAllStringFunc(text, func(raw string) string {
		return config.RedactURL(raw)
	})
}

func writeDomainError(w http.ResponseWriter, err error) {
	if err == nil {
		return
	}
	if de, ok := model.AsDomainError(err); ok {
		if de.RetryAfter > 0 {
			w.Header().Set("Retry-After", strconv.Itoa(de.RetryAfter))
		}
		detail := de.Detail
		if detail == "" {
			detail = "请求处理失败"
		}
		writeError(w, de.HTTPStatus(), redactTextURLs(detail))
		return
	}
	writeError(w, http.StatusInternalServerError, "内部服务错误")
}

var errBodyTooLarge = errors.New("request body too large")

func readBoundedBody(w http.ResponseWriter, r *http.Request, maxBytes int64) ([]byte, error) {
	if maxBytes <= 0 {
		maxBytes = int64(config.DefaultResourceBudgets().DefaultJSONMaxBytes)
	}
	if r.ContentLength > maxBytes {
		return nil, errBodyTooLarge
	}
	limited := http.MaxBytesReader(w, r.Body, maxBytes)
	data, err := io.ReadAll(limited)
	if err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			return nil, errBodyTooLarge
		}
		return nil, err
	}
	return data, nil
}

func decodeJSON(w http.ResponseWriter, r *http.Request, maxBytes int64, dst any) bool {
	data, err := readBoundedBody(w, r, maxBytes)
	if err != nil {
		if errors.Is(err, errBodyTooLarge) {
			writeError(w, http.StatusRequestEntityTooLarge, "请求体大小超出限制")
			return false
		}
		writeError(w, http.StatusBadRequest, "读取请求体失败")
		return false
	}
	if len(bytes.TrimSpace(data)) == 0 {
		writeJSONValidationError(w, "EOF while parsing a value")
		return false
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	if err := dec.Decode(dst); err != nil {
		writeJSONValidationError(w, err.Error())
		return false
	}
	// Ensure no trailing garbage after the first JSON value.
	var extra any
	if err := dec.Decode(&extra); !errors.Is(err, io.EOF) {
		writeJSONValidationError(w, "Extra data")
		return false
	}
	return true
}

func decodeJSONOptional(w http.ResponseWriter, r *http.Request, maxBytes int64, dst any) (present bool, ok bool) {
	data, err := readBoundedBody(w, r, maxBytes)
	if err != nil {
		if errors.Is(err, errBodyTooLarge) {
			writeError(w, http.StatusRequestEntityTooLarge, "请求体大小超出限制")
			return false, false
		}
		writeError(w, http.StatusBadRequest, "读取请求体失败")
		return false, false
	}
	trimmed := bytes.TrimSpace(data)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) {
		return false, true
	}
	dec := json.NewDecoder(bytes.NewReader(trimmed))
	if err := dec.Decode(dst); err != nil {
		writeJSONValidationError(w, err.Error())
		return true, false
	}
	var extra any
	if err := dec.Decode(&extra); !errors.Is(err, io.EOF) {
		writeJSONValidationError(w, "Extra data")
		return true, false
	}
	return true, true
}

func writeJSONValidationError(w http.ResponseWriter, msg string) {
	writeError(w, http.StatusUnprocessableEntity, []map[string]any{
		{
			"type":  "json_invalid",
			"loc":   []any{"body", 1},
			"msg":   "JSON decode error",
			"input": map[string]any{},
			"ctx":   map[string]any{"error": msg},
		},
	})
}

type trustedProxyMatcher struct {
	ips   []net.IP
	cidrs []*net.IPNet
}

func parseTrustedProxies(entries []string) trustedProxyMatcher {
	var m trustedProxyMatcher
	for _, raw := range entries {
		s := strings.TrimSpace(raw)
		if s == "" {
			continue
		}
		if strings.Contains(s, "/") {
			if _, ipNet, err := net.ParseCIDR(s); err == nil && ipNet != nil {
				m.cidrs = append(m.cidrs, ipNet)
			}
			continue
		}
		if ip := net.ParseIP(s); ip != nil {
			m.ips = append(m.ips, ip)
		}
	}
	return m
}

func (m trustedProxyMatcher) matches(ip net.IP) bool {
	if ip == nil {
		return false
	}
	for _, cand := range m.ips {
		if cand.Equal(ip) {
			return true
		}
	}
	for _, n := range m.cidrs {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

func extractRemoteIP(remoteAddr string) net.IP {
	host := strings.TrimSpace(remoteAddr)
	if host == "" {
		return nil
	}
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.Trim(host, "[]")
	return net.ParseIP(host)
}

func (s *Server) clientIP(r *http.Request) string {
	remoteIP := extractRemoteIP(r.RemoteAddr)
	if remoteIP != nil && s.trustedProxies.matches(remoteIP) {
		if xff := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); xff != "" {
			parts := strings.Split(xff, ",")
			var leftmostValid net.IP
			for i := len(parts) - 1; i >= 0; i-- {
				cand := net.ParseIP(strings.TrimSpace(parts[i]))
				if cand == nil {
					continue
				}
				leftmostValid = cand
				if !s.trustedProxies.matches(cand) {
					return cand.String()
				}
			}
			if leftmostValid != nil {
				return leftmostValid.String()
			}
		}
		if xri := strings.TrimSpace(r.Header.Get("X-Real-IP")); xri != "" {
			if cand := net.ParseIP(xri); cand != nil {
				return cand.String()
			}
		}
	}
	if remoteIP != nil {
		return remoteIP.String()
	}
	if r.RemoteAddr != "" {
		return r.RemoteAddr
	}
	return "unknown"
}

func isAPIPath(path string) bool {
	return path == "/api" || strings.HasPrefix(path, "/api/")
}

func isAPIOrExternalPath(path string) bool {
	return isAPIPath(path) ||
		path == "/bot" || strings.HasPrefix(path, "/bot/") ||
		path == "/downloads" || strings.HasPrefix(path, "/downloads/")
}

func isStateChangingMethod(method string) bool {
	switch method {
	case http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete:
		return true
	default:
		return false
	}
}

func isAuthExemptAPIPath(path string) bool {
	switch path {
	case "/api/health", "/api/auth/status", "/api/auth/login":
		return true
	default:
		return false
	}
}

func isMediaStreamPath(rawPath string) bool {
	if rawPath == "" || strings.Contains(rawPath, "..") {
		return false
	}
	cleanPath := stdpath.Clean(rawPath)
	if cleanPath != rawPath {
		return false
	}
	if strings.HasPrefix(cleanPath, "/api/stream/") ||
		strings.HasPrefix(cleanPath, "/api/navidrome/stream/") ||
		strings.HasPrefix(cleanPath, "/api/navidrome/download/") ||
		strings.HasPrefix(cleanPath, "/api/navidrome/cover/") {
		return true
	}
	switch cleanPath {
	case "/api/files/audio", "/api/files/cover", "/api/files/content":
		return true
	default:
		return false
	}
}

func isAllowedCORSOrigin(raw string) bool {
	origin := strings.TrimSpace(raw)
	if origin == "" || origin != raw || strings.ContainsAny(origin, "\r\n") {
		return false
	}
	u, err := url.Parse(origin)
	if err != nil || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return false
	}
	scheme := strings.ToLower(u.Scheme)
	host := strings.ToLower(u.Hostname())
	if port := u.Port(); port != "" {
		p, pErr := strconv.Atoi(port)
		if pErr != nil || p <= 0 || p > 65535 {
			return false
		}
	}
	switch scheme {
	case "capacitor", "ionic":
		return host == "localhost"
	case "http", "https":
		return host == "localhost" || host == "127.0.0.1"
	default:
		return false
	}
}

func applyCORSHeaders(w http.ResponseWriter, r *http.Request) {
	origin := r.Header.Get("Origin")
	if origin == "" || !isAllowedCORSOrigin(origin) {
		return
	}
	h := w.Header()
	h.Set("Access-Control-Allow-Origin", origin)
	h.Set("Vary", "Origin")
	h.Set("Access-Control-Allow-Credentials", "true")
	h.Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
	h.Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-MeloKeep-Request, Range")
	h.Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, Retry-After")
}

func (s *Server) authenticateAPIRequest(r *http.Request) (auth.Actor, error) {
	if s.deps.Sessions == nil {
		return auth.UnauthenticatedActor(), model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "登录已失效，请重新登录")
	}
	a, err := s.deps.Sessions.ValidateRequest(r)
	if err == nil {
		return a, nil
	}
	if (r.Method == http.MethodGet || r.Method == http.MethodHead) && isMediaStreamPath(r.URL.Path) {
		if mediaTok := strings.TrimSpace(r.URL.Query().Get("media_token")); mediaTok != "" {
			if userID, ok := s.deps.Sessions.VerifyMediaToken(mediaTok); ok {
				return auth.WebAdminActor(userID), nil
			}
		}
	}
	return auth.UnauthenticatedActor(), err
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	defer func() {
		if rec := recover(); rec != nil {
			if s.deps.Logs != nil {
				s.deps.Logs.Push("error", redactTextURLs(fmt.Sprintf("HTTP panic: %v", rec)), "system", "")
			}
			writeError(w, http.StatusInternalServerError, "内部服务错误")
		}
	}()

	applyCORSHeaders(w, r)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	path := r.URL.Path

	// Check route matching before serving SPA fallback so unknown /api/*, /bot/*, /downloads/*
	// always return JSON 404 (or 405 if another method exists) rather than SPA HTML.
	_, pattern := s.mux.Handler(r)
	if isAPIOrExternalPath(path) && (pattern == "" || pattern == "/" || pattern == "GET /") {
		if s.hasAnyMethodForPath(path) {
			writeError(w, http.StatusMethodNotAllowed, "Method Not Allowed")
			return
		}
		writeError(w, http.StatusNotFound, "Not Found")
		return
	}

	// CSRF enforcement for state-changing /api/* requests without Bearer auth.
	if isAPIPath(path) && isStateChangingMethod(r.Method) {
		if _, hasBearer := auth.ExtractBearerToken(r); !hasBearer {
			if err := auth.CheckCSRF(r); err != nil {
				writeDomainError(w, err)
				return
			}
		}
	}

	// Auth middleware for /api/* except health/auth status/login.
	var actor auth.Actor
	if isAPIPath(path) && !isAuthExemptAPIPath(path) {
		if s.deps.AuthEnabled {
			a, err := s.authenticateAPIRequest(r)
			if err != nil {
				writeDomainError(w, err)
				return
			}
			actor = a
		} else {
			actor = auth.AnonymousWebActor()
		}
	} else if s.deps.AuthEnabled {
		if s.deps.Sessions != nil {
			if a, err := s.deps.Sessions.ValidateRequest(r); err == nil {
				actor = a
			} else {
				actor = auth.UnauthenticatedActor()
			}
		} else {
			actor = auth.UnauthenticatedActor()
		}
	} else {
		actor = auth.AnonymousWebActor()
	}

	s.mux.ServeHTTP(w, r.WithContext(withActor(r.Context(), actor)))
}

func (s *Server) hasAnyMethodForPath(reqPath string) bool {
	for _, route := range s.routes {
		if matchRoutePattern(route.Path, reqPath) {
			return true
		}
	}
	return false
}

func matchRoutePattern(pattern, actual string) bool {
	if pattern == actual {
		return true
	}
	pSegs := strings.Split(strings.Trim(pattern, "/"), "/")
	aSegs := strings.Split(strings.Trim(actual, "/"), "/")
	if len(pSegs) != len(aSegs) {
		return false
	}
	for i := range pSegs {
		if strings.HasPrefix(pSegs[i], "{") && strings.HasSuffix(pSegs[i], "}") {
			if aSegs[i] == "" {
				return false
			}
			continue
		}
		if pSegs[i] != aSegs[i] {
			return false
		}
	}
	return true
}
