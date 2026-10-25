package bhttp

import (
	"bytes"
	"encoding/hex"
	"errors"
	"testing"
)

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// Messages from RFC 9458, Appendix A.
func TestRFC9458Vectors(t *testing.T) {
	q, err := UnmarshalRequest(mustHex(t, "00034745540568747470730b6578616d706c652e636f6d012f"), 1024)
	if err != nil {
		t.Fatal(err)
	}
	if q.Method != "GET" || q.Scheme != "https" || q.Authority != "example.com" || q.Path != "/" ||
		len(q.Header) != 0 || len(q.Body) != 0 {
		t.Fatalf("request = %+v", q)
	}
	s, err := UnmarshalResponse(mustHex(t, "0140c8"), 1024)
	if err != nil {
		t.Fatal(err)
	}
	if s.Status != 200 || len(s.Header) != 0 || len(s.Body) != 0 {
		t.Fatalf("response = %+v", s)
	}
	if got := (&Response{Status: 200}).Marshal(); !bytes.HasPrefix(got, mustHex(t, "0140c8")) {
		t.Fatalf("marshal 200 = %x", got)
	}
}

func TestRequestRoundTripWithPadding(t *testing.T) {
	q := &Request{
		Method: "POST", Scheme: "https", Authority: "crowd.example", Path: "/v1/reports",
		Header:  Fields{{"Content-Type", "application/json"}, {"date", "Sat, 25 Oct 2026 16:00:00 GMT"}},
		Body:    []byte(`{"version":1}`),
		Trailer: Fields{{"x-t", "1"}},
	}
	enc := q.Marshal(512)
	if len(enc) != 512 {
		t.Fatalf("len = %d, want 512", len(enc))
	}
	got, err := UnmarshalRequest(enc, 1024)
	if err != nil {
		t.Fatal(err)
	}
	if got.Method != q.Method || got.Path != q.Path || got.Authority != q.Authority ||
		string(got.Body) != string(q.Body) || got.Header.Get("Content-Type") != "application/json" ||
		got.Header.Get("DATE") != "Sat, 25 Oct 2026 16:00:00 GMT" || got.Trailer.Get("x-t") != "1" {
		t.Fatalf("round trip = %+v", got)
	}
	if got.Header.Get("missing") != "" {
		t.Fatal("unexpected header")
	}
	// No padding requested, or padding smaller than the message.
	if a, b := q.Marshal(0), q.Marshal(3); !bytes.Equal(a, b) {
		t.Fatal("padding below message length must not change the encoding")
	}
}

func TestTruncatedSections(t *testing.T) {
	// Header present, body and trailers truncated.
	q := (&Request{Method: "GET", Scheme: "https", Authority: "a", Path: "/", Header: Fields{{"a", "b"}}}).Marshal(0)
	for cut := len(q); cut >= len(q)-2; cut-- {
		got, err := UnmarshalRequest(q[:cut], 10)
		if err != nil {
			t.Fatalf("cut %d: %v", cut, err)
		}
		if got.Header.Get("a") != "b" {
			t.Fatalf("cut %d: header lost", cut)
		}
	}
	// Body but no trailers.
	q = (&Request{Method: "POST", Scheme: "https", Authority: "a", Path: "/", Body: []byte("xy")}).Marshal(0)
	got, err := UnmarshalRequest(q[:len(q)-1], 10)
	if err != nil || string(got.Body) != "xy" {
		t.Fatalf("got %+v, %v", got, err)
	}
}

func TestResponseRoundTrip(t *testing.T) {
	s := &Response{Status: 422, Header: Fields{{"content-type", "application/problem+json"}}, Body: []byte(`{}`),
		Trailer: Fields{{"x", "y"}}}
	got, err := UnmarshalResponse(s.Marshal(), 100)
	if err != nil {
		t.Fatal(err)
	}
	if got.Status != 422 || string(got.Body) != "{}" || got.Header.Get("content-type") != "application/problem+json" ||
		got.Trailer.Get("x") != "y" {
		t.Fatalf("got %+v", got)
	}
	// Header only (body truncated) and padded.
	b := append((&Response{Status: 204, Header: Fields{{"a", "1"}}}).Marshal()[:8], 0, 0, 0)
	got, err = UnmarshalResponse(b, 100)
	if err != nil || got.Status != 204 || got.Header.Get("a") != "1" {
		t.Fatalf("got %+v, %v", got, err)
	}
	// Body without trailer section.
	full := (&Response{Status: 200, Body: []byte("ok")}).Marshal()
	got, err = UnmarshalResponse(full[:len(full)-1], 100)
	if err != nil || string(got.Body) != "ok" {
		t.Fatalf("got %+v, %v", got, err)
	}
}

func TestInformationalResponsesAreSkipped(t *testing.T) {
	b := appendVarint(nil, 1)
	b = appendVarint(b, 103)
	b = appendFields(b, Fields{{"link", "</a>"}})
	b = appendVarint(b, 200)
	b = appendFields(b, nil)
	b = appendVarint(b, 0)
	got, err := UnmarshalResponse(b, 10)
	if err != nil || got.Status != 200 {
		t.Fatalf("got %+v, %v", got, err)
	}
}

func TestVarint(t *testing.T) {
	for _, v := range []uint64{0, 63, 64, 16383, 16384, 1<<30 - 1, 1 << 30, maxVarint} {
		b := appendVarint(nil, v)
		r := reader{b: b}
		got, err := r.varint()
		if err != nil || got != v || !r.done() {
			t.Fatalf("varint %d: got %d, %v (encoded %x)", v, got, err, b)
		}
	}
	defer func() {
		if recover() == nil {
			t.Fatal("expected panic for out-of-range varint")
		}
	}()
	appendVarint(nil, maxVarint+1)
}

func TestMalformed(t *testing.T) {
	valid := (&Request{Method: "POST", Scheme: "https", Authority: "a", Path: "/", Header: Fields{{"a", "b"}},
		Body: []byte("body"), Trailer: Fields{{"t", "v"}}}).Marshal(0)
	cases := map[string][]byte{
		"empty":            {},
		"indeterminate":    {0x02},
		"response framing": {0x01, 0x40, 0xc8},
		"truncated varint": {0x40},
		"long method":      append([]byte{0x00, 0x44, 0x01}, make([]byte, 10)...),
		"empty method":     {0x00, 0x00, 0x00, 0x00, 0x00},
		"bad method":       {0x00, 0x02, 'a', ' ', 0, 0, 0},
		"trailing garbage": append(append([]byte{}, valid...), 1),
		"body too long":    (&Request{Method: "P", Body: make([]byte, 20)}).Marshal(0),
		"upper-case name":  append([]byte{0x00, 1, 'G', 0, 0, 0}, 4, 1, 'A', 1, 'b'),
		"name with colon":  append([]byte{0x00, 1, 'G', 0, 0, 0}, 4, 1, ':', 1, 'b'),
		"value with CR":    append([]byte{0x00, 1, 'G', 0, 0, 0}, 4, 1, 'a', 1, '\r'),
		"empty name":       append([]byte{0x00, 1, 'G', 0, 0, 0}, 3, 0, 1, 'b'),
		"field overrun":    append([]byte{0x00, 1, 'G', 0, 0, 0}, 9, 1, 'a'),
		"field value cut":  append([]byte{0x00, 1, 'G', 0, 0, 0}, 3, 1, 'a', 5),
		"trailer garbage":  append([]byte{0x00, 1, 'G', 0, 0, 0, 0, 0}, 5, 1),
	}
	for name, b := range cases {
		if _, err := UnmarshalRequest(b, 10); !errors.Is(err, ErrMalformed) {
			t.Errorf("%s: err = %v, want ErrMalformed", name, err)
		}
	}
	many := Fields{}
	for i := 0; i <= MaxFieldLines; i++ {
		many = append(many, Field{"a", "b"})
	}
	if _, err := UnmarshalRequest((&Request{Method: "G", Header: many}).Marshal(0), 10); !errors.Is(err, ErrMalformed) {
		t.Errorf("too many fields: %v", err)
	}

	resp := map[string][]byte{
		"empty":           {},
		"request framing": {0x00},
		"bad status":      {0x01, 0x40, 0x32},
		"status 600":      {0x01, 0x42, 0x58},
		"cut status":      {0x01},
		"bad info fields": {0x01, 0x40, 0x64, 0x05},
		"bad header":      {0x01, 0x40, 0xc8, 0x05},
		"bad body":        {0x01, 0x40, 0xc8, 0x00, 0x30},
		"bad trailer":     {0x01, 0x40, 0xc8, 0x00, 0x00, 0x09},
		"trailing data":   {0x01, 0x40, 0xc8, 0x00, 0x00, 0x00, 0x07},
	}
	for name, b := range resp {
		if _, err := UnmarshalResponse(b, 10); !errors.Is(err, ErrMalformed) {
			t.Errorf("response %s: err = %v, want ErrMalformed", name, err)
		}
	}
}
