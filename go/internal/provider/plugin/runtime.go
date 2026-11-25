package plugin

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/transport"
	"github.com/dop251/goja"
)

const (
	// DefaultInvokeTimeout bounds a single plugin hook invocation (both JS CPU and HTTP I/O).
	DefaultInvokeTimeout = 15 * time.Second
	// MaxPluginLogs caps retained diagnostic log entries per plugin invocation.
	MaxPluginLogs = 100
)

var errEmitLimitReached = errors.New("plugin emit limit reached")

type logCollectorKey struct{}

func withLogCollector(ctx context.Context, c *logCollector) context.Context {
	if ctx == nil {
		ctx = context.Background()
	}
	return context.WithValue(ctx, logCollectorKey{}, c)
}

func logCollectorFrom(ctx context.Context) *logCollector {
	if ctx == nil {
		return nil
	}
	c, _ := ctx.Value(logCollectorKey{}).(*logCollector)
	return c
}

type runtimeConfig struct {
	pool           *transport.Pool
	settings       map[string]string
	allowedOrigins []string
	timeout        time.Duration
	logFn          provider.LogFunc
}

type invocationParams struct {
	fnName    string
	args      []any
	onEmit    func(map[string]any) error
	logBuffer *logCollector
}

type logCollector struct {
	mu      sync.Mutex
	entries []model.PluginLogEntryDTO
	secrets []string
	logFn   provider.LogFunc
	plugin  string
	taskID  string
}

func newLogCollector(pluginID, taskID string, logFn provider.LogFunc, secrets []string) *logCollector {
	cleaned := make([]string, 0, len(secrets))
	for _, s := range secrets {
		if len(strings.TrimSpace(s)) >= 3 {
			cleaned = append(cleaned, s)
		}
	}
	sort.Slice(cleaned, func(i, j int) bool {
		return len(cleaned[i]) > len(cleaned[j])
	})
	return &logCollector{
		entries: make([]model.PluginLogEntryDTO, 0),
		secrets: cleaned,
		logFn:   logFn,
		plugin:  pluginID,
		taskID:  taskID,
	}
}

func (c *logCollector) redact(msg string) string {
	out := msg
	for _, sec := range c.secrets {
		out = strings.ReplaceAll(out, sec, "***")
	}
	return out
}

func (c *logCollector) push(level, msg string) {
	if c == nil {
		return
	}
	normLevel := normalizeLogLevel(level)
	cleanMsg := c.redact(strings.TrimSpace(msg))
	if cleanMsg == "" {
		return
	}

	c.mu.Lock()
	shouldRecord := len(c.entries) < MaxPluginLogs
	if shouldRecord {
		c.entries = append(c.entries, model.PluginLogEntryDTO{
			Level:   normLevel,
			Message: cleanMsg,
			Time:    time.Now().Format(time.RFC3339),
		})
	}
	fn := c.logFn
	c.mu.Unlock()

	if shouldRecord && fn != nil {
		fwdLevel := normLevel
		if fwdLevel == "warn" {
			fwdLevel = "warning"
		}
		fn(fwdLevel, cleanMsg, "plugin:"+c.plugin, c.taskID)
	}
}

func (c *logCollector) snapshot() []model.PluginLogEntryDTO {
	if c == nil {
		return []model.PluginLogEntryDTO{}
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]model.PluginLogEntryDTO, len(c.entries))
	copy(out, c.entries)
	return out
}

func normalizeLogLevel(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "debug":
		return "debug"
	case "warn", "warning":
		return "warn"
	case "error", "err":
		return "error"
	default:
		return "info"
	}
}

func invokeCompiledPlugin(
	ctx context.Context,
	compiled *CompiledPlugin,
	cfg runtimeConfig,
	params invocationParams,
) (exported any, retErr error) {
	if compiled == nil || compiled.Program == nil {
		return nil, model.NewError(model.ErrInternal, http.StatusInternalServerError, "插件未完成预编译")
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return nil, mapContextErr(err)
	}

	timeout := cfg.timeout
	if timeout <= 0 {
		timeout = DefaultInvokeTimeout
	}

	runCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	rc := provider.RequestContextFrom(runCtx)
	pluginID := compiled.Manifest.ID
	_, cookies, _ := provider.SourceRequestOptions(rc, pluginID)
	effectiveSettings := effectiveSettingsMap(compiled.Manifest, cfg.settings)

	collector := params.logBuffer
	if collector == nil {
		collector = logCollectorFrom(runCtx)
	}
	if collector == nil {
		logFn := rc.LogFunc
		if logFn == nil {
			logFn = cfg.logFn
		}
		collector = newLogCollector(pluginID, rc.TaskID, logFn, collectSecrets(compiled.Manifest, effectiveSettings, cookies))
	}

	vm := goja.New()
	vm.SetMaxCallStackSize(1024)

	modObj := vm.NewObject()
	exportsInit := vm.NewObject()
	_ = modObj.Set("exports", exportsInit)
	_ = vm.Set("module", modObj)
	_ = vm.Set("exports", exportsInit)

	var emitStopErr error
	ctxObj := vm.NewObject()
	newHTTPBridge(runCtx, cfg.pool, rc, pluginID, cfg.allowedOrigins).install(vm, ctxObj)
	installCryptoAndEncodingBridge(vm, ctxObj)
	installHTMLBridge(vm, ctxObj)
	installReadOnlyMap(vm, ctxObj, "settings", effectiveSettings)
	installCookieObject(vm, ctxObj, cookies)

	_ = ctxObj.Set("emit", func(call goja.FunctionCall) goja.Value {
		if runCtx.Err() != nil {
			err := mapContextErr(runCtx.Err())
			emitStopErr = err
			panic(vm.NewGoError(err))
		}
		if params.onEmit == nil || len(call.Arguments) == 0 || isNilOrUndefined(call.Arguments[0]) {
			return goja.Undefined()
		}
		var item map[string]any
		if err := vm.ExportTo(call.Arguments[0], &item); err != nil {
			err = model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, "ctx.emit 参数必须为对象", err)
			emitStopErr = err
			panic(vm.NewGoError(err))
		}
		if err := params.onEmit(item); err != nil {
			emitStopErr = err
			panic(vm.NewGoError(err))
		}
		return goja.Undefined()
	})

	_ = ctxObj.Set("log", func(call goja.FunctionCall) goja.Value {
		level := argString(call, 0)
		msg := argString(call, 1)
		if len(call.Arguments) == 1 {
			msg = level
			level = "info"
		}
		collector.push(level, msg)
		return goja.Undefined()
	})

	stopWatch := make(chan struct{})
	watchDone := make(chan struct{})
	go func() {
		defer close(watchDone)
		select {
		case <-runCtx.Done():
			vm.Interrupt(runCtx.Err())
		case <-stopWatch:
		}
	}()
	defer func() {
		close(stopWatch)
		<-watchDone
		if r := recover(); r != nil {
			if retErr == nil {
				retErr = model.NewError(model.ErrInternal, http.StatusInternalServerError, fmt.Sprintf("插件执行异常: %v", r))
			}
		}
	}()

	if _, err := vm.RunProgram(compiled.Program); err != nil {
		return nil, classifyPluginExecError(runCtx, emitStopErr, err)
	}

	exportsObj, _, err := resolveExportsObject(vm)
	if err != nil {
		return nil, err
	}
	fnVal := exportsObj.Get(params.fnName)
	fn, ok := goja.AssertFunction(fnVal)
	if !ok {
		return nil, model.NewError(model.ErrUnsupported, http.StatusBadRequest, fmt.Sprintf("插件未导出 %s 函数", params.fnName))
	}

	jsArgs := make([]goja.Value, 0, 1+len(params.args))
	jsArgs = append(jsArgs, ctxObj)
	for _, arg := range params.args {
		jsArgs = append(jsArgs, vm.ToValue(arg))
	}

	resVal, err := fn(exportsObj, jsArgs...)
	if err != nil {
		return nil, classifyPluginExecError(runCtx, emitStopErr, err)
	}

	if !isNilOrUndefined(resVal) {
		if prom, isProm := resVal.Export().(*goja.Promise); isProm {
			switch prom.State() {
			case goja.PromiseStateFulfilled:
				resVal = prom.Result()
			case goja.PromiseStateRejected:
				rejMsg := "Promise rejected"
				if prom.Result() != nil {
					rejMsg = prom.Result().String()
				}
				return nil, classifyPluginExecError(runCtx, emitStopErr, errors.New(rejMsg))
			default:
				return nil, model.NewError(model.ErrInvalidResponse, http.StatusBadGateway, "插件异步 Promise 未在同步周期内完成")
			}
		}
	}

	if emitStopErr != nil {
		if errors.Is(emitStopErr, errEmitLimitReached) {
			return nil, errEmitLimitReached
		}
		return nil, emitStopErr
	}
	if isNilOrUndefined(resVal) {
		return nil, nil
	}
	return resVal.Export(), nil
}

func installReadOnlyMap(vm *goja.Runtime, parent *goja.Object, propName string, values map[string]string) {
	obj := vm.NewObject()
	for k, v := range values {
		_ = obj.DefineDataProperty(k, vm.ToValue(v), goja.FLAG_FALSE, goja.FLAG_FALSE, goja.FLAG_TRUE)
	}
	_ = parent.DefineDataProperty(propName, obj, goja.FLAG_FALSE, goja.FLAG_FALSE, goja.FLAG_TRUE)
}

func installCookieObject(vm *goja.Runtime, parent *goja.Object, cookies map[string]string) {
	proto := vm.NewObject()
	_ = proto.Set("get", func(call goja.FunctionCall) goja.Value {
		name := strings.TrimSpace(argString(call, 0))
		if name == "" {
			return vm.ToValue("")
		}
		return vm.ToValue(cookies[name])
	})
	_ = proto.Set("header", func(call goja.FunctionCall) goja.Value {
		return vm.ToValue(provider.FormatCookieHeader(cookies))
	})

	cookieObj := vm.NewObject()
	_ = cookieObj.SetPrototype(proto)
	for k, v := range cookies {
		_ = cookieObj.DefineDataProperty(k, vm.ToValue(v), goja.FLAG_FALSE, goja.FLAG_FALSE, goja.FLAG_TRUE)
	}
	_ = parent.DefineDataProperty("cookie", cookieObj, goja.FLAG_FALSE, goja.FLAG_FALSE, goja.FLAG_TRUE)
}

func collectSecrets(manifest model.PluginManifestDTO, settings, cookies map[string]string) []string {
	var secrets []string
	for _, f := range manifest.Settings {
		if IsSecretSettingField(f) {
			if val := strings.TrimSpace(settings[f.Key]); val != "" {
				secrets = append(secrets, val)
			}
		}
	}
	for _, val := range cookies {
		if trimmed := strings.TrimSpace(val); trimmed != "" {
			secrets = append(secrets, trimmed)
		}
	}
	return secrets
}

func mapContextErr(err error) error {
	if errors.Is(err, context.DeadlineExceeded) {
		return model.WrapError(model.ErrDeadline, http.StatusGatewayTimeout, "插件执行超时", err)
	}
	if errors.Is(err, context.Canceled) {
		return model.WrapError(model.ErrCancelled, 499, "插件执行已取消", err)
	}
	return model.WrapError(model.ErrInternal, http.StatusInternalServerError, "插件上下文异常", err)
}

func classifyPluginExecError(runCtx context.Context, emitStopErr, execErr error) error {
	if emitStopErr != nil {
		if errors.Is(emitStopErr, errEmitLimitReached) {
			return errEmitLimitReached
		}
		if de, ok := model.AsDomainError(emitStopErr); ok {
			return de
		}
	}
	if runCtx != nil && runCtx.Err() != nil {
		return mapContextErr(runCtx.Err())
	}
	var intr *goja.InterruptedError
	if errors.As(execErr, &intr) {
		if runCtx != nil && runCtx.Err() != nil {
			return mapContextErr(runCtx.Err())
		}
		return model.WrapError(model.ErrDeadline, http.StatusGatewayTimeout, "插件执行被中断", execErr)
	}
	if de, ok := model.AsDomainError(execErr); ok {
		return de
	}
	var jsEx *goja.Exception
	if errors.As(execErr, &jsEx) {
		if unwrapped := jsEx.Unwrap(); unwrapped != nil {
			if de, ok := model.AsDomainError(unwrapped); ok {
				return de
			}
		}
		return model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("插件执行失败: %s", jsEx.Value().String()), execErr)
	}
	return model.WrapError(model.ErrInvalidResponse, http.StatusBadGateway, fmt.Sprintf("插件执行失败: %v", execErr), execErr)
}
