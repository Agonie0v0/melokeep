package plugin

import (
	"html"
	"regexp"
	"strings"

	"github.com/dop251/goja"
)

var (
	htmlCommentRe = regexp.MustCompile(`(?s)<!--.*?-->`)
	htmlScriptRe  = regexp.MustCompile(`(?is)<script\b[^>]*>.*?</script>`)
	htmlStyleRe   = regexp.MustCompile(`(?is)<style\b[^>]*>.*?</style>`)
	htmlTagRe     = regexp.MustCompile(`<[^>]+>`)
	htmlAttrRe    = regexp.MustCompile(`([a-zA-Z0-9_:\-]+)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>` + "`" + `]+)))?`)
)

var htmlVoidTags = map[string]bool{
	"area": true, "base": true, "br": true, "col": true, "embed": true,
	"hr": true, "img": true, "input": true, "link": true, "meta": true,
	"param": true, "source": true, "track": true, "wbr": true,
}

func installHTMLBridge(vm *goja.Runtime, ctxObj *goja.Object) {
	htmlObj := vm.NewObject()

	_ = htmlObj.Set("stripTags", func(call goja.FunctionCall) goja.Value {
		return vm.ToValue(stripHTMLTags(argString(call, 0)))
	})

	_ = htmlObj.Set("unescape", func(call goja.FunctionCall) goja.Value {
		return vm.ToValue(html.UnescapeString(argString(call, 0)))
	})

	_ = htmlObj.Set("findText", func(call goja.FunctionCall) goja.Value {
		rawHTML := argString(call, 0)
		tagOrSel := argString(call, 1)
		className := argString(call, 2)
		return vm.ToValue(findHTMLText(rawHTML, tagOrSel, className))
	})

	_ = htmlObj.Set("findAttr", func(call goja.FunctionCall) goja.Value {
		rawHTML := argString(call, 0)
		tagOrSel := argString(call, 1)
		var className, attrName string
		if len(call.Arguments) >= 4 && !isNilOrUndefined(call.Arguments[3]) {
			className = argString(call, 2)
			attrName = argString(call, 3)
		} else {
			attrName = argString(call, 2)
		}
		return vm.ToValue(findHTMLAttr(rawHTML, tagOrSel, className, attrName))
	})

	_ = ctxObj.Set("html", htmlObj)
}

func stripHTMLTags(raw string) string {
	if raw == "" {
		return ""
	}
	cleaned := htmlCommentRe.ReplaceAllString(raw, "")
	cleaned = htmlScriptRe.ReplaceAllString(cleaned, "")
	cleaned = htmlStyleRe.ReplaceAllString(cleaned, "")
	cleaned = htmlTagRe.ReplaceAllString(cleaned, "")
	return strings.Join(strings.Fields(cleaned), " ")
}

func findHTMLText(rawHTML, tagOrSelector, className string) string {
	pred := buildSelectorPredicate(tagOrSelector, className)
	if pred == nil {
		return ""
	}
	doc := parseHTMLDocument(rawHTML)
	node := doc.findFirst(pred)
	if node == nil {
		return ""
	}
	includeRaw := strings.EqualFold(node.tag, "script") || strings.EqualFold(node.tag, "style")
	return node.text(includeRaw)
}

func findHTMLAttr(rawHTML, tagOrSelector, className, attrName string) string {
	attrName = strings.ToLower(strings.TrimSpace(attrName))
	if attrName == "" {
		return ""
	}
	pred := buildSelectorPredicate(tagOrSelector, className)
	if pred == nil {
		return ""
	}
	doc := parseHTMLDocument(rawHTML)
	node := doc.findFirst(pred)
	if node == nil {
		return ""
	}
	return node.attrs[attrName]
}

type htmlSelector struct {
	tag       string
	id        string
	classes   []string
	attrKey   string
	attrValue string
	hasAttr   bool
}

func buildSelectorPredicate(rawSelector, extraClass string) func(*htmlNode) bool {
	selStr := strings.TrimSpace(rawSelector)
	extraClasses := strings.Fields(strings.TrimSpace(extraClass))
	if selStr == "" && len(extraClasses) == 0 {
		return nil
	}

	tokens := strings.Fields(selStr)
	if len(tokens) == 0 {
		tokens = []string{""}
	}
	chain := make([]htmlSelector, 0, len(tokens))
	for i, tok := range tokens {
		var extra []string
		if i == len(tokens)-1 {
			extra = extraClasses
		}
		chain = append(chain, parseSingleSelector(tok, extra))
	}

	return func(n *htmlNode) bool {
		if n == nil || n.tag == "document" {
			return false
		}
		if !matchSingleSelector(n, chain[len(chain)-1]) {
			return false
		}
		cur := n.parent
		for i := len(chain) - 2; i >= 0; i-- {
			matched := false
			for cur != nil && cur.tag != "document" {
				if matchSingleSelector(cur, chain[i]) {
					matched = true
					cur = cur.parent
					break
				}
				cur = cur.parent
			}
			if !matched {
				return false
			}
		}
		return true
	}
}

func parseSingleSelector(selStr string, extraClasses []string) htmlSelector {
	var sel htmlSelector
	if idx := strings.Index(selStr, "["); idx >= 0 {
		if end := strings.Index(selStr[idx:], "]"); end > 1 {
			inside := strings.TrimSpace(selStr[idx+1 : idx+end])
			selStr = strings.TrimSpace(selStr[:idx])
			if eq := strings.IndexByte(inside, '='); eq >= 0 {
				sel.attrKey = strings.ToLower(strings.TrimSpace(inside[:eq]))
				val := strings.TrimSpace(inside[eq+1:])
				sel.attrValue = strings.Trim(val, `"'`)
				sel.hasAttr = sel.attrKey != ""
			} else if inside != "" {
				sel.attrKey = strings.ToLower(inside)
				sel.hasAttr = true
			}
		}
	}

	parts := strings.Split(selStr, ".")
	head := parts[0]
	for _, cls := range parts[1:] {
		if c := strings.TrimSpace(cls); c != "" {
			sel.classes = append(sel.classes, c)
		}
	}
	if hash := strings.IndexByte(head, '#'); hash >= 0 {
		sel.tag = strings.ToLower(strings.TrimSpace(head[:hash]))
		sel.id = strings.TrimSpace(head[hash+1:])
	} else {
		sel.tag = strings.ToLower(strings.TrimSpace(head))
	}
	sel.classes = append(sel.classes, extraClasses...)
	return sel
}

func matchSingleSelector(n *htmlNode, sel htmlSelector) bool {
	if n == nil || n.tag == "document" {
		return false
	}
	if sel.tag != "" && sel.tag != "*" && n.tag != sel.tag {
		return false
	}
	if sel.id != "" && n.attrs["id"] != sel.id {
		return false
	}
	for _, cls := range sel.classes {
		if !n.hasClass(cls) {
			return false
		}
	}
	if sel.hasAttr {
		val, exists := n.attrs[sel.attrKey]
		if !exists {
			return false
		}
		if sel.attrValue != "" && val != sel.attrValue {
			return false
		}
	}
	return true
}

type htmlNode struct {
	tag      string
	attrs    map[string]string
	children []*htmlNode
	textBuf  strings.Builder
	parent   *htmlNode
}

func (n *htmlNode) hasClass(cls string) bool {
	for _, c := range strings.Fields(n.attrs["class"]) {
		if c == cls {
			return true
		}
	}
	return false
}

func (n *htmlNode) text(includeRaw bool) string {
	var b strings.Builder
	var walk func(*htmlNode)
	walk = func(cur *htmlNode) {
		if cur.textBuf.Len() > 0 {
			b.WriteString(cur.textBuf.String())
		}
		for _, ch := range cur.children {
			if !includeRaw && (ch.tag == "script" || ch.tag == "style") {
				continue
			}
			walk(ch)
		}
	}
	walk(n)
	return strings.Join(strings.Fields(html.UnescapeString(b.String())), " ")
}

func (n *htmlNode) findFirst(pred func(*htmlNode) bool) *htmlNode {
	for _, ch := range n.children {
		if pred(ch) {
			return ch
		}
		if sub := ch.findFirst(pred); sub != nil {
			return sub
		}
	}
	return nil
}

func parseHTMLDocument(raw string) *htmlNode {
	root := &htmlNode{tag: "document", attrs: map[string]string{}}
	cur := root
	n := len(raw)
	i := 0
	for i < n {
		lt := strings.IndexByte(raw[i:], '<')
		if lt < 0 {
			cur.textBuf.WriteString(raw[i:])
			break
		}
		if lt > 0 {
			cur.textBuf.WriteString(raw[i : i+lt])
		}
		i += lt
		if strings.HasPrefix(raw[i:], "<!--") {
			end := strings.Index(raw[i+4:], "-->")
			if end < 0 {
				break
			}
			i += 4 + end + 3
			continue
		}
		if strings.HasPrefix(raw[i:], "<!") || strings.HasPrefix(raw[i:], "<?") {
			gt := strings.IndexByte(raw[i:], '>')
			if gt < 0 {
				break
			}
			i += gt + 1
			continue
		}
		gt := findHTMLTagEnd(raw, i+1)
		if gt < 0 {
			cur.textBuf.WriteString(raw[i:])
			break
		}
		inside := strings.TrimSpace(raw[i+1 : gt])
		i = gt + 1
		if inside == "" {
			continue
		}
		if strings.HasPrefix(inside, "/") {
			closing := strings.ToLower(strings.TrimSpace(inside[1:]))
			for p := cur; p != nil && p != root; p = p.parent {
				if p.tag == closing {
					cur = p.parent
					break
				}
			}
			continue
		}
		selfClose := false
		if strings.HasSuffix(inside, "/") {
			selfClose = true
			inside = strings.TrimSpace(inside[:len(inside)-1])
		}
		spaceIdx := strings.IndexAny(inside, " \t\r\n/")
		tag := inside
		attrPart := ""
		if spaceIdx >= 0 {
			tag = inside[:spaceIdx]
			attrPart = inside[spaceIdx:]
		}
		tag = strings.ToLower(strings.TrimSpace(tag))
		if tag == "" {
			continue
		}
		attrs := make(map[string]string)
		for _, m := range htmlAttrRe.FindAllStringSubmatch(attrPart, -1) {
			k := strings.ToLower(m[1])
			val := m[2]
			if val == "" {
				val = m[3]
			}
			if val == "" {
				val = m[4]
			}
			attrs[k] = html.UnescapeString(val)
		}
		node := &htmlNode{tag: tag, attrs: attrs, parent: cur}
		cur.children = append(cur.children, node)
		if tag == "script" || tag == "style" {
			endTag := "</" + tag
			lowerRest := strings.ToLower(raw[i:])
			idx := strings.Index(lowerRest, endTag)
			if idx >= 0 {
				node.textBuf.WriteString(raw[i : i+idx])
				closeEnd := strings.IndexByte(raw[i+idx:], '>')
				if closeEnd >= 0 {
					i = i + idx + closeEnd + 1
				} else {
					i = n
				}
			}
			continue
		}
		if !selfClose && !htmlVoidTags[tag] {
			cur = node
		}
	}
	return root
}

func findHTMLTagEnd(s string, start int) int {
	var quote byte
	for i := start; i < len(s); i++ {
		c := s[i]
		if quote != 0 {
			if c == quote {
				quote = 0
			}
			continue
		}
		if c == '"' || c == '\'' {
			quote = c
			continue
		}
		if c == '>' {
			return i
		}
	}
	return -1
}
