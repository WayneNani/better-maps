package protocol

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/crowd-traffic/internal/tiles"
)

// The example of the client contract in docs/CROWD_TRAFFIC_PRIVACY.md.
const contractExample = `{
  "version": 1,
  "reports": [
    {"window": 1793029500, "tile": {"x": 70394, "y": 43010},
     "entry": {"side": "W", "offset": 201}, "exit": {"side": "E", "offset": 239},
     "road_class": "MOTORWAY", "speed_bucket": 0},
    {"window": 1793029500, "tile": {"x": 70395, "y": 43011},
     "entry": {"side": "N", "offset": 109}, "exit": {"side": "E", "offset": 21},
     "road_class": "MOTORWAY", "speed_bucket": 5}
  ]
}`

func exampleNow() time.Time { return time.Unix(1793029500+900+60, 0) } // 1 min after the window

func TestContractExample(t *testing.T) {
	b, err := Decode([]byte(contractExample))
	if err != nil {
		t.Fatal(err)
	}
	if len(b.Reports) != 2 || b.Reports[0].Entry.Side != tiles.West || b.Reports[1].Exit.Side != tiles.East ||
		b.Reports[0].RoadClass != Motorway || b.Reports[0].Tile.X != 70394 {
		t.Fatalf("decoded %+v", b)
	}
	if err := DefaultRules().Validate(b, exampleNow()); err != nil {
		t.Fatal(err)
	}
	if k := b.Reports[0].Key().String(); k != "17/70394/43010/W-E/MOTORWAY" {
		t.Fatal(k)
	}
	enc, err := b.Encode()
	if err != nil {
		t.Fatal(err)
	}
	b2, err := Decode(enc)
	if err != nil || b2.Reports[1] != b.Reports[1] {
		t.Fatalf("re-decode: %+v, %v", b2, err)
	}
}

func TestBuckets(t *testing.T) {
	cases := map[float64]int{-5: 0, 0: 0, 9.99: 0, 10: 1, 34: 2, 35: 3, 54.9: 3, 55: 4, 80: 5, 109: 5, 110: 6, 250: 6}
	for v, want := range cases {
		if got := BucketOf(v); got != want {
			t.Errorf("BucketOf(%v) = %d, want %d", v, got, want)
		}
	}
}

func TestRoadClass(t *testing.T) {
	for c := Motorway; c <= Tertiary; c++ {
		b, err := json.Marshal(c)
		if err != nil {
			t.Fatal(err)
		}
		var back RoadClass
		if err := json.Unmarshal(b, &back); err != nil || back != c || back.String() != strings.Trim(string(b), `"`) {
			t.Errorf("%v: %s, %v", c, b, err)
		}
	}
	if _, err := json.Marshal(RoadClass(0)); err == nil {
		t.Error("zero road class marshalled")
	}
	var c RoadClass
	if err := json.Unmarshal([]byte(`"RESIDENTIAL"`), &c); err == nil {
		t.Error("residential accepted")
	}
	if RoadClass(9).String() != "RoadClass(9)" {
		t.Error(RoadClass(9).String())
	}
}

func reason(err error) Reason {
	var ve *ValidationError
	if errors.As(err, &ve) {
		return ve.Reason
	}
	return ""
}

func TestDecodeErrors(t *testing.T) {
	cases := map[string]Reason{
		`{"version":1,"reports":[],"extra":1}`:             ReasonSyntax,
		`{"version":1}{"version":1}`:                       ReasonSyntax,
		`{"version":"1"}`:                                  ReasonSyntax,
		`not json`:                                         ReasonSyntax,
		`{"version":1,"reports":[{"road_class":"FOO"}]}`:   ReasonSyntax,
		`{"version":1,"reports":[{"entry":{"side":"Q"}}]}`: ReasonSyntax,
		strings.Repeat(" ", MaxBodyBytes+1):                ReasonTooLarge,
	}
	for in, want := range cases {
		_, err := Decode([]byte(in))
		if got := reason(err); got != want {
			t.Errorf("%.40q: reason %q, want %q (%v)", in, got, want, err)
		}
	}
	err := &ValidationError{Reason: ReasonSyntax, Detail: "x"}
	if err.Error() != "invalid bundle: syntax: x" || (&ValidationError{Reason: ReasonEmpty}).Error() != "invalid bundle: empty" {
		t.Error(err.Error())
	}
}

func validBundle() *Bundle {
	b, _ := Decode([]byte(contractExample))
	return b
}

func TestValidate(t *testing.T) {
	rules := DefaultRules()
	now := exampleNow()
	w := int64(1793029500)
	mod := func(f func(b *Bundle)) *Bundle {
		b := validBundle()
		f(b)
		return b
	}
	cases := []struct {
		name string
		b    *Bundle
		now  time.Time
		want Reason
	}{
		{"version", mod(func(b *Bundle) { b.Version = 2 }), now, ReasonVersion},
		{"empty", mod(func(b *Bundle) { b.Reports = nil }), now, ReasonEmpty},
		{"too many", mod(func(b *Bundle) {
			for len(b.Reports) <= MaxReports {
				r := b.Reports[0]
				r.Tile.X += len(b.Reports)
				b.Reports = append(b.Reports, r)
			}
		}), now, ReasonTooMany},
		{"misaligned", mod(func(b *Bundle) { b.Reports[0].Window++; b.Reports[1].Window++ }), now, ReasonMisaligned},
		{"future", mod(func(b *Bundle) { b.Reports[0].Window += 900; b.Reports[1].Window += 900 }), time.Unix(w+899, 0), ReasonFuture},
		{"late after grace", validBundle(), time.Unix(w+900+300, 0), ReasonLate},
		{"two windows old", validBundle(), time.Unix(w+1800+10, 0), ReasonLate},
		{"mixed windows", mod(func(b *Bundle) { b.Reports[1].Window -= 900 }), now, ReasonMixedWindows},
		{"tile", mod(func(b *Bundle) { b.Reports[1].Tile.Y = -1 }), now, ReasonTile},
		{"side", mod(func(b *Bundle) { b.Reports[1].Exit.Side = 0 }), now, ReasonSide},
		{"offset high", mod(func(b *Bundle) { b.Reports[1].Exit.Offset = 256 }), now, ReasonOffset},
		{"offset negative", mod(func(b *Bundle) { b.Reports[0].Entry.Offset = -1 }), now, ReasonOffset},
		{"same edge", mod(func(b *Bundle) { b.Reports[0].Exit = b.Reports[0].Entry }), now, ReasonSameEdge},
		{"road class", mod(func(b *Bundle) { b.Reports[0].RoadClass = 0 }), now, ReasonRoadClass},
		{"bucket high", mod(func(b *Bundle) { b.Reports[0].SpeedBucket = NumBuckets }), now, ReasonSpeedBucket},
		{"bucket negative", mod(func(b *Bundle) { b.Reports[0].SpeedBucket = -1 }), now, ReasonSpeedBucket},
		{"duplicate", mod(func(b *Bundle) { b.Reports[1] = b.Reports[0]; b.Reports[1].SpeedBucket = 3 }), now, ReasonDuplicate},
		{"coverage", mod(func(b *Bundle) { b.Reports[1].Tile = tiles.At(48.85, 2.35) }), now, ReasonCoverage},
		{"spread", mod(func(b *Bundle) { b.Reports[1].Tile.X += 401 }), now, ReasonSpread},
	}
	for _, tc := range cases {
		if got := reason(rules.Validate(tc.b, tc.now)); got != tc.want {
			t.Errorf("%s: reason %q, want %q", tc.name, got, tc.want)
		}
	}
	// Within the window itself and within the grace period: accepted.
	for _, at := range []time.Time{time.Unix(w, 0), time.Unix(w+899, 0), time.Unix(w+900+299, 0)} {
		if err := rules.Validate(validBundle(), at); err != nil {
			t.Errorf("at %v: %v", at.Unix()-w, err)
		}
	}
	// Same tile, opposite direction is a different key (no duplicate).
	b := mod(func(b *Bundle) {
		b.Reports[1] = b.Reports[0]
		b.Reports[1].Entry, b.Reports[1].Exit = b.Reports[0].Exit, b.Reports[0].Entry
	})
	if err := rules.Validate(b, now); err != nil {
		t.Errorf("opposite direction: %v", err)
	}
	// Without coverage and spread limits, Paris is fine.
	open := Rules{Window: 900 * time.Second, Grace: 5 * time.Minute}
	if err := open.Validate(mod(func(b *Bundle) { b.Reports[1].Tile = tiles.At(48.85, 2.35) }), now); err != nil {
		t.Errorf("open rules: %v", err)
	}
}

func TestWindowStart(t *testing.T) {
	r := Rules{Window: 900 * time.Second}
	if got := r.WindowStart(time.Unix(1793029500+899, 0)); got != 1793029500 {
		t.Fatal(got)
	}
	if got := r.WindowStart(time.Unix(-1, 0)); got != -900 {
		t.Fatal(got)
	}
	if !Germany.Contains(52.5, 13.4) || Germany.Contains(48.85, 2.35) {
		t.Fatal("coverage")
	}
}
