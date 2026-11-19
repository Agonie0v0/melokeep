package builtin

import "github.com/agonie0v0/melokeep/internal/provider"

// Entries constructs all 7 built-in compliant providers with clear category and origin metadata.
func Entries(opts Options) []provider.Entry {
	return []provider.Entry{
		{
			Provider: NewAudiusMusicClient(opts),
			Meta: provider.Metadata{
				Label:            "Audius",
				Category:         "开放许可",
				Origin:           "builtin",
				Description:      "Audius 去中心化独立与开放许可音乐流媒体",
				ProxyRecommended: true,
			},
		},
		{
			Provider: NewFMAMusicClient(opts),
			Meta: provider.Metadata{
				Label:            "FMA",
				Category:         "开放许可",
				Origin:           "builtin",
				Description:      "Free Music Archive 开放许可音乐曲库",
				ProxyRecommended: true,
			},
		},
		{
			Provider: NewCCMixterMusicClient(opts),
			Meta: provider.Metadata{
				Label:            "ccMixter",
				Category:         "开放许可",
				Origin:           "builtin",
				Description:      "ccMixter 知识共享混音与独立音乐社区",
				ProxyRecommended: true,
			},
		},
		{
			Provider: NewOpenGameArtMusicClient(opts),
			Meta: provider.Metadata{
				Label:            "OpenGameArt",
				Category:         "开放许可",
				Origin:           "builtin",
				Description:      "OpenGameArt 开源游戏配乐与音频资源",
				ProxyRecommended: true,
			},
		},
		{
			Provider: NewWikimediaCommonsMusicClient(opts),
			Meta: provider.Metadata{
				Label:            "Wikimedia Commons",
				Category:         "开放许可",
				Origin:           "builtin",
				Description:      "Wikimedia Commons 公共领域与开放许可音频库",
				ProxyRecommended: true,
			},
		},
		{
			Provider: NewJamendoMusicClient(opts),
			Meta: provider.Metadata{
				Label:            "Jamendo",
				Category:         "开放许可",
				Origin:           "builtin",
				Description:      "Jamendo 开放许可独立音乐（需在音源设置填入免费申请的 client_id）",
				ProxyRecommended: true,
			},
		},
		{
			Provider: NewITunesMusicClient(opts),
			Meta: provider.Metadata{
				Label:            "iTunes",
				Category:         "官方试听",
				Origin:           "builtin",
				Description:      "Apple iTunes 公开播客节目与 30 秒官方试听片段",
				ProxyRecommended: false,
			},
		},
	}
}
