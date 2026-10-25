package datex2

import (
	"errors"
	"math"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

func parseFile(t *testing.T, name string) *Publication {
	t.Helper()
	f, err := os.Open(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	pub, err := Parse(f)
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return pub
}

func records(pub *Publication) map[string]*Record {
	res := map[string]*Record{}
	for i := range pub.Situations {
		for j := range pub.Situations[i].Records {
			r := &pub.Situations[i].Records[j]
			if _, dup := res[r.ID]; !dup {
				res[r.ID] = r
			}
		}
	}
	return res
}

func TestParseBWIncidents(t *testing.T) {
	pub := parseFile(t, "bw_incidents_v2.xml")
	if pub.Version != 2 || pub.Country != "de" || pub.NationalIdentifier != "D  BW LMS-BW" || pub.UpdateMethod != "allElementUpdate" {
		t.Errorf("header = %d %q %q %q", pub.Version, pub.Country, pub.NationalIdentifier, pub.UpdateMethod)
	}
	if !pub.PublicationTime.Equal(time.Date(2026, 10, 25, 16, 52, 52, 0, time.UTC)) && pub.PublicationTime.IsZero() {
		t.Errorf("publication time %v", pub.PublicationTime)
	}
	if len(pub.Situations) != 12 {
		t.Fatalf("%d situations", len(pub.Situations))
	}
	recs := records(pub)
	acc := recs["LMS-BW/r_LMS-BW/669322_D  BW LMS-BW.0"]
	if acc == nil {
		t.Fatal("accident record missing")
	}
	if acc.Type != "Accident" || acc.SubType != "accident" || acc.ValidityStatus != "active" || acc.Probability != "certain" {
		t.Errorf("accident = %+v", acc)
	}
	if acc.Location.Kind != GeometryLine || len(acc.Location.Points) < 10 || !acc.Location.HasAlertC || acc.Location.RoadNumber != "" {
		t.Errorf("accident location kind=%d points=%d alertc=%v road=%q", acc.Location.Kind, len(acc.Location.Points), acc.Location.HasAlertC, acc.Location.RoadNumber)
	}
	if acc.descriptor() != "A5 Karlsruhe Richtung Heidelberg zwischen Walldorf/Wiesloch (39) und Heidelberg/Schwetzingen (38)" {
		t.Errorf("descriptor %q", acc.descriptor())
	}
	// GML 2 coordinates are "lon,lat": the first point lies near Walldorf, not in Somalia.
	if p := acc.Location.Points[0]; p.Lat < 49 || p.Lat > 49.5 || p.Lon < 8.5 || p.Lon > 8.8 {
		t.Errorf("first point %v", p)
	}
	auth := recs["LMS-BW/r_LMS-BW/669307_D  BW LMS-BW.0"]
	if auth == nil || auth.Type != "AuthorityOperation" || auth.SubType != "accidentInvestigationWork" {
		t.Errorf("authority = %+v", auth)
	}
}

func TestParseBWRoadworks(t *testing.T) {
	pub := parseFile(t, "bw_roadworks_v2.xml")
	if pub.Version != 2 || len(pub.Situations) != 8 {
		t.Fatalf("version %d, %d situations", pub.Version, len(pub.Situations))
	}
	n, withRoad := 0, 0
	for _, r := range records(pub) {
		if r.Location.Kind != GeometryLine {
			t.Errorf("%s: kind %d", r.ID, r.Location.Kind)
		}
		if r.Location.RoadNumber != "" {
			withRoad++
		}
		if r.OverallStart.IsZero() || r.OverallEnd.IsZero() {
			t.Errorf("%s: validity %v - %v", r.ID, r.OverallStart, r.OverallEnd)
		}
		// posList is "lat lon" in Baden-Württemberg.
		if p := r.Location.Points[0]; p.Lat < 47.5 || p.Lat > 50 || p.Lon < 7.5 || p.Lon > 10.6 {
			t.Errorf("%s: point %v outside Baden-Württemberg", r.ID, p)
		}
		n++
	}
	if n < 8 || withRoad == 0 || withRoad == n {
		t.Errorf("%d records, %d with road number", n, withRoad)
	}
}

func TestParseSyntheticV2(t *testing.T) {
	pub := parseFile(t, "synthetic_v2.xml")
	if pub.Version != 2 || pub.NationalIdentifier != "DE-TEST-V2" || !pub.PublicationTime.Equal(time.Date(2026, 10, 25, 14, 58, 0, 0, time.UTC)) {
		t.Errorf("header %d %q %v", pub.Version, pub.NationalIdentifier, pub.PublicationTime)
	}
	recs := records(pub)
	check := func(id string, f func(r *Record) bool) {
		t.Helper()
		r := recs[id]
		if r == nil {
			t.Errorf("%s missing", id)
			return
		}
		if !f(r) {
			t.Errorf("%s = %+v", id, *r)
		}
	}
	check("syn-speed.0", func(r *Record) bool {
		return r.Type == "SpeedManagement" && r.SpeedLimit == 60 && r.Location.Kind == GeometryLine &&
			len(r.Location.Points) == 4 && r.Location.RoadNumber == "A9" // repeated point removed
	})
	check("syn-speed.1", func(r *Record) bool { return r.SpeedLimit == 0 && r.Location.Kind == GeometryDisplay })
	check("syn-animals.0", func(r *Record) bool {
		return r.Location.Kind == GeometryPoint && r.Location.Points[0] == traff.LatLon{Lat: 52.4, Lon: 13.05}
	})
	check("syn-infra.0", func(r *Record) bool { return r.Location.Kind == GeometryPoint && r.SubType == "gasLeak" })
	check("syn-ice.0", func(r *Record) bool {
		return r.Location.Kind == GeometryLine && r.Location.Points[0] == traff.LatLon{Lat: 49.45, Lon: 11.01} && r.Location.RoadNumber == "B 2"
	})
	check("syn-oil.0", func(r *Record) bool { return r.Location.Kind == GeometryDisplay && len(r.Comments) == 1 })
	check("syn-alertc.0", func(r *Record) bool { return r.Location.Kind == GeometryNone && r.Location.HasAlertC })
	check("syn-linref.0", func(r *Record) bool {
		return r.Location.Kind == GeometryNone && r.Location.HasLinearReference && !r.Location.HasAlertC
	})
	check("syn-noloc.0", func(r *Record) bool { return r.Location.Kind == GeometryNone && !r.Location.HasAlertC })
	check("syn-periods.0", func(r *Record) bool {
		return len(r.Periods) == 3 && r.Location.Kind == GeometryEndpoints && r.Location.FRC == "FRC0" &&
			r.OverallStart.Equal(time.Date(2026, 10, 19, 22, 0, 0, 0, time.UTC))
	})
	check("syn-group.0", func(r *Record) bool {
		return r.Location.Kind == GeometryEndpoints && r.Location.BothDirections && r.Location.HasAlertC &&
			r.Location.RoadNumber == "A 3" && r.LanesRestricted == 2 && r.DelaySeconds == 900 &&
			r.Location.Points[0] == traff.LatLon{Lat: 50.04, Lon: 8.53}
	})
	check("syn-utm.0", func(r *Record) bool {
		p := r.Location.Points[0]
		return r.Location.Kind == GeometryLine && math.Abs(p.Lat-51.60695) < 1e-4 && math.Abs(p.Lon-7.76862) < 1e-4
	})
	check("syn-bypoints.0", func(r *Record) bool {
		return r.Location.Kind == GeometryLine && len(r.Location.Points) == 3 && r.Location.RoadName == "Hauptstraße" &&
			r.Location.RoadNumber == "" && r.OverallStart.Equal(time.Date(2026, 10, 25, 10, 0, 0, 0, time.UTC))
	})
	check("syn-cancel.0", func(r *Record) bool { return r.Cancelled })
	check("syn-wrongway.0", func(r *Record) bool {
		return strings.HasPrefix(r.descriptor(), "A3 Frankfurt Richtung Köln") && r.Location.Points[0].Lat == 50.37
	})
	if _, ok := recs[""]; !ok {
		t.Error("record without ID not parsed")
	}
}

func TestParseSyntheticV3(t *testing.T) {
	pub := parseFile(t, "synthetic_v3.xml")
	if pub.Version != 3 || pub.Country != "DE" || pub.NationalIdentifier != "DE-TEST-V3" || len(pub.Situations) != 6 {
		t.Fatalf("header %d %q %q, %d situations", pub.Version, pub.Country, pub.NationalIdentifier, len(pub.Situations))
	}
	if pub.Situations[0].Urgency != "urgent" || pub.Situations[0].ID != "v3-accident" {
		t.Errorf("situation 0 = %+v", pub.Situations[0])
	}
	recs := records(pub)
	acc := recs["v3-accident_1"]
	if acc.Type != "Accident" || acc.SubType != "seriousAccident" || acc.Location.Kind != GeometryPoint ||
		acc.Location.RoadNumber != "A7" || acc.Location.RoadName != "Bundesautobahn 7" || acc.Comments[0].Type != "description" {
		t.Errorf("accident = %+v", acc)
	}
	q := recs["v3-accident_2"]
	if q.DelaySeconds != 1500 || q.Location.Kind != GeometryLine || len(q.Location.Points) != 3 || q.SubType != "stationaryTraffic" {
		t.Errorf("queue = %+v", q)
	}
	works := recs["v3-works_1"]
	if works.Location.Kind != GeometryLine || works.Location.RoadNumber != "A8" || !works.Location.HasAlertC || len(works.Periods) != 1 || works.LanesRestricted != 1 {
		t.Errorf("works = %+v", works)
	}
	fire := recs["v3-fire_1"]
	if fire.Location.Kind != GeometryEndpoints || len(fire.Location.Points) != 3 || fire.Location.FRC != "FRC1" {
		t.Errorf("fire = %+v", fire.Location)
	}
	if w := recs["v3-weather_1"]; w.Location.Kind != GeometryNone || !w.Location.HasAlertC {
		t.Errorf("fog = %+v", w.Location)
	}
	if w := recs["v3-weather_2"]; w.Location.Kind != GeometryDisplay || w.ValidityStatus != "planned" {
		t.Errorf("gusts = %+v", w)
	}
	if s := recs["v3-misc_1"]; s.SpeedLimit != 80 {
		t.Errorf("speed = %+v", s)
	}
}

func TestParseContainerAndLatin1(t *testing.T) {
	pub := parseFile(t, "container_v3.xml")
	if pub.Version != 3 || len(pub.Situations) != 1 || pub.Situations[0].Records[0].SubType != "brokenDownHeavyLorry" {
		t.Errorf("container: %+v", pub)
	}
	pub = parseFile(t, "latin1_v2.xml")
	if pub.Version != 2 || !strings.Contains(pub.Situations[0].Records[0].descriptor(), "Göppingen") ||
		!pub.PublicationTime.Equal(time.Date(2026, 10, 25, 15, 0, 0, 0, time.UTC)) {
		t.Errorf("latin1: %+v", pub.Situations[0].Records[0])
	}
}

func TestParseErrors(t *testing.T) {
	f, err := os.Open("testdata/measured_v2.xml")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err := Parse(f); !errors.Is(err, ErrNotSituationPublication) || !strings.Contains(err.Error(), "MeasuredDataPublication") {
		t.Errorf("measured data: %v", err)
	}
	cases := map[string]string{
		"empty":            ``,
		"no payload":       `<d2LogicalModel xmlns="http://datex2.eu/schema/2/2_0"><exchange/></d2LogicalModel>`,
		"malformed":        `<d2LogicalModel><payloadPublication`,
		"truncated":        `<payload xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="SituationPublication"><situation id="x"><situationRecord>`,
		"bad charset":      `<?xml version="1.0" encoding="EBCDIC"?><d2LogicalModel/>`,
		"bad updateMethod": `<d2LogicalModel><exchange><updateMethod><x></updateMethod></exchange></d2LogicalModel>`,
	}
	for name, doc := range cases {
		if _, err := Parse(strings.NewReader(doc)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestVersionDetection(t *testing.T) {
	cases := []struct {
		doc  string
		want int
	}{
		{`<payloadPublication xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="SituationPublication"/>`, 2},
		{`<payload xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="SituationPublication"/>`, 3},
		{`<x modelBaseVersion="3"><payloadPublication xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="SituationPublication"/></x>`, 3},
		{`<x><payload xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" modelBaseVersion="2" xsi:type="SituationPublication"/></x>`, 2},
	}
	for _, c := range cases {
		pub, err := Parse(strings.NewReader(c.doc))
		if err != nil || pub.Version != c.want {
			t.Errorf("%s: %v, %v", c.doc, pub, err)
		}
	}
}

func TestParseTime(t *testing.T) {
	cases := map[string]time.Time{
		"2026-10-25T16:17:33.083596596Z":    time.Date(2026, 10, 25, 16, 17, 33, 83596596, time.UTC),
		"2024-12-20T11:34:48.847+01:00":     time.Date(2024, 12, 20, 10, 34, 48, 847000000, time.UTC),
		"2026-10-14T00:00:00":               time.Date(2026, 10, 14, 0, 0, 0, 0, time.UTC),
		" 2026-10-20T07:04:07.4209557+0200": time.Date(2026, 10, 20, 5, 4, 7, 420955700, time.UTC),
	}
	for in, want := range cases {
		if got, ok := parseTime(in); !ok || !got.Equal(want) {
			t.Errorf("parseTime(%q) = %v, %v", in, got, ok)
		}
	}
	for _, in := range []string{"", "yesterday", "2026-13-01T00:00:00Z"} {
		if _, ok := parseTime(in); ok {
			t.Errorf("parseTime(%q) accepted", in)
		}
	}
}

// latLonToUTM is the forward projection (Snyder, p. 61), used to check the inverse.
func latLonToUTM(zone int, lat, lon float64) (northing, easting float64) {
	const a, f, k0 = 6378137.0, 1 / 298.257222101, 0.9996
	e2 := f * (2 - f)
	ep2 := e2 / (1 - e2)
	phi := lat * math.Pi / 180
	lam := (lon - float64(zone*6-183)) * math.Pi / 180
	n := a / math.Sqrt(1-e2*math.Sin(phi)*math.Sin(phi))
	tt := math.Tan(phi) * math.Tan(phi)
	c := ep2 * math.Cos(phi) * math.Cos(phi)
	aa := lam * math.Cos(phi)
	m := a * ((1-e2/4-3*e2*e2/64-5*e2*e2*e2/256)*phi - (3*e2/8+3*e2*e2/32+45*e2*e2*e2/1024)*math.Sin(2*phi) +
		(15*e2*e2/256+45*e2*e2*e2/1024)*math.Sin(4*phi) - (35*e2*e2*e2/3072)*math.Sin(6*phi))
	easting = 500000 + k0*n*(aa+(1-tt+c)*math.Pow(aa, 3)/6+(5-18*tt+tt*tt+72*c-58*ep2)*math.Pow(aa, 5)/120)
	northing = k0 * (m + n*math.Tan(phi)*(aa*aa/2+(5-tt+9*c+4*c*c)*math.Pow(aa, 4)/24+(61-58*tt+tt*tt+600*c-330*ep2)*math.Pow(aa, 6)/720))
	return northing, easting
}

func TestUTM(t *testing.T) {
	// On the central meridian the easting is 500 km.
	if p := utmToLatLon(32, 0, 500000); math.Abs(p.Lat) > 1e-9 || math.Abs(p.Lon-9) > 1e-9 {
		t.Errorf("origin = %v", p)
	}
	for _, c := range []struct {
		zone     int
		lat, lon float64
	}{{32, 51.60695, 7.76862}, {32, 47.5, 6.0}, {32, 54.8, 11.9}, {33, 52.5163, 13.3777}, {33, 50.9, 14.9}} {
		n, e := latLonToUTM(c.zone, c.lat, c.lon)
		p := utmToLatLon(c.zone, n, e)
		if math.Abs(p.Lat-c.lat) > 1e-6 || math.Abs(p.Lon-c.lon) > 1e-6 {
			t.Errorf("round trip %v -> %.2f %.2f -> %v", c, n, e, p)
		}
	}
}

func TestGeometryHelpers(t *testing.T) {
	if pts := gmlPoints(&node{children: []*node{{name: "posList", text: "1 2 x 4"}}}); pts != nil {
		t.Errorf("bad posList = %v", pts)
	}
	if pts := gmlPoints(&node{children: []*node{{name: "coordinates", text: "1,2 3"}}}); pts != nil {
		t.Errorf("short tuple = %v", pts)
	}
	if pts := gmlPoints(&node{children: []*node{{name: "coordinates", text: "1,a"}}}); pts != nil {
		t.Errorf("bad tuple = %v", pts)
	}
	// "lon lat" order in a posList is detected when the first value cannot be a latitude.
	if pts := gmlPoints(&node{children: []*node{{name: "posList", text: "120.5 45.1"}}}); len(pts) != 1 || pts[0].Lat != 45.1 {
		t.Errorf("swapped posList = %v", pts)
	}
	if c := coordinate(&node{children: []*node{{name: "latitude", text: "x"}, {name: "longitude", text: "1"}}}); c != nil {
		t.Errorf("bad coordinate = %v", c)
	}
	if pts := tpegLinear(&node{children: []*node{{name: "from"}}}); pts != nil {
		t.Errorf("tpeg without points = %v", pts)
	}
	l := Location{}
	if setPoints(&l, GeometryPoint, []traff.LatLon{{Lat: 0, Lon: 0}, {Lat: 100, Lon: 0}}) {
		t.Error("invalid points accepted")
	}
	var n *node
	if n.attr("x") != "" || n.child("x") != nil || n.childrenNamed("x") != nil || n.find("x") != nil || n.findAll("x") != nil || n.has("x") || n.multilingual() != "" {
		t.Error("nil node helpers")
	}
	ml := &node{children: []*node{{name: "values", children: []*node{{name: "value", attrs: map[string]string{"lang": "en"}}, {name: "value", text: "Text"}}}}}
	if ml.multilingual() != "Text" {
		t.Errorf("multilingual fallback = %q", ml.multilingual())
	}
	empty := &node{children: []*node{{name: "value"}}}
	if empty.multilingual() != "" {
		t.Error("empty multilingual")
	}
}
