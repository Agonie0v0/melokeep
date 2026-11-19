package plugin

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/dop251/goja"
)

const (
	// MaxSourceBytes caps a single plugin JavaScript source file at 512 KiB.
	MaxSourceBytes = 512 << 10
	// PluginAPIVersion is the only supported plugin manifest API version.
	PluginAPIVersion = 1

	inspectEvalTimeout = 3 * time.Second
	maxManifestNameLen = 64
	maxManifestVerLen  = 32
	maxManifestDescLen = 512
)

var (
	pluginIDRe    = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{1,47}$`)
	settingKeyRe  = regexp.MustCompile(`^[a-zA-Z0-9_.-]{1,64}$`)
	plainHTTPHint = regexp.MustCompile(`(?i)\bhttp://`)
)

// CompiledPlugin holds a validated, precompiled plugin program and static manifest metadata.
type CompiledPlugin struct {
	Code        string
	SHA256      string
	Manifest    model.PluginManifestDTO
	Program     *goja.Program
	ExportStyle string // "commonjs" or "global"
	HasSearch   bool
	HasResolve  bool
	HasPlaylist bool
	HasEpisodes bool
	HasLyrics   bool
	HasMetadata bool
}

// ValidatePluginID checks that id matches the strict plugin identifier syntax.
func ValidatePluginID(id string) error {
	trimmed := strings.TrimSpace(id)
	if !pluginIDRe.MatchString(trimmed) {
		return model.NewError(
			model.ErrInvalidInput,
			http.StatusBadRequest,
			"插件 ID 格式无效，必须匹配 ^[a-z0-9][a-z0-9_-]{1,47}$",
		)
	}
	return nil
}

// IsSecretSettingField reports whether a manifest setting field stores sensitive credentials.
func IsSecretSettingField(f model.PluginSettingFieldDTO) bool {
	t := strings.ToLower(strings.TrimSpace(f.Type))
	return t == "secret" || t == "password"
}

// ParseAndCompile executes the script in a strict read-only 3s sandbox, validates its manifest and exports,
// and precompiles an immutable *goja.Program.
func ParseAndCompile(code string) (*CompiledPlugin, error) {
	if strings.TrimSpace(code) == "" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件源码不能为空")
	}
	if len(code) > MaxSourceBytes {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件源码体积超过 512 KiB 上限")
	}

	sum := sha256.Sum256([]byte(code))
	digest := hex.EncodeToString(sum[:])

	vm := goja.New()
	vm.SetMaxCallStackSize(1024)

	modObj := vm.NewObject()
	exportsInit := vm.NewObject()
	_ = modObj.Set("exports", exportsInit)
	_ = vm.Set("module", modObj)
	_ = vm.Set("exports", exportsInit)

	evalCtx, cancel := context.WithTimeout(context.Background(), inspectEvalTimeout)
	defer cancel()

	stopWatch := make(chan struct{})
	watchDone := make(chan struct{})
	go func() {
		defer close(watchDone)
		select {
		case <-evalCtx.Done():
			vm.Interrupt(evalCtx.Err())
		case <-stopWatch:
		}
	}()

	_, runErr := vm.RunScript("plugin_inspect.js", code)
	close(stopWatch)
	<-watchDone

	if runErr != nil {
		var intr *goja.InterruptedError
		if errors.As(runErr, &intr) || errors.Is(evalCtx.Err(), context.DeadlineExceeded) {
			return nil, model.WrapError(model.ErrDeadline, http.StatusGatewayTimeout, "插件静态解析超时", runErr)
		}
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("插件脚本解析失败: %v", runErr), runErr)
	}

	exportsObj, style, err := resolveExportsObject(vm)
	if err != nil {
		return nil, err
	}

	manifestVal := exportsObj.Get("manifest")
	manifest, err := parseManifest(vm, manifestVal)
	if err != nil {
		return nil, err
	}

	_, hasResolve := goja.AssertFunction(exportsObj.Get("resolve"))
	if !hasResolve {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件必须导出 resolve 函数")
	}

	_, hasSearch := goja.AssertFunction(exportsObj.Get("search"))
	if manifest.Capabilities.Search && !hasSearch {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "声明了 capabilities.search 时必须导出 search 函数")
	}

	_, hasPlaylist := goja.AssertFunction(exportsObj.Get("playlist"))
	if manifest.Capabilities.Playlist && !hasPlaylist {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "声明了 capabilities.playlist 时必须导出 playlist 函数")
	}

	_, hasEpisodes := goja.AssertFunction(exportsObj.Get("episodes"))
	if manifest.Capabilities.Episodes && !hasEpisodes {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "声明了 capabilities.episodes 时必须导出 episodes 函数")
	}

	_, hasLyrics := goja.AssertFunction(exportsObj.Get("lyrics"))
	if manifest.Capabilities.Lyrics && !hasLyrics {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "声明了 capabilities.lyrics 时必须导出 lyrics 函数")
	}

	_, hasMetadata := goja.AssertFunction(exportsObj.Get("metadata"))
	if manifest.Capabilities.Metadata && !hasMetadata {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "声明了 capabilities.metadata 时必须导出 metadata 函数")
	}

	prog, err := goja.Compile(manifest.ID+".js", code, false)
	if err != nil {
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("插件脚本预编译失败: %v", err), err)
	}

	return &CompiledPlugin{
		Code:        code,
		SHA256:      digest,
		Manifest:    manifest,
		Program:     prog,
		ExportStyle: style,
		HasSearch:   hasSearch,
		HasResolve:  hasResolve,
		HasPlaylist: hasPlaylist,
		HasEpisodes: hasEpisodes,
		HasLyrics:   hasLyrics,
		HasMetadata: hasMetadata,
	}, nil
}

// Inspect statically parses and validates a plugin script without persisting or registering it.
func Inspect(ctx context.Context, code string) (model.PluginInspectResultDTO, error) {
	if ctx != nil {
		if err := ctx.Err(); err != nil {
			return model.PluginInspectResultDTO{}, model.WrapError(model.ErrCancelled, 499, "插件检查已取消", err)
		}
	}
	compiled, err := ParseAndCompile(code)
	if err != nil {
		return model.PluginInspectResultDTO{}, err
	}

	caps := compiled.Manifest.Capabilities
	capNames := make([]string, 0, 6)
	if caps.Search {
		capNames = append(capNames, "搜索")
	}
	capNames = append(capNames, "播放解析")
	if caps.Playlist {
		capNames = append(capNames, "歌单导入")
	}
	if caps.Episodes {
		capNames = append(capNames, "分集展开")
	}
	if caps.Lyrics {
		capNames = append(capNames, "歌词获取")
	}
	if caps.Metadata {
		capNames = append(capNames, "元数据刮削")
	}

	permissions := []string{
		fmt.Sprintf("声明能力：%s", strings.Join(capNames, "、")),
		"网络访问：仅允许公网 HTTP/HTTPS 请求（单次调用最多 24 次、单批最多 8 个、单响应上限 4 MiB）",
		"沙箱隔离：纯 JS 运行环境，无本地文件、环境变量、子进程或原生模块权限，单次调用 15 秒超时硬中断",
	}

	secretCount := 0
	for _, f := range compiled.Manifest.Settings {
		if IsSecretSettingField(f) {
			secretCount++
		}
	}
	if len(compiled.Manifest.Settings) > 0 {
		permissions = append(permissions, fmt.Sprintf("配置项：共 %d 项（含 %d 项敏感凭据字段）", len(compiled.Manifest.Settings), secretCount))
	}

	warnings := []string{
		"默认禁止访问内网及本机回环地址；仅当管理员在 allowed_origins 显式授权后方可访问指定内部 Origin",
	}
	if secretCount > 0 {
		warnings = append(warnings, "该插件包含敏感凭据配置项（secret/password），请仅在确认脚本来源可信后填写凭据")
	}
	if plainHTTPHint.MatchString(code) {
		warnings = append(warnings, "脚本源码中检测到明文 http:// 地址，网络传输可能未加密")
	}

	return model.PluginInspectResultDTO{
		SHA256:      compiled.SHA256,
		Manifest:    compiled.Manifest,
		Permissions: permissions,
		Warnings:    warnings,
	}, nil
}

func resolveExportsObject(vm *goja.Runtime) (*goja.Object, string, error) {
	if modVal := vm.Get("module"); !isNilOrUndefined(modVal) {
		modObj := modVal.ToObject(vm)
		if exVal := modObj.Get("exports"); !isNilOrUndefined(exVal) {
			if exObj, ok := exVal.(*goja.Object); ok {
				if !isNilOrUndefined(exObj.Get("manifest")) {
					return exObj, "commonjs", nil
				}
			}
		}
	}
	const globalProbe = `(function () {
		var out = {};
		if (typeof manifest !== "undefined") out.manifest = manifest;
		if (typeof search !== "undefined") out.search = search;
		if (typeof resolve !== "undefined") out.resolve = resolve;
		if (typeof playlist !== "undefined") out.playlist = playlist;
		if (typeof episodes !== "undefined") out.episodes = episodes;
		if (typeof lyrics !== "undefined") out.lyrics = lyrics;
		if (typeof metadata !== "undefined") out.metadata = metadata;
		return out;
	})()`
	if probedVal, err := vm.RunString(globalProbe); err == nil && !isNilOrUndefined(probedVal) {
		if probedObj, ok := probedVal.(*goja.Object); ok && !isNilOrUndefined(probedObj.Get("manifest")) {
			return probedObj, "global", nil
		}
	}
	return nil, "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件未导出 manifest 对象")
}

func parseManifest(vm *goja.Runtime, v goja.Value) (model.PluginManifestDTO, error) {
	if isNilOrUndefined(v) {
		return model.PluginManifestDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "插件缺少 manifest 对象")
	}
	obj, ok := v.(*goja.Object)
	if !ok || obj.ClassName() == "Array" {
		return model.PluginManifestDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest 必须为对象")
	}

	apiVer, err := parseAPIVersion(obj)
	if err != nil {
		return model.PluginManifestDTO{}, err
	}
	if apiVer != PluginAPIVersion {
		return model.PluginManifestDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("不支持的 manifest.api_version=%d，仅支持 %d", apiVer, PluginAPIVersion))
	}

	id := strings.TrimSpace(readJSString(obj, "id"))
	if err := ValidatePluginID(id); err != nil {
		return model.PluginManifestDTO{}, err
	}

	name := strings.TrimSpace(readJSString(obj, "name"))
	if name == "" || utf8.RuneCountInString(name) > maxManifestNameLen {
		return model.PluginManifestDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest.name 不能为空且不能超过 64 个字符")
	}

	version := strings.TrimSpace(readJSString(obj, "version"))
	if version == "" || utf8.RuneCountInString(version) > maxManifestVerLen {
		return model.PluginManifestDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest.version 不能为空且不能超过 32 个字符")
	}

	description := strings.TrimSpace(readJSString(obj, "description"))
	if utf8.RuneCountInString(description) > maxManifestDescLen {
		runes := []rune(description)
		description = string(runes[:maxManifestDescLen])
	}

	category := strings.TrimSpace(readJSString(obj, "category"))
	caps, err := parseCapabilities(obj.Get("capabilities"), description)
	if err != nil {
		return model.PluginManifestDTO{}, err
	}

	settings, err := parseSettingsFields(vm, obj.Get("settings"))
	if err != nil {
		return model.PluginManifestDTO{}, err
	}

	return model.PluginManifestDTO{
		APIVersion:   apiVer,
		ID:           id,
		Name:         name,
		Version:      version,
		Description:  description,
		Category:     category,
		Capabilities: caps,
		Settings:     settings,
	}, nil
}

func parseAPIVersion(obj *goja.Object) (int, error) {
	raw := obj.Get("api_version")
	if isNilOrUndefined(raw) {
		raw = obj.Get("apiVersion")
	}
	if isNilOrUndefined(raw) {
		return 0, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest 缺少 api_version / apiVersion")
	}
	switch v := raw.Export().(type) {
	case int64:
		return int(v), nil
	case int:
		return v, nil
	case float64:
		if float64(int(v)) != v {
			return 0, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest.api_version 必须为整数 1")
		}
		return int(v), nil
	default:
		return 0, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest.api_version 必须为数字 1")
	}
}

func parseCapabilities(v goja.Value, defaultDesc string) (model.SourceCapabilityDTO, error) {
	if isNilOrUndefined(v) {
		return model.SourceCapabilityDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest 缺少 capabilities 对象")
	}
	obj, ok := v.(*goja.Object)
	if !ok || obj.ClassName() == "Array" {
		return model.SourceCapabilityDTO{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest.capabilities 必须为对象")
	}
	note := strings.TrimSpace(readJSString(obj, "note"))
	if note == "" {
		note = defaultDesc
	}
	return model.SourceCapabilityDTO{
		Tier:     "plugin",
		Note:     note,
		Search:   readJSBool(obj, "search"),
		Playlist: readJSBool(obj, "playlist"),
		Lyrics:   readJSBool(obj, "lyrics"),
		Metadata: readJSBool(obj, "metadata"),
		Episodes: readJSBool(obj, "episodes"),
	}, nil
}

func parseSettingsFields(vm *goja.Runtime, v goja.Value) ([]model.PluginSettingFieldDTO, error) {
	if isNilOrUndefined(v) {
		return []model.PluginSettingFieldDTO{}, nil
	}
	arrObj, ok := v.(*goja.Object)
	if !ok || arrObj.ClassName() != "Array" {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "manifest.settings 必须为数组")
	}

	var rawItems []any
	if err := vm.ExportTo(arrObj, &rawItems); err != nil {
		return nil, model.WrapError(model.ErrInvalidInput, http.StatusBadRequest, "manifest.settings 格式无效", err)
	}

	fields := make([]model.PluginSettingFieldDTO, 0, len(rawItems))
	seenKeys := make(map[string]bool, len(rawItems))
	for i, item := range rawItems {
		m, ok := item.(map[string]any)
		if !ok {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("manifest.settings[%d] 必须为对象", i))
		}
		key := strings.TrimSpace(anyToString(m["key"]))
		if !settingKeyRe.MatchString(key) {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("manifest.settings[%d].key 格式无效", i))
		}
		if seenKeys[key] {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("manifest.settings 存在重复 key: %s", key))
		}
		seenKeys[key] = true

		label := strings.TrimSpace(anyToString(m["label"]))
		if label == "" {
			label = key
		}
		fieldType := strings.ToLower(strings.TrimSpace(anyToString(m["type"])))
		if fieldType == "" {
			fieldType = "text"
		}
		switch fieldType {
		case "text", "secret", "password", "url", "number", "boolean":
		default:
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, fmt.Sprintf("manifest.settings[%s].type 不支持 %q", key, fieldType))
		}

		defVal := anyToString(m["default_value"])
		if defVal == "" {
			defVal = anyToString(m["defaultValue"])
		}

		fields = append(fields, model.PluginSettingFieldDTO{
			Key:          key,
			Label:        label,
			Type:         fieldType,
			Placeholder:  strings.TrimSpace(anyToString(m["placeholder"])),
			Required:     anyToBool(m["required"]),
			DefaultValue: defVal,
		})
	}
	return fields, nil
}

func isNilOrUndefined(v goja.Value) bool {
	return v == nil || goja.IsUndefined(v) || goja.IsNull(v)
}

func readJSString(obj *goja.Object, keys ...string) string {
	for _, k := range keys {
		val := obj.Get(k)
		if !isNilOrUndefined(val) {
			return val.String()
		}
	}
	return ""
}

func readJSBool(obj *goja.Object, keys ...string) bool {
	for _, k := range keys {
		val := obj.Get(k)
		if !isNilOrUndefined(val) {
			return val.ToBoolean()
		}
	}
	return false
}

func anyToString(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case float64:
		if float64(int64(x)) == x {
			return strconv.FormatInt(int64(x), 10)
		}
		return strconv.FormatFloat(x, 'f', -1, 64)
	case int:
		return strconv.Itoa(x)
	case int64:
		return strconv.FormatInt(x, 10)
	case bool:
		return strconv.FormatBool(x)
	default:
		return fmt.Sprint(x)
	}
}

func anyToBool(v any) bool {
	switch x := v.(type) {
	case bool:
		return x
	case string:
		b, _ := strconv.ParseBool(strings.TrimSpace(x))
		return b
	case int:
		return x != 0
	case int64:
		return x != 0
	case float64:
		return x != 0
	default:
		return false
	}
}

// redactSettingsMap returns a copy of settings with secret fields masked using config.HiddenPlaceholder.
func redactSettingsMap(manifest model.PluginManifestDTO, settings map[string]string) map[string]string {
	out := make(map[string]string, len(settings))
	secretKeys := make(map[string]bool)
	for _, f := range manifest.Settings {
		if IsSecretSettingField(f) {
			secretKeys[f.Key] = true
		}
	}
	for k, v := range settings {
		if secretKeys[k] && strings.TrimSpace(v) != "" {
			out[k] = config.HiddenPlaceholder
		} else {
			out[k] = v
		}
	}
	return out
}

// effectiveSettingsMap merges manifest default values with persisted user settings.
func effectiveSettingsMap(manifest model.PluginManifestDTO, stored map[string]string) map[string]string {
	out := make(map[string]string, len(manifest.Settings)+len(stored))
	for _, f := range manifest.Settings {
		if f.DefaultValue != "" {
			out[f.Key] = f.DefaultValue
		}
	}
	for k, v := range stored {
		if v != "" {
			out[k] = v
		} else if _, exists := out[k]; !exists {
			out[k] = ""
		}
	}
	return out
}

// mergeSettingsUpdate merges incoming settings updates while preserving existing secrets when hidden placeholders are passed.
func mergeSettingsUpdate(manifest model.PluginManifestDTO, existing, incoming map[string]string) map[string]string {
	merged := make(map[string]string, len(existing)+len(incoming))
	for k, v := range existing {
		merged[k] = v
	}
	secretKeys := make(map[string]bool)
	for _, f := range manifest.Settings {
		if IsSecretSettingField(f) {
			secretKeys[f.Key] = true
		}
	}
	for k, v := range incoming {
		if secretKeys[k] {
			if v == config.HiddenPlaceholder || v == "__MELOKEEP_HIDDEN__" {
				if prev, ok := existing[k]; ok {
					merged[k] = prev
				}
				continue
			}
			merged[k] = config.MergeHiddenString(existing[k], v)
			continue
		}
		merged[k] = v
	}
	return merged
}
