package builtin

import (
	"html"
	"net/url"
	"regexp"
	"strings"
)

type domNode struct {
	Tag      string
	Attrs    map[string]string
	Children []*domNode
	TextBuf  strings.Builder
	Parent   *domNode
}

func (n *domNode) Attr(name string) string {
	if n == nil || n.Attrs == nil {
		return ""
	}
	return n.Attrs[strings.ToLower(name)]
}

func (n *domNode) HasClass(cls string) bool {
	for _, c := range strings.Fields(n.Attr("class")) {
		if c == cls {
			return true
		}
	}
	return false
}

func (n *domNode) Text() string {
	if n == nil {
		return ""
	}
	var b strings.Builder
	var walk func(*domNode)
	walk = func(cur *domNode) {
		if cur.TextBuf.Len() > 0 {
			b.WriteString(cur.TextBuf.String())
		}
		for _, ch := range cur.Children {
			walk(ch)
		}
	}
	walk(n)
	return strings.TrimSpace(html.UnescapeString(b.String()))
}

func (n *domNode) FindAll(pred func(*domNode) bool) []*domNode {
	var out []*domNode
	var walk func(*domNode)
	walk = func(cur *domNode) {
		for _, ch := range cur.Children {
			if pred(ch) {
				out = append(out, ch)
			}
			walk(ch)
		}
	}
	if n != nil {
		walk(n)
	}
	return out
}

func (n *domNode) Find(pred func(*domNode) bool) *domNode {
	var found *domNode
	var walk func(*domNode) bool
	walk = func(cur *domNode) bool {
		for _, ch := range cur.Children {
			if pred(ch) {
				found = ch
				return true
			}
			if walk(ch) {
				return true
			}
		}
		return false
	}
	if n != nil {
		walk(n)
	}
	return found
}

var voidTags = map[string]bool{
	"area": true, "base": true, "br": true, "col": true, "embed": true,
	"hr": true, "img": true, "input": true, "link": true, "meta": true,
	"param": true, "source": true, "track": true, "wbr": true,
}

var attrRe = regexp.MustCompile(`([a-zA-Z0-9_:\-]+)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>` + "`" + `]+)))?`)

func parseHTML(raw string) *domNode {
	root := &domNode{Tag: "document", Attrs: map[string]string{}}
	cur := root
	n := len(raw)
	i := 0
	for i < n {
		lt := strings.IndexByte(raw[i:], '<')
		if lt < 0 {
			cur.TextBuf.WriteString(raw[i:])
			break
		}
		if lt > 0 {
			cur.TextBuf.WriteString(raw[i : i+lt])
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
		gt := findTagEnd(raw, i+1)
		if gt < 0 {
			cur.TextBuf.WriteString(raw[i:])
			break
		}
		inside := strings.TrimSpace(raw[i+1 : gt])
		i = gt + 1
		if inside == "" {
			continue
		}
		if strings.HasPrefix(inside, "/") {
			closing := strings.ToLower(strings.TrimSpace(inside[1:]))
			for p := cur; p != nil && p != root; p = p.Parent {
				if p.Tag == closing {
					cur = p.Parent
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
		for _, m := range attrRe.FindAllStringSubmatch(attrPart, -1) {
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
		node := &domNode{Tag: tag, Attrs: attrs, Parent: cur}
		cur.Children = append(cur.Children, node)
		if tag == "script" || tag == "style" {
			endTag := "</" + tag
			lowerRest := strings.ToLower(raw[i:])
			idx := strings.Index(lowerRest, endTag)
			if idx >= 0 {
				node.TextBuf.WriteString(raw[i : i+idx])
				closeEnd := strings.IndexByte(raw[i+idx:], '>')
				if closeEnd >= 0 {
					i = i + idx + closeEnd + 1
				} else {
					i = n
				}
			}
			continue
		}
		if !selfClose && !voidTags[tag] {
			cur = node
		}
	}
	return root
}

func findTagEnd(s string, start int) int {
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

func resolveURL(baseRaw, refRaw string) string {
	refRaw = strings.TrimSpace(refRaw)
	if refRaw == "" {
		return ""
	}
	base, err := url.Parse(baseRaw)
	if err != nil {
		return refRaw
	}
	ref, err := url.Parse(refRaw)
	if err != nil {
		return refRaw
	}
	return base.ResolveReference(ref).String()
}
