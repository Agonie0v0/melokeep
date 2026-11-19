package bot

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf16"

	"github.com/agonie0v0/melokeep/internal/auth"
	"github.com/agonie0v0/melokeep/internal/config"
	"github.com/agonie0v0/melokeep/internal/model"
)

const helpMenuText = `MeloKeep · 功能菜单

搜索音乐
直接发送歌名或歌手，例如：Morning Breeze
也可发送 /search Morning Breeze

试听与下载
切换音源查看结果，Telegram 可点击编号，其他渠道发送“下载 1”。完成后收到音频、原文件或下载链接。

我的任务
/status 查看进度，并通过按钮继续、取消或获取下载链接。

翻页：下一页 / 上一页 / 第2页。下载：下载 1。

AI 助手（需在网页通知设置中启用）
直接发送需求或歌单图片，或 /ai 你的需求。
/ai_new 新对话 · /ai_stop 停止本轮 · /ai_status 执行状态
/ai_readonly 仅查询 · /ai_write 允许操作（受网页权限开关限制）

/menu 随时打开功能菜单。Telegram 仅支持白名单用户私聊；企业微信留空白名单时允许应用可见成员。`

var tgMenuCommands = []map[string]string{
	{"command": "start", "description": "打开功能菜单"},
	{"command": "menu", "description": "功能菜单"},
	{"command": "search", "description": "搜索音乐：/search 歌名 歌手"},
	{"command": "status", "description": "我的任务与下载进度"},
	{"command": "download", "description": "下载指定搜索结果"},
	{"command": "link", "description": "获取任务下载链接"},
	{"command": "file", "description": "重新发送已完成文件"},
	{"command": "cancel", "description": "取消下载任务"},
	{"command": "resume", "description": "继续下载任务"},
	{"command": "ai", "description": "AI 对话：/ai 你的需求"},
	{"command": "ai_new", "description": "开始新的 AI 对话"},
	{"command": "ai_stop", "description": "停止 AI 本轮执行"},
	{"command": "ai_status", "description": "查看 AI 执行状态"},
	{"command": "ai_readonly", "description": "AI 仅查询模式"},
	{"command": "ai_write", "description": "AI 允许操作模式"},
	{"command": "help", "description": "使用帮助"},
}

var statusLabels = map[string]string{
	"queued":           "排队中",
	"resolving":        "解析中",
	"downloading":      "下载中",
	"verifying":        "校验中",
	"publishing":       "入库中",
	"enriching":        "整理文件中",
	"complete":         "下载完成",
	"failed":           "下载失败",
	"paused":           "已暂停",
	"cancelled":        "已取消",
	"cancel_requested": "取消中",
	"pause_requested":  "暂停中",
}

var builtinSourceLabels = map[string]string{
	"AudiusMusicClient":           "Audius",
	"FMAMusicClient":              "FMA",
	"CCMixterMusicClient":         "ccMixter",
	"OpenGameArtMusicClient":      "OpenGameArt",
	"WikimediaCommonsMusicClient": "Wikimedia Commons",
	"JamendoMusicClient":          "Jamendo",
	"ITunesMusicClient":           "iTunes",
}

var (
	reSizeLabel      = regexp.MustCompile(`(?i)^\s*(\d+(?:\.\d+)?)\s*(?:[KMGT]i?B|bytes?|B)\s*$`)
	reVersionBracket = regexp.MustCompile(`[\(（\[【]([^\)）\]】]{1,24})[\)）\]】]`)
	reStripBrackets  = regexp.MustCompile(`\s*[\(（\[【][^\)）\]】]*[\)）\]】]\s*`)
	reDerivativeKW   = regexp.MustCompile(`(?i)dj|伴奏|纯享|remix|live|instrumental|karaoke|倍速|0\.8|1\.2`)
	reDerivativeVal  = regexp.MustCompile(`(?i)dj|伴奏|纯享版|instrumental|karaoke|0\.8倍|1\.2倍|倍速`)
	reSpaces         = regexp.MustCompile(`\s+`)
)

func conciseText(v string, limit int) string {
	cleaned := strings.TrimSpace(reSpaces.ReplaceAllString(v, " "))
	runes := []rune(cleaned)
	if limit <= 0 || len(runes) <= limit {
		return cleaned
	}
	if limit == 1 {
		return "…"
	}
	return string(runes[:limit-1]) + "…"
}

func formatByteSize(bytesVal float64) string {
	if bytesVal < 0 {
		return "大小未知"
	}
	units := []string{"B", "KB", "MB", "GB", "TB"}
	for i, u := range units {
		if bytesVal < 1024 || i == len(units)-1 {
			if i == 0 {
				return fmt.Sprintf("%.0f %s", bytesVal, u)
			}
			return fmt.Sprintf("%.1f %s", bytesVal, u)
		}
		bytesVal /= 1024
	}
	return "大小未知"
}

func formatTrackSize(t model.TrackDTO) string {
	if t.SizeBytes != nil && *t.SizeBytes > 0 {
		return formatByteSize(float64(*t.SizeBytes))
	}
	if t.SizeLabel != nil {
		label := strings.TrimSpace(*t.SizeLabel)
		if m := reSizeLabel.FindStringSubmatch(label); m != nil {
			if f, err := strconv.ParseFloat(m[1], 64); err == nil && f > 0 {
				return conciseText(label, 24)
			}
		}
	}
	return "大小未知"
}

func formatDurationLabel(sec float64) string {
	if sec <= 0 {
		return "时长未知"
	}
	total := int(sec + 0.5)
	return fmt.Sprintf("%d:%02d", total/60, total%60)
}

func formatJobSize(ctx context.Context, s *Service, job model.DownloadJobDTO) string {
	if job.File != nil && *job.File != "" && s.backend != nil {
		if _, sz, _, err := s.backend.ResolveDownloadFile(ctx, *job.File); err == nil {
			return "文件大小：" + formatByteSize(float64(sz))
		}
	}
	if job.Bytes > 0 && job.Status == "complete" {
		return "文件大小：" + formatByteSize(float64(job.Bytes))
	}
	return "文件大小：大小未知（文件不可访问）"
}

func formatJobProgress(job model.DownloadJobDTO) string {
	if job.TotalBytes != nil && *job.TotalBytes > 0 {
		pct := float64(job.Bytes) / float64(*job.TotalBytes) * 100
		if pct > 100 {
			pct = 100
		}
		return fmt.Sprintf("%s / %s · %.1f%%", formatByteSize(float64(job.Bytes)), formatByteSize(float64(*job.TotalBytes)), pct)
	}
	if job.Status == "complete" {
		if job.Bytes > 0 {
			return "文件大小：" + formatByteSize(float64(job.Bytes))
		}
		return "文件大小：" + formatTrackSize(job.Track)
	}
	if job.Bytes > 0 {
		return fmt.Sprintf("已下载 %s · 总大小未知", formatByteSize(float64(job.Bytes)))
	}
	return "文件大小：" + formatTrackSize(job.Track)
}

func trackVersionLabel(t model.TrackDTO) string {
	title := strings.TrimSpace(t.Title)
	album := conciseText(t.Album, 28)
	var firstTag string
	if m := reVersionBracket.FindStringSubmatch(title); m != nil {
		firstTag = strings.TrimSpace(m[1])
	}
	base := strings.TrimSpace(reStripBrackets.ReplaceAllString(title, ""))
	if album != "" {
		if firstTag != "" && album == base && !strings.Contains(album, firstTag) {
			return conciseText(fmt.Sprintf("%s（%s）", album, firstTag), 32)
		}
		return album
	}
	if firstTag != "" {
		return conciseText(firstTag, 28)
	}
	return "标准版本"
}

func sourceLabelForTrack(t model.TrackDTO, availableSources []model.AvailableSourceDTO) string {
	for _, src := range availableSources {
		if (t.Source != "" && (src.ID == t.Source || (src.EngineName != "" && src.EngineName == t.Source))) ||
			(t.RootSource != "" && (src.ID == t.RootSource || (src.EngineName != "" && src.EngineName == t.RootSource))) {
			if label := strings.TrimSpace(src.Label); label != "" {
				return label
			}
		}
	}
	if l, ok := builtinSourceLabels[t.Source]; ok {
		return l
	}
	if t.RootSource != "" {
		if l, ok := builtinSourceLabels[t.RootSource]; ok {
			return l
		}
	}
	if strings.TrimSpace(t.Source) != "" {
		return t.Source
	}
	return t.RootSource
}

func formatTrackLines(idx int, t model.TrackDTO, sourceName string, compact, showSource bool) []string {
	title := conciseText(t.Title, 48)
	artist := conciseText(t.Artist, 28)
	qual := "音质待解析"
	if len(t.Qualities) > 0 && strings.TrimSpace(t.Qualities[0].Label) != "" {
		qual = conciseText(t.Qualities[0].Label, 24)
	}
	size := formatTrackSize(t)
	if compact {
		return []string{
			fmt.Sprintf("%d. %s", idx, title),
			fmt.Sprintf("   %s · %s", artist, conciseText(sourceName, 18)),
			fmt.Sprintf("   %s · %s", qual, size),
		}
	}
	header := fmt.Sprintf("%d. %s", idx, title)
	if artist != "" {
		header += " - " + artist
	}
	version := trackVersionLabel(t)
	spec := fmt.Sprintf("音质：%s ｜ 大小：%s ｜ 时长：%s", qual, size, formatDurationLabel(t.Duration))
	if showSource && sourceName != "" {
		return []string{
			header,
			fmt.Sprintf("   音源：%s ｜ 版本：%s", conciseText(sourceName, 18), version),
			"   " + spec,
		}
	}
	return []string{
		header,
		fmt.Sprintf("   版本：%s ｜ %s", version, spec),
	}
}

func messageHeading(channel, title, subtitle string) string {
	heading := fmt.Sprintf("【%s】", title)
	if channel == ChannelTelegram {
		heading = "🎵 " + title
	}
	if subtitle != "" {
		return heading + "\n" + subtitle
	}
	return heading
}

func commandHint(channel string, source, task, interactive bool) string {
	if channel == ChannelTelegram || !interactive {
		return ""
	}
	if task {
		return "操作：获取链接 · 状态 · 链接 <任务编号> · 取消 <任务编号>"
	}
	if source {
		return "操作：下载 1 · 音源 2 · 下一页 / 上一页 · 状态"
	}
	return "操作：下载 1 · 下一页 / 上一页 · 状态"
}

func statusLabelText(status string) string {
	if l, ok := statusLabels[status]; ok {
		return l
	}
	return status
}

func buttonRows(buttons []InlineButton, cols int) [][]InlineButton {
	if cols < 1 {
		cols = 3
	}
	var rows [][]InlineButton
	for i := 0; i < len(buttons); i += cols {
		end := i + cols
		if end > len(buttons) {
			end = len(buttons)
		}
		rows = append(rows, buttons[i:end])
	}
	return rows
}

func searchPageSize(channel string) int {
	if channel == ChannelTelegram {
		return 8
	}
	return 5
}

func utf16Len(s string) int {
	n := 0
	for _, r := range s {
		n += utf16.RuneLen(r)
	}
	return n
}

func splitReplyChunks(channel, text, linkURL string) ([]string, error) {
	limit := 4000
	if channel == ChannelWeCom || channel == ChannelFeishu || channel == ChannelDingTalk || channel == ChannelDiscord {
		limit = 1800
	}
	if channel == ChannelDiscord {
		suffix := ""
		if linkURL != "" {
			suffix = "\n" + linkURL
		}
		avail := 2000 - utf16Len("MeloKeep · "+suffix)
		if avail < limit {
			limit = avail
		}
		if limit < 2 {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "Discord 下载链接过长，请缩短通知公网访问地址")
		}
	}
	measured := func(v string) int {
		if channel == ChannelWeCom {
			return len(v)
		}
		return utf16Len(v)
	}

	var blocks []string
	rest := text
	for rest != "" {
		if idx := strings.Index(rest, "\n\n"); idx >= 0 {
			blocks = append(blocks, rest[:idx+2])
			rest = rest[idx+2:]
		} else {
			blocks = append(blocks, rest)
			break
		}
	}

	var chunks []string
	current := ""
	for _, block := range blocks {
		if current != "" && measured(current+block) <= limit {
			current += block
			continue
		}
		if measured(block) <= limit {
			if current != "" {
				chunks = append(chunks, current)
			}
			current = block
			continue
		}
		// Split oversized block line by line, keeping trailing newlines.
		rem := block
		for rem != "" {
			var line string
			if idx := strings.IndexByte(rem, '\n'); idx >= 0 {
				line = rem[:idx+1]
				rem = rem[idx+1:]
			} else {
				line = rem
				rem = ""
			}
			if current != "" && measured(current+line) > limit && measured(line) <= limit {
				chunks = append(chunks, current)
				current = line
				continue
			}
			for _, r := range line {
				rs := string(r)
				if measured(current+rs) > limit && current != "" {
					chunks = append(chunks, current)
					current = ""
				}
				current += rs
			}
		}
	}
	if current != "" || len(chunks) == 0 {
		chunks = append(chunks, current)
	}
	return chunks, nil
}

func bigramsSimilarity(a, b string) float64 {
	ra, rb := []rune(a), []rune(b)
	if len(ra) == 0 && len(rb) == 0 {
		return 1
	}
	if len(ra) == 0 || len(rb) == 0 {
		return 0
	}
	if a == b {
		return 1
	}
	// Character overlap Dice score on runes for compact similarity ordering.
	cntA := make(map[rune]int, len(ra))
	for _, r := range ra {
		cntA[r]++
	}
	matches := 0
	for _, r := range rb {
		if cntA[r] > 0 {
			matches++
			cntA[r]--
		}
	}
	return float64(2*matches) / float64(len(ra)+len(rb))
}

func bestQualityScore(q model.QualityDTO) (bool, int) {
	codec := strings.ToLower(strings.TrimSpace(q.Codec))
	lossless := q.Lossless ||
		codec == "flac" || codec == "wav" || codec == "alac" || codec == "ape" || codec == "wv" || codec == "tta" || codec == "dsf" || codec == "dff" ||
		strings.Contains(q.Label, "无损") || strings.Contains(q.ID, ":flac:")
	br := 0
	if q.Bitrate != nil {
		br = *q.Bitrate
	}
	return lossless, br
}

func rankTracks(tracks []model.TrackDTO, keyword string) []model.TrackDTO {
	normalize := func(v string) string {
		var b strings.Builder
		for _, r := range strings.ToLower(v) {
			if unicode.IsLetter(r) || unicode.IsDigit(r) {
				b.WriteRune(r)
			}
		}
		return b.String()
	}
	var terms []string
	for _, part := range strings.Fields(keyword) {
		if n := normalize(part); n != "" {
			terms = append(terms, n)
		}
	}
	joinedTerms := strings.Join(terms, "")
	wantDerivative := reDerivativeKW.MatchString(keyword)

	type scored struct {
		track      model.TrackDTO
		matched    int
		exact      bool
		lossless   bool
		bitrate    int
		derivative bool
		simMilli   int
	}
	items := make([]scored, 0, len(tracks))
	for _, t := range tracks {
		if t.Episodes > 0 {
			continue
		}
		quals := append([]model.QualityDTO(nil), t.Qualities...)
		sort.SliceStable(quals, func(i, j int) bool {
			li, bi := bestQualityScore(quals[i])
			lj, bj := bestQualityScore(quals[j])
			if li != lj {
				return li
			}
			return bi > bj
		})
		t.Qualities = quals

		title := normalize(t.Title)
		artist := normalize(t.Artist)
		matched := 0
		exact := joinedTerms != "" && title == joinedTerms
		maxSim := 0.0
		for _, term := range terms {
			if strings.Contains(title+artist, term) {
				matched++
			}
			if title == term {
				exact = true
			}
			if sim := bigramsSimilarity(term, title); sim > maxSim {
				maxSim = sim
			}
		}
		lossless, bitrate := false, 0
		if len(quals) > 0 {
			lossless, bitrate = bestQualityScore(quals[0])
		}
		deriv := !wantDerivative && reDerivativeVal.MatchString(t.Title+" "+t.Album)
		items = append(items, scored{
			track:      t,
			matched:    matched,
			exact:      exact,
			lossless:   lossless,
			bitrate:    bitrate,
			derivative: deriv,
			simMilli:   int(maxSim * 1000),
		})
	}

	sort.SliceStable(items, func(i, j int) bool {
		a, b := items[i], items[j]
		if a.matched != b.matched {
			return a.matched > b.matched
		}
		if a.exact != b.exact {
			return a.exact
		}
		if a.lossless != b.lossless {
			return a.lossless
		}
		if a.bitrate != b.bitrate {
			return a.bitrate > b.bitrate
		}
		if a.derivative != b.derivative {
			return !a.derivative
		}
		return a.simMilli > b.simMilli
	})

	out := make([]model.TrackDTO, len(items))
	for i, it := range items {
		out[i] = it.track
	}
	return out
}

func (s *Service) menuButtons() [][]InlineButton {
	cfg := s.Config()
	buttons := [][]InlineButton{
		{
			{Text: "搜索音乐", CallbackData: "/search"},
			{Text: "我的任务", CallbackData: "/status"},
		},
	}
	if cfg.TGAIEnabled || cfg.WeComAIEnabled || cfg.FeishuAIEnabled || cfg.DingTalkAIEnabled || cfg.DiscordAIEnabled {
		buttons = append(buttons, []InlineButton{
			{Text: "AI 助手", CallbackData: "/ai"},
			{Text: "AI 状态", CallbackData: "/ai_status"},
		})
	}
	if links := s.Links(); links != nil {
		buttons = append(buttons, []InlineButton{
			{Text: "打开网页试听", URL: links.baseURL},
		})
	}
	buttons = append(buttons, []InlineButton{
		{Text: "使用帮助", CallbackData: "/help"},
	})
	return buttons
}

func (s *Service) taskButtons(ident, status, prefix string) []InlineButton {
	var row []InlineButton
	switch status {
	case "complete":
		if s.Links() != nil {
			row = append(row, InlineButton{Text: prefix + "获取链接", CallbackData: "/link " + ident})
		}
		row = append(row, InlineButton{Text: prefix + "发送文件", CallbackData: "/file " + ident})
	case "paused", "failed":
		row = append(row, InlineButton{Text: prefix + "继续下载", CallbackData: "/resume " + ident})
	}
	switch status {
	case "queued", "resolving", "downloading", "verifying", "publishing", "enriching", "paused", "failed":
		row = append(row, InlineButton{Text: prefix + "取消任务", CallbackData: "/cancel " + ident})
	}
	return row
}

func shortTaskID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

func (s *Service) buildCompletedJobText(ctx context.Context, channel, user string, job model.DownloadJobDTO) string {
	lines := []string{
		messageHeading(channel, "下载完成", ""),
		"",
		conciseText(job.Track.Title, 60),
		"歌手：" + conciseText(job.Track.Artist, 36),
		"任务：" + shortTaskID(job.ID),
		formatJobSize(ctx, s, job),
	}
	if job.Processing != nil && strings.TrimSpace(job.Processing.Detail) != "" {
		lines = append(lines, conciseText(job.Processing.Detail, 120))
	}
	interactive := user != "downloads"
	if hint := commandHint(channel, false, true, interactive); hint != "" {
		lines = append(lines, "", hint)
	}
	return strings.Join(lines, "\n")
}

// enqueueCompletedTaskPrompt notifies the user when a download completes so they can
// explicitly choose to generate a download link or (on Telegram) request the audio file directly.
func (s *Service) enqueueCompletedTaskPrompt(ctx context.Context, channel, user, eventKey string, job model.DownloadJobDTO) error {
	text := s.buildCompletedJobText(ctx, channel, user, job)
	var buttons [][]InlineButton
	if channel == ChannelTelegram {
		if s.Links() != nil {
			text += "\n\n请选择发送文件或获取原文件下载链接："
		} else {
			text += "\n\n点击下方发送文件；如需下载链接请先配置公网访问地址。"
		}
		if tBtns := s.taskButtons(job.ID, "complete", ""); len(tBtns) > 0 {
			buttons = append(buttons, tBtns)
		}
		buttons = append(buttons, []InlineButton{{Text: "我的任务", CallbackData: "/status"}})
	} else {
		if s.Links() != nil {
			text += fmt.Sprintf("\n\n回复「获取链接」或「链接 %s」获取原文件下载链接。", shortTaskID(job.ID))
		} else {
			text += "\n\n请在网页端下载原文件，或在通知设置填写公网访问地址后发送「获取链接」。"
		}
	}
	return s.enqueueReply(ctx, channel, user, eventKey, OutboxPayload{
		Text:    text,
		Buttons: buttons,
		JobID:   job.ID,
	})
}

func (s *Service) enqueueCompletedTaskNotice(ctx context.Context, channel, user, eventKey string, job model.DownloadJobDTO) error {
	text := s.buildCompletedJobText(ctx, channel, user, job)
	isTextOnlyChannel := channel == ChannelFeishu || channel == ChannelDingTalk || channel == ChannelDiscord
	if isTextOnlyChannel {
		var u string
		if links := s.Links(); links != nil {
			if link, err := links.Create(ctx, job, s.nowFn()); err == nil {
				u = link
			}
		}
		if u != "" {
			text += "\n\n该渠道不支持直接发送音频文件，已为你生成原文件下载链接。"
		} else {
			text += "\n\n该渠道不支持直接发送音频文件；请在网页下载或先配置公网访问地址后再获取链接。"
		}
		return s.enqueueReply(ctx, channel, user, eventKey, OutboxPayload{
			Text:  text,
			URL:   u,
			JobID: job.ID,
		})
	}
	if channel == ChannelWeCom && job.File != nil && *job.File != "" && s.backend != nil {
		if _, sz, _, err := s.backend.ResolveDownloadFile(ctx, *job.File); err == nil && sz > wecomFileMax {
			if links := s.Links(); links != nil {
				if u, lErr := links.Create(ctx, job, s.nowFn()); lErr == nil {
					return s.enqueueReply(ctx, channel, user, eventKey, OutboxPayload{
						Text:  text + fmt.Sprintf("\n文件超过该渠道发送上限（%s），请通过链接下载原文件。", formatByteSize(wecomFileMax)),
						URL:   u,
						JobID: job.ID,
					})
				}
			}
			return s.enqueueReply(ctx, channel, user, eventKey, OutboxPayload{
				Text: text + "\n文件超过发送上限，请在通知设置中填写访问地址后获取链接，或配置自建 Telegram Bot API。",
			})
		}
	}
	return s.enqueueReply(ctx, channel, user, eventKey, OutboxPayload{
		Text:  text,
		JobID: job.ID,
		File:  true,
	})
}

func (s *Service) startCatalogSearch(ctx context.Context, actor auth.Actor, msg InboundMessage, keyword string, catalog []model.AvailableSourceDTO) error {
	configured := splitCSV(s.Config().BotSources)
	sources := make([]botSourceState, 0, len(catalog))
	known := make(map[string]bool)
	for _, src := range catalog {
		if src.ID != "" {
			known[src.ID] = true
			if canon := config.CanonicalEngineName(src.ID); canon != "" {
				known[canon] = true
			}
		}
		if src.EngineName != "" {
			known[src.EngineName] = true
		}
		if len(configured) > 0 {
			matched := false
			for _, c := range configured {
				canonC := config.CanonicalEngineName(c)
				if c == src.ID || (src.EngineName != "" && (c == src.EngineName || canonC == src.EngineName)) {
					matched = true
					break
				}
			}
			if !matched {
				continue
			}
		}
		label := src.Label
		if label == "" {
			label = src.ID
		}
		sources = append(sources, botSourceState{
			ID:         src.ID,
			Label:      label,
			EngineName: src.EngineName,
			State:      "searching",
		})
	}
	if len(sources) == 0 {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "没有有效的机器人音源，请检查音源设置")
	}
	for _, c := range configured {
		if !known[c] && !known[config.CanonicalEngineName(c)] {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "没有有效的机器人音源，请检查音源设置")
		}
	}

	owner := msg.Channel + ":" + msg.User
	searchID := msg.ID
	if len(searchID) > 8 {
		searchID = searchID[:8]
	}
	perSource := 0
	if ssb, ok := s.backend.(StreamSearchBackend); ok {
		perSource = ssb.ResultsPerSource()
	}
	view := "all"
	if msg.Channel == ChannelTelegram {
		view = "source"
	}
	res := botSearchResult{
		ID:               owner + ":" + searchID,
		Keyword:          keyword,
		Expires:          s.nowFn().Unix() + 1800,
		Selected:         0,
		Page:             1,
		View:             view,
		ResultsPerSource: perSource,
		SourceStates:     sources,
	}
	if err := s.saveSearchResult(ctx, res); err != nil {
		return err
	}

	cleanMsg := msg
	cleanMsg.ReplyMessageID = nil
	if msg.Channel == ChannelTelegram {
		if err := s.sendSourcePage(ctx, cleanMsg, searchID, res, 1, nil); err != nil {
			return err
		}
	} else {
		if err := s.saveSession(ctx, owner, searchID, 1, res.Expires); err != nil {
			return err
		}
	}
	s.launchSourceSearch(actor, cleanMsg, searchID, res)
	return nil
}

func (s *Service) saveSearchResult(ctx context.Context, res botSearchResult) error {
	raw, _ := json.Marshal(res)
	_, err := s.store.PutDurableRecord(ctx, nsBotSearch, res.ID, string(raw), 0)
	return err
}

func (s *Service) loadSearchResult(ctx context.Context, key string) (botSearchResult, bool, error) {
	rec, ok, err := s.store.GetDurableRecord(ctx, nsBotSearch, key)
	if err != nil || !ok {
		return botSearchResult{}, ok, err
	}
	var res botSearchResult
	if err := json.Unmarshal([]byte(rec.BodyJSON), &res); err != nil {
		return botSearchResult{}, false, err
	}
	return res, true, nil
}

func (s *Service) saveSession(ctx context.Context, owner, searchID string, page int, expires int64) error {
	sess := botSessionState{ID: owner, SearchID: searchID, Page: page, Expires: expires}
	raw, _ := json.Marshal(sess)
	_, err := s.store.PutDurableRecord(ctx, nsBotSession, owner, string(raw), 0)
	return err
}

func (s *Service) launchSourceSearch(actor auth.Actor, msg InboundMessage, searchID string, initial botSearchResult) {
	owner := msg.Channel + ":" + msg.User
	parent := s.workerCtx()
	s.searchMu.Lock()
	if s.searchCancel == nil {
		s.searchCancel = make(map[string]context.CancelFunc)
	}
	if prev, ok := s.searchCancel[owner]; ok && prev != nil {
		prev()
	}
	searchCtx, cancel := context.WithCancel(parent)
	s.searchCancel[owner] = cancel
	s.searchMu.Unlock()

	go func() {
		defer cancel()
		n := len(initial.SourceStates)
		jobs := make(chan int, n)
		if initial.Selected >= 0 && initial.Selected < n {
			jobs <- initial.Selected
		}
		for i := 0; i < n; i++ {
			if i != initial.Selected {
				jobs <- i
			}
		}
		close(jobs)

		workers := 4
		if n < workers {
			workers = n
		}
		doneCh := make(chan struct{}, workers)
		for w := 0; w < workers; w++ {
			go func() {
				defer func() { doneCh <- struct{}{} }()
				for idx := range jobs {
					if searchCtx.Err() != nil {
						return
					}
					s.runSingleSourceSearch(searchCtx, actor, msg, searchID, initial.ID, initial.Keyword, idx, initial.SourceStates[idx])
				}
			}()
		}
		for w := 0; w < workers; w++ {
			<-doneCh
		}
		if searchCtx.Err() != nil {
			return
		}
		if msg.Channel != ChannelTelegram {
			if cur, ok, _ := s.loadSearchResult(searchCtx, initial.ID); ok && cur.Expires > s.nowFn().Unix() {
				_ = s.sendTextSearchSummary(searchCtx, msg, searchID, cur, max(1, cur.Page))
			}
		}
		s.searchMu.Lock()
		delete(s.searchCancel, owner)
		s.searchMu.Unlock()
	}()
}

func (s *Service) runSingleSourceSearch(ctx context.Context, actor auth.Actor, msg InboundMessage, searchID, resID, keyword string, index int, source botSourceState) {
	var streamed []model.TrackDTO
	seen := make(map[string]bool)
	lastUpdate := time.Time{}

	onTrack := func(tr model.TrackDTO) {
		if msg.Channel != ChannelTelegram || ctx.Err() != nil {
			return
		}
		if len(streamed) >= 50 || seen[tr.ID] {
			return
		}
		now := time.Now()
		if !lastUpdate.IsZero() && now.Sub(lastUpdate) < time.Second {
			return
		}
		lastUpdate = now
		seen[tr.ID] = true
		streamed = append(streamed, tr)

		s.searchResMu.Lock()
		cur, ok, _ := s.loadSearchResult(ctx, resID)
		if ok && cur.Expires > s.nowFn().Unix() && index < len(cur.SourceStates) {
			cur.SourceStates[index].Tracks = append([]model.TrackDTO(nil), streamed...)
			_ = s.saveSearchResult(ctx, cur)
		}
		s.searchResMu.Unlock()
		if ok && cur.Expires > s.nowFn().Unix() && cur.Selected == index {
			_ = s.sendSourcePage(ctx, msg, searchID, cur, max(1, cur.Page), nil)
		}
	}

	var (
		tracks  []model.TrackDTO
		partial bool
		err     error
	)
	if ssb, ok := s.backend.(StreamSearchBackend); ok && msg.Channel == ChannelTelegram {
		tracks, partial, err = ssb.SearchTracksStream(ctx, actor, keyword, []string{source.ID}, onTrack)
	} else {
		tracks, partial, err = s.backend.SearchTracks(ctx, actor, keyword, []string{source.ID})
	}
	if ctx.Err() != nil {
		return
	}

	state := "complete"
	var finalTracks []model.TrackDTO
	if err != nil {
		if s.onLog != nil {
			s.onLog("warning", fmt.Sprintf("机器人音源搜索失败：%s · %s", source.Label, s.RedactError(err)), "bot", "")
		}
		state = "failed"
	} else {
		if partial {
			state = "failed"
		}
		finalTracks = append([]model.TrackDTO(nil), streamed...)
		for _, tr := range rankTracks(tracks, keyword) {
			if !seen[tr.ID] {
				finalTracks = append(finalTracks, tr)
			}
		}
		if len(finalTracks) > 50 {
			finalTracks = finalTracks[:50]
		}
	}

	s.searchResMu.Lock()
	cur, ok, _ := s.loadSearchResult(ctx, resID)
	if ok && cur.Expires > s.nowFn().Unix() && index < len(cur.SourceStates) {
		cur.SourceStates[index].Tracks = finalTracks
		cur.SourceStates[index].State = state
		_ = s.saveSearchResult(ctx, cur)
	}
	s.searchResMu.Unlock()

	if ok && cur.Expires > s.nowFn().Unix() && msg.Channel == ChannelTelegram && cur.Selected == index {
		_ = s.sendSourcePage(ctx, msg, searchID, cur, max(1, cur.Page), nil)
	}
}

func (s *Service) sendSourcePage(ctx context.Context, msg InboundMessage, searchID string, res botSearchResult, page int, sourceIndex *int) error {
	selected := res.Selected
	if sourceIndex != nil {
		selected = *sourceIndex
	}
	if selected < 0 || selected >= len(res.SourceStates) {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "音源序号无效或搜索已过期")
	}
	source := res.SourceStates[selected]
	tracks := source.Tracks
	size := searchPageSize(msg.Channel)
	if msg.Channel != ChannelTelegram && res.ResultsPerSource > 0 {
		size = res.ResultsPerSource
	}
	pages := (len(tracks) + size - 1) / size
	if pages < 1 {
		pages = 1
	}
	if page < 1 || page > pages {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索页码无效")
	}
	start := (page - 1) * size
	end := start + size
	if end > len(tracks) {
		end = len(tracks)
	}
	visible := tracks[start:end]

	res.Selected = selected
	res.Page = page
	if msg.ReplyMessageID != nil && *msg.ReplyMessageID > 0 {
		mid := *msg.ReplyMessageID
		res.CardMsg = &mid
	}
	if err := s.saveSearchResult(ctx, res); err != nil {
		return err
	}

	subtitle := fmt.Sprintf("第 %d/%d 页 · 共 %d 首", page, pages, len(tracks))
	lines := []string{
		messageHeading(msg.Channel, fmt.Sprintf("搜索源：%s · %s", source.Label, conciseText(res.Keyword, 60)), subtitle),
		"",
	}
	if source.State == "searching" {
		lines = append(lines, "搜索中… 可先切换其他音源，完成后自动更新。")
	}
	if len(tracks) == 0 && source.State != "searching" {
		if source.State == "failed" {
			lines = append(lines, "搜索已停止、失败或超时，请重新搜索或检查音源设置。")
		} else {
			lines = append(lines, "没有可下载结果，请换关键词或切换音源。")
		}
	} else if len(tracks) > 0 {
		var availableSources []model.AvailableSourceDTO
		if s.backend != nil {
			availableSources = s.backend.AvailableSources()
		}
		for i, tr := range visible {
			sn := sourceLabelForTrack(tr, availableSources)
			if sn == "" || (sn == tr.Source && source.Label != "" && (tr.Source == source.ID || tr.Source == source.EngineName)) {
				sn = source.Label
			}
			lines = append(lines, formatTrackLines(start+i+1, tr, sn, msg.Channel == ChannelTelegram, false)...)
			lines = append(lines, "")
		}
		if source.State == "failed" {
			lines = append(lines, "", "提示：该音源部分结果已返回，其余失败或超时。")
		}
	}

	var buttons [][]InlineButton
	if msg.Channel == ChannelTelegram {
		var dlBtns []InlineButton
		for i := start + 1; i <= end; i++ {
			dlBtns = append(dlBtns, InlineButton{
				Text:         strconv.Itoa(i),
				CallbackData: fmt.Sprintf("/download %s %d %d", searchID, i, selected),
			})
		}
		buttons = append(buttons, buttonRows(dlBtns, size)...)
		var nav []InlineButton
		if page > 1 {
			nav = append(nav, InlineButton{Text: "上一页", CallbackData: fmt.Sprintf("/results %s %d %d", searchID, page-1, selected)})
		}
		if page < pages {
			nav = append(nav, InlineButton{Text: "下一页", CallbackData: fmt.Sprintf("/results %s %d %d", searchID, page+1, selected)})
		}
		if len(nav) > 0 {
			buttons = append(buttons, nav)
		}
		var srcBtns []InlineButton
		for i, src := range res.SourceStates {
			prefix := ""
			if i == selected {
				prefix = "✓ "
			}
			srcBtns = append(srcBtns, InlineButton{
				Text:         prefix + conciseText(src.Label, 18),
				CallbackData: fmt.Sprintf("/source %s %d", searchID, i),
			})
		}
		buttons = append(buttons, buttonRows(srcBtns, 4)...)
		buttons = append(buttons, []InlineButton{
			{Text: "我的任务", CallbackData: "/status"},
			{Text: "功能菜单", CallbackData: "/menu"},
		})
	} else {
		var srcParts []string
		for i, src := range res.SourceStates {
			srcParts = append(srcParts, fmt.Sprintf("%d.%s", i+1, src.Label))
		}
		lines = append(lines, "操作提示", commandHint(msg.Channel, true, false, true), "切换音源：音源 序号（"+strings.Join(srcParts, "  ")+"）")
		if links := s.Links(); links != nil && (msg.Channel == ChannelFeishu || msg.Channel == ChannelDingTalk || msg.Channel == ChannelDiscord) {
			lines = append(lines, "试听："+links.baseURL)
		}
	}

	cardKey := ""
	if msg.Channel == ChannelTelegram {
		cardKey = res.ID
	}
	if err := s.saveSession(ctx, msg.Channel+":"+msg.User, searchID, page, res.Expires); err != nil {
		return err
	}
	return s.enqueueReply(ctx, msg.Channel, msg.User, fmt.Sprintf("card:%s:%d", res.ID, s.cardSeq.Add(1)), OutboxPayload{
		Text:          strings.Join(lines, "\n"),
		Buttons:       buttons,
		CardKey:       cardKey,
		EditMessageID: msg.ReplyMessageID,
	})
}

func (s *Service) sendTextSearchSummary(ctx context.Context, msg InboundMessage, searchID string, res botSearchResult, page int) error {
	size := searchPageSize(msg.Channel)
	if res.ResultsPerSource > 0 {
		size = res.ResultsPerSource
	}
	pages := 1
	totalTracks := 0
	activeSources := 0
	for _, src := range res.SourceStates {
		totalTracks += len(src.Tracks)
		if len(src.Tracks) > 0 {
			activeSources++
		}
		p := (len(src.Tracks) + size - 1) / size
		if p > pages {
			pages = p
		}
	}
	if page < 1 || page > pages {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "搜索页码无效")
	}
	start := (page - 1) * size
	var headingTitle, subtitle string
	if len(res.SourceStates) == 1 {
		headingTitle = fmt.Sprintf("搜索源：%s · %s", res.SourceStates[0].Label, conciseText(res.Keyword, 60))
		subtitle = fmt.Sprintf("第 %d/%d 页 · 共 %d 首", page, pages, totalTracks)
	} else {
		headingTitle = fmt.Sprintf("搜索结果 · %s", conciseText(res.Keyword, 60))
		subtitle = fmt.Sprintf("第 %d/%d 页 · %d/%d 个音源共 %d 首（按相关性与音质排序）", page, pages, activeSources, len(res.SourceStates), totalTracks)
	}

	lines := []string{messageHeading(msg.Channel, headingTitle, subtitle), ""}
	var flat []botFlatTrack
	var emptyLines []string
	for sIdx, src := range res.SourceStates {
		end := start + size
		if end > len(src.Tracks) {
			end = len(src.Tracks)
		}
		var visible []model.TrackDTO
		if start < len(src.Tracks) {
			visible = src.Tracks[start:end]
		}
		if len(visible) > 0 {
			if len(res.SourceStates) > 1 {
				countDesc := fmt.Sprintf("共 %d 首", len(src.Tracks))
				if len(src.Tracks) > len(visible) {
					countDesc = fmt.Sprintf("本页 %d 首 / 共 %d 首", len(visible), len(src.Tracks))
				}
				lines = append(lines, fmt.Sprintf("▸ 搜索源：%s（%s）", src.Label, countDesc))
			}
			for _, tr := range visible {
				flat = append(flat, botFlatTrack{Track: tr, SourceIndex: sIdx})
				lines = append(lines, formatTrackLines(len(flat), tr, src.Label, false, false)...)
			}
			if src.State == "failed" {
				lines = append(lines, "   提示：该音源部分结果已返回，其余失败或超时。")
			}
			lines = append(lines, "")
		} else if page == 1 {
			reason := "没有可下载结果"
			if src.State == "failed" {
				reason = "搜索已停止、失败或超时"
			}
			if len(res.SourceStates) == 1 {
				if src.State == "failed" {
					lines = append(lines, reason+"，请重新搜索或检查音源设置。", "")
				} else {
					lines = append(lines, "没有可下载结果，请换关键词或切换音源。", "")
				}
			} else {
				emptyLines = append(emptyLines, fmt.Sprintf("▸ 搜索源：%s：%s", src.Label, reason))
			}
		}
	}
	if len(emptyLines) > 0 {
		lines = append(lines, emptyLines...)
		lines = append(lines, "")
	}

	res.Page = page
	res.View = "all"
	res.FlatTracks = flat
	if err := s.saveSearchResult(ctx, res); err != nil {
		return err
	}

	var srcParts []string
	for i, src := range res.SourceStates {
		srcParts = append(srcParts, fmt.Sprintf("%d.%s", i+1, src.Label))
	}
	lines = append(lines, "操作提示")
	if len(flat) > 0 {
		lines = append(lines, fmt.Sprintf("· 下载歌曲：下载 序号（例如：下载 1，当前可选 1–%d）", len(flat)))
	}
	if len(res.SourceStates) > 1 {
		lines = append(lines, "· 单独查看音源：音源 序号（"+strings.Join(srcParts, "  ")+"）")
	}
	if pages > 1 {
		lines = append(lines, "· 翻页查看：下一页 / 上一页 / 第2页")
	}
	lines = append(lines, "· 任务管理：状态 · 链接 <任务编号> · 取消 <任务编号>")
	if links := s.Links(); links != nil && (msg.Channel == ChannelFeishu || msg.Channel == ChannelDingTalk || msg.Channel == ChannelDiscord) {
		lines = append(lines, "· 网页试听："+links.baseURL)
	}

	if err := s.saveSession(ctx, msg.Channel+":"+msg.User, searchID, page, res.Expires); err != nil {
		return err
	}
	return s.enqueueReply(ctx, msg.Channel, msg.User, fmt.Sprintf("summary:%s:%d:%s", searchID, page, msg.ID), OutboxPayload{
		Text: strings.Join(lines, "\n"),
	})
}
