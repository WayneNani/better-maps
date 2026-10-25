package datex2

import (
	"encoding/xml"
	"strings"
)

// node is a namespace-agnostic XML element. DATEX II v2 puts everything into one namespace
// (http://datex2.eu/schema/2/2_0), v3 spreads the same element names over several namespaces
// (sit:, loc:, com:, ...). Working on local names lets one extraction code handle both.
type node struct {
	name     string
	attrs    map[string]string // local attribute name -> value
	text     string
	children []*node
}

// UnmarshalXML implements xml.Unmarshaler.
func (n *node) UnmarshalXML(d *xml.Decoder, start xml.StartElement) error {
	n.name = start.Name.Local
	for _, a := range start.Attr {
		if n.attrs == nil {
			n.attrs = make(map[string]string, len(start.Attr))
		}
		n.attrs[a.Name.Local] = a.Value
	}
	var text strings.Builder
	for {
		tok, err := d.Token()
		if err != nil {
			return err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			c := &node{}
			if err := c.UnmarshalXML(d, t); err != nil {
				return err
			}
			n.children = append(n.children, c)
		case xml.CharData:
			text.Write(t)
		case xml.EndElement:
			n.text = strings.TrimSpace(text.String())
			return nil
		}
	}
}

// attr returns an attribute value by local name.
func (n *node) attr(name string) string {
	if n == nil {
		return ""
	}
	return n.attrs[name]
}

// xsiType returns the xsi:type attribute without namespace prefix ("sit:Accident" -> "Accident").
func (n *node) xsiType() string { return stripPrefix(n.attr("type")) }

func stripPrefix(s string) string {
	if i := strings.LastIndexByte(s, ':'); i >= 0 {
		return s[i+1:]
	}
	return s
}

// child returns the first direct child with the given local name.
func (n *node) child(name string) *node {
	if n == nil {
		return nil
	}
	for _, c := range n.children {
		if c.name == name {
			return c
		}
	}
	return nil
}

// childrenNamed returns all direct children with the given local name.
func (n *node) childrenNamed(name string) []*node {
	if n == nil {
		return nil
	}
	var res []*node
	for _, c := range n.children {
		if c.name == name {
			res = append(res, c)
		}
	}
	return res
}

// path follows a chain of first children.
func (n *node) path(names ...string) *node {
	for _, name := range names {
		n = n.child(name)
	}
	return n
}

// textAt returns the text of the element at path, or "".
func (n *node) textAt(names ...string) string {
	if c := n.path(names...); c != nil {
		return c.text
	}
	return ""
}

// find returns the first descendant (depth first, document order) with the given local name.
// n itself is not considered.
func (n *node) find(name string) *node {
	if n == nil {
		return nil
	}
	for _, c := range n.children {
		if c.name == name {
			return c
		}
		if r := c.find(name); r != nil {
			return r
		}
	}
	return nil
}

// findAll returns all descendants with the given local name in document order. Matches are not
// searched for nested matches.
func (n *node) findAll(name string) []*node {
	if n == nil {
		return nil
	}
	var res []*node
	for _, c := range n.children {
		if c.name == name {
			res = append(res, c)
			continue
		}
		res = append(res, c.findAll(name)...)
	}
	return res
}

// has reports whether any descendant's local name starts with prefix.
func (n *node) has(prefix string) bool {
	if n == nil {
		return false
	}
	for _, c := range n.children {
		if strings.HasPrefix(c.name, prefix) || c.has(prefix) {
			return true
		}
	}
	return false
}

// multilingual returns the text of a DATEX II multilingual string (values/value), preferring
// German. Plain text content is returned as well.
func (n *node) multilingual() string {
	if n == nil {
		return ""
	}
	vals := n.findAll("value")
	if len(vals) == 0 {
		return n.text
	}
	for _, v := range vals {
		if l := strings.ToLower(v.attr("lang")); strings.HasPrefix(l, "de") && v.text != "" {
			return v.text
		}
	}
	for _, v := range vals {
		if v.text != "" {
			return v.text
		}
	}
	return ""
}
