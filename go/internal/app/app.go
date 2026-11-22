package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/agonie0v0/melokeep/internal/agent"
	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/bot"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/download"
	"github.com/agonie0v0/melokeep/internal/files"
	"github.com/agonie0v0/melokeep/internal/helper"
	"github.com/agonie0v0/melokeep/internal/httpapi"
	"github.com/agonie0v0/melokeep/internal/legacyimport"
	"github.com/agonie0v0/melokeep/internal/library"
	"github.com/agonie0v0/melokeep/internal/library/index"
	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/navidrome"
	"github.com/agonie0v0/melokeep/internal/operations"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/catalog"
	"github.com/agonie0v0/melokeep/internal/provider/plugin"
	"github.com/agonie0v0/melokeep/internal/search"
	"github.com/agonie0v0/melokeep/internal/store"
	"github.com/agonie0v0/melokeep/internal/transport"
)

// StartupRecovery holds the aggregated result of startup crash recovery checks.
type StartupRecovery struct {
	Files         files.RecoveryReport
	OutboxUnknown int64
}

// App is the fully assembled MeloKeep application instance.
type App struct {
	Cfg         Config
	AbsDataDir  string
	AbsWorkDir  string
	Store       *store.Store
	Transport   *transport.Pool
	Supervisor  *helper.Supervisor
	Tagger      *helper.TaggerClient
	Providers   *provider.Registry
	Plugins     *plugin.Manager
	AssetLocks  *files.AssetLockManager
	Files       *files.Coordinator
	Media       *media.Runner
	Tags        *media.TagService
	Indexer     *index.Indexer
	Search      *search.Service
	Download    *download.Service
	Library     *library.Service
	Agent       *agent.Service
	Navidrome   *navidrome.Client
	Bot         *bot.Service
	Operations  *operations.Registry
	Idempotency *store.IdempotencyStore
	Logs        *httpapi.LogBuffer
	HTTP        *httpapi.Server
	Handler     http.Handler
	Recovery    StartupRecovery

	snapMu  sync.RWMutex
	snapRev int64
	snap    config.Snapshot

	instanceLock *FileLock
	rootLocks    []*FileLock

	closeOnce  sync.Once
	restartCh  chan struct{}
	restarting atomic.Bool
}

// New wires all MeloKeep subsystems, enforces OS single-instance & root write locks,
// runs startup crash recovery, and prepares the HTTP handler.
func New(ctx context.Context, cfg Config) (*App, error) {
	if strings.TrimSpace(cfg.ConfigError) != "" {
		return nil, errors.New(cfg.ConfigError)
	}
	if (strings.TrimSpace(cfg.AuthUser) == "") != (strings.TrimSpace(cfg.AuthPass) == "") {
		return nil, errors.New("MELOKEEP_USER 与 MELOKEEP_PASS 必须同时设置；拒绝以半配置认证启动")
	}
	if cfg.Budgets == (config.ResourceBudgets{}) {
		cfg.Budgets = config.DefaultResourceBudgets()
	}
	if strings.TrimSpace(cfg.EngineVersion) == "" {
		cfg.EngineVersion = DefaultVersion
	}
	if strings.TrimSpace(cfg.DataDir) == "" {
		cfg.DataDir = "data"
	}
	absDataDir, err := filepath.Abs(cfg.DataDir)
	if err != nil {
		return nil, fmt.Errorf("resolve data dir: %w", err)
	}
	if err := os.MkdirAll(filepath.Join(absDataDir, "go", "tmp"), 0o750); err != nil {
		return nil, fmt.Errorf("create data/go/tmp: %w", err)
	}
	if !cfg.ReadOnly {
		if err := os.MkdirAll(filepath.Join(absDataDir, "go", "staging"), 0o750); err != nil {
			return nil, fmt.Errorf("create data/go/staging: %w", err)
		}
		if err := os.MkdirAll(filepath.Join(absDataDir, "go", "history"), 0o750); err != nil {
			return nil, fmt.Errorf("create data/go/history: %w", err)
		}
	}
	if len(cfg.MusicRoots) == 0 {
		cfg.MusicRoots = []string{"melokeep_outputs"}
	}

	a := &App{
		Cfg:        cfg,
		AbsDataDir: absDataDir,
		Logs:       httpapi.NewLogBuffer(800),
		restartCh:  make(chan struct{}, 1),
	}
	if strings.TrimSpace(cfg.ConfigWarning) != "" {
		a.Logs.Push("warn", cfg.ConfigWarning, "system", "")
	}
	cleanupOnErr := true
	defer func() {
		if cleanupOnErr {
			_ = a.Close()
		}
	}()

	// 1. Acquire single-instance OS lock on <DataDir>/go/melokeep.lock.
	instLock, err := AcquireFileLock(filepath.Join(absDataDir, "go", "melokeep.lock"), "instance", cfg.EngineVersion)
	if err != nil {
		return nil, err
	}
	a.instanceLock = instLock

	// 2. Prepare music roots and acquire per-root write locks when writable.
	absRoots := make([]string, 0, len(cfg.MusicRoots))
	for _, rawRoot := range cfg.MusicRoots {
		rAbs, err := filepath.Abs(rawRoot)
		if err != nil {
			return nil, fmt.Errorf("resolve music root %q: %w", rawRoot, err)
		}
		if !cfg.ReadOnly {
			if err := os.MkdirAll(rAbs, 0o750); err != nil {
				return nil, fmt.Errorf("create music root %q: %w", rAbs, err)
			}
			canon, cErr := files.CanonicalizeRoot(rAbs)
			if cErr != nil {
				return nil, cErr
			}
			rLock, lErr := AcquireFileLock(filepath.Join(canon, ".melokeep-write.lock"), "root:"+canon, cfg.EngineVersion)
			if lErr != nil {
				return nil, fmt.Errorf("root write lock %s: %w", canon, lErr)
			}
			a.rootLocks = append(a.rootLocks, rLock)
			rAbs = canon
		}
		absRoots = append(absRoots, rAbs)
	}
	a.AbsWorkDir = absRoots[0]

	// 3. Open SQLite store (WAL, foreign_keys=ON, schema migrations).
	st, err := store.Open(ctx, store.Options{Path: store.DefaultDBPath(absDataDir)})
	if err != nil {
		return nil, err
	}
	a.Store = st

	// 4. Refuse write-mode startup if an unrecovered legacy import blocked write switch.
	if !cfg.ReadOnly && !cfg.AllowBlockedWrite {
		if err := a.checkMigrationWriteGuard(ctx); err != nil {
			return nil, err
		}
	}

	// 5. Reconcile managed roots with DB roots table.
	managedRoots, err := a.reconcileRoots(ctx, absRoots)
	if err != nil {
		return nil, err
	}

	// 6. Wire transport, tag helper supervisor, built-in providers and the JS plugin manager.
	var poolOpts []transport.Option
	if cfg.AllowTestLoopback {
		poolOpts = append(poolOpts, transport.WithAllowTestLoopback(true))
	}
	a.Transport = transport.NewPool(poolOpts...)

	helperDir := DiscoverHelperDir(cfg.HelperDir)
	pythonExe := DiscoverPython(cfg.PythonPath)
	var taggerScript string
	if helperDir != "" {
		tPath := filepath.Join(helperDir, "mutagen", "tagger.py")
		if _, err := os.Stat(tPath); err == nil {
			taggerScript = tPath
		}
	}
	a.Supervisor = helper.NewSupervisor(helper.SupervisorConfig{
		PythonExe:    pythonExe,
		TaggerScript: taggerScript,
		WorkRoot:     filepath.Join(absDataDir, "go", "tmp"),
		Budgets:      cfg.Budgets,
	})
	a.Tagger = helper.NewTaggerClient(a.Supervisor)

	reg, err := catalog.NewFullRegistry(catalog.Options{
		Pool:      a.Transport,
		Endpoints: cfg.ProviderEndpoints,
	})
	if err != nil {
		return nil, err
	}
	a.Providers = reg

	// Installed and enabled plugins must be hot-registered before the persisted runtime
	// config is validated against the registry. LoadAll isolates per-plugin failures.
	a.Plugins = plugin.NewManager(a.Store, a.Transport, a.Providers, filepath.Join(absDataDir, "go", "plugins"), a.Logs.Push)
	if err := a.Plugins.LoadAll(ctx); err != nil {
		a.Logs.Push("warn", "加载已安装插件失败（已跳过）: "+err.Error(), "plugin", "")
	}

	// 7. Load initial RuntimeConfig from DB if present, overlay runtime paths.
	rtCfg, rtRev := a.loadInitialRuntimeConfig(ctx)
	a.snapRev = rtRev
	a.snap = config.NewSnapshot(rtCfg, cfg.Budgets)

	// 8. Files coordinator, media runner, tag service, library indexer, search.
	a.AssetLocks = files.NewAssetLockManager(5 * time.Second)
	a.Files = files.NewCoordinator(a.Store, a.AssetLocks, managedRoots)
	a.Files.SetHistoryDir(filepath.Join(absDataDir, "go", "history"))
	a.Media = media.NewRunner(cfg.Budgets, cfg.FFmpegPath, cfg.FFprobePath, cfg.FpcalcPath)
	a.Tags = media.NewTagService(a.Tagger, a.Media)
	a.Indexer = index.NewIndexer(a.Store, a.AssetLocks, a.Tags)
	a.Search = search.NewService(a.Store, a.Providers, cfg.Budgets, search.WithLogger(a.Logs.Push))

	// 9. Navidrome client (prefer durable_records, fallback to migrated settings namespace).
	var navCfg navidrome.Settings
	navClient, err := navidrome.NewClient(a.Transport, a.Store, navCfg, func(rctx context.Context, tr model.TrackDTO) {
		if a.Store == nil || tr.ID == "" {
			return
		}
		if raw, mErr := json.Marshal(tr); mErr == nil {
			_, _ = a.Store.PutDurableRecord(rctx, "track_dto", tr.ID, string(raw), 0)
		}
	})
	if err != nil {
		return nil, err
	}
	if loaded, lErr := navClient.LoadSettings(ctx); lErr == nil && loaded.URL != "" {
		navClient.SetSettings(loaded)
	} else if rec, ok, sErr := a.Store.GetSetting(ctx, store.NamespaceNavidrome); sErr == nil && ok {
		var ns navidrome.Settings
		if json.Unmarshal([]byte(rec.BodyJSON), &ns) == nil {
			if v, vErr := navidrome.ValidateSettings(ns); vErr == nil {
				navClient.SetSettings(v)
			}
		}
	}
	a.Navidrome = navClient

	// 10. Library scraper & service + Navidrome auto-scan hook.
	scraper := library.NewScraper(a.Transport, a.Providers, a.Media, library.ScraperOptions{
		AcoustIDKeyFunc: func(kctx context.Context) string {
			if a.Store == nil {
				return ""
			}
			rec, ok, err := a.Store.GetSetting(kctx, "library_credentials")
			if err != nil || !ok {
				return ""
			}
			var creds struct {
				AcoustIDAPIKey string `json:"acoustid_api_key"`
			}
			if json.Unmarshal([]byte(rec.BodyJSON), &creds) == nil {
				return strings.TrimSpace(creds.AcoustIDAPIKey)
			}
			return ""
		},
	})
	a.Library = library.NewService(a.Store, a.Files, a.Tags, a.Media, scraper, library.ServiceOptions{
		SnapshotProvider: a.Snapshot,
		Logger:           a.Logs.Push,
		NavidromeHook: func(hctx context.Context) error {
			if a.Cfg.ReadOnly || a.Navidrome == nil {
				return nil
			}
			ns := a.Navidrome.Settings()
			if !ns.Enabled || !ns.AutoScan {
				return nil
			}
			_, err := a.Navidrome.StartScan(hctx, auth.SystemActor())
			return err
		},
	})

	// 11. Download service with auto-organize delegation.
	a.Download = download.NewService(
		a.Store,
		a.Transport,
		a.Providers,
		a.Files,
		a.Media,
		a.Tags,
		download.Options{
			StagingRoot:        filepath.Join(absDataDir, "go", "staging"),
			Budgets:            cfg.Budgets,
			SnapshotProvider:   a.Snapshot,
			AutoOrganizeRunner: a.Library.AutoOrganizeDownload,
			Logger:             a.Logs.Push,
		},
	)

	// 12. Operations registry + AI Agent service.
	a.Idempotency = store.NewIdempotencyStore(a.Store, 24*time.Hour)
	a.Operations = operations.NewRegistry(a.Idempotency)
	if err := operations.RegisterCoreSpecs(a.Operations); err != nil {
		return nil, err
	}

	agentClient := agent.NewClient(a.Transport, cfg.Budgets)
	a.Agent = agent.NewService(a.Store, a.Operations, agentClient, agent.Options{
		Budgets: cfg.Budgets,
		ProxyResolver: func() string {
			return a.Snapshot().Runtime.ProxyURL
		},
		ConfigRevisionFunc: func() string {
			return a.Snapshot().Revision
		},
		WriteToggle: func(_ context.Context, actor auth.Actor) (bool, string) {
			if a.Cfg.ReadOnly {
				return false, "只读模式禁止 AI 执行写操作"
			}
			if actor.ReadOnlyAI {
				return false, "当前为只读 AI 会话"
			}
			return true, ""
		},
		TaskLookup:        a.lookupAgentTasks,
		PendingReconciler: a.reconcileAgentPendingWrite,
		Logger:            a.Logs.Push,
	})
	if err := agent.RegisterAgentHandlers(a.Operations, a.Agent); err != nil {
		return nil, err
	}

	// 13. Bot & notification service.
	botCfg := bot.DefaultConfig()
	if rec, ok, err := a.Store.GetDurableRecord(ctx, "bot_setting", "config"); err == nil && ok {
		var stored bot.Config
		if json.Unmarshal([]byte(rec.BodyJSON), &stored) == nil {
			botCfg = bot.SanitizeSecrets(stored)
		}
	}
	botCfg.AllowTestLoopback = cfg.AllowTestLoopback
	newBotOpts := func(initCfg bot.Config) bot.ServiceOptions {
		return bot.ServiceOptions{
			Store:    a.Store,
			Pool:     a.Transport,
			LocalAPI: bot.NewLocalBotAPI(bot.LocalBotAPISpec{DataDir: filepath.Join(absDataDir, "telegram-bot-api")}),
			Backend:  &botBackendAdapter{app: a},
			Initial:  initCfg,
			ReadOnly: cfg.ReadOnly,
			OnLog: func(level, msg, cat, taskID string) {
				if a.Logs != nil {
					a.Logs.Push(level, msg, cat, taskID)
				}
			},
			ProjectProxy: func() string {
				return a.Snapshot().Runtime.ProxyURL
			},
		}
	}
	botSvc, err := bot.NewService(ctx, newBotOpts(botCfg))
	if err != nil {
		if a.Logs != nil {
			a.Logs.Push("warn", fmt.Sprintf("已保存的机器人配置校验未通过（%v），已暂停启用机器人渠道以保证服务正常启动；请前往设置页检查并重新保存", err), "bot", "")
		}
		fallbackCfg := botCfg
		fallbackCfg.TGEnabled = false
		fallbackCfg.WeComEnabled = false
		fallbackCfg.FeishuEnabled = false
		fallbackCfg.FeishuBotEnabled = false
		fallbackCfg.DingTalkEnabled = false
		fallbackCfg.DingTalkBotEnabled = false
		fallbackCfg.DiscordEnabled = false
		fallbackCfg.DiscordBotEnabled = false
		botSvc, err = bot.NewService(ctx, newBotOpts(fallbackCfg))
		if err != nil {
			defCfg := bot.DefaultConfig()
			defCfg.AllowTestLoopback = cfg.AllowTestLoopback
			botSvc, err = bot.NewService(ctx, newBotOpts(defCfg))
			if err != nil {
				return nil, err
			}
		}
	}
	a.Bot = botSvc

	// 14. Execute deterministic startup crash recovery before enabling watchers/outbox/HTTP.
	if !cfg.ReadOnly {
		fileRec, err := a.Files.RecoverOnStartup(ctx)
		if err != nil {
			return nil, fmt.Errorf("files recovery: %w", err)
		}
		a.Recovery.Files = fileRec
		outboxUnknown, err := a.Bot.RecoverOnStartup(ctx)
		if err != nil {
			return nil, fmt.Errorf("bot recovery: %w", err)
		}
		a.Recovery.OutboxUnknown = outboxUnknown
		if err := a.Agent.RecoverOnStartup(ctx); err != nil {
			return nil, fmt.Errorf("agent recovery: %w", err)
		}
		if _, err := a.Library.RecoverOnStartup(ctx); err != nil {
			return nil, fmt.Errorf("library recovery: %w", err)
		}
		if n, expErr := a.Files.ExpireBackups(ctx, time.Now()); expErr == nil && n > 0 && a.Logs != nil {
			a.Logs.Push("info", fmt.Sprintf("启动时已清理 %d 条过期历史备份", n), "library", "")
		}
	}

	// 15. Build HTTP API server + optional read-only guard.
	staticFS := cfg.FrontendFS
	if staticFS == nil {
		staticFS, _ = DiscoverStaticFS(cfg.StaticDir)
	}
	authEnabled := strings.TrimSpace(cfg.AuthUser) != "" && strings.TrimSpace(cfg.AuthPass) != ""
	srv, err := httpapi.NewServer(httpapi.Dependencies{
		Store:           a.Store,
		Sessions:        auth.NewSessionManagerWithStore(a.Store),
		RateLimiter:     auth.NewLoginRateLimiter(),
		AuthEnabled:     authEnabled,
		AuthUserDigest:  digestCred(cfg.AuthUser),
		AuthPassDigest:  digestCred(cfg.AuthPass),
		CookieSecure:    cfg.CookieSecure,
		TrustedProxies:  cfg.TrustedProxies,
		InitialSnapshot: a.Snapshot(),
		Budgets:         cfg.Budgets,
		Providers:       a.Providers,
		Plugins:         a.Plugins,
		Transport:       a.Transport,
		Search:          a.Search,
		Download:        a.Download,
		Files:           a.Files,
		Media:           a.Media,
		Tags:            a.Tags,
		Indexer:         a.Indexer,
		Library:         a.Library,
		Agent:           a.Agent,
		Operations:      a.Operations,
		Bot:             &botGatewayAdapter{svc: a.Bot, allowTestLoopback: cfg.AllowTestLoopback},
		Navidrome:       &navidromeGatewayAdapter{client: a.Navidrome, app: a},
		Logs:            a.Logs,
		FrontendFS:      staticFS,
		EngineVersion:   cfg.EngineVersion,
		BuildInfo:       cfg.BuildInfo,
		ReadOnly:        cfg.ReadOnly,
		IsWindows:       runtime.GOOS == "windows",
		WorkDir:         a.AbsWorkDir,
		RestartCallback: func() {
			a.restarting.Store(true)
			select {
			case a.restartCh <- struct{}{}:
			default:
			}
		},
	})
	if err != nil {
		return nil, err
	}
	a.HTTP = srv
	if err := RegisterDomainOperations(a.Operations, a); err != nil {
		return nil, err
	}
	if cfg.ReadOnly {
		a.Handler = readOnlyMiddleware(srv)
	} else {
		a.Handler = srv
	}

	a.Logs.Push("info", fmt.Sprintf("MeloKeep Go 引擎启动就绪 (read_only=%v, roots=%d, providers=%d)", cfg.ReadOnly, len(managedRoots), len(a.Providers.IDs())), "system", "")
	cleanupOnErr = false
	return a, nil
}

// RestartRequested reports whether shutdown was triggered by an in-process restart request.
func (a *App) RestartRequested() bool {
	return a.restarting.Load()
}

// Snapshot returns the latest RuntimeConfig snapshot, refreshing automatically if persisted settings changed.
func (a *App) Snapshot() config.Snapshot {
	a.snapMu.RLock()
	cached := a.snap
	cachedRev := a.snapRev
	a.snapMu.RUnlock()

	if a.Store == nil {
		return cached
	}
	rec, ok, err := a.Store.GetSetting(context.Background(), store.NamespaceRuntimeConfig)
	if err != nil || !ok || rec.Revision == cachedRev {
		return cached
	}
	var rt config.RuntimeConfig
	if json.Unmarshal([]byte(rec.BodyJSON), &rt) != nil {
		return cached
	}
	rt.Sources = a.sanitizeSources(rt.Sources)
	rt.DataDir = a.AbsDataDir
	rt.WorkDir = a.AbsWorkDir
	next := config.NewSnapshot(rt, a.Cfg.Budgets)

	a.snapMu.Lock()
	if rec.Revision != a.snapRev {
		a.snapRev = rec.Revision
		a.snap = next
	}
	out := a.snap
	a.snapMu.Unlock()
	return out
}

// Run starts background workers (when writable) and serves HTTP until ctx is cancelled or restart is requested.
func (a *App) Run(ctx context.Context) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	if !a.Cfg.ReadOnly {
		if w := a.Library.Watcher(); w != nil {
			w.Start(runCtx)
		}
		if a.Bot != nil {
			if err := a.Bot.SyncLocalAPI(runCtx); err != nil && a.Logs != nil {
				a.Logs.Push("warning", "内置 Telegram Bot API 启动失败: "+a.Bot.RedactError(err), "bot", "")
			}
			a.Bot.StartWorkers(runCtx)
		}
		go a.runOutboxLoop(runCtx)
		go a.runBackupExpiryLoop(runCtx)
	}
	var bgWG sync.WaitGroup
	if !a.Cfg.ReadOnly && a.Indexer != nil && a.Store != nil {
		bgWG.Add(1)
		go func() {
			defer bgWG.Done()
			a.scanIndexedRoots(runCtx)
			a.runLibraryRescanLoop(runCtx)
		}()
	}

	addr := fmt.Sprintf("%s:%d", a.Cfg.Host, a.Cfg.Port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen %s: %w", addr, err)
	}
	httpSrv := &http.Server{
		Handler:           a.Handler,
		ReadHeaderTimeout: 15 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		err := httpSrv.Serve(ln)
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
			return
		}
		errCh <- nil
	}()

	select {
	case <-runCtx.Done():
	case <-a.restartCh:
	case err := <-errCh:
		cancel()
		bgWG.Wait()
		_ = a.Close()
		return err
	}

	cancel()
	shutCtx, shutCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutCancel()
	_ = httpSrv.Shutdown(shutCtx)
	<-errCh
	bgWG.Wait()
	return a.Close()
}

func (a *App) scanIndexedRoots(ctx context.Context) {
	if a.Cfg.ReadOnly || a.Indexer == nil || a.Store == nil {
		return
	}
	roots, err := a.Store.ListRoots(ctx)
	if err != nil {
		return
	}
	for _, r := range roots {
		if ctx.Err() != nil {
			return
		}
		if !r.Enabled {
			continue
		}
		if _, err := a.Indexer.ScanRoot(ctx, r); err != nil && a.Logs != nil {
			a.Logs.Push("warning", "曲库目录初始索引失败: "+err.Error(), "library", r.ID)
		}
	}
}

const libraryRescanInterval = 60 * time.Second

func (a *App) runLibraryRescanLoop(ctx context.Context) {
	if a.Cfg.ReadOnly || a.Indexer == nil || a.Store == nil {
		return
	}
	ticker := time.NewTicker(libraryRescanInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			a.scanIndexedRoots(ctx)
		}
	}
}

// Close gracefully tears down all subsystems in reverse initialization order and releases OS locks.
func (a *App) Close() error {
	var firstErr error
	a.closeOnce.Do(func() {
		if a.Bot != nil {
			a.Bot.Close()
		}
		if a.Agent != nil {
			_ = a.Agent.Close()
		}
		if a.Library != nil {
			_ = a.Library.Close()
		}
		if a.Download != nil {
			_ = a.Download.Close()
		}
		if a.Search != nil {
			_ = a.Search.Close()
		}
		if a.Supervisor != nil {
			a.Supervisor.Close()
		}
		if a.Transport != nil {
			a.Transport.CloseAll()
		}
		if a.Store != nil {
			if err := a.Store.Close(); err != nil && firstErr == nil {
				firstErr = err
			}
		}
		for _, rl := range a.rootLocks {
			if rl != nil {
				_ = rl.Release()
			}
		}
		if a.instanceLock != nil {
			_ = a.instanceLock.Release()
		}
	})
	return firstErr
}

func (a *App) runOutboxLoop(ctx context.Context) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if a.Bot != nil && !a.Cfg.ReadOnly {
				_, _ = a.Bot.ProcessOutboxOnce(ctx, 20)
			}
		}
	}
}

func (a *App) runBackupExpiryLoop(ctx context.Context) {
	ticker := time.NewTicker(6 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if a.Files != nil && !a.Cfg.ReadOnly {
				if n, err := a.Files.ExpireBackups(ctx, time.Now()); err == nil && n > 0 && a.Logs != nil {
					a.Logs.Push("info", fmt.Sprintf("定时清理了 %d 条过期历史备份", n), "library", "")
				}
			}
		}
	}
}

func (a *App) checkMigrationWriteGuard(ctx context.Context) error {
	var reportJSON string
	err := a.Store.Reader().QueryRowContext(ctx, `
		SELECT report_json FROM import_runs
		WHERE state = 'completed'
		ORDER BY completed_at DESC
		LIMIT 1
	`).Scan(&reportJSON)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	var rep legacyimport.ImportReport
	if json.Unmarshal([]byte(reportJSON), &rep) == nil && rep.BlocksWriteSwitch {
		return model.NewError(
			model.ErrConflict,
			http.StatusConflict,
			"检测到未完成的旧版迁移操作或损坏曲目（blocks_write_switch=true），禁止直接切换写模式；请先以 --read-only 检查修复",
		)
	}
	return nil
}

func (a *App) reconcileRoots(ctx context.Context, absRoots []string) ([]files.Root, error) {
	existing, err := a.Store.ListRoots(ctx)
	if err != nil {
		return nil, err
	}
	byCanon := make(map[string]store.RootRecord, len(existing))
	for _, r := range existing {
		byCanon[r.CanonicalPath] = r
	}

	out := make([]files.Root, 0, len(absRoots))
	seenID := make(map[string]bool)
	for _, raw := range absRoots {
		canon, err := files.CanonicalizeRoot(raw)
		if err != nil && a.Cfg.ReadOnly {
			// In read-only mode, a configured root may match an existing DB record even if read-only dir creation wasn't run.
			canon = raw
		} else if err != nil {
			return nil, err
		}
		existingID := ""
		if rec, ok := byCanon[canon]; ok {
			existingID = rec.ID
		}
		if a.Cfg.ReadOnly {
			if rec, ok := byCanon[canon]; ok {
				root := files.RootFromRecord(rec)
				out = append(out, root)
				seenID[root.ID] = true
				continue
			}
			id := "readonly-root"
			if seenID[id] {
				id = fmt.Sprintf("readonly-root-%d", len(out)+1)
			}
			out = append(out, files.Root{ID: id, CanonicalPath: canon})
			seenID[id] = true
			continue
		}
		root, rec, err := files.RegisterRoot(canon, existingID)
		if err != nil {
			return nil, err
		}
		if err := a.Store.UpsertRoot(ctx, rec); err != nil {
			return nil, err
		}
		out = append(out, root)
		seenID[root.ID] = true
	}
	for _, rec := range existing {
		if seenID[rec.ID] {
			continue
		}
		if st, statErr := os.Stat(rec.CanonicalPath); statErr == nil && st.IsDir() {
			if a.Cfg.ReadOnly {
				out = append(out, files.RootFromRecord(rec))
				seenID[rec.ID] = true
				continue
			}
			if root, _, rErr := files.RegisterRoot(rec.CanonicalPath, rec.ID); rErr == nil {
				out = append(out, root)
				seenID[rec.ID] = true
			}
		} else if !a.Cfg.ReadOnly && rec.Enabled && errors.Is(statErr, os.ErrNotExist) {
			activeCount, countErr := a.Store.CountActiveAssetsByRoot(ctx, rec.ID)
			if countErr == nil && activeCount == 0 {
				const reason = "目录已不存在且无有效曲目，已自动停用"
				if disErr := a.Store.SetRootEnabled(ctx, rec.ID, false, reason); disErr == nil && a.Logs != nil {
					a.Logs.Push("info", fmt.Sprintf("已自动停用无效曲库旧目录：%s (id=%s)", rec.CanonicalPath, rec.ID), "library", rec.ID)
				}
			}
		}
	}
	return out, nil
}

func (a *App) loadInitialRuntimeConfig(ctx context.Context) (config.RuntimeConfig, int64) {
	rt := config.DefaultRuntimeConfig()
	var rev int64
	if rec, ok, err := a.Store.GetSetting(ctx, store.NamespaceRuntimeConfig); err == nil && ok {
		var stored config.RuntimeConfig
		if json.Unmarshal([]byte(rec.BodyJSON), &stored) == nil {
			rt = stored
			rev = rec.Revision
		}
	}
	rt.Sources = a.sanitizeSources(rt.Sources)
	if rt.ResultsPerSource <= 0 {
		rt.ResultsPerSource = 5
	}
	if rt.DownloadConcurrency <= 0 {
		rt.DownloadConcurrency = 2
	}
	if rt.DownloadLayout == "" {
		rt.DownloadLayout = "source"
	}
	if rt.LyricsMode == "" {
		rt.LyricsMode = "both"
	}
	rt.DataDir = a.AbsDataDir
	rt.WorkDir = a.AbsWorkDir
	return rt, rev
}

// sanitizeSources keeps only sources that are currently registered (built-in or enabled plugin),
// normalized to their canonical provider ID. Legacy configs that only reference retired or
// uninstalled sources silently fall back to the compliant default source list.
func (a *App) sanitizeSources(sources []string) []string {
	if a.Providers == nil {
		if len(sources) > 0 {
			return append([]string(nil), sources...)
		}
		return config.DefaultRuntimeConfig().Sources
	}
	seen := make(map[string]bool, len(sources))
	out := make([]string, 0, len(sources))
	for _, raw := range sources {
		p, ok := a.Providers.Get(raw)
		if !ok {
			continue
		}
		id := p.ID()
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	if len(out) > 0 {
		return out
	}
	if len(sources) > 0 && a.Logs != nil {
		a.Logs.Push("warn", "已保存的音源配置均已失效，已自动恢复为默认音源", "system", "")
	}
	return config.DefaultRuntimeConfig().Sources
}

func (a *App) lookupAgentTasks(ctx context.Context, ids []string) ([]agent.TaskState, error) {
	out := make([]agent.TaskState, 0, len(ids))
	for _, id := range ids {
		if dl, ok, err := a.Store.GetDownload(ctx, id); err == nil && ok {
			terminal := dl.Status == model.DownloadComplete || dl.Status == model.DownloadFailed || dl.Status == model.DownloadCancelled
			st := agent.TaskState{
				ID:        dl.ID,
				Status:    string(dl.Status),
				Detail:    dl.Detail,
				Completed: dl.Status == model.DownloadComplete,
				Terminal:  terminal,
			}
			if dto, err := a.Download.ToDTO(ctx, dl); err == nil {
				st.Title = dto.Track.Title
				if dto.Processing != nil {
					raw, _ := json.Marshal(dto.Processing)
					var pm map[string]any
					_ = json.Unmarshal(raw, &pm)
					st.Processing = pm
				}
			}
			out = append(out, st)
			continue
		}
		if job, _, ok, err := a.Store.GetLibraryJob(ctx, id); err == nil && ok {
			terminal := job.Status == model.LibraryJobComplete || job.Status == model.LibraryJobFailed || job.Status == model.LibraryJobCancelled || job.Status == model.LibraryJobPartial
			out = append(out, agent.TaskState{
				ID:        job.ID,
				Status:    string(job.Status),
				Detail:    job.Detail,
				Completed: job.Status == model.LibraryJobComplete,
				Terminal:  terminal,
			})
		}
	}
	return out, nil
}

func (a *App) reconcileAgentPendingWrite(ctx context.Context, pw agent.PendingWrite) (bool, map[string]any, error) {
	if a.Idempotency == nil || pw.IdempotencyKey == "" || pw.Operation == "" {
		return false, nil, nil
	}
	rows, err := a.Store.Reader().QueryContext(ctx, `
		SELECT result_ref FROM operation_requests
		WHERE operation = ? AND idempotency_key = ?
		LIMIT 1
	`, pw.Operation, pw.IdempotencyKey)
	if err != nil {
		return false, nil, err
	}
	defer rows.Close()
	if !rows.Next() {
		return false, nil, nil
	}
	var resultRef string
	if err := rows.Scan(&resultRef); err != nil {
		return false, nil, err
	}
	var outcome map[string]any
	if json.Unmarshal([]byte(resultRef), &outcome) == nil && len(outcome) > 0 {
		return true, outcome, nil
	}
	return true, map[string]any{"reconciled": true, "operation": pw.Operation}, nil
}

func readOnlyMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet, http.MethodHead, http.MethodOptions:
			next.ServeHTTP(w, r)
			return
		}
		p := r.URL.Path
		if p == "/api/auth/login" ||
			p == "/api/auth/logout" ||
			p == "/api/auth/media-token" ||
			p == "/api/search" ||
			p == "/api/search/jobs" ||
			p == "/api/resolve" ||
			p == "/api/songs/validate" ||
			(strings.HasPrefix(p, "/api/tracks/") && (strings.HasSuffix(p, "/playback") || strings.HasSuffix(p, "/versions"))) {
			next.ServeHTTP(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"detail":"只读验证模式禁止写入或外部发送操作"}`))
	})
}
