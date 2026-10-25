// Package bhttp implements the known-length form of Binary HTTP messages (RFC 9292), which is the
// payload format of Oblivious HTTP (RFC 9458).
//
// Only what the crowd-traffic gateway needs is supported: known-length requests and responses
// (framing indicators 0 and 1), field sections, content, trailers, truncation of empty trailing
// sections and zero padding. Indeterminate-length messages (framing indicators 2 and 3) are
// rejected; informational (1xx) responses are skipped when decoding.
package bhttp

import (
	"errors"
	"fmt"
	"strings"
)

// Limits protect the decoder against oversized or hostile input.
const (
	MaxFieldLines   = 64
	MaxControlBytes = 1024
	MaxFieldBytes   = 8192
)

// ErrMalformed is returned (wrapped) for every decoding error.
var ErrMalformed = errors.New("bhttp: malformed message")

// Field is a header or trailer field line. Names are lower case on the wire.
type Field struct {
	Name, Value string
}

// Fields is an ordered list of field lines.
type Fields []Field

// Get returns the value of the first field with the given name (case-insensitive).
func (f Fields) Get(name string) string {
	name = strings.ToLower(name)
	for _, l := range f {
		if l.Name == name {
			return l.Value
		}
	}
	return ""
}

// Request is a known-length binary HTTP request.
type Request struct {
	Method    string
	Scheme    string
	Authority string
	Path      string
	Header    Fields
	Body      []byte
	Trailer   Fields
}

// Response is a known-length binary HTTP response.
type Response struct {
	Status  int
	Header  Fields
	Body    []byte
	Trailer Fields
}

// --- variable-length integers (RFC 9000, Section 16) ----------------------------------------

const maxVarint = 1<<62 - 1

func appendVarint(b []byte, v uint64) []byte {
	switch {
	case v < 1<<6:
		return append(b, byte(v))
	case v < 1<<14:
		return append(b, 0x40|byte(v>>8), byte(v))
	case v < 1<<30:
		return append(b, 0x80|byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
	default:
		if v > maxVarint {
			panic("bhttp: varint out of range")
		}
		return append(b, 0xc0|byte(v>>56), byte(v>>48), byte(v>>40), byte(v>>32),
			byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
	}
}

type reader struct {
	b   []byte
	off int
}

func (r *reader) done() bool { return r.off >= len(r.b) }

func (r *reader) varint() (uint64, error) {
	if r.done() {
		return 0, fmt.Errorf("%w: truncated varint", ErrMalformed)
	}
	n := 1 << (r.b[r.off] >> 6)
	if r.off+n > len(r.b) {
		return 0, fmt.Errorf("%w: truncated varint", ErrMalformed)
	}
	v := uint64(r.b[r.off] & 0x3f)
	for i := 1; i < n; i++ {
		v = v<<8 | uint64(r.b[r.off+i])
	}
	r.off += n
	return v, nil
}

func (r *reader) bytes(limit int) ([]byte, error) {
	l, err := r.varint()
	if err != nil {
		return nil, err
	}
	if l > uint64(limit) || l > uint64(len(r.b)-r.off) {
		return nil, fmt.Errorf("%w: length %d exceeds limit or input", ErrMalformed, l)
	}
	v := r.b[r.off : r.off+int(l)]
	r.off += int(l)
	return v, nil
}

// paddingOnly reports whether the rest of the input consists of zero bytes. RFC 9292 allows
// zero padding after a message and truncation of empty trailing sections; a decoder therefore
// treats a section that starts with padding as absent.
func (r *reader) paddingOnly() bool {
	for _, c := range r.b[r.off:] {
		if c != 0 {
			return false
		}
	}
	return true
}

// --- field sections -------------------------------------------------------------------------

func appendFields(b []byte, f Fields) []byte {
	var sec []byte
	for _, l := range f {
		name := strings.ToLower(l.Name)
		sec = appendVarint(sec, uint64(len(name)))
		sec = append(sec, name...)
		sec = appendVarint(sec, uint64(len(l.Value)))
		sec = append(sec, l.Value...)
	}
	b = appendVarint(b, uint64(len(sec)))
	return append(b, sec...)
}

func validName(n []byte) bool {
	if len(n) == 0 {
		return false
	}
	for _, c := range n {
		if c <= ' ' || c >= 0x7f || (c >= 'A' && c <= 'Z') || c == ':' {
			return false
		}
	}
	return true
}

func validValue(v []byte) bool {
	for _, c := range v {
		if c == '\r' || c == '\n' || c == 0 {
			return false
		}
	}
	return true
}

func (r *reader) fields() (Fields, error) {
	sec, err := r.bytes(MaxFieldBytes)
	if err != nil {
		return nil, err
	}
	sr := reader{b: sec}
	var res Fields
	for !sr.done() {
		if len(res) >= MaxFieldLines {
			return nil, fmt.Errorf("%w: too many field lines", ErrMalformed)
		}
		name, err := sr.bytes(MaxFieldBytes)
		if err != nil {
			return nil, err
		}
		value, err := sr.bytes(MaxFieldBytes)
		if err != nil {
			return nil, err
		}
		if !validName(name) || !validValue(value) {
			return nil, fmt.Errorf("%w: invalid field line", ErrMalformed)
		}
		res = append(res, Field{Name: string(name), Value: string(value)})
	}
	return res, nil
}

// --- requests -------------------------------------------------------------------------------

// Marshal encodes the request in known-length form. If padTo is larger than the encoded length,
// zero bytes are appended up to padTo bytes, so that all requests of a client have the same size.
func (q *Request) Marshal(padTo int) []byte {
	b := appendVarint(nil, 0)
	for _, s := range []string{q.Method, q.Scheme, q.Authority, q.Path} {
		b = appendVarint(b, uint64(len(s)))
		b = append(b, s...)
	}
	b = appendFields(b, q.Header)
	b = appendVarint(b, uint64(len(q.Body)))
	b = append(b, q.Body...)
	b = appendFields(b, q.Trailer)
	if padTo > len(b) {
		b = append(b, make([]byte, padTo-len(b))...)
	}
	return b
}

// UnmarshalRequest decodes a known-length request. maxBody limits the content length.
func UnmarshalRequest(data []byte, maxBody int) (*Request, error) {
	r := reader{b: data}
	fi, err := r.varint()
	if err != nil {
		return nil, err
	}
	if fi != 0 {
		return nil, fmt.Errorf("%w: framing indicator %d is not a known-length request", ErrMalformed, fi)
	}
	var ctrl [4]string
	for i := range ctrl {
		v, err := r.bytes(MaxControlBytes)
		if err != nil {
			return nil, err
		}
		ctrl[i] = string(v)
	}
	q := &Request{Method: ctrl[0], Scheme: ctrl[1], Authority: ctrl[2], Path: ctrl[3]}
	if q.Method == "" || !validName([]byte(strings.ToLower(q.Method))) {
		return nil, fmt.Errorf("%w: invalid method", ErrMalformed)
	}
	if r.paddingOnly() {
		return q, nil
	}
	if q.Header, err = r.fields(); err != nil {
		return nil, err
	}
	if r.paddingOnly() {
		return q, nil
	}
	body, err := r.bytes(maxBody)
	if err != nil {
		return nil, err
	}
	q.Body = body
	if r.paddingOnly() {
		return q, nil
	}
	if q.Trailer, err = r.fields(); err != nil {
		return nil, err
	}
	if !r.paddingOnly() {
		return nil, fmt.Errorf("%w: trailing data", ErrMalformed)
	}
	return q, nil
}

// --- responses ------------------------------------------------------------------------------

// Marshal encodes the response in known-length form.
func (s *Response) Marshal() []byte {
	b := appendVarint(nil, 1)
	b = appendVarint(b, uint64(s.Status))
	b = appendFields(b, s.Header)
	b = appendVarint(b, uint64(len(s.Body)))
	b = append(b, s.Body...)
	return appendFields(b, s.Trailer)
}

// UnmarshalResponse decodes a known-length response. Informational responses are skipped.
func UnmarshalResponse(data []byte, maxBody int) (*Response, error) {
	r := reader{b: data}
	fi, err := r.varint()
	if err != nil {
		return nil, err
	}
	if fi != 1 {
		return nil, fmt.Errorf("%w: framing indicator %d is not a known-length response", ErrMalformed, fi)
	}
	s := &Response{}
	for {
		st, err := r.varint()
		if err != nil {
			return nil, err
		}
		if st < 100 || st > 599 {
			return nil, fmt.Errorf("%w: status %d", ErrMalformed, st)
		}
		if st >= 200 {
			s.Status = int(st)
			break
		}
		if _, err := r.fields(); err != nil { // informational response header section
			return nil, err
		}
	}
	if r.paddingOnly() {
		return s, nil
	}
	if s.Header, err = r.fields(); err != nil {
		return nil, err
	}
	if r.paddingOnly() {
		return s, nil
	}
	if s.Body, err = r.bytes(maxBody); err != nil {
		return nil, err
	}
	if r.paddingOnly() {
		return s, nil
	}
	if s.Trailer, err = r.fields(); err != nil {
		return nil, err
	}
	if !r.paddingOnly() {
		return nil, fmt.Errorf("%w: trailing data", ErrMalformed)
	}
	return s, nil
}
