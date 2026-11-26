package httpapi

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/recommend"
)

type stubRecommendAI struct {
	configured bool
	replyFn    func(ctx context.Context, callIdx int, sys, user string) (string, error)
	calls      atomic.Int32
}

func (a *stubRecommendAI) DirectComplete(ctx context.Context, systemPromptText, userPromptText string) (string, bool, error) {
	idx := int(a.calls.Add(1))
	if !a.configured {
		return "", false, nil
	}
	if a.replyFn == nil {
		return "", true, nil
	}
	out, err := a.replyFn(ctx, idx, systemPromptText, userPromptText)
	return out, true, err
}

type parsedSSEEvent struct {
	ID    string
	Event string
	Data  string
}

func readSSEEvents(t *testing.T, r io.Reader) []parsedSSEEvent {
	t.Helper()
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 2*1024*1024)
	var out []parsedSSEEvent
	var cur parsedSSEEvent
	hasFields := false
	for sc.Scan() {
		line := sc.Text()
		if line == "" {
			if hasFields {
				out = append(out, cur)
				cur = parsedSSEEvent{}
				hasFields = false
			}
			continue
		}
		if strings.HasPrefix(line, ":") {
			continue
		}
		hasFields = true
		switch {
		case strings.HasPrefix(line, "id: "):
			cur.ID = strings.TrimPrefix(line, "id: ")
		case strings.HasPrefix(line, "event: "):
			cur.Event = strings.TrimPrefix(line, "event: ")
		case strings.HasPrefix(line, "data: "):
			if cur.Data != "" {
				cur.Data += "\n"
			}
			cur.Data += strings.TrimPrefix(line, "data: ")
		}
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("scan SSE: %v", err)
	}
	if hasFields {
		out = append(out, cur)
	}
	return out
}

func TestRecommendHistoryAuthCSRFAndValidation(t *testing.T) {
	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		d.AuthEnabled = true
		d.AuthUserDigest = sha256.Sum256([]byte("admin"))
		d.AuthPassDigest = sha256.Sum256([]byte("secret-pass"))
		recSvc := recommend.NewService(recommend.Options{
			Store:     d.Store,
			Providers: d.Providers,
			Transport: d.Transport,
		})
		t.Cleanup(func() { _ = recSvc.Close() })
		d.Recommend = recSvc
	})

	// 1. Unauthenticated GET /api/recommendations/* -> 401.
	for _, path := range []string{
		"/api/recommendations/history",
		"/api/recommendations/daily",
		"/api/recommendations/daily/jobs/job-1/events",
	} {
		unauthGet := httptest.NewRecorder()
		srv.ServeHTTP(unauthGet, httptest.NewRequest(http.MethodGet, path, nil))
		if unauthGet.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401 for unauthenticated GET %s, got %d %s", path, unauthGet.Code, unauthGet.Body.String())
		}
	}

	// 2. Unauthenticated state-changing /api/recommendations/* without CSRF header -> 403.
	for _, tc := range []struct {
		method string
		path   string
		body   string
	}{
		{http.MethodPost, "/api/recommendations/history", `{"title":"Midnight City","artist":"M83"}`},
		{http.MethodPost, "/api/recommendations/daily/start", `{}`},
		{http.MethodDelete, "/api/recommendations/daily/jobs/job-1", ""},
	} {
		noCSRF := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
		noCSRFRec := httptest.NewRecorder()
		srv.ServeHTTP(noCSRFRec, noCSRF)
		if noCSRFRec.Code != http.StatusForbidden {
			t.Fatalf("expected 403 missing CSRF for %s %s, got %d %s", tc.method, tc.path, noCSRFRec.Code, noCSRFRec.Body.String())
		}
	}

	// 3. Unauthenticated POST /api/recommendations/history with CSRF header -> 401.
	withCSRF := httptest.NewRequest(http.MethodPost, "/api/recommendations/history", strings.NewReader(`{"title":"Midnight City","artist":"M83"}`))
	withCSRF.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	withCSRFRec := httptest.NewRecorder()
	srv.ServeHTTP(withCSRFRec, withCSRF)
	if withCSRFRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 with CSRF but no session, got %d %s", withCSRFRec.Code, withCSRFRec.Body.String())
	}

	// 4. Web login to acquire session cookie.
	loginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass"}`))
	loginReq.Header.Set("Content-Type", "application/json")
	loginReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	loginRec := httptest.NewRecorder()
	srv.ServeHTTP(loginRec, loginReq)
	if loginRec.Code != http.StatusOK {
		t.Fatalf("web login failed: %d %s", loginRec.Code, loginRec.Body.String())
	}
	var sessionCookie *http.Cookie
	for _, c := range loginRec.Result().Cookies() {
		if c.Name == auth.SessionCookieName && c.Path == "/api" && c.Value != "" {
			sessionCookie = c
			break
		}
	}
	if sessionCookie == nil {
		t.Fatalf("missing session cookie")
	}

	// 5. Cookie-authenticated POST without CSRF header -> 403.
	cookieNoCSRF := httptest.NewRequest(http.MethodPost, "/api/recommendations/history", strings.NewReader(`{"title":"Midnight City","artist":"M83"}`))
	cookieNoCSRF.AddCookie(sessionCookie)
	cookieNoCSRFRec := httptest.NewRecorder()
	srv.ServeHTTP(cookieNoCSRFRec, cookieNoCSRF)
	if cookieNoCSRFRec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for cookie POST without CSRF, got %d %s", cookieNoCSRFRec.Code, cookieNoCSRFRec.Body.String())
	}

	// 6. Validation errors: empty body / malformed JSON -> 422, blank title -> 400.
	for _, tc := range []struct {
		name     string
		body     string
		wantCode int
		wantSub  string
	}{
		{name: "empty body", body: "", wantCode: http.StatusUnprocessableEntity, wantSub: `"json_invalid"`},
		{name: "corrupt json", body: "{bad", wantCode: http.StatusUnprocessableEntity, wantSub: `"json_invalid"`},
		{name: "blank title", body: `{"title":"   ","artist":"M83"}`, wantCode: http.StatusBadRequest, wantSub: "播放历史曲目标题不能为空"},
	} {
		req := httptest.NewRequest(http.MethodPost, "/api/recommendations/history", strings.NewReader(tc.body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
		req.AddCookie(sessionCookie)
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		if rec.Code != tc.wantCode || !strings.Contains(rec.Body.String(), tc.wantSub) {
			t.Fatalf("%s: got status=%d body=%s, want status=%d containing %q", tc.name, rec.Code, rec.Body.String(), tc.wantCode, tc.wantSub)
		}
	}

	// 7. Valid single history POST -> 200 {"ok":true,"count":1}.
	postReq := httptest.NewRequest(http.MethodPost, "/api/recommendations/history", strings.NewReader(`{"track_id":"mk:1","title":"Midnight City","artist":"M83","genre":"Synthwave","source":"melokeep","duration_ms":243000,"played_ms":240000}`))
	postReq.Header.Set("Content-Type", "application/json")
	postReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	postReq.AddCookie(sessionCookie)
	postRec := httptest.NewRecorder()
	srv.ServeHTTP(postRec, postReq)
	if postRec.Code != http.StatusOK {
		t.Fatalf("POST history failed: %d %s", postRec.Code, postRec.Body.String())
	}
	var postResp struct {
		OK    bool `json:"ok"`
		Count int  `json:"count"`
	}
	if err := json.Unmarshal(postRec.Body.Bytes(), &postResp); err != nil || !postResp.OK || postResp.Count != 1 {
		t.Fatalf("unexpected POST history body: %s err=%v", postRec.Body.String(), err)
	}

	// 8. App Bearer auth (exempt from CSRF) batch POST -> 200 {"ok":true,"count":2}.
	appLoginReq := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"admin","password":"secret-pass","client_type":"app","device_name":"TestPhone"}`))
	appLoginReq.Header.Set("Content-Type", "application/json")
	appLoginReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	appLoginRec := httptest.NewRecorder()
	srv.ServeHTTP(appLoginRec, appLoginReq)
	if appLoginRec.Code != http.StatusOK {
		t.Fatalf("app login failed: %d %s", appLoginRec.Code, appLoginRec.Body.String())
	}
	var appLoginResp struct {
		Token string `json:"token"`
	}
	_ = json.Unmarshal(appLoginRec.Body.Bytes(), &appLoginResp)
	if appLoginResp.Token == "" {
		t.Fatalf("expected bearer token from app login")
	}

	batchReq := httptest.NewRequest(http.MethodPost, "/api/recommendations/history", strings.NewReader(`{"items":[{"title":"Time","artist":"Hans Zimmer","source":"navidrome","played_ms":180000}]}`))
	batchReq.Header.Set("Content-Type", "application/json")
	batchReq.Header.Set("Authorization", "Bearer "+appLoginResp.Token)
	batchRec := httptest.NewRecorder()
	srv.ServeHTTP(batchRec, batchReq)
	if batchRec.Code != http.StatusOK {
		t.Fatalf("bearer POST history failed: %d %s", batchRec.Code, batchRec.Body.String())
	}

	// 9. Authenticated GET /api/recommendations/history -> 200 with recorded items.
	getReq := httptest.NewRequest(http.MethodGet, "/api/recommendations/history?limit=10", nil)
	getReq.AddCookie(sessionCookie)
	getRec := httptest.NewRecorder()
	srv.ServeHTTP(getRec, getReq)
	if getRec.Code != http.StatusOK {
		t.Fatalf("GET history failed: %d %s", getRec.Code, getRec.Body.String())
	}
	var listResp struct {
		Items []recommend.HistoryEntry `json:"items"`
	}
	if err := json.Unmarshal(getRec.Body.Bytes(), &listResp); err != nil {
		t.Fatalf("decode GET history: %v", err)
	}
	if len(listResp.Items) != 2 {
		t.Fatalf("expected 2 history items, got %d (%+v)", len(listResp.Items), listResp.Items)
	}
}

func TestRecommendDailyStreamCacheRefreshAndCancel(t *testing.T) {
	itunesSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"results": []any{}})
	}))
	defer itunesSrv.Close()

	ai := &stubRecommendAI{
		configured: true,
		replyFn: func(ctx context.Context, callIdx int, sys, user string) (string, error) {
			offset := (callIdx - 1) * 100
			cands := make([]map[string]any, 0, 50)
			for i := 1; i <= 50; i++ {
				cands = append(cands, map[string]any{
					"title":    fmt.Sprintf("Daily Song %d", offset+i),
					"artist":   fmt.Sprintf("Daily Artist %d", offset+i),
					"reason":   fmt.Sprintf("推荐理由 #%d", offset+i),
					"category": "discover",
				})
			}
			raw, _ := json.Marshal(map[string]any{
				"curator_note": fmt.Sprintf("第 %d 批今日推荐", callIdx),
				"style_tags":   []string{"#DreamPop", "合成器", "深夜"},
				"candidates":   cands,
			})
			return string(raw), nil
		},
	}

	var searchCalls atomic.Int32
	prov := &stubProvider{
		id:         "AudiusMusicClient",
		engineName: "AudiusMusicClient",
		category:   "开放许可",
		label:      "Audius",
		searchFn: func(ctx context.Context, opts model.SearchOptions, emit func(model.ProviderTrack) error) (model.SearchSummary, error) {
			searchCalls.Add(1)
			kw := strings.TrimSpace(opts.Keyword)
			if strings.HasPrefix(kw, "Daily Song ") {
				parts := strings.Split(kw, " ")
				if len(parts) >= 6 {
					num := parts[2]
					_ = emit(model.ProviderTrack{
						Source:     "AudiusMusicClient",
						SourceID:   "aud-" + num,
						RootSource: "AudiusMusicClient",
						Title:      "Daily Song " + num,
						Artist:     "Daily Artist " + num,
						Album:      "Album " + num,
						Duration:   210,
					})
					return model.SearchSummary{Count: 1}, nil
				}
			}
			return model.SearchSummary{}, nil
		},
	}

	fixedNow := time.Date(2026, 11, 26, 10, 30, 0, 0, time.Local)
	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		reg, err := provider.NewRegistry([]provider.Entry{
			{
				Provider: prov,
				Meta:     provider.Metadata{Label: "Audius", Category: "开放许可"},
			},
		})
		if err != nil {
			t.Fatalf("new registry: %v", err)
		}
		d.Providers = reg
		recSvc := recommend.NewService(recommend.Options{
			Store:           d.Store,
			Providers:       reg,
			Transport:       d.Transport,
			AI:              ai,
			ITunesSearchURL: itunesSrv.URL,
			Now:             func() time.Time { return fixedNow },
		})
		t.Cleanup(func() { _ = recSvc.Close() })
		d.Recommend = recSvc
	})

	ts := httptest.NewServer(srv)
	defer ts.Close()

	// 1. GET /api/recommendations/daily is empty initially.
	initResp, err := http.Get(ts.URL + "/api/recommendations/daily")
	if err != nil {
		t.Fatalf("GET /api/recommendations/daily: %v", err)
	}
	initBody, _ := io.ReadAll(initResp.Body)
	_ = initResp.Body.Close()
	if initResp.StatusCode != http.StatusOK {
		t.Fatalf("GET initial daily status=%d body=%s", initResp.StatusCode, string(initBody))
	}
	var initDTO recommend.DailyResponseDTO
	if err := json.Unmarshal(initBody, &initDTO); err != nil {
		t.Fatalf("decode initial daily: %v", err)
	}
	if initDTO.Status != "empty" || initDTO.JobID != "" || len(initDTO.Items) != 0 || initDTO.TargetCount != 30 {
		t.Fatalf("expected empty initial daily DTO, got %+v", initDTO)
	}

	// 2. POST /api/recommendations/daily/start starts streaming job 1.
	startReq, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/recommendations/daily/start", nil)
	startReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	startResp, err := http.DefaultClient.Do(startReq)
	if err != nil {
		t.Fatalf("POST start 1: %v", err)
	}
	startBody, _ := io.ReadAll(startResp.Body)
	_ = startResp.Body.Close()
	if startResp.StatusCode != http.StatusOK {
		t.Fatalf("POST start 1 status=%d body=%s", startResp.StatusCode, string(startBody))
	}
	var started1 recommend.DailyResponseDTO
	if err := json.Unmarshal(startBody, &started1); err != nil {
		t.Fatalf("decode start 1: %v", err)
	}
	if started1.Status != "generating" || started1.JobID == "" || started1.RefreshSeq != 1 {
		t.Fatalf("expected generating job seq=1, got %+v", started1)
	}

	// 3. Connect to SSE GET /api/recommendations/daily/jobs/{ident}/events and verify meta, item, done.
	sseResp, err := http.Get(ts.URL + "/api/recommendations/daily/jobs/" + started1.JobID + "/events")
	if err != nil {
		t.Fatalf("GET SSE 1: %v", err)
	}
	if sseResp.StatusCode != http.StatusOK || !strings.Contains(sseResp.Header.Get("Content-Type"), "text/event-stream") {
		t.Fatalf("unexpected SSE headers: status=%d ct=%q", sseResp.StatusCode, sseResp.Header.Get("Content-Type"))
	}
	events := readSSEEvents(t, sseResp.Body)
	_ = sseResp.Body.Close()

	var sawMeta bool
	var itemCount int
	var done1 recommend.DoneEventPayload
	var sawDone bool
	for _, ev := range events {
		switch ev.Event {
		case "meta":
			var mp recommend.MetaEventPayload
			if err := json.Unmarshal([]byte(ev.Data), &mp); err != nil {
				t.Fatalf("unmarshal meta: %v", err)
			}
			if mp.Mode != "ai" || mp.RefreshSeq != 1 || len(mp.StyleTags) != 3 || mp.StyleTags[0] != "DreamPop" {
				t.Fatalf("unexpected meta payload: %+v", mp)
			}
			sawMeta = true
		case "item":
			var ip recommend.ItemEventPayload
			if err := json.Unmarshal([]byte(ev.Data), &ip); err != nil {
				t.Fatalf("unmarshal item: %v", err)
			}
			if ip.Item.Track.ID == "" {
				t.Fatalf("item event missing track ID: %+v", ip)
			}
			itemCount++
		case "done":
			if err := json.Unmarshal([]byte(ev.Data), &done1); err != nil {
				t.Fatalf("unmarshal done: %v", err)
			}
			sawDone = true
		}
	}
	if !sawMeta || itemCount != 30 || !sawDone || done1.Cancelled {
		t.Fatalf("incomplete SSE stream: meta=%v items=%d done=%v cancelled=%v", sawMeta, itemCount, sawDone, done1.Cancelled)
	}
	if done1.Daily.Status != "ready" || done1.Daily.JobID != "" || len(done1.Daily.Items) != 30 || done1.Daily.GeneratedAt <= 0 {
		t.Fatalf("unexpected done1.Daily: status=%s job=%q len=%d genAt=%d", done1.Daily.Status, done1.Daily.JobID, len(done1.Daily.Items), done1.Daily.GeneratedAt)
	}

	// 4. Immediately calling POST /api/recommendations/daily/start returns cached ready without job_id.
	prevSearches := searchCalls.Load()
	prevAICalls := ai.calls.Load()
	cachedReq, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/recommendations/daily/start", strings.NewReader(`{}`))
	cachedReq.Header.Set("Content-Type", "application/json")
	cachedReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	cachedResp, err := http.DefaultClient.Do(cachedReq)
	if err != nil {
		t.Fatalf("POST cached start: %v", err)
	}
	cachedBody, _ := io.ReadAll(cachedResp.Body)
	_ = cachedResp.Body.Close()
	if cachedResp.StatusCode != http.StatusOK {
		t.Fatalf("POST cached start status=%d body=%s", cachedResp.StatusCode, string(cachedBody))
	}
	var cachedDTO recommend.DailyResponseDTO
	if err := json.Unmarshal(cachedBody, &cachedDTO); err != nil {
		t.Fatalf("decode cached start: %v", err)
	}
	if cachedDTO.Status != "ready" || cachedDTO.JobID != "" || len(cachedDTO.Items) != 30 || cachedDTO.RefreshSeq != 1 {
		t.Fatalf("expected immediate cached ready response, got %+v", cachedDTO)
	}
	if cachedDTO.GeneratedAt != done1.Daily.GeneratedAt {
		t.Fatalf("cached GeneratedAt=%d != done.Daily.GeneratedAt=%d", cachedDTO.GeneratedAt, done1.Daily.GeneratedAt)
	}
	if searchCalls.Load() != prevSearches || ai.calls.Load() != prevAICalls {
		t.Fatalf("expected no AI or provider calls on cache hit")
	}

	// GET /api/recommendations/daily also returns ready snapshot.
	getDailyResp, err := http.Get(ts.URL + "/api/recommendations/daily")
	if err != nil {
		t.Fatalf("GET ready daily: %v", err)
	}
	getDailyBody, _ := io.ReadAll(getDailyResp.Body)
	_ = getDailyResp.Body.Close()
	var getDailyDTO recommend.DailyResponseDTO
	_ = json.Unmarshal(getDailyBody, &getDailyDTO)
	if getDailyDTO.Status != "ready" || len(getDailyDTO.Items) != 30 || getDailyDTO.GeneratedAt != done1.Daily.GeneratedAt {
		t.Fatalf("unexpected GET ready daily: %+v", getDailyDTO)
	}

	// 5. Force refresh increments refresh_seq and yields a distinct batch.
	batch1Keys := make(map[string]bool, 30)
	for _, it := range done1.Daily.Items {
		batch1Keys[recommend.TrackNormKey(it.Track.Title, it.Track.Artist)] = true
	}
	forceReq, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/recommendations/daily/start", strings.NewReader(`{"force":true,"seeds":[{"title":"Daydream","artist":"M83","weight":1.5}]}`))
	forceReq.Header.Set("Content-Type", "application/json")
	forceReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	forceResp, err := http.DefaultClient.Do(forceReq)
	if err != nil {
		t.Fatalf("POST force refresh: %v", err)
	}
	forceBody, _ := io.ReadAll(forceResp.Body)
	_ = forceResp.Body.Close()
	if forceResp.StatusCode != http.StatusOK {
		t.Fatalf("POST force refresh status=%d body=%s", forceResp.StatusCode, string(forceBody))
	}
	var started2 recommend.DailyResponseDTO
	_ = json.Unmarshal(forceBody, &started2)
	if started2.Status != "generating" || started2.JobID == "" || started2.RefreshSeq != 2 {
		t.Fatalf("expected generating seq=2, got %+v", started2)
	}

	sse2Resp, err := http.Get(ts.URL + "/api/recommendations/daily/jobs/" + started2.JobID + "/events")
	if err != nil {
		t.Fatalf("GET SSE 2: %v", err)
	}
	events2 := readSSEEvents(t, sse2Resp.Body)
	_ = sse2Resp.Body.Close()
	var done2 recommend.DoneEventPayload
	var sawDone2 bool
	for _, ev := range events2 {
		if ev.Event == "done" {
			_ = json.Unmarshal([]byte(ev.Data), &done2)
			sawDone2 = true
		}
	}
	if !sawDone2 || done2.Daily.RefreshSeq != 2 || len(done2.Daily.Items) != 30 {
		t.Fatalf("unexpected batch 2 done: saw=%v daily=%+v", sawDone2, done2.Daily)
	}
	for _, it := range done2.Daily.Items {
		if batch1Keys[recommend.TrackNormKey(it.Track.Title, it.Track.Artist)] {
			t.Fatalf("batch 2 repeated track from batch 1: %s - %s", it.Track.Title, it.Track.Artist)
		}
	}

	// SSE replay with Last-Event-ID omits earlier frames, and error paths return 404/400.
	replayReq, _ := http.NewRequest(http.MethodGet, ts.URL+"/api/recommendations/daily/jobs/"+started2.JobID+"/events", nil)
	replayReq.Header.Set("Last-Event-ID", "1")
	replayResp, err := http.DefaultClient.Do(replayReq)
	if err != nil {
		t.Fatalf("GET replay SSE: %v", err)
	}
	replayEvents := readSSEEvents(t, replayResp.Body)
	_ = replayResp.Body.Close()
	for _, ev := range replayEvents {
		if ev.ID == "1" {
			t.Fatalf("Last-Event-ID=1 replay unexpectedly included event id 1: %+v", ev)
		}
	}

	unkResp, err := http.Get(ts.URL + "/api/recommendations/daily/jobs/non-existent/events")
	if err != nil {
		t.Fatalf("GET unknown SSE: %v", err)
	}
	_ = unkResp.Body.Close()
	if unkResp.StatusCode != http.StatusNotFound {
		t.Fatalf("expected 404 for unknown job SSE, got %d", unkResp.StatusCode)
	}
	badAfterResp, err := http.Get(ts.URL + "/api/recommendations/daily/jobs/" + started2.JobID + "/events?after=bad")
	if err != nil {
		t.Fatalf("GET bad after SSE: %v", err)
	}
	_ = badAfterResp.Body.Close()
	if badAfterResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid after, got %d", badAfterResp.StatusCode)
	}
}

func TestRecommendDailyJobCancellation(t *testing.T) {
	entered := make(chan struct{})
	var enterOnce sync.Once
	blockingAI := &stubRecommendAI{
		configured: true,
		replyFn: func(ctx context.Context, callIdx int, sys, user string) (string, error) {
			enterOnce.Do(func() { close(entered) })
			<-ctx.Done()
			return "", ctx.Err()
		},
	}

	srv, _, _ := newTestServer(t, func(d *Dependencies, _ string) {
		recSvc := recommend.NewService(recommend.Options{
			Store:     d.Store,
			Providers: d.Providers,
			Transport: d.Transport,
			AI:        blockingAI,
		})
		t.Cleanup(func() { _ = recSvc.Close() })
		d.Recommend = recSvc
	})

	ts := httptest.NewServer(srv)
	defer ts.Close()

	startReq, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/recommendations/daily/start", bytes.NewReader([]byte(`{}`)))
	startReq.Header.Set("Content-Type", "application/json")
	startReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	startResp, err := http.DefaultClient.Do(startReq)
	if err != nil {
		t.Fatalf("POST start: %v", err)
	}
	startBody, _ := io.ReadAll(startResp.Body)
	_ = startResp.Body.Close()
	var started recommend.DailyResponseDTO
	_ = json.Unmarshal(startBody, &started)
	if started.JobID == "" {
		t.Fatalf("expected job_id, got %s", string(startBody))
	}

	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for job to enter AI step")
	}

	type sseRes struct {
		events []parsedSSEEvent
		err    error
	}
	sseCh := make(chan sseRes, 1)
	go func() {
		resp, gErr := http.Get(ts.URL + "/api/recommendations/daily/jobs/" + started.JobID + "/events")
		if gErr != nil {
			sseCh <- sseRes{err: gErr}
			return
		}
		defer resp.Body.Close()
		evs := readSSEEvents(t, resp.Body)
		sseCh <- sseRes{events: evs}
	}()

	delReq, _ := http.NewRequest(http.MethodDelete, ts.URL+"/api/recommendations/daily/jobs/"+started.JobID, nil)
	delReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	delResp, err := http.DefaultClient.Do(delReq)
	if err != nil {
		t.Fatalf("DELETE job: %v", err)
	}
	delBody, _ := io.ReadAll(delResp.Body)
	_ = delResp.Body.Close()
	if delResp.StatusCode != http.StatusOK || !strings.Contains(string(delBody), `"cancelled":true`) {
		t.Fatalf("expected cancelled:true, got status=%d body=%s", delResp.StatusCode, string(delBody))
	}

	select {
	case res := <-sseCh:
		if res.err != nil {
			t.Fatalf("SSE error: %v", res.err)
		}
		var done recommend.DoneEventPayload
		var sawDone bool
		for _, ev := range res.events {
			if ev.Event == "done" {
				_ = json.Unmarshal([]byte(ev.Data), &done)
				sawDone = true
			}
		}
		if !sawDone || !done.Cancelled {
			t.Fatalf("expected cancelled done SSE event, got sawDone=%v done=%+v", sawDone, done)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for SSE close after cancellation")
	}

	// Idempotent DELETE on unknown/finished job also returns 200 {"cancelled":true}.
	delMissingReq, _ := http.NewRequest(http.MethodDelete, ts.URL+"/api/recommendations/daily/jobs/non-existent", nil)
	delMissingReq.Header.Set(auth.CSRFHeaderName, auth.CSRFHeaderValue)
	delMissingResp, err := http.DefaultClient.Do(delMissingReq)
	if err != nil {
		t.Fatalf("DELETE missing job: %v", err)
	}
	_ = delMissingResp.Body.Close()
	if delMissingResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 on idempotent DELETE, got %d", delMissingResp.StatusCode)
	}
}
