package config

import (
	"net/url"
	"strings"
)

const HiddenPlaceholder = "[已隐藏]"

var previousNames = []string{"melodock"}

// CanonicalEnv normalizes legacy MELODOCK_* variables into MELOKEEP_*.
// Precedence: MELOKEEP_* > MELODOCK_*.
func CanonicalEnv(values map[string]string) map[string]string {
	out := make(map[string]string, len(values))
	for k, v := range values {
		if !hasLegacyPrefix(k) {
			out[k] = v
		}
	}
	for i := len(previousNames) - 1; i >= 0; i-- {
		prefix := strings.ToUpper(previousNames[i]) + "_"
		for k, v := range values {
			if strings.HasPrefix(k, prefix) {
				target := "MELOKEEP_" + k[len(prefix):]
				if _, exists := out[target]; !exists {
					out[target] = v
				}
			}
		}
	}
	return out
}

func hasLegacyPrefix(key string) bool {
	for _, name := range previousNames {
		if strings.HasPrefix(key, strings.ToUpper(name)+"_") {
			return true
		}
	}
	return false
}

// RedactValue masks non-empty secret strings using the legacy placeholder.
func RedactValue(v string) string {
	if strings.TrimSpace(v) == "" {
		return ""
	}
	return HiddenPlaceholder
}

// RedactURL masks userinfo passwords and sensitive query parameters in URLs.
func RedactURL(raw string) string {
	if strings.TrimSpace(raw) == "" {
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil {
		return HiddenPlaceholder
	}
	if u.User != nil {
		if _, hasPass := u.User.Password(); hasPass {
			u.User = url.UserPassword(u.User.Username(), "***")
		}
	}
	if u.RawQuery != "" {
		q := u.Query()
		changed := false
		for k := range q {
			lk := strings.ToLower(k)
			if strings.Contains(lk, "token") || strings.Contains(lk, "secret") ||
				strings.Contains(lk, "pass") || strings.Contains(lk, "cookie") ||
				strings.Contains(lk, "key") || strings.Contains(lk, "auth") ||
				lk == "sig" || lk == "signature" {
				q.Set(k, "***")
				changed = true
			}
		}
		if changed {
			u.RawQuery = q.Encode()
		}
	}
	return u.String()
}

// MergeHiddenString keeps the existing secret when the incoming value is the hidden placeholder
// or matches the redacted form of existing (e.g. proxy URLs with masked passwords).
func MergeHiddenString(existing, incoming string) string {
	if incoming == HiddenPlaceholder {
		return existing
	}
	if existing != "" && RedactURL(existing) == incoming {
		return existing
	}
	return incoming
}

// MergeHiddenConfig recursively preserves existing secrets wherever incoming values equal HiddenPlaceholder.
func MergeHiddenConfig(existing, incoming map[string]any) map[string]any {
	if incoming == nil {
		return nil
	}
	merged := make(map[string]any, len(incoming))
	for k, v := range incoming {
		var prev any
		if existing != nil {
			prev = existing[k]
		}
		switch typed := v.(type) {
		case string:
			if typed == HiddenPlaceholder {
				if prevStr, ok := prev.(string); ok {
					merged[k] = prevStr
					continue
				}
			}
			merged[k] = typed
		case map[string]any:
			prevMap, _ := prev.(map[string]any)
			merged[k] = MergeHiddenConfig(prevMap, typed)
		default:
			merged[k] = v
		}
	}
	return merged
}
