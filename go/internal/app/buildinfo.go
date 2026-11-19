package app

import (
	"os"
	"runtime/debug"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
)

var (
	buildVersion string
	buildCommit  string
	buildRef     string
	buildTime    string
)

// CurrentBuildInfo returns compile-time version and VCS metadata, falling back to Go build info.
func CurrentBuildInfo() model.BuildInfoDTO {
	info := model.BuildInfoDTO{
		Version:     strings.TrimSpace(buildVersion),
		Commit:      strings.TrimSpace(buildCommit),
		Ref:         strings.TrimSpace(buildRef),
		BuildTime:   strings.TrimSpace(buildTime),
		InContainer: strings.TrimSpace(os.Getenv("MELOKEEP_CONTAINER")) != "",
	}
	if bi, ok := debug.ReadBuildInfo(); ok {
		for _, s := range bi.Settings {
			switch s.Key {
			case "vcs.revision":
				if info.Commit == "" {
					info.Commit = strings.TrimSpace(s.Value)
				}
			case "vcs.time":
				if info.BuildTime == "" {
					info.BuildTime = strings.TrimSpace(s.Value)
				}
			case "vcs.modified":
				if strings.EqualFold(strings.TrimSpace(s.Value), "true") {
					info.Dirty = true
				}
			}
		}
	}
	if info.Version == "" {
		info.Version = DefaultVersion
	}
	if len(info.Commit) > 7 {
		info.ShortCommit = info.Commit[:7]
	} else {
		info.ShortCommit = info.Commit
	}
	return info
}
