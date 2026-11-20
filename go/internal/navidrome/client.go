package navidrome

import (
	"context"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

const (
	settingsNamespace = "navidrome"
	settingsRecordID  = "config"
	maxJSONBytes      = 16 << 20
)

// TrackRememberFunc optionally registers projected Navidrome tracks as resolvable references.
type TrackRememberFunc func(ctx context.Context, track model.TrackDTO)

// Client implements the server-side OpenSubsonic Navidrome client.
// Credentials are never returned in browser media URLs or error details.
type Client struct {
	pool     *transport.Pool
	store    *store.Store
	remember TrackRememberFunc

	mu       sync.RWMutex
	settings Settings
	revision string
}

func NewClient(pool *transport.Pool, st *store.Store, initial Settings, remember TrackRememberFunc) (*Client, error) {
	if pool == nil {
		pool = transport.NewPool()
	}
	validated, err := ValidateSettings(initial)
	if err != nil {
		return nil, err
	}
	c := &Client{
		pool:     pool,
		store:    st,
		remember: remember,
		settings: validated,
		revision: computeRevision(validated),
	}
	return c, nil
}

// LoadSettings loads persisted settings from store.DurableRecords if available.
func (c *Client) LoadSettings(ctx context.Context) (Settings, error) {
	if c.store == nil {
		return c.Settings(), nil
	}
	rec, ok, err := c.store.GetDurableRecord(ctx, settingsNamespace, settingsRecordID)
	if err != nil {
		return Settings{}, err
	}
	if !ok {
		return c.Settings(), nil
	}
	var s Settings
	if err := json.Unmarshal([]byte(rec.BodyJSON), &s); err != nil {
		return Settings{}, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "Navidrome 配置反序列化失败", err)
	}
	validated, err := ValidateSettings(s)
	if err != nil {
		return Settings{}, err
	}
	c.SetSettings(validated)
	return validated, nil
}

// UpdateSettings validates, merges hidden placeholders, persists to durable_records, and updates active config.
func (c *Client) UpdateSettings(ctx context.Context, actor auth.Actor, upd SettingsUpdate) (model.NavidromeConfigDTO, error) {
	if !actor.Authenticated || !actor.Has(auth.PermAdmin) {
		return model.NavidromeConfigDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "需要管理员权限修改 Navidrome 配置")
	}
	cur := c.Settings()
	merged, err := MergeSettings(cur, upd)
	if err != nil {
		return model.NavidromeConfigDTO{}, err
	}
	if c.store != nil {
		raw, err := json.Marshal(merged)
		if err != nil {
			return model.NavidromeConfigDTO{}, err
		}
		if _, err := c.store.PutDurableRecord(ctx, settingsNamespace, settingsRecordID, string(raw), 0); err != nil {
			return model.NavidromeConfigDTO{}, err
		}
	}
	oldRev := c.Revision()
	c.SetSettings(merged)
	if oldRev != "" && oldRev != c.Revision() && c.pool != nil {
		c.pool.InvalidateRevision(oldRev)
	}
	return merged.PublicDTO(), nil
}

func (c *Client) Settings() Settings {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.settings
}

func (c *Client) Revision() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.revision
}

func (c *Client) SetSettings(s Settings) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.settings = s
	c.revision = computeRevision(s)
}

func (c *Client) PublicConfig() model.NavidromeConfigDTO {
	return c.Settings().PublicDTO()
}

func computeRevision(s Settings) string {
	raw, _ := json.Marshal(s)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:16])
}

func (c *Client) authParams(s Settings) (url.Values, error) {
	if !s.Enabled || s.URL == "" {
		return nil, model.NewError(model.ErrConflict, http.StatusConflict, "MeloKeep 服务端未配置并启用 Navidrome（App 端可直接独立连接）")
	}
	q := url.Values{}
	q.Set("v", "1.16.1")
	q.Set("c", "melokeep")
	q.Set("f", "json")
	if strings.TrimSpace(s.APIKey) != "" {
		q.Set("apiKey", s.APIKey)
		return q, nil
	}
	if strings.TrimSpace(s.Username) == "" || strings.TrimSpace(s.Password) == "" {
		return nil, model.NewError(model.ErrConflict, http.StatusConflict, "请填写 Navidrome 用户名和密码，或 API Key")
	}
	var saltBytes [12]byte
	if _, err := rand.Read(saltBytes[:]); err != nil {
		return nil, model.WrapError(model.ErrInternal, http.StatusInternalServerError, "生成认证随机盐失败", err)
	}
	salt := hex.EncodeToString(saltBytes[:])
	sum := md5.Sum([]byte(s.Password + salt))
	q.Set("u", s.Username)
	q.Set("s", salt)
	q.Set("t", hex.EncodeToString(sum[:]))
	return q, nil
}

type subsonicEnvelope struct {
	SubsonicResponse subsonicBody `json:"subsonic-response"`
}

type subsonicBody struct {
	Status        string          `json:"status"`
	Version       string          `json:"version"`
	ServerVersion string          `json:"serverVersion"`
	Error         *subsonicError  `json:"error"`
	ScanStatus    *ScanStatus     `json:"scanStatus"`
	Song          json.RawMessage `json:"song"`
	Playlists     json.RawMessage `json:"playlists"`
	Playlist      json.RawMessage `json:"playlist"`
	SearchResult3 json.RawMessage `json:"searchResult3"`
	AlbumList2    json.RawMessage `json:"albumList2"`
	Album         json.RawMessage `json:"album"`
	Artists       json.RawMessage `json:"artists"`
	Artist        json.RawMessage `json:"artist"`
	LyricsList    json.RawMessage `json:"lyricsList"`
	Lyrics        json.RawMessage `json:"lyrics"`
}

type subsonicError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (c *Client) doRequest(ctx context.Context, method, endpoint string, extra url.Values, reqHeaders http.Header, stream bool) (*http.Response, error) {
	s := c.Settings()
	rev := c.Revision()
	authQ, err := c.authParams(s)
	if err != nil {
		return nil, err
	}
	targetURL := s.URL + "/rest/" + url.PathEscape(endpoint) + ".view"
	allParams := url.Values{}
	for k, vals := range authQ {
		for _, v := range vals {
			allParams.Add(k, v)
		}
	}
	for k, vals := range extra {
		for _, v := range vals {
			allParams.Add(k, v)
		}
	}

	var req *http.Request
	if strings.EqualFold(method, http.MethodPost) {
		req, err = http.NewRequestWithContext(ctx, http.MethodPost, targetURL, strings.NewReader(allParams.Encode()))
		if err == nil {
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		}
	} else {
		req, err = http.NewRequestWithContext(ctx, http.MethodGet, targetURL+"?"+allParams.Encode(), nil)
	}
	if err != nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Navidrome 请求地址无效")
	}
	for k, vals := range reqHeaders {
		for _, v := range vals {
			req.Header.Add(k, v)
		}
	}

	parsedOrigin, err := url.Parse(s.URL)
	if err != nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Navidrome 服务地址无效")
	}
	origin := parsedOrigin.Scheme + "://" + parsedOrigin.Host
	maxBytes := int64(maxJSONBytes)
	if stream {
		maxBytes = -1
	}
	opts := transport.RequestOptions{
		Domain:                 transport.DomainConfiguredInternal,
		ConfigRevision:         rev,
		Timeout:                30 * time.Second,
		MaxResponseBytes:       maxBytes,
		AllowedInternalOrigins: []string{origin},
		HasSideEffects:         strings.EqualFold(method, http.MethodPost),
	}
	resp, err := c.pool.Do(ctx, req, opts)
	if err != nil {
		var de *model.DomainError
		if errors.As(err, &de) {
			if de.Code == model.ErrCancelled || de.Code == model.ErrForbidden || de.Code == model.ErrInvalidInput {
				return nil, de
			}
			if de.Code == model.ErrDeadline {
				return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "Navidrome 响应超时，请检查服务状态")
			}
		}
		return nil, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "无法连接 Navidrome，请检查服务地址和网络")
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent && resp.StatusCode != http.StatusRequestedRangeNotSatisfiable {
		_ = resp.Body.Close()
		return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "Navidrome 请求失败，请检查服务地址、账号权限和网络")
	}
	return resp, nil
}

func (c *Client) requestJSON(ctx context.Context, method, endpoint string, extra url.Values) (subsonicBody, error) {
	resp, err := c.doRequest(ctx, method, endpoint, extra, nil, false)
	if err != nil {
		return subsonicBody{}, err
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxJSONBytes+1))
	if err != nil {
		return subsonicBody{}, model.NewError(model.ErrUnavailable, http.StatusServiceUnavailable, "读取 Navidrome 响应失败")
	}
	if len(raw) > maxJSONBytes {
		return subsonicBody{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "Navidrome 响应体积超过限制")
	}
	var env subsonicEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return subsonicBody{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "Navidrome 返回了无效 JSON 响应")
	}
	body := env.SubsonicResponse
	if body.Status != "ok" {
		code := 0
		if body.Error != nil {
			code = body.Error.Code
		}
		switch code {
		case 40, 41:
			return subsonicBody{}, model.NewError(model.ErrUnauthorized, http.StatusUnauthorized, "Navidrome 认证失败，请检查用户名、密码或 API Key")
		case 50:
			return subsonicBody{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "当前 Navidrome 账号没有此操作权限")
		case 70:
			return subsonicBody{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "Navidrome 歌单或歌曲不存在，请刷新后重试")
		default:
			return subsonicBody{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("Navidrome 接口拒绝请求（错误码 %d），请检查认证与权限", code))
		}
	}
	return body, nil
}

func (c *Client) Ping(ctx context.Context) (PingResult, error) {
	body, err := c.requestJSON(ctx, http.MethodGet, "ping", nil)
	if err != nil {
		return PingResult{}, err
	}
	ver := body.ServerVersion
	if ver == "" {
		ver = body.Version
	}
	return PingResult{OK: true, Version: ver}, nil
}

func (c *Client) StartScan(ctx context.Context, actor auth.Actor) (ScanStatus, error) {
	if !actor.Authenticated || (!actor.Has(auth.PermLocalWrite) && !actor.Has(auth.PermMaintenance) && !actor.Has(auth.PermAdmin)) {
		return ScanStatus{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "缺少触发 Navidrome 扫描的权限")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "startScan", nil)
	if err != nil {
		return ScanStatus{}, err
	}
	if body.ScanStatus == nil {
		return ScanStatus{}, nil
	}
	return *body.ScanStatus, nil
}

func (c *Client) GetScanStatus(ctx context.Context) (ScanStatus, error) {
	body, err := c.requestJSON(ctx, http.MethodGet, "getScanStatus", nil)
	if err != nil {
		return ScanStatus{}, err
	}
	if body.ScanStatus == nil {
		return ScanStatus{}, nil
	}
	return *body.ScanStatus, nil
}

// CanDelete checks both actor permissions and the server-side delete_enabled guardrail.
func (c *Client) CanDelete(actor auth.Actor) error {
	if !actor.Authenticated || actor.ReadOnlyAI || !actor.Has(auth.PermExternalWrite) {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "当前账号无权执行 Navidrome 删除操作")
	}
	if !c.Settings().DeleteEnabled {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "未开启 Navidrome 删除权限，已拦截删除操作")
	}
	return nil
}

type rawSong struct {
	ID       string  `json:"id"`
	Title    string  `json:"title"`
	Artist   string  `json:"artist"`
	Album    string  `json:"album"`
	Duration float64 `json:"duration"`
	CoverArt string  `json:"coverArt"`
	Size     *int64  `json:"size"`
	Suffix   string  `json:"suffix"`
	BitRate  *int    `json:"bitRate"`
	Path     string  `json:"path"`
}

// SongFileInfo carries remote path and size metadata returned by Subsonic getSong.
type SongFileInfo struct {
	ID     string
	Title  string
	Artist string
	Album  string
	Path   string
	Size   *int64
	Suffix string
}

// GetSongFileInfo fetches metadata and reported file path for a remote Navidrome song ID.
func normalizeNavidromeID(raw string) string {
	id := strings.TrimSpace(raw)
	if strings.HasPrefix(id, "navidrome:") {
		return strings.TrimPrefix(id, "navidrome:")
	}
	if strings.HasPrefix(id, "navidrome-") {
		return strings.TrimPrefix(id, "navidrome-")
	}
	return id
}

func (c *Client) GetSongFileInfo(ctx context.Context, id string) (SongFileInfo, error) {
	id = normalizeNavidromeID(id)
	if id == "" {
		return SongFileInfo{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌曲 ID 不能为空")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "getSong", url.Values{"id": {id}})
	if err != nil {
		return SongFileInfo{}, err
	}
	var s rawSong
	if len(body.Song) > 0 {
		_ = json.Unmarshal(body.Song, &s)
	}
	if strings.TrimSpace(s.ID) != id || strings.TrimSpace(s.ID) == "" {
		return SongFileInfo{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "Navidrome 歌曲不存在")
	}
	return SongFileInfo{
		ID:     s.ID,
		Title:  s.Title,
		Artist: s.Artist,
		Album:  s.Album,
		Path:   s.Path,
		Size:   s.Size,
		Suffix: s.Suffix,
	}, nil
}

// GetSong fetches metadata for a remote Navidrome song ID and projects it as a playable TrackDTO.
func (c *Client) GetSong(ctx context.Context, id string) (model.TrackDTO, error) {
	id = normalizeNavidromeID(id)
	if id == "" {
		return model.TrackDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌曲 ID 不能为空")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "getSong", url.Values{"id": {id}})
	if err != nil {
		return model.TrackDTO{}, err
	}
	var s rawSong
	if len(body.Song) > 0 {
		_ = json.Unmarshal(body.Song, &s)
	}
	if strings.TrimSpace(s.ID) == "" || strings.TrimSpace(s.ID) != id {
		return model.TrackDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "Navidrome 歌曲不存在")
	}
	dto := projectSong(s)
	c.rememberTracks(ctx, []model.TrackDTO{dto})
	return dto, nil
}

type rawAlbum struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Title     string    `json:"title"`
	Artist    string    `json:"artist"`
	Year      *int      `json:"year"`
	SongCount int       `json:"songCount"`
	CoverArt  string    `json:"coverArt"`
	Song      []rawSong `json:"song"`
}

type rawPlaylist struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Owner     string    `json:"owner"`
	Readonly  bool      `json:"readonly"`
	SongCount int       `json:"songCount"`
	CoverArt  string    `json:"coverArt"`
	Created   string    `json:"created"`
	Changed   string    `json:"changed"`
	Entry     []rawSong `json:"entry"`
}

func projectSong(s rawSong) model.TrackDTO {
	var cover *string
	if s.CoverArt != "" {
		u := "/api/navidrome/cover/" + url.PathEscape(s.CoverArt)
		cover = &u
	}
	title := s.Title
	if title == "" {
		title = "未知歌曲"
	}
	streamURL := "/api/navidrome/stream/" + url.PathEscape(s.ID)
	codec := s.Suffix
	detail := "Navidrome 音乐库"
	return model.TrackDTO{
		ID:        "navidrome-" + s.ID,
		Source:    "navidrome",
		SourceID:  s.ID,
		Title:     title,
		Artist:    s.Artist,
		Album:     s.Album,
		Duration:  s.Duration,
		Cover:     cover,
		SizeBytes: s.Size,
		Qualities: []model.QualityDTO{},
		Playback: model.PlaybackDTO{
			State:       "ready",
			URL:         &streamURL,
			Codec:       &codec,
			Bitrate:     s.BitRate,
			Detail:      &detail,
			PreviewOnly: false,
		},
	}
}

func projectAlbum(a rawAlbum) AlbumDTO {
	var cover *string
	if a.CoverArt != "" {
		u := "/api/navidrome/cover/" + url.PathEscape(a.CoverArt)
		cover = &u
	}
	name := a.Name
	if name == "" {
		name = a.Title
	}
	if name == "" {
		name = "未知专辑"
	}
	return AlbumDTO{
		ID:        a.ID,
		Name:      name,
		Artist:    a.Artist,
		Year:      a.Year,
		SongCount: a.SongCount,
		Cover:     cover,
	}
}

func (c *Client) projectPlaylistSummary(p rawPlaylist) model.PlaylistSummaryDTO {
	var cover *string
	if p.CoverArt != "" {
		u := "/api/navidrome/cover/" + url.PathEscape(p.CoverArt)
		cover = &u
	}
	var created int64
	if p.Created != "" {
		if t, err := time.Parse(time.RFC3339, p.Created); err == nil {
			created = t.Unix()
		}
	}
	name := p.Name
	if name == "" {
		name = "未命名歌单"
	}
	username := c.Settings().Username
	canManage := p.Owner == "" || username == "" || strings.EqualFold(p.Owner, username)
	readOnly := p.Readonly || !canManage
	return model.PlaylistSummaryDTO{
		ID:         "navidrome:" + p.ID,
		Name:       name,
		Source:     "navidrome",
		SourceURL:  "",
		CreatedAt:  created,
		TrackCount: p.SongCount,
		IssueCount: 0,
		Cover:      cover,
		ReadOnly:   &readOnly,
		CanManage:  &canManage,
		Owner:      p.Owner,
		Detail:     "与 Navidrome 同步，修改直接保存到服务器。",
	}
}

func playlistRevision(p rawPlaylist) string {
	ids := make([]string, 0, len(p.Entry))
	for _, e := range p.Entry {
		ids = append(ids, e.ID)
	}
	payload, _ := json.Marshal([]any{p.ID, p.Changed, ids})
	sum := sha256.Sum256(payload)
	return hex.EncodeToString(sum[:])
}

func (c *Client) rememberTracks(ctx context.Context, tracks []model.TrackDTO) {
	if c.remember == nil {
		return
	}
	for _, t := range tracks {
		c.remember(ctx, t)
	}
}

func (c *Client) GetPlaylists(ctx context.Context) ([]model.PlaylistSummaryDTO, error) {
	if !c.Settings().Enabled {
		return []model.PlaylistSummaryDTO{}, nil
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "getPlaylists", nil)
	if err != nil {
		return nil, err
	}
	var wrapper struct {
		Playlist []rawPlaylist `json:"playlist"`
	}
	if len(body.Playlists) > 0 {
		_ = json.Unmarshal(body.Playlists, &wrapper)
	}
	out := make([]model.PlaylistSummaryDTO, 0, len(wrapper.Playlist))
	for _, p := range wrapper.Playlist {
		out = append(out, c.projectPlaylistSummary(p))
	}
	return out, nil
}

func (c *Client) GetPlaylist(ctx context.Context, id string) (model.SavedPlaylistDTO, error) {
	id = normalizeNavidromeID(id)
	if id == "" {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌单 ID 不能为空")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "getPlaylist", url.Values{"id": {id}})
	if err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	var p rawPlaylist
	if len(body.Playlist) > 0 {
		_ = json.Unmarshal(body.Playlist, &p)
	}
	if p.ID == "" {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrNotFound, http.StatusNotFound, "Navidrome 歌单不存在或无权访问")
	}
	summary := c.projectPlaylistSummary(p)
	tracks := make([]model.TrackDTO, 0, len(p.Entry))
	for i, song := range p.Entry {
		idx := i
		t := projectSong(song)
		t.PlaylistIndex = &idx
		tracks = append(tracks, t)
	}
	c.rememberTracks(ctx, tracks)
	return model.SavedPlaylistDTO{
		ID:        summary.ID,
		Name:      summary.Name,
		Source:    summary.Source,
		SourceURL: summary.SourceURL,
		CreatedAt: summary.CreatedAt,
		ReadOnly:  summary.ReadOnly,
		CanManage: summary.CanManage,
		Revision:  playlistRevision(p),
		Owner:     summary.Owner,
		Detail:    summary.Detail,
		Tracks:    tracks,
		Issues:    []model.PlaylistIssueDTO{},
	}, nil
}

func (c *Client) CreatePlaylist(ctx context.Context, actor auth.Actor, name string, songIDs []string) (model.SavedPlaylistDTO, error) {
	if !actor.Authenticated || actor.ReadOnlyAI || !actor.Has(auth.PermExternalWrite) {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "当前账号无权创建 Navidrome 歌单")
	}
	name = strings.TrimSpace(name)
	if name == "" || len([]rune(name)) > 100 {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌单名称须为 1–100 个字符")
	}
	if len(songIDs) > 3000 {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "单次添加曲目数不能超过 3000")
	}
	q := url.Values{"name": {name}}
	for _, sid := range songIDs {
		if norm := normalizeNavidromeID(sid); norm != "" {
			q.Add("songId", norm)
		}
	}
	body, err := c.requestJSON(ctx, http.MethodPost, "createPlaylist", q)
	if err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	var p rawPlaylist
	if len(body.Playlist) > 0 {
		_ = json.Unmarshal(body.Playlist, &p)
	}
	if p.ID == "" {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "Navidrome 未返回新歌单，请同步歌单确认结果后再试")
	}
	return c.GetPlaylist(ctx, p.ID)
}

func (c *Client) RenamePlaylist(ctx context.Context, actor auth.Actor, id, name string) (model.SavedPlaylistDTO, error) {
	if !actor.Authenticated || actor.ReadOnlyAI || !actor.Has(auth.PermExternalWrite) {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "当前账号无权修改 Navidrome 歌单")
	}
	id = normalizeNavidromeID(id)
	name = strings.TrimSpace(name)
	if name == "" || len([]rune(name)) > 100 {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌单名称须为 1–100 个字符")
	}
	cur, err := c.GetPlaylist(ctx, id)
	if err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	if cur.CanManage != nil && !*cur.CanManage {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "只能修改自己拥有的 Navidrome 歌单")
	}
	if _, err := c.requestJSON(ctx, http.MethodPost, "updatePlaylist", url.Values{"playlistId": {id}, "name": {name}}); err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	return c.GetPlaylist(ctx, id)
}

func (c *Client) DeletePlaylist(ctx context.Context, actor auth.Actor, id string) error {
	if err := c.CanDelete(actor); err != nil {
		return err
	}
	id = normalizeNavidromeID(id)
	cur, err := c.GetPlaylist(ctx, id)
	if err != nil {
		return err
	}
	if cur.CanManage != nil && !*cur.CanManage {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "只能删除自己拥有的 Navidrome 歌单")
	}
	_, err = c.requestJSON(ctx, http.MethodPost, "deletePlaylist", url.Values{"id": {id}})
	return err
}

func (c *Client) AddPlaylistTracks(ctx context.Context, actor auth.Actor, id string, songIDs []string) (model.SavedPlaylistDTO, error) {
	if !actor.Authenticated || actor.ReadOnlyAI || !actor.Has(auth.PermExternalWrite) {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "当前账号无权修改 Navidrome 歌单")
	}
	id = normalizeNavidromeID(id)
	if len(songIDs) == 0 || len(songIDs) > 3000 {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请提供 1–3000 首歌曲")
	}
	cur, err := c.GetPlaylist(ctx, id)
	if err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	if cur.ReadOnly != nil && *cur.ReadOnly {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "此 Navidrome 歌单为只读或当前账号无编辑权限")
	}
	q := url.Values{"playlistId": {id}}
	for _, sid := range songIDs {
		if norm := normalizeNavidromeID(sid); norm != "" {
			q.Add("songIdToAdd", norm)
		}
	}
	if _, err := c.requestJSON(ctx, http.MethodPost, "updatePlaylist", q); err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	return c.GetPlaylist(ctx, id)
}

func (c *Client) RemovePlaylistTracks(ctx context.Context, actor auth.Actor, id string, indexes []int, revision string) (model.SavedPlaylistDTO, error) {
	if err := c.CanDelete(actor); err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	id = normalizeNavidromeID(id)
	if len(indexes) == 0 || len(indexes) > 3000 || len(revision) != 64 {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "移除歌曲参数或 revision 格式无效")
	}
	cur, err := c.GetPlaylist(ctx, id)
	if err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	if cur.ReadOnly != nil && *cur.ReadOnly {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrForbidden, http.StatusForbidden, "此 Navidrome 歌单为只读或当前账号无编辑权限")
	}
	if cur.Revision != revision {
		return model.SavedPlaylistDTO{}, model.NewError(model.ErrConflict, http.StatusConflict, "Navidrome 歌单已变化，请同步歌单后重新选择要移除的歌曲")
	}
	seen := make(map[int]bool, len(indexes))
	uniq := make([]int, 0, len(indexes))
	for _, idx := range indexes {
		if idx < 0 || idx >= len(cur.Tracks) {
			return model.SavedPlaylistDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌单歌曲位置无效，请同步歌单后重试")
		}
		if !seen[idx] {
			seen[idx] = true
			uniq = append(uniq, idx)
		}
	}
	sort.Sort(sort.Reverse(sort.IntSlice(uniq)))
	q := url.Values{"playlistId": {id}}
	for _, idx := range uniq {
		q.Add("songIndexToRemove", strconv.Itoa(idx))
	}
	if _, err := c.requestJSON(ctx, http.MethodPost, "updatePlaylist", q); err != nil {
		return model.SavedPlaylistDTO{}, err
	}
	return c.GetPlaylist(ctx, id)
}

func (c *Client) GetSongs(ctx context.Context, offset, size int) (SongsPageDTO, error) {
	if offset < 0 || size < 1 || size > 100 {
		return SongsPageDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌曲分页参数无效")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "search3", url.Values{
		"query":       {""},
		"songCount":   {strconv.Itoa(size + 1)},
		"songOffset":  {strconv.Itoa(offset)},
		"albumCount":  {"0"},
		"artistCount": {"0"},
	})
	if err != nil {
		return SongsPageDTO{}, err
	}
	var sr struct {
		Song []rawSong `json:"song"`
	}
	if len(body.SearchResult3) > 0 {
		_ = json.Unmarshal(body.SearchResult3, &sr)
	}
	hasMore := len(sr.Song) > size
	items := sr.Song
	if hasMore {
		items = items[:size]
	}
	tracks := make([]model.TrackDTO, 0, len(items))
	for _, s := range items {
		tracks = append(tracks, projectSong(s))
	}
	c.rememberTracks(ctx, tracks)
	return SongsPageDTO{Tracks: tracks, HasMore: hasMore}, nil
}

var validAlbumSorts = map[string]bool{
	"newest":               true,
	"alphabeticalByName":   true,
	"alphabeticalByArtist": true,
	"recent":               true,
	"starred":              true,
}

func (c *Client) GetAlbums(ctx context.Context, offset, size int, sortBy string) (AlbumsPageDTO, error) {
	if sortBy == "" {
		sortBy = "newest"
	}
	if offset < 0 || size < 1 || size > 100 || !validAlbumSorts[sortBy] {
		return AlbumsPageDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "专辑分页或排序参数无效")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "getAlbumList2", url.Values{
		"type":   {sortBy},
		"size":   {strconv.Itoa(size)},
		"offset": {strconv.Itoa(offset)},
	})
	if err != nil {
		return AlbumsPageDTO{}, err
	}
	var al struct {
		Album []rawAlbum `json:"album"`
	}
	if len(body.AlbumList2) > 0 {
		_ = json.Unmarshal(body.AlbumList2, &al)
	}
	out := make([]AlbumDTO, 0, len(al.Album))
	for _, a := range al.Album {
		out = append(out, projectAlbum(a))
	}
	return AlbumsPageDTO{Albums: out, HasMore: len(out) == size}, nil
}

func (c *Client) GetAlbum(ctx context.Context, id string) (AlbumDetailDTO, error) {
	id = normalizeNavidromeID(id)
	if id == "" {
		return AlbumDetailDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "专辑 ID 不能为空")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "getAlbum", url.Values{"id": {id}})
	if err != nil {
		return AlbumDetailDTO{}, err
	}
	var a rawAlbum
	if len(body.Album) > 0 {
		_ = json.Unmarshal(body.Album, &a)
	}
	tracks := make([]model.TrackDTO, 0, len(a.Song))
	for _, s := range a.Song {
		tracks = append(tracks, projectSong(s))
	}
	c.rememberTracks(ctx, tracks)
	return AlbumDetailDTO{AlbumDTO: projectAlbum(a), Tracks: tracks}, nil
}

func (c *Client) GetArtists(ctx context.Context) ([]ArtistDTO, error) {
	body, err := c.requestJSON(ctx, http.MethodGet, "getArtists", nil)
	if err != nil {
		return nil, err
	}
	var wrapper struct {
		Index []struct {
			Artist []struct {
				ID         string `json:"id"`
				Name       string `json:"name"`
				AlbumCount int    `json:"albumCount"`
			} `json:"artist"`
		} `json:"index"`
	}
	if len(body.Artists) > 0 {
		_ = json.Unmarshal(body.Artists, &wrapper)
	}
	var out []ArtistDTO
	for _, idx := range wrapper.Index {
		for _, a := range idx.Artist {
			out = append(out, ArtistDTO{ID: a.ID, Name: a.Name, AlbumCount: a.AlbumCount})
		}
	}
	if out == nil {
		out = []ArtistDTO{}
	}
	return out, nil
}

func (c *Client) GetArtist(ctx context.Context, id string) (ArtistDetailDTO, error) {
	id = normalizeNavidromeID(id)
	if id == "" {
		return ArtistDetailDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "歌手 ID 不能为空")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "getArtist", url.Values{"id": {id}})
	if err != nil {
		return ArtistDetailDTO{}, err
	}
	var wrapper struct {
		Name  string     `json:"name"`
		Album []rawAlbum `json:"album"`
	}
	if len(body.Artist) > 0 {
		_ = json.Unmarshal(body.Artist, &wrapper)
	}
	albums := make([]AlbumDTO, 0, len(wrapper.Album))
	for _, a := range wrapper.Album {
		albums = append(albums, projectAlbum(a))
	}
	return ArtistDetailDTO{Name: wrapper.Name, Albums: albums}, nil
}

func (c *Client) Search(ctx context.Context, query string, offset int) (SearchResultDTO, error) {
	q := strings.TrimSpace(query)
	if q == "" || len([]rune(q)) > 200 || offset < 0 {
		return SearchResultDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "请输入有效的搜索内容")
	}
	body, err := c.requestJSON(ctx, http.MethodGet, "search3", url.Values{
		"query":       {q},
		"songCount":   {"50"},
		"songOffset":  {strconv.Itoa(offset)},
		"albumCount":  {"50"},
		"albumOffset": {strconv.Itoa(offset)},
		"artistCount": {"0"},
	})
	if err != nil {
		return SearchResultDTO{}, err
	}
	var sr struct {
		Song  []rawSong  `json:"song"`
		Album []rawAlbum `json:"album"`
	}
	if len(body.SearchResult3) > 0 {
		_ = json.Unmarshal(body.SearchResult3, &sr)
	}
	tracks := make([]model.TrackDTO, 0, len(sr.Song))
	for _, s := range sr.Song {
		tracks = append(tracks, projectSong(s))
	}
	c.rememberTracks(ctx, tracks)
	albums := make([]AlbumDTO, 0, len(sr.Album))
	for _, a := range sr.Album {
		albums = append(albums, projectAlbum(a))
	}
	return SearchResultDTO{
		Tracks:  tracks,
		Albums:  albums,
		HasMore: len(tracks) == 50 || len(albums) == 50,
	}, nil
}

func (c *Client) GetLyrics(ctx context.Context, id, artist, title string) string {
	id = normalizeNavidromeID(id)
	var plain string
	if body, err := c.requestJSON(ctx, http.MethodGet, "getLyricsBySongId", url.Values{"id": {id}}); err == nil && len(body.LyricsList) > 0 {
		var ll struct {
			StructuredLyrics []struct {
				Synced bool    `json:"synced"`
				Offset float64 `json:"offset"`
				Line   []struct {
					Start *float64 `json:"start"`
					Value string   `json:"value"`
				} `json:"line"`
			} `json:"structuredLyrics"`
		}
		if err := json.Unmarshal(body.LyricsList, &ll); err == nil {
			for _, entry := range ll.StructuredLyrics {
				var rawLines []string
				for _, row := range entry.Line {
					rawLines = append(rawLines, row.Value)
				}
				text := strings.TrimSpace(strings.Join(rawLines, "\n"))
				if text == "" {
					continue
				}
				if plain == "" {
					plain = text
				}
				if !entry.Synced {
					continue
				}
				offset := entry.Offset
				if math.IsNaN(offset) || math.IsInf(offset, 0) {
					offset = 0
				}
				var timed []string
				for _, row := range entry.Line {
					if row.Start == nil || math.IsNaN(*row.Start) || math.IsInf(*row.Start, 0) || *row.Start < 0 {
						continue
					}
					ms := int64(math.Round(*row.Start - offset))
					if ms < 0 {
						ms = 0
					}
					minutes := ms / 60000
					rem := ms % 60000
					seconds := rem / 1000
					frac := rem % 1000
					timed = append(timed, fmt.Sprintf("[%02d:%02d.%03d]%s", minutes, seconds, frac, row.Value))
				}
				if len(timed) > 0 {
					return strings.Join(timed, "\n")
				}
			}
		}
	}
	if plain != "" {
		return plain
	}
	if body, err := c.requestJSON(ctx, http.MethodGet, "getLyrics", url.Values{"artist": {artist}, "title": {title}}); err == nil && len(body.Lyrics) > 0 {
		var l struct {
			Value string `json:"value"`
		}
		if err := json.Unmarshal(body.Lyrics, &l); err == nil {
			return l.Value
		}
	}
	return ""
}

// OpenMedia opens a controlled proxy stream for "stream" or "getCoverArt", passing safe Range headers
// and rejecting JSON/XML error payloads instead of leaking raw Subsonic responses.
func (c *Client) OpenMedia(ctx context.Context, endpoint, id, rangeHeader string) (*MediaStream, error) {
	if endpoint != "stream" && endpoint != "download" && endpoint != "getCoverArt" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的媒体端点")
	}
	if strings.TrimSpace(id) == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "媒体 ID 不能为空")
	}
	if endpoint != "download" {
		return c.openMediaAttempt(ctx, endpoint, id, rangeHeader)
	}
	ms, err := c.openMediaAttempt(ctx, "download", id, rangeHeader)
	if err == nil {
		return ms, nil
	}
	if ctx.Err() != nil {
		return nil, err
	}
	var de *model.DomainError
	if errors.As(err, &de) {
		switch de.Code {
		case model.ErrCancelled, model.ErrForbidden, model.ErrInvalidInput:
			return nil, err
		}
	}
	return c.openMediaAttempt(ctx, "stream", id, rangeHeader)
}

func (c *Client) openMediaAttempt(ctx context.Context, endpoint, id, rangeHeader string) (*MediaStream, error) {
	params := url.Values{"id": {id}}
	switch endpoint {
	case "stream":
		params.Set("format", "raw")
	case "getCoverArt":
		params.Set("size", "600")
	case "download":
		// download.view returns the original file without transcoding; do not force format=raw.
	}
	hdr := http.Header{}
	if strings.TrimSpace(rangeHeader) != "" {
		hdr.Set("Range", rangeHeader)
	}
	resp, err := c.doRequest(ctx, http.MethodGet, endpoint, params, hdr, true)
	if err != nil {
		return nil, err
	}
	ct := strings.ToLower(resp.Header.Get("Content-Type"))
	if strings.Contains(ct, "json") || strings.Contains(ct, "xml") {
		_ = resp.Body.Close()
		return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "Navidrome 未返回可用媒体，请检查文件和账号权限")
	}
	safe := make(http.Header)
	for _, k := range []string{"Content-Length", "Content-Range", "Accept-Ranges", "Content-Type"} {
		if v := resp.Header.Get(k); v != "" {
			safe.Set(k, v)
		}
	}
	if endpoint == "download" {
		if v := resp.Header.Get("Content-Disposition"); v != "" {
			safe.Set("Content-Disposition", v)
		}
		safe.Set("X-Content-Type-Options", "nosniff")
	}
	if endpoint == "getCoverArt" {
		mimeOnly := strings.TrimSpace(strings.Split(ct, ";")[0])
		switch mimeOnly {
		case "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif":
			safe.Set("Content-Type", mimeOnly)
		default:
			_ = resp.Body.Close()
			return nil, model.NewError(model.ErrInvalidResponse, http.StatusUnsupportedMediaType, "不支持的封面图片格式")
		}
		safe.Set("X-Content-Type-Options", "nosniff")
		safe.Set("Content-Security-Policy", "default-src 'none'")
	}
	return &MediaStream{
		StatusCode: resp.StatusCode,
		Headers:    safe,
		Body:       resp.Body,
	}, nil
}

// RedactError removes configured Navidrome secrets and raw URLs before logging.
func (c *Client) RedactError(err error) string {
	if err == nil {
		return ""
	}
	msg := err.Error()
	s := c.Settings()
	for _, secret := range []string{s.Password, s.APIKey} {
		if secret != "" {
			msg = strings.ReplaceAll(msg, secret, config.HiddenPlaceholder)
		}
	}
	return msg
}
