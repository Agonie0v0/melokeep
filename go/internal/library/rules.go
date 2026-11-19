package library

import (
	"bytes"
	"compress/gzip"
	"embed"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"unicode"
	"unicode/utf8"

	"github.com/agonie0v0/melokeep/internal/media"
	"github.com/agonie0v0/melokeep/internal/model"
)

//go:embed data/*.tsv.gz
var ruleTablesFS embed.FS

var (
	ruleAliasMap = map[string]string{
		"albumartist": "album_artist",
		"tracknumber": "track_number",
		"discnumber":  "disc_number",
	}
	ruleAllowedMethods = map[string]bool{
		"strip": true, "lstrip": true, "rstrip": true,
		"replace": true, "split": true,
		"lower": true, "upper": true, "title": true, "zfill": true,
	}
	ruleVariables = func() map[string]bool {
		m := make(map[string]bool, len(media.TagFields)+16)
		for _, f := range media.TagFields {
			m[f] = true
		}
		for a := range ruleAliasMap {
			m[a] = true
		}
		for _, extra := range []string{
			"first_artist", "first_artist_letter", "filename", "file_song_name",
			"parent_path", "parent_parent_path", "year", "counter", "null", "lyrics", "ext",
		} {
			m[extra] = true
		}
		return m
	}()
	tagFieldSet = func() map[string]bool {
		m := make(map[string]bool, len(media.TagFields))
		for _, f := range media.TagFields {
			m[f] = true
		}
		return m
	}()

	artistSplitRe  = regexp.MustCompile(`\s*[,，/;&、]\s*`)
	exprSubRe      = regexp.MustCompile(`\$\{([^{}]+)\}`)
	whitespaceRe   = regexp.MustCompile(`\s+`)
	unsafeSegRe    = regexp.MustCompile(`[<>:"/\\|?*\x00-\x1f\x7f]`)
	winReservedRe  = regexp.MustCompile(`(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$`)
	leadingDigitRe = regexp.MustCompile(`^\d+`)
)

// --- Embedded Pinyin & OpenCC tables ---

type openccTable struct {
	entries map[string]string
	maxLen  int
}

var (
	tablesOnce sync.Once
	pinyinMap  map[rune]string
	t2sTable   openccTable
	s2tTable   openccTable
	tablesErr  error
)

func loadRuleTables() {
	tablesOnce.Do(func() {
		pinyinMap = make(map[rune]string, 42000)
		rawPin, err := readGzipEmbed("data/pinyin_initials.tsv.gz")
		if err != nil {
			tablesErr = err
			return
		}
		for _, line := range strings.Split(string(rawPin), "\n") {
			if line == "" {
				continue
			}
			tab := strings.IndexByte(line, '\t')
			if tab <= 0 {
				continue
			}
			cp, err := strconv.ParseInt(line[:tab], 16, 32)
			if err != nil {
				continue
			}
			pinyinMap[rune(cp)] = line[tab+1:]
		}
		t2sTable, err = loadOpenCCTable("data/t2s.tsv.gz")
		if err != nil {
			tablesErr = err
			return
		}
		s2tTable, err = loadOpenCCTable("data/s2t.tsv.gz")
		if err != nil {
			tablesErr = err
			return
		}
	})
}

func readGzipEmbed(name string) ([]byte, error) {
	raw, err := ruleTablesFS.ReadFile(name)
	if err != nil {
		return nil, err
	}
	zr, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	defer zr.Close()
	return io.ReadAll(zr)
}

func loadOpenCCTable(name string) (openccTable, error) {
	raw, err := readGzipEmbed(name)
	if err != nil {
		return openccTable{}, err
	}
	tbl := openccTable{entries: make(map[string]string, 16000)}
	for _, line := range strings.Split(string(raw), "\n") {
		if line == "" {
			continue
		}
		tab := strings.IndexByte(line, '\t')
		if tab <= 0 {
			continue
		}
		k := line[:tab]
		v := line[tab+1:]
		tbl.entries[k] = v
		if rc := utf8.RuneCountInString(k); rc > tbl.maxLen {
			tbl.maxLen = rc
		}
	}
	return tbl, nil
}

func (t openccTable) convert(s string) string {
	if s == "" || len(t.entries) == 0 {
		return s
	}
	runes := []rune(s)
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(runes); {
		maxTry := t.maxLen
		if i+maxTry > len(runes) {
			maxTry = len(runes) - i
		}
		matched := false
		for l := maxTry; l >= 1; l-- {
			sub := string(runes[i : i+l])
			if repl, ok := t.entries[sub]; ok {
				b.WriteString(repl)
				i += l
				matched = true
				break
			}
		}
		if !matched {
			b.WriteRune(runes[i])
			i++
		}
	}
	return b.String()
}

// --- Variables, Transform, SafeSegment, ValidateTemplate ---

func Variables(path string, tags map[string]string, counter int, lyrics string) map[string]string {
	loadRuleTables()
	vals := make(map[string]string, len(ruleVariables))
	for k := range ruleVariables {
		vals[k] = ""
	}
	for k, v := range tags {
		vals[k] = v
	}
	for alias, canonical := range ruleAliasMap {
		if v, ok := tags[canonical]; ok {
			vals[alias] = v
		}
	}
	artist := ""
	if tags != nil {
		artist = tags["artist"]
	}
	first := artistSplitRe.Split(artist, 2)[0]
	letter := "#"
	if first != "" {
		r, _ := utf8.DecodeRuneInString(first)
		if r != utf8.RuneError {
			if l, ok := pinyinMap[r]; ok && l != "" {
				letter = l
			} else {
				letter = strings.ToUpper(string(r))
			}
		}
	}
	date := ""
	if tags != nil {
		date = tags["date"]
	}
	year := date
	if utf8.RuneCountInString(year) > 4 {
		runes := []rune(year)
		year = string(runes[:4])
	}
	cleanPath := filepath.Clean(path)
	base := filepath.Base(cleanPath)
	ext := filepath.Ext(base)
	stem := strings.TrimSuffix(base, ext)
	parent := filepath.Base(filepath.Dir(cleanPath))
	if parent == "." || parent == string(filepath.Separator) {
		parent = ""
	}
	parentParent := filepath.Base(filepath.Dir(filepath.Dir(cleanPath)))
	if parentParent == "." || parentParent == string(filepath.Separator) {
		parentParent = ""
	}
	vals["first_artist"] = first
	vals["first_artist_letter"] = letter
	vals["year"] = year
	vals["counter"] = strconv.Itoa(counter)
	vals["null"] = ""
	vals["lyrics"] = lyrics
	vals["filename"] = base
	vals["file_song_name"] = stem
	vals["parent_path"] = parent
	vals["parent_parent_path"] = parentParent
	vals["ext"] = strings.TrimPrefix(ext, ".")
	return vals
}

func Transform(value, find, replacement, chinese string, repair bool) string {
	loadRuleTables()
	if repair {
		if repaired, ok := repairLatin1UTF8(value); ok && repaired != value {
			value = repaired
		}
	}
	value = normalizeNFC(strings.TrimSpace(whitespaceRe.ReplaceAllString(value, " ")))
	if find != "" {
		value = strings.ReplaceAll(value, find, replacement)
	}
	switch chinese {
	case "simplified":
		value = t2sTable.convert(value)
	case "traditional":
		value = s2tTable.convert(value)
	}
	return value
}

func repairLatin1UTF8(s string) (string, bool) {
	if s == "" {
		return s, false
	}
	buf := make([]byte, 0, len(s))
	for _, r := range s {
		if r > 0xFF {
			return "", false
		}
		buf = append(buf, byte(r))
	}
	if !utf8.Valid(buf) {
		return "", false
	}
	return string(buf), true
}

func normalizeNFC(s string) string {
	if s == "" {
		return s
	}
	var out []rune
	for _, r := range s {
		if len(out) > 0 {
			if composed, ok := combiningComposeTable[[2]rune{out[len(out)-1], r}]; ok {
				out[len(out)-1] = composed
				continue
			}
		}
		out = append(out, r)
	}
	return string(out)
}

var combiningComposeTable = map[[2]rune]rune{
	{'A', '\u0300'}: 'À', {'A', '\u0301'}: 'Á', {'A', '\u0302'}: 'Â', {'A', '\u0303'}: 'Ã', {'A', '\u0308'}: 'Ä', {'A', '\u030A'}: 'Å',
	{'a', '\u0300'}: 'à', {'a', '\u0301'}: 'á', {'a', '\u0302'}: 'â', {'a', '\u0303'}: 'ã', {'a', '\u0308'}: 'ä', {'a', '\u030A'}: 'å',
	{'C', '\u0327'}: 'Ç', {'c', '\u0327'}: 'ç',
	{'E', '\u0300'}: 'È', {'E', '\u0301'}: 'É', {'E', '\u0302'}: 'Ê', {'E', '\u0308'}: 'Ë',
	{'e', '\u0300'}: 'è', {'e', '\u0301'}: 'é', {'e', '\u0302'}: 'ê', {'e', '\u0308'}: 'ë',
	{'I', '\u0300'}: 'Ì', {'I', '\u0301'}: 'Í', {'I', '\u0302'}: 'Î', {'I', '\u0308'}: 'Ï',
	{'i', '\u0300'}: 'ì', {'i', '\u0301'}: 'í', {'i', '\u0302'}: 'î', {'i', '\u0308'}: 'ï',
	{'N', '\u0303'}: 'Ñ', {'n', '\u0303'}: 'ñ',
	{'O', '\u0300'}: 'Ò', {'O', '\u0301'}: 'Ó', {'O', '\u0302'}: 'Ô', {'O', '\u0303'}: 'Õ', {'O', '\u0308'}: 'Ö',
	{'o', '\u0300'}: 'ò', {'o', '\u0301'}: 'ó', {'o', '\u0302'}: 'ô', {'o', '\u0303'}: 'õ', {'o', '\u0308'}: 'ö',
	{'U', '\u0300'}: 'Ù', {'U', '\u0301'}: 'Ú', {'U', '\u0302'}: 'Û', {'U', '\u0308'}: 'Ü',
	{'u', '\u0300'}: 'ù', {'u', '\u0301'}: 'ú', {'u', '\u0302'}: 'û', {'u', '\u0308'}: 'ü',
	{'Y', '\u0301'}: 'Ý', {'y', '\u0301'}: 'ý', {'y', '\u0308'}: 'ÿ',
}

func SafeSegment(value, fallback string) string {
	s := unsafeSegRe.ReplaceAllString(value, "_")
	s = strings.Trim(s, " .")
	if len(s) > 160 {
		s = truncateUTF8Bytes(s, 160)
		s = strings.TrimRight(s, " .")
	}
	if s == "" {
		s = fallback
	}
	firstDot := s
	if idx := strings.IndexByte(s, '.'); idx >= 0 {
		firstDot = s[:idx]
	}
	if winReservedRe.MatchString(firstDot) {
		s = "_" + s
	}
	return s
}

func truncateUTF8Bytes(s string, maxBytes int) string {
	if len(s) <= maxBytes {
		return s
	}
	n := maxBytes
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}

func ValidateTemplate(tmpl string, filenameOnly bool) error {
	if tmpl == "" || utf8.RuneCountInString(tmpl) > 1000 || strings.HasPrefix(tmpl, "/") || strings.HasPrefix(tmpl, "\\") {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "整理模板应为音乐库内的相对路径")
	}
	for _, seg := range strings.FieldsFunc(tmpl, func(r rune) bool { return r == '/' || r == '\\' }) {
		if seg == ".." {
			return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "整理模板应为音乐库内的相对路径")
		}
	}
	dummy := make(map[string]string, len(ruleVariables))
	for k := range ruleVariables {
		dummy[k] = "1"
	}
	rendered, err := Render(tmpl, dummy, nil)
	if err != nil {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "模板格式无效或包含不支持的变量")
	}
	if filenameOnly && strings.Contains(rendered, "/") {
		return model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件名模板不能包含目录分隔符")
	}
	return nil
}

// --- Extract ---

type extractPart struct {
	literal string
	key     string
	hasKey  bool
}

func Extract(template, stem string) (map[string]string, error) {
	replaced := strings.ReplaceAll(template, "${", "{")
	tokens, err := parseFormatTokens(replaced)
	if err != nil {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "提取模板格式无效")
	}
	var parts []extractPart
	seen := make(map[string]bool)
	for _, tok := range tokens {
		if tok.hasField {
			if tok.spec != "" || tok.conv != "" {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "提取模板应使用不重复的标签变量")
			}
			key := tok.field
			if canon, ok := ruleAliasMap[key]; ok {
				key = canon
			}
			if !tagFieldSet[key] || seen[key] {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "提取模板应使用不重复的标签变量")
			}
			if len(parts) > 0 && parts[len(parts)-1].hasKey && tok.literal == "" {
				return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "提取变量之间必须有固定分隔符")
			}
			seen[key] = true
			parts = append(parts, extractPart{literal: tok.literal, key: key, hasKey: true})
		} else {
			parts = append(parts, extractPart{literal: tok.literal})
		}
	}
	if len(seen) == 0 {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "提取模板至少包含一个标签变量")
	}

	pos := 0
	out := make(map[string]string, len(seen))
	for i, part := range parts {
		if !strings.HasPrefix(stem[pos:], part.literal) {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件名不符合提取模板")
		}
		pos += len(part.literal)
		if !part.hasKey {
			continue
		}
		sep := ""
		if i+1 < len(parts) {
			sep = parts[i+1].literal
		}
		end := len(stem)
		if sep != "" {
			if i+1 == len(parts)-1 && !parts[len(parts)-1].hasKey && strings.HasSuffix(stem, sep) {
				end = len(stem) - len(sep)
			} else {
				idx := strings.Index(stem[pos:], sep)
				if idx < 0 {
					return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件名不符合提取模板")
				}
				end = pos + idx
			}
		}
		if end <= pos {
			return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件名不符合提取模板")
		}
		out[part.key] = strings.TrimSpace(stem[pos:end])
		pos = end
	}
	if pos != len(stem) {
		return nil, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "文件名不符合提取模板")
	}
	return out, nil
}

// --- Render ---

type formatToken struct {
	literal  string
	field    string
	spec     string
	conv     string
	hasField bool
}

func parseFormatTokens(tmpl string) ([]formatToken, error) {
	var tokens []formatToken
	var lit strings.Builder
	for i := 0; i < len(tmpl); {
		ch := tmpl[i]
		if ch == '{' {
			if i+1 < len(tmpl) && tmpl[i+1] == '{' {
				lit.WriteByte('{')
				i += 2
				continue
			}
			end := strings.IndexByte(tmpl[i+1:], '}')
			if end < 0 {
				return nil, fmt.Errorf("unclosed brace")
			}
			inner := tmpl[i+1 : i+1+end]
			if strings.ContainsRune(inner, '{') {
				return nil, fmt.Errorf("nested brace")
			}
			field := inner
			conv := ""
			spec := ""
			if idx := strings.IndexByte(field, ':'); idx >= 0 {
				spec = field[idx+1:]
				field = field[:idx]
			}
			if idx := strings.IndexByte(field, '!'); idx >= 0 {
				conv = field[idx+1:]
				field = field[:idx]
			}
			tokens = append(tokens, formatToken{
				literal:  lit.String(),
				field:    field,
				spec:     spec,
				conv:     conv,
				hasField: true,
			})
			lit.Reset()
			i += end + 2
			continue
		}
		if ch == '}' {
			if i+1 < len(tmpl) && tmpl[i+1] == '}' {
				lit.WriteByte('}')
				i += 2
				continue
			}
			return nil, fmt.Errorf("single closing brace")
		}
		lit.WriteByte(ch)
		i++
	}
	if lit.Len() > 0 || len(tokens) == 0 {
		tokens = append(tokens, formatToken{literal: lit.String()})
	}
	return tokens, nil
}

func Render(template string, values map[string]string, formatValue func(string) string) (string, error) {
	if formatValue == nil {
		formatValue = func(s string) string { return s }
	}
	expressions := make(map[string]string)
	var subErr error
	replaced := exprSubRe.ReplaceAllStringFunc(template, func(m string) string {
		if subErr != nil {
			return m
		}
		inner := m[2 : len(m)-1]
		val, err := Expression(inner, values)
		if err != nil {
			subErr = err
			return m
		}
		key := "__expression_" + strconv.Itoa(len(expressions))
		expressions[key] = val
		return "{" + key + "}"
	})
	if subErr != nil {
		return "", subErr
	}
	if strings.Contains(replaced, "${") {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式括号未闭合或包含嵌套括号")
	}
	tokens, err := parseFormatTokens(replaced)
	if err != nil {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "模板格式无效或包含不支持的变量")
	}
	var out strings.Builder
	for _, tok := range tokens {
		out.WriteString(tok.literal)
		if tok.hasField {
			if tok.spec != "" || tok.conv != "" {
				return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "模板包含不支持的变量")
			}
			if val, ok := expressions[tok.field]; ok {
				out.WriteString(formatValue(val))
			} else if ruleVariables[tok.field] {
				out.WriteString(formatValue(values[tok.field]))
			} else {
				return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "模板包含不支持的变量")
			}
		}
	}
	res := out.String()
	if utf8.RuneCountInString(res) > 10000 {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "模板结果过长")
	}
	return res, nil
}

// --- Bounded AST Expression Parser & Evaluator ---

type exprTokKind int

const (
	etEOF exprTokKind = iota
	etIdent
	etStr
	etInt
	etPlus
	etMinus
	etLParen
	etRParen
	etLBracket
	etRBracket
	etDot
	etComma
	etEq
)

type exprTok struct {
	kind exprTokKind
	val  string
	num  int
}

func tokenizeExpr(s string) ([]exprTok, error) {
	var toks []exprTok
	for i := 0; i < len(s); {
		r, w := utf8.DecodeRuneInString(s[i:])
		if unicode.IsSpace(r) {
			i += w
			continue
		}
		switch s[i] {
		case '+':
			toks = append(toks, exprTok{kind: etPlus})
			i++
		case '-':
			toks = append(toks, exprTok{kind: etMinus})
			i++
		case '(':
			toks = append(toks, exprTok{kind: etLParen})
			i++
		case ')':
			toks = append(toks, exprTok{kind: etRParen})
			i++
		case '[':
			toks = append(toks, exprTok{kind: etLBracket})
			i++
		case ']':
			toks = append(toks, exprTok{kind: etRBracket})
			i++
		case '.':
			toks = append(toks, exprTok{kind: etDot})
			i++
		case ',':
			toks = append(toks, exprTok{kind: etComma})
			i++
		case '=':
			toks = append(toks, exprTok{kind: etEq})
			i++
		case '\'', '"':
			quote := s[i]
			i++
			var sb strings.Builder
			closed := false
			for i < len(s) {
				c := s[i]
				if c == '\\' {
					i++
					if i >= len(s) {
						return nil, fmt.Errorf("unterminated escape")
					}
					switch s[i] {
					case 'n':
						sb.WriteByte('\n')
					case 'r':
						sb.WriteByte('\r')
					case 't':
						sb.WriteByte('\t')
					case '\\', '\'', '"':
						sb.WriteByte(s[i])
					default:
						sb.WriteByte('\\')
						sb.WriteByte(s[i])
					}
					i++
					continue
				}
				if c == quote {
					i++
					closed = true
					break
				}
				sb.WriteByte(c)
				i++
			}
			if !closed {
				return nil, fmt.Errorf("unterminated string")
			}
			toks = append(toks, exprTok{kind: etStr, val: sb.String()})
		default:
			if s[i] >= '0' && s[i] <= '9' {
				start := i
				for i < len(s) && s[i] >= '0' && s[i] <= '9' {
					i++
				}
				n, err := strconv.Atoi(s[start:i])
				if err != nil {
					return nil, fmt.Errorf("invalid int")
				}
				toks = append(toks, exprTok{kind: etInt, num: n})
			} else if s[i] == '_' || unicode.IsLetter(r) {
				start := i
				for i < len(s) {
					r2, w2 := utf8.DecodeRuneInString(s[i:])
					if s[i] == '_' || unicode.IsLetter(r2) || unicode.IsDigit(r2) {
						i += w2
					} else {
						break
					}
				}
				toks = append(toks, exprTok{kind: etIdent, val: s[start:i]})
			} else {
				return nil, fmt.Errorf("unexpected token")
			}
		}
	}
	toks = append(toks, exprTok{kind: etEOF})
	return toks, nil
}

type astNode interface {
	nodes() int
}

type astName struct{ id string }
type astConst struct{ val exprVal }
type astAdd struct{ left, right astNode }
type astBoolOp struct {
	isOr   bool
	values []astNode
}
type astIfExp struct{ test, body, orelse astNode }
type astNot struct{ operand astNode }
type astUSub struct{ operand astNode }
type astSubscript struct{ value, slice astNode }
type astCallName struct {
	fn   string
	args []astNode
}
type astCallMethod struct {
	recv   astNode
	method string
	args   []astNode
}

func (astName) nodes() int  { return 1 }
func (astConst) nodes() int { return 1 }
func (n astAdd) nodes() int { return 1 + n.left.nodes() + n.right.nodes() }
func (n astBoolOp) nodes() int {
	c := 1
	for _, v := range n.values {
		c += v.nodes()
	}
	return c
}
func (n astIfExp) nodes() int     { return 1 + n.test.nodes() + n.body.nodes() + n.orelse.nodes() }
func (n astNot) nodes() int       { return 1 + n.operand.nodes() }
func (n astUSub) nodes() int      { return 1 + n.operand.nodes() }
func (n astSubscript) nodes() int { return 1 + n.value.nodes() + n.slice.nodes() }
func (n astCallName) nodes() int {
	c := 2
	for _, a := range n.args {
		c += a.nodes()
	}
	return c
}
func (n astCallMethod) nodes() int {
	c := 2 + n.recv.nodes()
	for _, a := range n.args {
		c += a.nodes()
	}
	return c
}

type exprParser struct {
	toks []exprTok
	pos  int
}

func (p *exprParser) peek() exprTok { return p.toks[p.pos] }
func (p *exprParser) next() exprTok {
	t := p.toks[p.pos]
	if p.pos < len(p.toks)-1 {
		p.pos++
	}
	return t
}

func (p *exprParser) parseExpr() (astNode, error) {
	body, err := p.parseOr()
	if err != nil {
		return nil, err
	}
	if p.peek().kind == etIdent && p.peek().val == "if" {
		p.next()
		test, err := p.parseOr()
		if err != nil {
			return nil, err
		}
		if p.peek().kind != etIdent || p.peek().val != "else" {
			return nil, fmt.Errorf("expected else")
		}
		p.next()
		orelse, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		return astIfExp{test: test, body: body, orelse: orelse}, nil
	}
	return body, nil
}

func (p *exprParser) parseOr() (astNode, error) {
	first, err := p.parseAnd()
	if err != nil {
		return nil, err
	}
	vals := []astNode{first}
	for p.peek().kind == etIdent && p.peek().val == "or" {
		p.next()
		nxt, err := p.parseAnd()
		if err != nil {
			return nil, err
		}
		vals = append(vals, nxt)
	}
	if len(vals) == 1 {
		return first, nil
	}
	return astBoolOp{isOr: true, values: vals}, nil
}

func (p *exprParser) parseAnd() (astNode, error) {
	first, err := p.parseNot()
	if err != nil {
		return nil, err
	}
	vals := []astNode{first}
	for p.peek().kind == etIdent && p.peek().val == "and" {
		p.next()
		nxt, err := p.parseNot()
		if err != nil {
			return nil, err
		}
		vals = append(vals, nxt)
	}
	if len(vals) == 1 {
		return first, nil
	}
	return astBoolOp{isOr: false, values: vals}, nil
}

func (p *exprParser) parseNot() (astNode, error) {
	if p.peek().kind == etIdent && p.peek().val == "not" {
		p.next()
		op, err := p.parseNot()
		if err != nil {
			return nil, err
		}
		return astNot{operand: op}, nil
	}
	return p.parseAdd()
}

func (p *exprParser) parseAdd() (astNode, error) {
	left, err := p.parseUnary()
	if err != nil {
		return nil, err
	}
	for p.peek().kind == etPlus {
		p.next()
		right, err := p.parseUnary()
		if err != nil {
			return nil, err
		}
		left = astAdd{left: left, right: right}
	}
	return left, nil
}

func (p *exprParser) parseUnary() (astNode, error) {
	if p.peek().kind == etMinus {
		p.next()
		tok := p.peek()
		if tok.kind != etInt && !(tok.kind == etIdent && (tok.val == "True" || tok.val == "False")) {
			return nil, fmt.Errorf("unsupported unary minus")
		}
		atom, err := p.parseAtom()
		if err != nil {
			return nil, err
		}
		return astUSub{operand: atom}, nil
	}
	return p.parsePostfix()
}

func (p *exprParser) parsePostfix() (astNode, error) {
	node, err := p.parseAtom()
	if err != nil {
		return nil, err
	}
	for {
		switch p.peek().kind {
		case etLBracket:
			p.next()
			idx, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			if p.peek().kind != etRBracket {
				return nil, fmt.Errorf("expected ]")
			}
			p.next()
			node = astSubscript{value: node, slice: idx}
		case etDot:
			p.next()
			id := p.next()
			if id.kind != etIdent {
				return nil, fmt.Errorf("expected method name")
			}
			if p.peek().kind != etLParen {
				return nil, fmt.Errorf("attribute access without call not supported")
			}
			args, err := p.parseCallArgs()
			if err != nil {
				return nil, err
			}
			node = astCallMethod{recv: node, method: id.val, args: args}
		default:
			return node, nil
		}
	}
}

func (p *exprParser) parseCallArgs() ([]astNode, error) {
	if p.next().kind != etLParen {
		return nil, fmt.Errorf("expected (")
	}
	var args []astNode
	for p.peek().kind != etRParen {
		if len(args) > 0 {
			if p.peek().kind != etComma {
				return nil, fmt.Errorf("expected ,")
			}
			p.next()
			if p.peek().kind == etRParen {
				break
			}
		}
		if p.pos+1 < len(p.toks) && p.toks[p.pos].kind == etIdent && p.toks[p.pos+1].kind == etEq {
			return nil, fmt.Errorf("keyword arguments not supported")
		}
		arg, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		args = append(args, arg)
	}
	if p.next().kind != etRParen {
		return nil, fmt.Errorf("expected )")
	}
	return args, nil
}

func (p *exprParser) parseAtom() (astNode, error) {
	t := p.next()
	switch t.kind {
	case etStr:
		return astConst{val: exprVal{kind: valStr, s: t.val}}, nil
	case etInt:
		return astConst{val: exprVal{kind: valInt, i: t.num}}, nil
	case etLParen:
		inner, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		if p.next().kind != etRParen {
			return nil, fmt.Errorf("expected )")
		}
		return inner, nil
	case etIdent:
		if t.val == "True" {
			return astConst{val: exprVal{kind: valBool, b: true}}, nil
		}
		if t.val == "False" {
			return astConst{val: exprVal{kind: valBool, b: false}}, nil
		}
		if p.peek().kind == etLParen {
			args, err := p.parseCallArgs()
			if err != nil {
				return nil, err
			}
			return astCallName{fn: t.val, args: args}, nil
		}
		return astName{id: t.val}, nil
	default:
		return nil, fmt.Errorf("syntax error")
	}
}

type valKind int

const (
	valStr valKind = iota
	valInt
	valBool
	valList
)

type exprVal struct {
	kind valKind
	s    string
	i    int
	b    bool
	list []string
}

func (v exprVal) truthy() bool {
	switch v.kind {
	case valStr:
		return v.s != ""
	case valInt:
		return v.i != 0
	case valBool:
		return v.b
	case valList:
		return len(v.list) > 0
	}
	return false
}

func (v exprVal) toString() (string, error) {
	switch v.kind {
	case valStr:
		return v.s, nil
	case valInt:
		return strconv.Itoa(v.i), nil
	case valBool:
		if v.b {
			return "True", nil
		}
		return "False", nil
	case valList:
		quoted := make([]string, len(v.list))
		for idx, item := range v.list {
			quoted[idx] = "'" + item + "'"
		}
		return "[" + strings.Join(quoted, ", ") + "]", nil
	}
	return "", fmt.Errorf("invalid value")
}

func Expression(text string, values map[string]string) (string, error) {
	if utf8.RuneCountInString(text) > 1000 {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式过长")
	}
	toks, err := tokenizeExpr(text)
	if err != nil {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式格式错误")
	}
	p := &exprParser{toks: toks}
	root, err := p.parseExpr()
	if err != nil || p.peek().kind != etEOF {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式格式错误")
	}
	if root.nodes()+1 > 100 {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式过于复杂")
	}
	res, err := evalAST(root, values)
	if err != nil {
		return "", err
	}
	out, err := res.toString()
	if err != nil {
		return "", model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
	}
	return out, nil
}

func evalAST(node astNode, values map[string]string) (exprVal, error) {
	switch n := node.(type) {
	case astName:
		if !ruleVariables[n.id] {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的表达式；可使用标签变量、条件、拼接和常用字符串方法")
		}
		v := values[n.id]
		if utf8.RuneCountInString(v) > 10000 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量内容超过 10000 字符")
		}
		return exprVal{kind: valStr, s: v}, nil
	case astConst:
		if n.val.kind == valInt && (n.val.i > 1000 || n.val.i < -1000) {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "表达式数字应在 -1000 到 1000 内")
		}
		return n.val, nil
	case astUSub:
		v, err := evalAST(n.operand, values)
		if err != nil {
			return exprVal{}, err
		}
		switch v.kind {
		case valInt:
			return exprVal{kind: valInt, i: -v.i}, nil
		case valBool:
			if v.b {
				return exprVal{kind: valInt, i: -1}, nil
			}
			return exprVal{kind: valInt, i: 0}, nil
		default:
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
		}
	case astNot:
		v, err := evalAST(n.operand, values)
		if err != nil {
			return exprVal{}, err
		}
		return exprVal{kind: valBool, b: !v.truthy()}, nil
	case astBoolOp:
		var res exprVal
		for _, child := range n.values {
			v, err := evalAST(child, values)
			if err != nil {
				return exprVal{}, err
			}
			res = v
			if (n.isOr && res.truthy()) || (!n.isOr && !res.truthy()) {
				break
			}
		}
		return res, nil
	case astIfExp:
		t, err := evalAST(n.test, values)
		if err != nil {
			return exprVal{}, err
		}
		if t.truthy() {
			return evalAST(n.body, values)
		}
		return evalAST(n.orelse, values)
	case astAdd:
		l, err := evalAST(n.left, values)
		if err != nil {
			return exprVal{}, err
		}
		r, err := evalAST(n.right, values)
		if err != nil {
			return exprVal{}, err
		}
		if l.kind == valStr && r.kind == valStr {
			return exprVal{kind: valStr, s: l.s + r.s}, nil
		}
		if (l.kind == valInt || l.kind == valBool) && (r.kind == valInt || r.kind == valBool) {
			li, ri := l.i, r.i
			if l.kind == valBool && l.b {
				li = 1
			}
			if r.kind == valBool && r.b {
				ri = 1
			}
			return exprVal{kind: valInt, i: li + ri}, nil
		}
		return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
	case astSubscript:
		v, err := evalAST(n.value, values)
		if err != nil {
			return exprVal{}, err
		}
		idxVal, err := evalAST(n.slice, values)
		if err != nil {
			return exprVal{}, err
		}
		if idxVal.kind != valInt {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的表达式；可使用标签变量、条件、拼接和常用字符串方法")
		}
		idx := idxVal.i
		if v.kind == valStr {
			runes := []rune(v.s)
			if idx < 0 {
				idx += len(runes)
			}
			if idx >= 0 && idx < len(runes) {
				return exprVal{kind: valStr, s: string(runes[idx])}, nil
			}
			return exprVal{kind: valStr, s: ""}, nil
		}
		if v.kind == valList {
			if idx < 0 {
				idx += len(v.list)
			}
			if idx >= 0 && idx < len(v.list) {
				return exprVal{kind: valStr, s: v.list[idx]}, nil
			}
			return exprVal{kind: valStr, s: ""}, nil
		}
		return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的表达式；可使用标签变量、条件、拼接和常用字符串方法")
	case astCallName:
		if (n.fn != "str" && n.fn != "int") || len(n.args) != 1 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的表达式；可使用标签变量、条件、拼接和常用字符串方法")
		}
		a, err := evalAST(n.args[0], values)
		if err != nil {
			return exprVal{}, err
		}
		if n.fn == "str" {
			s, err := a.toString()
			if err != nil {
				return exprVal{}, err
			}
			return exprVal{kind: valStr, s: s}, nil
		}
		switch a.kind {
		case valInt:
			return a, nil
		case valBool:
			if a.b {
				return exprVal{kind: valInt, i: 1}, nil
			}
			return exprVal{kind: valInt, i: 0}, nil
		case valStr:
			iv, convErr := strconv.Atoi(strings.TrimSpace(a.s))
			if convErr != nil {
				return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
			}
			return exprVal{kind: valInt, i: iv}, nil
		default:
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
		}
	case astCallMethod:
		if !ruleAllowedMethods[n.method] || len(n.args) > 3 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的表达式；可使用标签变量、条件、拼接和常用字符串方法")
		}
		recv, err := evalAST(n.recv, values)
		if err != nil {
			return exprVal{}, err
		}
		if recv.kind != valStr {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的表达式；可使用标签变量、条件、拼接和常用字符串方法")
		}
		args := make([]exprVal, 0, len(n.args))
		for _, a := range n.args {
			ev, err := evalAST(a, values)
			if err != nil {
				return exprVal{}, err
			}
			args = append(args, ev)
		}
		return evalStringMethod(recv.s, n.method, args)
	}
	return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的表达式；可使用标签变量、条件、拼接和常用字符串方法")
}

func evalStringMethod(s, method string, args []exprVal) (exprVal, error) {
	switch method {
	case "strip", "lstrip", "rstrip":
		if len(args) > 1 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
		}
		var out string
		if len(args) == 0 {
			switch method {
			case "strip":
				out = strings.TrimSpace(s)
			case "lstrip":
				out = strings.TrimLeftFunc(s, unicode.IsSpace)
			case "rstrip":
				out = strings.TrimRightFunc(s, unicode.IsSpace)
			}
		} else {
			if args[0].kind != valStr {
				return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
			}
			switch method {
			case "strip":
				out = strings.Trim(s, args[0].s)
			case "lstrip":
				out = strings.TrimLeft(s, args[0].s)
			case "rstrip":
				out = strings.TrimRight(s, args[0].s)
			}
		}
		return exprVal{kind: valStr, s: out}, nil
	case "lower", "upper", "title":
		if len(args) != 0 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
		}
		var out string
		switch method {
		case "lower":
			out = strings.ToLower(s)
		case "upper":
			out = strings.ToUpper(s)
		case "title":
			out = pythonTitle(s)
		}
		return exprVal{kind: valStr, s: out}, nil
	case "zfill":
		if len(args) != 1 || args[0].kind != valInt {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
		}
		width := args[0].i
		if width > 10000 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量结果过长")
		}
		rc := utf8.RuneCountInString(s)
		if rc >= width || width <= 0 {
			return exprVal{kind: valStr, s: s}, nil
		}
		pad := strings.Repeat("0", width-rc)
		if strings.HasPrefix(s, "+") || strings.HasPrefix(s, "-") {
			return exprVal{kind: valStr, s: s[:1] + pad + s[1:]}, nil
		}
		return exprVal{kind: valStr, s: pad + s}, nil
	case "replace":
		if len(args) < 2 || len(args) > 3 || args[0].kind != valStr || args[1].kind != valStr {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
		}
		oldS, newS := args[0].s, args[1].s
		count := -1
		if len(args) == 3 {
			if args[2].kind != valInt {
				return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
			}
			count = args[2].i
		}
		occ := utf8.RuneCountInString(s) + 1
		if oldS != "" {
			occ = strings.Count(s, oldS)
		}
		if count >= 0 && count < occ {
			occ = count
		}
		if utf8.RuneCountInString(s)+occ*(utf8.RuneCountInString(newS)-utf8.RuneCountInString(oldS)) > 10000 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量结果过长")
		}
		out := strings.Replace(s, oldS, newS, count)
		if utf8.RuneCountInString(out) > 10000 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量结果过长")
		}
		return exprVal{kind: valStr, s: out}, nil
	case "split":
		if len(args) > 2 {
			return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
		}
		var out []string
		if len(args) == 0 {
			out = strings.Fields(s)
		} else {
			if args[0].kind != valStr || args[0].s == "" {
				return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
			}
			maxSplit := -1
			if len(args) == 2 {
				if args[1].kind != valInt {
					return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "变量表达式的类型或参数无效")
				}
				maxSplit = args[1].i
			}
			if maxSplit < 0 {
				out = strings.Split(s, args[0].s)
			} else {
				out = strings.SplitN(s, args[0].s, maxSplit+1)
			}
		}
		return exprVal{kind: valList, list: out}, nil
	}
	return exprVal{}, model.NewError(model.ErrInvalidInput, http.StatusBadRequest, "不支持的表达式")
}

func pythonTitle(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	prevCased := false
	for _, r := range s {
		if unicode.IsLetter(r) {
			if !prevCased {
				b.WriteRune(unicode.ToTitle(r))
			} else {
				b.WriteRune(unicode.ToLower(r))
			}
			prevCased = true
		} else {
			b.WriteRune(r)
			prevCased = false
		}
	}
	return b.String()
}

// --- Target Path Resolution & Reservation ---

func BuildTargetPath(libraryRoot, sourcePath string, tags map[string]string, folderTemplate, filenameTemplate string, counter int, reserved map[string]struct{}, sidecars []string) (string, error) {
	vals := Variables(sourcePath, tags, counter, "")
	if vals["title"] == "" {
		stem := strings.TrimSuffix(filepath.Base(sourcePath), filepath.Ext(sourcePath))
		vals["title"] = SafeSegment(stem, "未知")
	}
	if vals["artist"] == "" {
		vals["artist"] = "未知歌手"
	}
	if vals["album_artist"] == "" {
		vals["album_artist"] = vals["artist"]
	}
	if vals["album"] == "" {
		vals["album"] = "未知专辑"
	}
	vals["ext"] = strings.TrimPrefix(filepath.Ext(sourcePath), ".")
	if m := leadingDigitRe.FindString(vals["track_number"]); m != "" {
		if n, err := strconv.Atoi(m); err == nil {
			vals["track_number"] = fmt.Sprintf("%02d", n)
		}
	} else {
		vals["track_number"] = ""
	}

	rawFolder, err := Render(folderTemplate, vals, func(v string) string { return SafeSegment(v, "") })
	if err != nil {
		return "", err
	}
	rawFolder = strings.ReplaceAll(rawFolder, "\\", "/")
	folderSegs := strings.Split(rawFolder, "/")
	parts := make([]string, 0, len(folderSegs)+2)
	parts = append(parts, libraryRoot)
	for _, seg := range folderSegs {
		parts = append(parts, SafeSegment(seg, "未知"))
	}

	rawName, err := Render(filenameTemplate, vals, func(v string) string { return SafeSegment(v, "") })
	if err != nil {
		return "", err
	}
	rawName = strings.Trim(rawName, " -_.")
	rawName = whitespaceRe.ReplaceAllString(rawName, " ")
	ext := strings.ToLower(filepath.Ext(sourcePath))
	dest := filepath.Join(append(parts, SafeSegment(rawName, vals["title"])+ext)...)

	absLib, err := filepath.Abs(libraryRoot)
	if err != nil {
		return "", err
	}
	absDest, err := filepath.Abs(dest)
	if err != nil {
		return "", err
	}
	rel, err := filepath.Rel(absLib, absDest)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", model.NewError(model.ErrForbidden, http.StatusForbidden, "整理路径超出音乐库")
	}
	return ReserveTargetPath(sourcePath, absDest, reserved, sidecars, "both", "both"), nil
}

func ReserveTargetPath(sourcePath, targetPath string, reserved map[string]struct{}, sidecars []string, lyricsMode, coverMode string) string {
	suffixSet := map[string]struct{}{
		strings.ToLower(filepath.Ext(sourcePath)): {},
	}
	for _, sc := range sidecars {
		suffixSet[strings.ToLower(filepath.Ext(sc))] = struct{}{}
	}
	if lyricsMode == "both" || lyricsMode == "lrc" {
		suffixSet[".lrc"] = struct{}{}
	}
	if coverMode == "both" || coverMode == "file" {
		suffixSet[".jpg"] = struct{}{}
		suffixSet[".png"] = struct{}{}
	}
	suffixes := make([]string, 0, len(suffixSet))
	for s := range suffixSet {
		suffixes = append(suffixes, s)
	}
	sort.Strings(suffixes)

	allowed := map[string]struct{}{filepath.Clean(sourcePath): {}}
	allowedLower := map[string]struct{}{strings.ToLower(filepath.Clean(sourcePath)): {}}
	for _, sc := range sidecars {
		cleanedSC := filepath.Clean(sc)
		allowed[cleanedSC] = struct{}{}
		allowedLower[strings.ToLower(cleanedSC)] = struct{}{}
	}
	isAllowedSelf := func(p string) bool {
		if _, ok := allowed[p]; ok {
			return true
		}
		if runtime.GOOS == "windows" {
			_, ok := allowedLower[strings.ToLower(p)]
			return ok
		}
		return false
	}

	dir := filepath.Dir(targetPath)
	ext := filepath.Ext(targetPath)
	stem := strings.TrimSuffix(filepath.Base(targetPath), ext)
	candidate := targetPath
	for count := 0; ; count++ {
		if count > 0 {
			candidate = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", stem, count, ext))
		}
		candStem := strings.TrimSuffix(candidate, filepath.Ext(candidate))
		conflict := false
		for _, sfx := range suffixes {
			p := filepath.Clean(candStem + sfx)
			if reserved != nil {
				if _, ok := reserved[p]; ok {
					conflict = true
					break
				}
			}
			if !isAllowedSelf(p) {
				if _, err := os.Lstat(p); err == nil {
					conflict = true
					break
				}
			}
		}
		if !conflict {
			if reserved != nil {
				for _, sfx := range suffixes {
					reserved[filepath.Clean(candStem+sfx)] = struct{}{}
				}
			}
			return candidate
		}
	}
}
