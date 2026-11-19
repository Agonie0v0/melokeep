package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const (
	defaultUpdateRepo    = "Agonie0v0/melokeep"
	defaultGitHubAPIBase = "https://api.github.com"
	updateCacheTTL       = 30 * time.Minute
	updateRequestTimeout = 12 * time.Second
	updateMaxCommits     = 20
	updateMaxBodyBytes   = 1 << 20
)

type githubReleaseResponse struct {
	TagName         string `json:"tag_name"`
	Name            string `json:"name"`
	Body            string `json:"body"`
	PublishedAt     string `json:"published_at"`
	HTMLURL         string `json:"html_url"`
	TargetCommitish string `json:"target_commitish"`
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

func (s *Server) handleSystemUpdate(w http.ResponseWriter, r *http.Request) {
	force := r.URL.Query().Get("force") == "1" || strings.EqualFold(r.URL.Query().Get("force"), "true")
	res := s.checkSystemUpdate(r.Context(), force)
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) checkSystemUpdate(ctx context.Context, force bool) model.SystemUpdateDTO {
	s.updateMu.Lock()
	if !force && s.updateCache != nil && time.Now().Before(s.updateCacheExpires) {
		cached := *s.updateCache
		cached.Cached = true
		if cached.Commits == nil {
			cached.Commits = []model.UpdateCommitDTO{}
		}
		s.updateMu.Unlock()
		return cached
	}
	s.updateMu.Unlock()

	current := s.currentBuildInfo()
	repo := strings.TrimSpace(s.deps.UpdateRepo)
	if repo == "" {
		repo = defaultUpdateRepo
	}
	apiBase := strings.TrimRight(strings.TrimSpace(s.deps.GitHubAPIBase), "/")
	if apiBase == "" {
		apiBase = defaultGitHubAPIBase
	}

	out := model.SystemUpdateDTO{
		Repo:      repo,
		Mode:      "error",
		Current:   current,
		Commits:   []model.UpdateCommitDTO{},
		CheckedAt: time.Now().UTC().Format(time.RFC3339),
	}

	result, err := s.fetchSystemUpdate(ctx, repo, apiBase, current)
	if err != nil {
		out.Error = err.Error()
		return out
	}

	s.updateMu.Lock()
	copyCache := result
	s.updateCache = &copyCache
	s.updateCacheExpires = time.Now().Add(updateCacheTTL)
	s.updateMu.Unlock()
	return result
}

func (s *Server) fetchSystemUpdate(ctx context.Context, repo, apiBase string, current model.BuildInfoDTO) (model.SystemUpdateDTO, error) {
	now := time.Now().UTC().Format(time.RFC3339)
	out := model.SystemUpdateDTO{
		Repo:      repo,
		Current:   current,
		Commits:   []model.UpdateCommitDTO{},
		CheckedAt: now,
	}

	var rel githubReleaseResponse
	status, raw, err := s.githubGet(ctx, apiBase, "/repos/"+repo+"/releases/latest", current.Version)
	if err != nil {
		return out, fmt.Errorf("检查更新失败：%v", err)
	}
	if status == http.StatusOK {
		if err := json.Unmarshal(raw, &rel); err != nil {
			return out, fmt.Errorf("解析 GitHub Release 响应失败：%v", err)
		}
		out.Mode = "release"
		out.ReleaseNotes = strings.TrimSpace(rel.Body)
		out.Latest = &model.UpdateTargetDTO{
			Version:     strings.TrimSpace(rel.TagName),
			Tag:         strings.TrimSpace(rel.TagName),
			Title:       firstNonEmptyStr(rel.Name, rel.TagName),
			PublishedAt: strings.TrimSpace(rel.PublishedAt),
			URL:         strings.TrimSpace(rel.HTMLURL),
		}
		if curVer, okCur := parseSemver(current.Version); okCur && current.Version != "1.0.0-go" {
			if latestVer, okLatest := parseSemver(rel.TagName); okLatest {
				out.Comparable = true
				out.HasUpdate = semverLess(curVer, latestVer)
			}
		}
		if !out.Comparable && current.Commit != "" && rel.TagName != "" {
			cmpStatus, cmpRaw, cmpErr := s.githubGet(ctx, apiBase, "/repos/"+repo+"/compare/"+url.PathEscape(current.Commit)+"..."+url.PathEscape(rel.TagName), current.Version)
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
		return out, githubStatusError(status)
	}

	// No GitHub Releases published yet: fall back to comparing against the main branch.
	if current.Commit != "" {
		cmpStatus, cmpRaw, cmpErr := s.githubGet(ctx, apiBase, "/repos/"+repo+"/compare/"+url.PathEscape(current.Commit)+"...main", current.Version)
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
			return out, githubStatusError(cmpStatus)
		}
	}

	// Fallback when build commit is missing or unpushed (compare returned 404).
	listStatus, listRaw, listErr := s.githubGet(ctx, apiBase, "/repos/"+repo+"/commits?sha=main&per_page=10", current.Version)
	if listErr != nil {
		return out, fmt.Errorf("检查更新失败：%v", listErr)
	}
	if listStatus != http.StatusOK {
		return out, githubStatusError(listStatus)
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

func (s *Server) githubGet(ctx context.Context, apiBase, path, version string) (int, []byte, error) {
	targetURL := apiBase + path
	buildReq := func() (*http.Request, error) {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Accept", "application/vnd.github+json")
		req.Header.Set("User-Agent", "MeloKeep/"+firstNonEmptyStr(version, "1.0.0-go"))
		req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
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
			MaxResponseBytes: updateMaxBodyBytes,
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
	body, err := io.ReadAll(io.LimitReader(resp.Body, updateMaxBodyBytes))
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, body, nil
}

func githubStatusError(status int) error {
	switch status {
	case http.StatusForbidden, http.StatusTooManyRequests:
		return fmt.Errorf("GitHub API 请求受限（HTTP %d），请稍后再试或配置代理", status)
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
