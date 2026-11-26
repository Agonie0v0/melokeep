package recommend

import (
	"regexp"
	"strings"
	"unicode"

	"github.com/agonie0v0/melokeep/internal/model"
)

var (
	parenSuffixRe = regexp.MustCompile(`[\(\[（【][^()\[\]（）【】]*[\)\]）】]`)
	artistSepRe   = regexp.MustCompile(`(?i)\s*(?:[,，/;&、]|\bfeat\.?\b|\bft\.?\b|\bwith\b|\bx\b)\s*`)
	multiSpaceRe  = regexp.MustCompile(`\s+`)
)

var penaltyTokens = []struct {
	token   string
	penalty int
}{
	{"伴奏", 35},
	{"instrumental", 35},
	{"karaoke", 35},
	{"ktv", 35},
	{"翻唱", 35},
	{"cover", 35},
	{"dj", 35},
	{"sped up", 35},
	{"slowed", 35},
	{"消音", 35},
	{"铃声", 35},
	{"ringtone", 35},
	{"纯音乐", 25},
	{"remix", 20},
	{"live", 18},
}

// NormalizeText lowercases text, strips parenthetical version/feat tags (when non-empty outside),
// and collapses punctuation into single spaces.
func NormalizeText(s string) string {
	trimmed := strings.TrimSpace(strings.ToLower(s))
	if trimmed == "" {
		return ""
	}
	stripped := strings.TrimSpace(parenSuffixRe.ReplaceAllString(trimmed, " "))
	if stripped == "" {
		stripped = trimmed
	}
	var b strings.Builder
	b.Grow(len(stripped))
	lastSpace := true
	for _, r := range stripped {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
			lastSpace = false
		} else if !lastSpace {
			b.WriteByte(' ')
			lastSpace = true
		}
	}
	return strings.TrimSpace(multiSpaceRe.ReplaceAllString(b.String(), " "))
}

// PrimaryArtist extracts the primary normalized artist name before multi-artist delimiters.
func PrimaryArtist(artist string) string {
	raw := strings.TrimSpace(artist)
	if raw == "" {
		return ""
	}
	parts := artistSepRe.Split(raw, 2)
	if len(parts) > 0 {
		if p := NormalizeText(parts[0]); p != "" {
			return p
		}
	}
	return NormalizeText(raw)
}

// TrackNormKey computes a canonical deduplication key from title and artist.
func TrackNormKey(title, artist string) string {
	nt := NormalizeText(title)
	if nt == "" {
		return ""
	}
	na := PrimaryArtist(artist)
	if na == "" {
		na = NormalizeText(artist)
	}
	return nt + "|" + na
}

func tokenOverlapRatio(a, b string) float64 {
	if a == "" || b == "" {
		return 0
	}
	if a == b {
		return 1.0
	}
	ta := strings.Fields(a)
	tb := strings.Fields(b)
	// For CJK or single-token strings without spaces, compare rune sets for partial matching.
	if len(ta) <= 1 && len(tb) <= 1 {
		ra := []rune(strings.ReplaceAll(a, " ", ""))
		rb := []rune(strings.ReplaceAll(b, " ", ""))
		if len(ra) == 0 || len(rb) == 0 {
			return 0
		}
		setB := make(map[rune]bool, len(rb))
		for _, r := range rb {
			setB[r] = true
		}
		hits := 0
		seen := make(map[rune]bool, len(ra))
		for _, r := range ra {
			if !seen[r] && setB[r] {
				seen[r] = true
				hits++
			}
		}
		denom := len(ra)
		if len(rb) > denom {
			denom = len(rb)
		}
		return float64(hits) / float64(denom)
	}
	setB := make(map[string]bool, len(tb))
	for _, tok := range tb {
		setB[tok] = true
	}
	hits := 0
	seen := make(map[string]bool, len(ta))
	for _, tok := range ta {
		if !seen[tok] && setB[tok] {
			seen[tok] = true
			hits++
		}
	}
	denom := len(ta)
	if len(tb) > denom {
		denom = len(tb)
	}
	if denom == 0 {
		return 0
	}
	return float64(hits) / float64(denom)
}

func hasFullPlayableVariant(tr model.ProviderTrack) bool {
	if len(tr.Variants) == 0 {
		return false
	}
	for _, v := range tr.Variants {
		if !v.PreviewOnly {
			return true
		}
	}
	return false
}

func hasOnlyPreviewVariants(tr model.ProviderTrack) bool {
	if len(tr.Variants) == 0 {
		return false
	}
	for _, v := range tr.Variants {
		if !v.PreviewOnly {
			return false
		}
	}
	return true
}

func hasLosslessVariant(tr model.ProviderTrack) bool {
	for _, v := range tr.Variants {
		if v.Lossless {
			return true
		}
	}
	return false
}

// ScoreCandidateMatch evaluates how well a provider search result matches the desired (title, artist)
// while penalizing covers, DJ remixes, instrumentals, and preview-only clips.
func ScoreCandidateMatch(candTitle, candArtist string, got model.ProviderTrack) (int, bool) {
	normCandTitle := NormalizeText(candTitle)
	normGotTitle := NormalizeText(got.Title)
	if normCandTitle == "" || normGotTitle == "" {
		return 0, false
	}

	titleExact := normCandTitle == normGotTitle
	titleScore := 0
	switch {
	case titleExact:
		titleScore = 60
	case strings.Contains(normGotTitle, normCandTitle) || strings.Contains(normCandTitle, normGotTitle):
		titleScore = 46
	default:
		ratio := tokenOverlapRatio(normCandTitle, normGotTitle)
		titleScore = int(ratio * 50)
	}
	if titleScore < 20 {
		return titleScore, false
	}

	normCandArtist := NormalizeText(candArtist)
	primCandArtist := PrimaryArtist(candArtist)
	normGotArtist := NormalizeText(got.Artist)
	primGotArtist := PrimaryArtist(got.Artist)

	artistScore := 0
	if normCandArtist == "" {
		artistScore = 15
	} else if normGotArtist != "" {
		switch {
		case normCandArtist == normGotArtist || (primCandArtist != "" && primCandArtist == primGotArtist):
			artistScore = 35
		case strings.Contains(normGotArtist, primCandArtist) || strings.Contains(primCandArtist, normGotArtist):
			artistScore = 24
		default:
			r1 := tokenOverlapRatio(normCandArtist, normGotArtist)
			r2 := tokenOverlapRatio(primCandArtist, primGotArtist)
			if r2 > r1 {
				r1 = r2
			}
			artistScore = int(r1 * 28)
		}
	}

	score := titleScore + artistScore
	if normCandArtist != "" && artistScore == 0 {
		if !titleExact {
			score -= 22
		} else if len([]rune(normCandTitle)) <= 2 {
			score -= 20
		} else {
			score -= 10
		}
	}

	rawCandLower := strings.ToLower(candTitle)
	rawGotLower := strings.ToLower(got.Title)
	for _, pt := range penaltyTokens {
		if strings.Contains(rawGotLower, pt.token) && !strings.Contains(rawCandLower, pt.token) {
			score -= pt.penalty
		}
	}

	if !got.PayRequired {
		score += 8
	} else {
		score -= 12
	}
	if hasFullPlayableVariant(got) {
		score += 8
	} else if hasOnlyPreviewVariants(got) {
		score -= 6
	}
	if hasLosslessVariant(got) {
		score += 4
	}
	if got.Cover != nil && strings.TrimSpace(*got.Cover) != "" {
		score += 3
	}
	if got.Duration >= 60 {
		score += 4
	} else if got.Duration > 0 && got.Duration < 45 {
		score -= 10
	}

	if score < 0 {
		score = 0
	}
	return score, score >= 55
}
