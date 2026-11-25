package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const (
	defaultUpdateRepo          = "Agonie0v0/melokeep"
	defaultGitHubAPIBase       = "https://api.github.com"
	updateCacheTTL             = 30 * time.Minute
	updateRequestTimeout       = 12 * time.Second
	updateMaxCommits           = 20
	updateMaxBodyBytes         = 1 << 20
	updateReleasesMaxBodyBytes = 4 << 20
	appUpdateMaxPages          = 3
	appUpdatePerPage           = 100
	githubTokenMaxLength       = 512
)

type appUpdateCacheEntry struct {
	result  model.AppUpdateCheckDTO
	expires time.Time
}

type systemUpdateSettingsRecord struct {
	GitHubToken string `json:"github_token,omitempty"`
}

type resolvedGitHubToken struct {
	token  string
	source string
	saved  bool
}

func (r resolvedGitHubToken) configured() bool {
	return r.token != ""
}

func (r resolvedGitHubToken) settingsDTO() model.SystemUpdateSettingsDTO {
	return model.SystemUpdateSettingsDTO{
		GitHubTokenConfigured: r.configured(),
		GitHubTokenSource:     r.source,
		GitHubTokenSaved:      r.saved,
	}
}

type githubReleaseAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
	Size               int64  `json:"size"`
	ContentType        string `json:"content_type"`
}

type githubReleaseResponse struct {
	TagName         string               `json:"tag_name"`
	Name            string               `json:"name"`
	Body            string               `json:"body"`
	PublishedAt     string               `json:"published_at"`
	HTMLURL         string               `json:"html_url"`
	TargetCommitish string               `json:"target_commitish"`
	Draft           bool                 `json:"draft"`
	Prerelease      bool                 `json:"prerelease"`
	Assets          []githubReleaseAsset `json:"assets"`
}

type githubCommitAuthor struct {
	Name string `json:"name"`
	Date string `json:"date"`
}

type githubCommitEntry struct {
	SHA     string `json:"sha"`
	HTMLURL string `json:"html_url"`
	Commit  struct {
		Message string             `json:"message"`
		Author  githubCommitAuthor `json:"author"`
	} `json:"commit"`
}

type githubCompareResponse struct {
	Status   string              `json:"status"`
	AheadBy  int                 `json:"ahead_by"`
	BehindBy int                 `json:"behind_by"`
	HTMLURL  string              `json:"html_url"`
	Commits  []githubCommitEntry `json:"commits"`
}

func (s *Server) currentBuildInfo() model.BuildInfoDTO {
	bi := s.deps.BuildInfo
	if strings.TrimSpace(bi.Version) == "" {
		bi.Version = strings.TrimSpace(s.deps.EngineVersion)
	}
	if bi.Version == "" {
		bi.Version = "1.0.0-go"
	}
	if bi.ShortCommit == "" && bi.Commit != "" {
		bi.ShortCommit = shortSHA(bi.Commit)
	}
	if !bi.InContainer && strings.TrimSpace(os.Getenv("MELOKEEP_CONTAINER")) != "" {
		bi.InContainer = true
	}
	return bi
}

func shortSHA(sha string) string {
	sha = strings.TrimSpace(sha)
	if len(sha) > 7 {
		return sha[:7]
	}
	return sha
}

func firstLine(s string) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	for _, line := range strings.Split(s, "\n") {
		if trimmed := strings.TrimSpace(line); trimmed != "" {
			return trimmed
		}
	}
	return ""
}

func toUpdateCommits(entries []githubCommitEntry, limit int) []model.UpdateCommitDTO {
	if len(entries) == 0 {
		return []model.UpdateCommitDTO{}
	}
	out := make([]model.UpdateCommitDTO, 0, min(len(entries), limit))
	// Reverse when compare returns oldest->newest? GitHub compare returns oldest->newest,
	// while /commits returns newest->oldest. Normalize by keeping caller order and bounding.
	for _, e := range entries {
		if len(out) >= limit {
			break
		}
		out = append(out, model.UpdateCommitDTO{
			SHA:    e.SHA,
			Short:  shortSHA(e.SHA),
			Title:  firstLine(e.Commit.Message),
			Author: strings.TrimSpace(e.Commit.Author.Name),
			Date:   strings.TrimSpace(e.Commit.Author.Date),
			URL:    strings.TrimSpace(e.HTMLURL),
		})
	}
	return out
}

func reverseCommits(entries []githubCommitEntry) []githubCommitEntry {
	out := make([]githubCommitEntry, len(entries))
	for i := range entries {
		out[i] = entries[len(entries)-1-i]
	}
	return out
}

func parseSemver(raw string) ([3]int, bool) {
	var ver [3]int
	clean := strings.TrimSpace(raw)
	clean = strings.TrimPrefix(strings.TrimPrefix(clean, "v"), "V")
	if idx := strings.IndexAny(clean, "-+"); idx >= 0 {
		clean = clean[:idx]
	}
	parts := strings.Split(clean, ".")
	if len(parts) < 2 || len(parts) > 3 {
		return ver, false
	}
	for i, part := range parts {
		if part == "" {
			return ver, false
		}
		n, err := strconv.Atoi(part)
		if err != nil || n < 0 {
			return ver, false
		}
		ver[i] = n
	}
	return ver, true
}

func semverLess(a, b [3]int) bool {
	for i := 0; i < 3; i++ {
		if a[i] != b[i] {
			return a[i] < b[i]
		}
	}
	return false
}

func isForceQuery(r *http.Request) bool {
	if r == nil {
		return false
	}
	v := r.URL.Query().Get("force")
	return v == "1" || strings.EqualFold(v, "true")
}

func (s *Server) updateTargetRepoAndAPIBase() (string, string) {
	repo := strings.TrimSpace(s.deps.UpdateRepo)
	if repo == "" {
		repo = defaultUpdateRepo
	}
	apiBase := strings.TrimRight(strings.TrimSpace(s.deps.GitHubAPIBase), "/")
	if apiBase == "" {
		apiBase = defaultGitHubAPIBase
	}
	return repo, apiBase
}

func (s *Server) invalidateUpdateCaches() {
	s.updateMu.Lock()
	s.updateCache = nil
	s.updateCacheExpires = time.Time{}
	clear(s.appUpdateCache)
	s.updateMu.Unlock()
}

func (s *Server) readPersistedGitHubToken(ctx context.Context) (string, error) {
	if s.deps.Store == nil {
		return "", nil
	}
	rec, ok, err := s.deps.Store.GetSetting(ctx, store.NamespaceSystemUpdate)
	if err != nil {
		return "", err
	}
	if !ok || strings.TrimSpace(rec.BodyJSON) == "" {
		return "", nil
	}
	var cfg systemUpdateSettingsRecord
	if err := json.Unmarshal([]byte(rec.BodyJSON), &cfg); err != nil {
		return "", nil
	}
	return strings.TrimSpace(cfg.GitHubToken), nil
}

func (s *Server) resolveGitHubTokenStrict(ctx context.Context) (resolvedGitHubToken, error) {
	savedTok, err := s.readPersistedGitHubToken(ctx)
	if err != nil {
		return resolvedGitHubToken{}, err
	}
	if savedTok != "" {
		return resolvedGitHubToken{
			token:  savedTok,
			source: "settings",
			saved:  true,
		}, nil
	}
	if envTok := firstNonEmptyStr(os.Getenv("MELOKEEP_GITHUB_TOKEN"), os.Getenv("GITHUB_TOKEN")); envTok != "" {
		return resolvedGitHubToken{
			token:  envTok,
			source: "env",
			saved:  false,
		}, nil
	}
	return resolvedGitHubToken{}, nil
}

func (s *Server) resolveGitHubToken(ctx context.Context) resolvedGitHubToken {
	tok, err := s.resolveGitHubTokenStrict(ctx)
	if err == nil {
		return tok
	}
	if envTok := firstNonEmptyStr(os.Getenv("MELOKEEP_GITHUB_TOKEN"), os.Getenv("GITHUB_TOKEN")); envTok != "" {
		return resolvedGitHubToken{
			token:  envTok,
			source: "env",
			saved:  false,
		}
	}
	return resolvedGitHubToken{}
}

func validateGitHubToken(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "GitHub Token 不能为空")
	}
	if len(trimmed) > githubTokenMaxLength {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "GitHub Token 长度不能超过 512 个字符")
	}
	for _, r := range trimmed {
		if r <= 0x20 || r == 0x7f {
			return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "GitHub Token 不能包含空白或控制字符")
		}
	}
	return trimmed, nil
}

func applyTokenStatusToSystemUpdate(dto *model.SystemUpdateDTO, tok resolvedGitHubToken) {
	if dto == nil {
		return
	}
	dto.GitHubTokenConfigured = tok.configured()
	dto.GitHubTokenSource = tok.source
	dto.GitHubTokenSaved = tok.saved
}

func applyTokenStatusToAppUpdate(dto *model.AppUpdateCheckDTO, tok resolvedGitHubToken) {
	if dto == nil {
		return
	}
	dto.GitHubTokenConfigured = tok.configured()
	dto.GitHubTokenSource = tok.source
	dto.GitHubTokenSaved = tok.saved
}

func (s *Server) handleSystemUpdateSettingsRead(w http.ResponseWriter, r *http.Request) {
	actor := actorFromRequest(r)
	if !actor.Authenticated || actor.ReadOnlyAI || !actor.Has(auth.PermAdmin) {
		writeError(w, http.StatusForbidden, "需要管理员权限查看系统更新配置")
		return
	}
	tok, err := s.resolveGitHubTokenStrict(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, tok.settingsDTO())
}

func (s *Server) handleSystemUpdateSettingsWrite(w http.ResponseWriter, r *http.Request) {
	if s.deps.ReadOnly {
		writeError(w, http.StatusForbidden, "只读模式下不允许修改系统更新配置")
		return
	}
	actor := actorFromRequest(r)
	if !actor.Authenticated || actor.ReadOnlyAI || !actor.Has(auth.PermAdmin) {
		writeError(w, http.StatusForbidden, "需要管理员权限修改系统更新配置")
		return
	}
	var req struct {
		GitHubToken      *string `json:"github_token"`
		ClearGitHubToken bool    `json:"clear_github_token"`
	}
	if !decodeJSON(w, r, s.defaultJSONLimit(), &req) {
		return
	}
	var nextToken string
	if !req.ClearGitHubToken {
		if req.GitHubToken == nil {
			writeError(w, http.StatusBadRequest, "请填写有效的 GitHub Token，或传 clear_github_token=true 清空")
			return
		}
		cleaned, err := validateGitHubToken(*req.GitHubToken)
		if err != nil {
			writeDomainError(w, err)
			return
		}
		nextToken = cleaned
	}
	if s.deps.Store == nil {
		writeError(w, http.StatusServiceUnavailable, "存储服务未初始化")
		return
	}
	raw, err := json.Marshal(systemUpdateSettingsRecord{GitHubToken: nextToken})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "序列化系统更新配置失败")
		return
	}
	if _, err := s.deps.Store.PutSetting(r.Context(), store.NamespaceSystemUpdate, string(raw), 0); err != nil {
		writeDomainError(w, err)
		return
	}
	s.invalidateUpdateCaches()
	if req.ClearGitHubToken {
		s.pushLog("info", "已清空保存的 GitHub Token", "system", "")
	} else {
		s.pushLog("info", "已保存 GitHub Token（已脱敏存储，用于提升更新检测限额）", "system", "")
	}
	tok, err := s.resolveGitHubTokenStrict(r.Context())
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, tok.settingsDTO())
}

func (s *Server) handleSystemUpdate(w http.ResponseWriter, r *http.Request) {
	res := s.checkSystemUpdate(r.Context(), isForceQuery(r))
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) checkSystemUpdate(ctx context.Context, force bool) model.SystemUpdateDTO {
	tok := s.resolveGitHubToken(ctx)

	s.updateMu.Lock()
	if !force && s.updateCache != nil && time.Now().Before(s.updateCacheExpires) {
		cached := *s.updateCache
		cached.Cached = true
		if cached.Commits == nil {
			cached.Commits = []model.UpdateCommitDTO{}
		}
		s.updateMu.Unlock()
		selfUpd := s.selfUpdateStatus(ctx)
		cached.SelfUpdate = &selfUpd
		applyTokenStatusToSystemUpdate(&cached, tok)
		return cached
	}
	s.updateMu.Unlock()

	current := s.currentBuildInfo()
	repo, apiBase := s.updateTargetRepoAndAPIBase()

	out := model.SystemUpdateDTO{
		Repo:      repo,
		Mode:      "error",
		Current:   current,
		Commits:   []model.UpdateCommitDTO{},
		CheckedAt: time.Now().UTC().Format(time.RFC3339),
	}
	applyTokenStatusToSystemUpdate(&out, tok)

	result, err := s.fetchSystemUpdate(ctx, repo, apiBase, current, tok.token)
	if err != nil {
		out.Error = err.Error()
		selfUpd := s.selfUpdateStatus(ctx)
		out.SelfUpdate = &selfUpd
		return out
	}

	applyTokenStatusToSystemUpdate(&result, tok)
	s.updateMu.Lock()
	copyCache := result
	s.updateCache = &copyCache
	s.updateCacheExpires = time.Now().Add(updateCacheTTL)
	s.updateMu.Unlock()
	selfUpd := s.selfUpdateStatus(ctx)
	result.SelfUpdate = &selfUpd
	return result
}

func (s *Server) fetchSystemUpdate(ctx context.Context, repo, apiBase string, current model.BuildInfoDTO, token string) (model.SystemUpdateDTO, error) {
	now := time.Now().UTC().Format(time.RFC3339)
	out := model.SystemUpdateDTO{
		Repo:      repo,
		Current:   current,
		Commits:   []model.UpdateCommitDTO{},
		CheckedAt: now,
	}

	var rel githubReleaseResponse
	status, raw, err := s.githubGet(ctx, apiBase, "/repos/"+repo+"/releases/latest", current.Version, token, updateMaxBodyBytes)
	if err != nil {
		return out, fmt.Errorf("检查更新失败：%v", err)
	}
	if status == http.StatusOK {
		if err := json.Unmarshal(raw, &rel); err != nil {
			return out, fmt.Errorf("解析 GitHub Release 响应失败：%v", err)
		}
		var assets []model.UpdateAssetDTO
		for _, a := range rel.Assets {
			name := strings.TrimSpace(a.Name)
			dlURL := strings.TrimSpace(a.BrowserDownloadURL)
			if name == "" || dlURL == "" {
				continue
			}
			assets = append(assets, model.UpdateAssetDTO{
				Name:        name,
				DownloadURL: dlURL,
				Size:        a.Size,
				ContentType: strings.TrimSpace(a.ContentType),
			})
		}
		out.Mode = "release"
		out.ReleaseNotes = strings.TrimSpace(rel.Body)
		out.Latest = &model.UpdateTargetDTO{
			Version:     strings.TrimSpace(rel.TagName),
			Tag:         strings.TrimSpace(rel.TagName),
			Title:       firstNonEmptyStr(rel.Name, rel.TagName),
			PublishedAt: strings.TrimSpace(rel.PublishedAt),
			URL:         strings.TrimSpace(rel.HTMLURL),
			Assets:      assets,
		}
		if curVer, okCur := parseSemver(current.Version); okCur && current.Version != "1.0.0-go" {
			if latestVer, okLatest := parseSemver(rel.TagName); okLatest {
				out.Comparable = true
				out.HasUpdate = semverLess(curVer, latestVer)
			}
		}
		if !out.Comparable && current.Commit != "" && rel.TagName != "" {
			cmpStatus, cmpRaw, cmpErr := s.githubGet(ctx, apiBase, "/repos/"+repo+"/compare/"+url.PathEscape(current.Commit)+"..."+url.PathEscape(rel.TagName), current.Version, token, updateMaxBodyBytes)
			if cmpErr == nil && cmpStatus == http.StatusOK {
				var cmp githubCompareResponse
				if json.Unmarshal(cmpRaw, &cmp) == nil {
					out.Comparable = true
					out.AheadBy = cmp.AheadBy
					out.BehindBy = cmp.BehindBy
					out.HasUpdate = cmp.AheadBy > 0
					out.Commits = toUpdateCommits(reverseCommits(cmp.Commits), updateMaxCommits)
				}
			}
		}
		return out, nil
	}
	if status != http.StatusNotFound {
		return out, githubStatusError(status, token != "")
	}

	// No GitHub Releases published yet: fall back to comparing against the main branch.
	if current.Commit != "" {
		cmpStatus, cmpRaw, cmpErr := s.githubGet(ctx, apiBase, "/repos/"+repo+"/compare/"+url.PathEscape(current.Commit)+"...main", current.Version, token, updateMaxBodyBytes)
		if cmpErr != nil {
			return out, fmt.Errorf("检查更新失败：%v", cmpErr)
		}
		if cmpStatus == http.StatusOK {
			var cmp githubCompareResponse
			if err := json.Unmarshal(cmpRaw, &cmp); err != nil {
				return out, fmt.Errorf("解析提交对比响应失败：%v", err)
			}
			commits := toUpdateCommits(reverseCommits(cmp.Commits), updateMaxCommits)
			out.Mode = "compare"
			out.Comparable = true
			out.AheadBy = cmp.AheadBy
			out.BehindBy = cmp.BehindBy
			out.HasUpdate = cmp.AheadBy > 0
			out.Commits = commits
			if len(commits) > 0 {
				latest := commits[0]
				out.Latest = &model.UpdateTargetDTO{
					Version:     "main@" + latest.Short,
					Commit:      latest.SHA,
					ShortCommit: latest.Short,
					Title:       latest.Title,
					PublishedAt: latest.Date,
					URL:         firstNonEmptyStr(cmp.HTMLURL, latest.URL),
				}
			} else {
				out.Latest = &model.UpdateTargetDTO{
					Version:     "main@" + current.ShortCommit,
					Commit:      current.Commit,
					ShortCommit: current.ShortCommit,
					URL:         "https://github.com/" + repo + "/commit/" + current.Commit,
				}
			}
			return out, nil
		}
		if cmpStatus != http.StatusNotFound {
			return out, githubStatusError(cmpStatus, token != "")
		}
	}

	// Fallback when build commit is missing or unpushed (compare returned 404).
	listStatus, listRaw, listErr := s.githubGet(ctx, apiBase, "/repos/"+repo+"/commits?sha=main&per_page=10", current.Version, token, updateMaxBodyBytes)
	if listErr != nil {
		return out, fmt.Errorf("检查更新失败：%v", listErr)
	}
	if listStatus != http.StatusOK {
		return out, githubStatusError(listStatus, token != "")
	}
	var entries []githubCommitEntry
	if err := json.Unmarshal(listRaw, &entries); err != nil {
		return out, fmt.Errorf("解析提交列表失败：%v", err)
	}
	commits := toUpdateCommits(entries, 10)
	out.Mode = "commits"
	out.Comparable = false
	out.HasUpdate = false
	out.Commits = commits
	if len(commits) > 0 {
		latest := commits[0]
		out.Latest = &model.UpdateTargetDTO{
			Version:     "main@" + latest.Short,
			Commit:      latest.SHA,
			ShortCommit: latest.Short,
			Title:       latest.Title,
			PublishedAt: latest.Date,
			URL:         latest.URL,
		}
	}
	return out, nil
}

func (s *Server) handleSystemUpdateApp(w http.ResponseWriter, r *http.Request) {
	platform := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("platform")))
	if platform != "android" && platform != "ios" {
		writeError(w, http.StatusBadRequest, "platform 参数仅支持 android 或 ios")
		return
	}
	res := s.checkAppUpdate(r.Context(), platform, isForceQuery(r))
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) checkAppUpdate(ctx context.Context, platform string, force bool) model.AppUpdateCheckDTO {
	tok := s.resolveGitHubToken(ctx)

	s.updateMu.Lock()
	if !force && s.appUpdateCache != nil {
		if entry, ok := s.appUpdateCache[platform]; ok && time.Now().Before(entry.expires) {
			cached := entry.result
			cached.Cached = true
			s.updateMu.Unlock()
			applyTokenStatusToAppUpdate(&cached, tok)
			return cached
		}
	}
	s.updateMu.Unlock()

	current := s.currentBuildInfo()
	repo, apiBase := s.updateTargetRepoAndAPIBase()
	out := model.AppUpdateCheckDTO{
		Platform:  platform,
		Repo:      repo,
		CheckedAt: time.Now().UTC().Format(time.RFC3339),
	}
	applyTokenStatusToAppUpdate(&out, tok)

	rel, err := s.fetchAppUpdateRelease(ctx, repo, apiBase, current.Version, platform, tok.token)
	if err != nil {
		out.Error = err.Error()
		return out
	}

	out.Release = rel
	s.updateMu.Lock()
	if s.appUpdateCache == nil {
		s.appUpdateCache = make(map[string]appUpdateCacheEntry)
	}
	s.appUpdateCache[platform] = appUpdateCacheEntry{
		result:  out,
		expires: time.Now().Add(updateCacheTTL),
	}
	s.updateMu.Unlock()
	return out
}

func releaseHasPlatformAsset(rel githubReleaseResponse, platform string) bool {
	ext := ".apk"
	if platform == "ios" {
		ext = ".ipa"
	}
	for _, a := range rel.Assets {
		if strings.TrimSpace(a.BrowserDownloadURL) == "" {
			continue
		}
		if strings.HasSuffix(strings.ToLower(strings.TrimSpace(a.Name)), ext) {
			return true
		}
	}
	return false
}

func toAppUpdateReleaseDTO(rel githubReleaseResponse) *model.AppUpdateReleaseDTO {
	assets := make([]model.AppUpdateAssetDTO, 0, len(rel.Assets))
	for _, a := range rel.Assets {
		name := strings.TrimSpace(a.Name)
		dlURL := strings.TrimSpace(a.BrowserDownloadURL)
		if name == "" || dlURL == "" {
			continue
		}
		assets = append(assets, model.AppUpdateAssetDTO{
			Name:               name,
			BrowserDownloadURL: dlURL,
			Size:               a.Size,
			ContentType:        strings.TrimSpace(a.ContentType),
		})
	}
	return &model.AppUpdateReleaseDTO{
		TagName:     strings.TrimSpace(rel.TagName),
		Name:        strings.TrimSpace(rel.Name),
		HTMLURL:     strings.TrimSpace(rel.HTMLURL),
		Body:        strings.TrimSpace(rel.Body),
		PublishedAt: strings.TrimSpace(rel.PublishedAt),
		Assets:      assets,
		Draft:       rel.Draft,
		Prerelease:  rel.Prerelease,
	}
}

type parsedAppSemver struct {
	core []int
	pre  []string
}

func parseAppSemver(raw string) (parsedAppSemver, bool) {
	cleaned := strings.TrimSpace(raw)
	cleaned = strings.TrimPrefix(cleaned, "refs/tags/")
	cleaned = strings.TrimPrefix(cleaned, "release-")
	cleaned = strings.TrimPrefix(strings.TrimPrefix(cleaned, "v"), "V")
	if idx := strings.IndexByte(cleaned, '+'); idx >= 0 {
		cleaned = cleaned[:idx]
	}
	cleaned = strings.TrimSpace(cleaned)
	if cleaned == "" {
		return parsedAppSemver{}, false
	}
	corePart, prePart, hasPre := strings.Cut(cleaned, "-")
	parts := strings.Split(corePart, ".")
	if len(parts) == 0 {
		return parsedAppSemver{}, false
	}
	core := make([]int, 0, len(parts))
	for _, part := range parts {
		if part == "" {
			return parsedAppSemver{}, false
		}
		n, err := strconv.Atoi(part)
		if err != nil || n < 0 {
			return parsedAppSemver{}, false
		}
		core = append(core, n)
	}
	var pre []string
	if hasPre && strings.TrimSpace(prePart) != "" {
		for _, seg := range strings.Split(prePart, ".") {
			if trimmed := strings.TrimSpace(seg); trimmed != "" {
				pre = append(pre, trimmed)
			}
		}
	}
	return parsedAppSemver{core: core, pre: pre}, true
}

func compareAppSemverTags(aTag, bTag string) int {
	a, okA := parseAppSemver(aTag)
	b, okB := parseAppSemver(bTag)
	if !okA && !okB {
		return 0
	}
	if okA && !okB {
		return 1
	}
	if !okA && okB {
		return -1
	}
	maxCore := max(len(a.core), len(b.core), 3)
	for i := 0; i < maxCore; i++ {
		av, bv := 0, 0
		if i < len(a.core) {
			av = a.core[i]
		}
		if i < len(b.core) {
			bv = b.core[i]
		}
		if av != bv {
			if av > bv {
				return 1
			}
			return -1
		}
	}
	if len(a.pre) == 0 && len(b.pre) == 0 {
		return 0
	}
	if len(a.pre) == 0 {
		return 1
	}
	if len(b.pre) == 0 {
		return -1
	}
	maxPre := max(len(a.pre), len(b.pre))
	for i := 0; i < maxPre; i++ {
		if i >= len(a.pre) {
			return -1
		}
		if i >= len(b.pre) {
			return 1
		}
		an, errA := strconv.Atoi(a.pre[i])
		bn, errB := strconv.Atoi(b.pre[i])
		switch {
		case errA == nil && errB == nil:
			if an != bn {
				if an > bn {
					return 1
				}
				return -1
			}
		case errA == nil:
			return -1
		case errB == nil:
			return 1
		default:
			if cmp := strings.Compare(strings.ToLower(a.pre[i]), strings.ToLower(b.pre[i])); cmp != 0 {
				return cmp
			}
		}
	}
	return 0
}

func (s *Server) fetchAppUpdateRelease(ctx context.Context, repo, apiBase, version, platform, token string) (*model.AppUpdateReleaseDTO, error) {
	for page := 1; page <= appUpdateMaxPages; page++ {
		path := fmt.Sprintf("/repos/%s/releases?per_page=%d&page=%d", repo, appUpdatePerPage, page)
		status, raw, err := s.githubGet(ctx, apiBase, path, version, token, updateReleasesMaxBodyBytes)
		if err != nil {
			return nil, fmt.Errorf("检查 App 更新失败：%v", err)
		}
		if status != http.StatusOK {
			return nil, githubStatusError(status, token != "")
		}
		var releases []githubReleaseResponse
		if err := json.Unmarshal(raw, &releases); err != nil {
			return nil, fmt.Errorf("解析 GitHub Release 列表失败：%v", err)
		}
		if len(releases) == 0 {
			break
		}
		var candidates []githubReleaseResponse
		for _, rel := range releases {
			if rel.Draft || rel.Prerelease || !releaseHasPlatformAsset(rel, platform) {
				continue
			}
			candidates = append(candidates, rel)
		}
		if len(candidates) > 0 {
			sort.SliceStable(candidates, func(i, j int) bool {
				return compareAppSemverTags(candidates[i].TagName, candidates[j].TagName) > 0
			})
			return toAppUpdateReleaseDTO(candidates[0]), nil
		}
		if len(releases) < appUpdatePerPage {
			break
		}
	}
	return nil, nil
}

func (s *Server) githubGet(ctx context.Context, apiBase, path, version, token string, maxBytes int64) (int, []byte, error) {
	if maxBytes <= 0 {
		maxBytes = updateMaxBodyBytes
	}
	targetURL := apiBase + path
	buildReq := func() (*http.Request, error) {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Accept", "application/vnd.github+json")
		req.Header.Set("User-Agent", "MeloKeep/"+firstNonEmptyStr(version, "1.0.0-go"))
		req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
		if tok := strings.TrimSpace(token); tok != "" {
			req.Header.Set("Authorization", "Bearer "+tok)
		}
		return req, nil
	}

	var (
		resp *http.Response
		err  error
	)
	if s.deps.Transport != nil {
		snap := s.currentSnapshot()
		resp, err = s.deps.Transport.DoWithRetry(ctx, buildReq, transport.RequestOptions{
			Domain:           transport.DomainPublicMedia,
			ConfigRevision:   snap.Revision,
			ProxyURL:         snap.Runtime.ProxyURL,
			Timeout:          updateRequestTimeout,
			MaxResponseBytes: maxBytes,
			Idempotent:       true,
		})
	} else {
		req, rErr := buildReq()
		if rErr != nil {
			return 0, nil, rErr
		}
		client := &http.Client{Timeout: updateRequestTimeout}
		resp, err = client.Do(req)
	}
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBytes))
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, body, nil
}

func githubStatusError(status int, hasToken bool) error {
	switch status {
	case http.StatusUnauthorized:
		if hasToken {
			return fmt.Errorf("GitHub Token 无效或已过期（HTTP 401），请更新或清空 GitHub Token 后重试")
		}
		return fmt.Errorf("GitHub API 拒绝未认证访问（HTTP 401），请配置 GitHub Token 后重试")
	case http.StatusForbidden, http.StatusTooManyRequests:
		if hasToken {
			return fmt.Errorf("GitHub API 请求受限（HTTP %d），请检查 GitHub Token 权限、限额或稍后重试", status)
		}
		return fmt.Errorf("GitHub API 请求受限（HTTP %d），建议配置 GitHub Token 或代理后重试", status)
	default:
		return fmt.Errorf("GitHub API 返回异常状态（HTTP %d）", status)
	}
}

func firstNonEmptyStr(values ...string) string {
	for _, v := range values {
		if trimmed := strings.TrimSpace(v); trimmed != "" {
			return trimmed
		}
	}
	return ""
}
