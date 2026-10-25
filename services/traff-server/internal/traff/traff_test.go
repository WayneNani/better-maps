package traff

import (
	"encoding/xml"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestParseBBox(t *testing.T) {
	tests := []struct {
		in      string
		want    BBox
		wantErr bool
	}{
		{in: "48.0000 11.0000 49.0000 12.0000", want: BBox{48, 11, 49, 12}},
		{in: "+53.8 +20.9 +56.5 +26.9", want: BBox{53.8, 20.9, 56.5, 26.9}},
		{in: "49 12 48 11", want: BBox{48, 11, 49, 12}}, // swapped corners
		{in: "48,11,49,12", want: BBox{48, 11, 49, 12}},
		{in: " 48\t11\n49 12 ", want: BBox{48, 11, 49, 12}},
		{in: "48 11 49", wantErr: true},
		{in: "48 11 49 x", wantErr: true},
		{in: "", wantErr: true},
		{in: "91 11 92 12", wantErr: true},
		{in: "48 181 49 182", wantErr: true},
	}
	for _, tc := range tests {
		got, err := ParseBBox(tc.in)
		if (err != nil) != tc.wantErr {
			t.Errorf("ParseBBox(%q) error = %v, wantErr %v", tc.in, err, tc.wantErr)
			continue
		}
		if !tc.wantErr && got != tc.want {
			t.Errorf("ParseBBox(%q) = %+v, want %+v", tc.in, got, tc.want)
		}
	}
}

func TestBBoxOperations(t *testing.T) {
	a := BBox{48, 11, 49, 12}
	cases := []struct {
		b                BBox
		intersects, cont bool
	}{
		{BBox{48.5, 11.5, 48.6, 11.6}, true, true},
		{BBox{49, 12, 50, 13}, true, false}, // touching corner counts as overlap
		{BBox{49.1, 11, 50, 12}, false, false},
		{BBox{47, 10, 50, 13}, true, false},
		{BBox{48, 12.0001, 49, 13}, false, false},
	}
	for _, c := range cases {
		if got := a.Intersects(c.b); got != c.intersects {
			t.Errorf("%v.Intersects(%v) = %v", a, c.b, got)
		}
		if got := c.b.Intersects(a); got != c.intersects {
			t.Errorf("Intersects not symmetric for %v", c.b)
		}
		if got := a.Contains(c.b); got != c.cont {
			t.Errorf("%v.Contains(%v) = %v", a, c.b, got)
		}
	}
	u := a.Union(BBox{47, 12.5, 47.5, 13})
	if u != (BBox{47, 11, 49, 13}) {
		t.Errorf("Union = %v", u)
	}
	if s := a.String(); s != "+48.000000 +11.000000 +49.000000 +12.000000" {
		t.Errorf("String = %q", s)
	}
	if b, err := ParseBBox(a.String()); err != nil || b != a {
		t.Errorf("round trip failed: %v %v", b, err)
	}
}

func TestRoadClass(t *testing.T) {
	order := []RoadClass{RoadClassOther, RoadClassTertiary, RoadClassSecondary, RoadClassPrimary, RoadClassTrunk, RoadClassMotorway}
	for i := 1; i < len(order); i++ {
		if order[i].Rank() <= order[i-1].Rank() {
			t.Errorf("%s should rank above %s", order[i], order[i-1])
		}
	}
	if RoadClass("").Rank() != -1 || RoadClass("FOO").Rank() != -1 {
		t.Error("unknown road classes must rank -1")
	}
	if rc, err := ParseRoadClass(" primary "); err != nil || rc != RoadClassPrimary {
		t.Errorf("ParseRoadClass = %v, %v", rc, err)
	}
	if _, err := ParseRoadClass("highway"); err == nil {
		t.Error("expected error for unknown road class")
	}
}

func TestFilterMatching(t *testing.T) {
	box := BBox{48, 11, 49, 12}
	inside := BBox{48.5, 11.5, 48.5, 11.5}
	outside := BBox{50, 11, 50.1, 11.1}

	f := Filter{BBox: &box, MinRoadClass: RoadClassPrimary}
	if !f.MatchesArea(inside) || f.MatchesArea(outside) {
		t.Error("area matching wrong")
	}
	if !f.MatchesRoadClass(RoadClassMotorway) || !f.MatchesRoadClass(RoadClassPrimary) {
		t.Error("higher or equal road classes must match")
	}
	if f.MatchesRoadClass(RoadClassSecondary) {
		t.Error("lower road class must not match")
	}
	if !f.MatchesRoadClass("") {
		t.Error("messages without road class always match")
	}
	if !(Filter{}).MatchesArea(outside) || !(Filter{}).MatchesRoadClass(RoadClassOther) {
		t.Error("empty filter must match everything")
	}

	other := BBox{50, 11, 51, 12}
	fl := FilterList{{BBox: &box, MinRoadClass: RoadClassMotorway}, {BBox: &other}}
	if fl.Matches(inside, RoadClassPrimary) {
		t.Error("primary road in first box must not match motorway-only item")
	}
	if !fl.Matches(inside, RoadClassMotorway) {
		t.Error("motorway in first box must match")
	}
	if !fl.Matches(outside, RoadClassTertiary) {
		t.Error("second item has no road class constraint")
	}
	if (FilterList{}).Matches(inside, RoadClassMotorway) {
		t.Error("empty filter list matches nothing")
	}
}

func TestCoverage(t *testing.T) {
	de := []BBox{{47.2, 5.8, 55.1, 15.1}}
	berlin := BBox{52.3, 13.0, 52.7, 13.8}
	border := BBox{47, 5, 48, 7}
	paris := BBox{48.7, 2.2, 49, 2.5}
	f := func(bs ...BBox) FilterList {
		var fl FilterList
		for i := range bs {
			fl = append(fl, Filter{BBox: &bs[i]})
		}
		return fl
	}
	cases := []struct {
		fl   FilterList
		cov  []BBox
		want string
	}{
		{f(berlin), de, "covered"},
		{f(berlin, border), de, "partial"},
		{f(border), de, "partial"},
		{f(paris), de, "none"},
		{f(berlin, paris), de, "partial"},
		{FilterList{{}}, de, "partial"},
		{f(paris), nil, "covered"},
	}
	for i, c := range cases {
		if got := c.fl.Coverage(c.cov); got != c.want {
			t.Errorf("case %d: Coverage = %q, want %q", i, got, c.want)
		}
	}
}

func TestParseRequest(t *testing.T) {
	req, err := ParseRequest(strings.NewReader(`<?xml version="1.0" encoding="UTF-8"?>
<request operation="subscribe">
  <filter_list>
    <filter bbox="48.0000 11.0000 49.0000 12.0000"/>
    <filter min_road_class="PRIMARY" bbox="47.2701 8.9763 54.8358 23.9463"/>
    <filter min_road_class="motorway"/>
  </filter_list>
</request>`), 10)
	if err != nil {
		t.Fatal(err)
	}
	if req.Operation != OpSubscribe || !req.HasFilterList || len(req.Filters) != 3 {
		t.Fatalf("unexpected request %+v", req)
	}
	if req.Filters[0].BBox == nil || req.Filters[0].MinRoadClass != "" {
		t.Errorf("filter 0 = %+v", req.Filters[0])
	}
	if req.Filters[1].MinRoadClass != RoadClassPrimary || req.Filters[1].BBox.MaxLon != 23.9463 {
		t.Errorf("filter 1 = %+v", req.Filters[1])
	}
	if req.Filters[2].BBox != nil || req.Filters[2].MinRoadClass != RoadClassMotorway {
		t.Errorf("filter 2 = %+v", req.Filters[2])
	}

	req, err = ParseRequest(strings.NewReader(`<request operation="POLL" subscription_id=" abc "/>`), 0)
	if err != nil || req.SubscriptionID != "abc" || req.HasFilterList {
		t.Errorf("poll request = %+v, %v", req, err)
	}

	req, err = ParseRequest(strings.NewReader(`<request operation="SUBSCRIBE"><filter_list></filter_list></request>`), 0)
	if err != nil || !req.HasFilterList || len(req.Filters) != 0 {
		t.Errorf("empty filter list = %+v, %v", req, err)
	}

	bad := []string{
		``,
		`<request/>`,
		`<request operation="SUBSCRIBE"><filter_list><filter bbox="1 2 3"/></filter_list></request>`,
		`<request operation="SUBSCRIBE"><filter_list><filter min_road_class="FOO"/></filter_list></request>`,
		`<request operation="SUBSCRIBE"><filter_list><filter/><filter/><filter/></filter_list></request>`,
		`<other operation="POLL"/>`,
		`<?xml version="1.0" encoding="ISO-8859-2"?><request operation="POLL"/>`,
		`<request operation="POLL"`,
	}
	for _, b := range bad {
		if _, err := ParseRequest(strings.NewReader(b), 2); !errors.Is(err, ErrInvalidRequest) {
			t.Errorf("ParseRequest(%q) error = %v, want ErrInvalidRequest", b, err)
		}
	}
	if _, err := ParseRequest(strings.NewReader(`<?xml version="1.0" encoding="us-ascii"?><request operation="POLL"/>`), 0); err != nil {
		t.Errorf("ascii charset rejected: %v", err)
	}
}

func sampleMessage() Message {
	d := 14.4
	start := time.Date(2026, 10, 25, 8, 0, 0, 0, time.UTC)
	return Message{
		ID:             "test:1",
		ReceiveTime:    NewTime(start),
		UpdateTime:     NewTime(start.Add(time.Minute)),
		ExpirationTime: NewTime(start.Add(time.Hour)),
		StartTime:      TimePtr(start),
		EndTime:        TimePtr(start.Add(48 * time.Hour)),
		Merge:          &Merge{Replaces: []Replaces{{ID: "test:0"}}},
		Location: &Location{
			Country: "DE", Directionality: OneDirection, RoadClass: RoadClassMotorway, RoadRef: "A 7",
			Origin: "Würzburg", Destination: "Fulda",
			From: &Point{Coord: LatLon{50.302025, 9.848275}, Distance: &d, JunctionName: "Bad Brückenau", JunctionRef: "94"},
			Via:  NewPoint(50.31, 9.84),
			To:   NewPoint(50.319385, 9.838167),
		},
		Events: []Event{{Class: "CONGESTION", Type: "CONGESTION_QUEUE", Speed: new(int)}},
	}
}

func TestMessageXMLRoundTrip(t *testing.T) {
	m := sampleMessage()
	data, err := xml.Marshal(&m)
	if err != nil {
		t.Fatal(err)
	}
	s := string(data)
	for _, want := range []string{
		`id="test:1"`, `receive_time="2026-10-25T08:00:00Z"`, `expiration_time="2026-10-25T09:00:00Z"`,
		`end_time="2026-10-27T08:00:00Z"`, `cancellation="false"`, `<from distance="14.4" junction_name="Bad Brückenau" junction_ref="94">+50.302025 +9.848275</from>`,
		`road_ref="A 7"`, `<events><event class="CONGESTION" type="CONGESTION_QUEUE" speed="0"></event></events>`,
		`<merge><replaces id="test:0"></replaces></merge>`,
	} {
		if !strings.Contains(s, want) {
			t.Errorf("marshaled message lacks %s:\n%s", want, s)
		}
	}
	var back Message
	if err := xml.Unmarshal(data, &back); err != nil {
		t.Fatal(err)
	}
	if back.ID != m.ID || !back.ExpirationTime.Equal(m.ExpirationTime.Time) || back.Location.From.Coord != m.Location.From.Coord ||
		*back.Location.From.Distance != d(m) || back.Location.To.Coord != m.Location.To.Coord || back.EndTime == nil {
		t.Errorf("round trip mismatch: %+v", back)
	}

	bad := []string{
		`<message id="x" receive_time="yesterday" update_time="2026-10-25T08:00:00Z" expiration_time="2026-10-25T08:00:00Z"/>`,
		`<message id="x"><location><from>1 2 3</from></location></message>`,
		`<message id="x"><location><from>abc def</from></location></message>`,
		`<message id="x"><location><from>95 10</from></location></message>`,
	}
	for _, b := range bad {
		if err := xml.Unmarshal([]byte(b), &back); err == nil {
			t.Errorf("Unmarshal(%q) succeeded", b)
		}
	}
	var tt Time
	if err := tt.UnmarshalXMLAttr(xml.Attr{Value: "2026-10-25T10:00:00+0200"}); err != nil || tt.Hour() != 8 {
		t.Errorf("offset without colon: %v %v", tt, err)
	}
}

func d(m Message) float64 { return *m.Location.From.Distance }

func TestMessageHelpers(t *testing.T) {
	m := sampleMessage()
	if got := m.EffectiveExpiration(); !got.Equal(m.EndTime.Time) {
		t.Errorf("EffectiveExpiration = %v", got)
	}
	m2 := m
	m2.EndTime = nil
	m2.StartTime = TimePtr(m.ExpirationTime.Add(time.Hour))
	if got := m2.EffectiveExpiration(); !got.Equal(m2.StartTime.Time) {
		t.Errorf("EffectiveExpiration with future start = %v", got)
	}
	m2.StartTime = nil
	if got := m2.EffectiveExpiration(); !got.Equal(m.ExpirationTime.Time) {
		t.Errorf("EffectiveExpiration without start/end = %v", got)
	}

	b, ok := m.BBox()
	if !ok || b != (BBox{50.302025, 9.838167, 50.319385, 9.848275}) {
		t.Errorf("BBox = %v %v", b, ok)
	}
	if _, ok := (&Message{}).BBox(); ok {
		t.Error("message without location has no bbox")
	}
	if _, ok := (&Message{Location: &Location{}}).BBox(); ok {
		t.Error("location without points has no bbox")
	}

	c := m.Clone()
	c.Location.From.Coord.Lat = 1
	*c.Location.From.Distance = 99
	c.Events[0].Type = "X"
	c.StartTime.Time = time.Time{}
	c.EndTime.Time = time.Time{}
	c.Merge.Replaces[0].ID = "changed"
	if m.Location.From.Coord.Lat == 1 || *m.Location.From.Distance == 99 || m.Events[0].Type == "X" ||
		m.StartTime.IsZero() || m.EndTime.IsZero() || m.Merge.Replaces[0].ID == "changed" {
		t.Error("Clone is not deep")
	}
	if got := len(m.Location.Points()); got != 3 {
		t.Errorf("Points = %d", got)
	}
}

func TestResponseMarshal(t *testing.T) {
	r := &Response{Status: StatusOK, SubscriptionID: "abc", Timeout: 3600, Feed: &Feed{Messages: []Message{sampleMessage()}}}
	data, err := r.Marshal()
	if err != nil {
		t.Fatal(err)
	}
	s := string(data)
	if !strings.HasPrefix(s, xml.Header) || !strings.Contains(s, `<response status="OK" subscription_id="abc" timeout="3600">`) ||
		!strings.Contains(s, "<feed>") || !strings.Contains(s, `<message id="test:1"`) {
		t.Errorf("unexpected response:\n%s", s)
	}
	empty, _ := (&Response{Status: StatusOK, Feed: &Feed{}}).Marshal()
	if !strings.Contains(string(empty), "<feed></feed>") {
		t.Errorf("empty feed must be serialized: %s", empty)
	}
	caps, _ := (&Response{Status: StatusOK, Capabilities: &Capabilities{MinVersion: MinVersion, TargetVersion: TargetVersion}}).Marshal()
	if !strings.Contains(string(caps), `<capabilities min_version="0.8" target_version="0.8"></capabilities>`) {
		t.Errorf("capabilities: %s", caps)
	}
	f, err := MarshalFeed(&Feed{Messages: []Message{sampleMessage()}})
	if err != nil || !strings.Contains(string(f), "<feed>") {
		t.Errorf("MarshalFeed: %s %v", f, err)
	}
}

func TestLatLon(t *testing.T) {
	p := LatLon{-33.5, 151.25}
	if p.String() != "-33.500000 +151.250000" || !p.Valid() {
		t.Errorf("LatLon = %s", p)
	}
	if (LatLon{91, 0}).Valid() || (LatLon{0, -181}).Valid() {
		t.Error("out-of-range coordinates reported valid")
	}
	if _, err := ParseCoordinates("+52.1 13.4"); err != nil {
		t.Error(err)
	}
}

func TestCancellationHasNoEvents(t *testing.T) {
	m := Message{ID: "x", Cancellation: true}
	data, err := xml.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "events") || !strings.HasPrefix(string(data), `<message id="x"`) || !strings.Contains(string(data), `cancellation="true"`) {
		t.Errorf("cancellation = %s", data)
	}
	var back Message
	if err := xml.Unmarshal(data, &back); err != nil || !back.Cancellation || back.Events != nil {
		t.Errorf("round trip = %+v %v", back, err)
	}
}
