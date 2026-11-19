package config

import "strings"

// SourceAlias maps between public source IDs and internal engine client names.
type SourceAlias struct {
	PublicID   string
	EngineName string
}

var (
	coreAliases    []SourceAlias
	aliasToEngine  = map[string]string{}
	engineToPublic = map[string]string{}
)

// CoreSourceAliases returns the canonical alias table for core music sources.
func CoreSourceAliases() []SourceAlias {
	out := make([]SourceAlias, len(coreAliases))
	copy(out, coreAliases)
	return out
}

// CanonicalEngineName resolves a public alias or client name to its canonical engine client name.
func CanonicalEngineName(source string) string {
	s := strings.TrimSpace(source)
	if s == "" {
		return ""
	}
	if eng, ok := aliasToEngine[strings.ToLower(s)]; ok {
		return eng
	}
	return s
}

// PublicSourceID resolves a canonical engine name or alias to its public API source ID.
func PublicSourceID(source string) string {
	eng := CanonicalEngineName(source)
	if pub, ok := engineToPublic[eng]; ok {
		return pub
	}
	return eng
}
