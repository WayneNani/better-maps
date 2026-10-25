package datex2

import (
	"bufio"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"math"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// ErrNotSituationPublication is returned for well-formed DATEX II documents that do not carry a
// SituationPublication (e.g. MeasuredDataPublication).
var ErrNotSituationPublication = errors.New("datex2: document is not a SituationPublication")

// Parse reads a DATEX II v2 (d2LogicalModel/payloadPublication) or v3 (payload, possibly wrapped
// in a messageContainer) document. Situations are decoded one at a time, so large publications
// do not have to fit into memory as a whole.
func Parse(r io.Reader) (*Publication, error) {
	d := xml.NewDecoder(r)
	d.CharsetReader = charsetReader
	pub := &Publication{}
	depth := 0
	payloadDepth := -1
	sawPayload := false
	for {
		tok, err := d.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("datex2: %w", err)
		}
		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			if pub.Version == 0 {
				pub.Version = versionOf(t)
			}
			local := t.Name.Local
			switch {
			case payloadDepth < 0 && (local == "payloadPublication" || local == "payload"):
				typ := stripPrefix(attrValue(t, "type"))
				if typ != "SituationPublication" {
					return nil, fmt.Errorf("%w (payload type %q)", ErrNotSituationPublication, typ)
				}
				if v := versionOf(t); v != 0 {
					pub.Version = v
				}
				if pub.Version == 0 {
					pub.Version = 2
					if local == "payload" {
						pub.Version = 3
					}
				}
				payloadDepth = depth
				sawPayload = true
			case payloadDepth < 0 && local == "updateMethod":
				var s string
				if err := d.DecodeElement(&s, &t); err != nil {
					return nil, fmt.Errorf("datex2: %w", err)
				}
				pub.UpdateMethod = strings.TrimSpace(s)
				depth--
			case payloadDepth >= 0 && depth == payloadDepth+1:
				var n node
				if err := d.DecodeElement(&n, &t); err != nil {
					return nil, fmt.Errorf("datex2: %w", err)
				}
				depth--
				switch local {
				case "publicationTime":
					pub.PublicationTime, _ = parseTime(n.text)
				case "publicationCreator":
					pub.Country = n.textAt("country")
					pub.NationalIdentifier = n.textAt("nationalIdentifier")
				case "situation":
					pub.Situations = append(pub.Situations, situationFrom(&n))
				}
			}
		case xml.EndElement:
			if depth == payloadDepth {
				payloadDepth = -1
			}
			depth--
		}
	}
	if !sawPayload {
		return nil, ErrNotSituationPublication
	}
	return pub, nil
}

func attrValue(t xml.StartElement, local string) string {
	for _, a := range t.Attr {
		if a.Name.Local == local {
			return a.Value
		}
	}
	return ""
}

// versionOf derives the DATEX II major version from the namespace or modelBaseVersion of an
// element; 0 means unknown.
func versionOf(t xml.StartElement) int {
	ns := t.Name.Space
	switch {
	case strings.Contains(ns, "/schema/2/"):
		return 2
	case strings.Contains(ns, "/schema/3/"):
		return 3
	}
	if v, err := strconv.Atoi(strings.TrimSpace(attrValue(t, "modelBaseVersion"))); err == nil && (v == 2 || v == 3) {
		return v
	}
	return 0
}

// charsetReader supports the single-byte encodings some older DATEX II producers use.
func charsetReader(charset string, input io.Reader) (io.Reader, error) {
	switch strings.ToLower(charset) {
	case "iso-8859-1", "iso8859-1", "latin1", "latin-1", "windows-1252", "cp1252":
		return &latin1Reader{r: bufio.NewReader(input)}, nil
	}
	return nil, fmt.Errorf("datex2: unsupported charset %q", charset)
}

// latin1Reader converts ISO 8859-1 to UTF-8.
type latin1Reader struct {
	r   *bufio.Reader
	buf []byte
}

func (l *latin1Reader) Read(p []byte) (int, error) {
	for len(l.buf) < len(p) {
		b, err := l.r.ReadByte()
		if err != nil {
			if len(l.buf) == 0 {
				return 0, err
			}
			break
		}
		l.buf = utf8.AppendRune(l.buf, rune(b))
	}
	n := copy(p, l.buf)
	l.buf = l.buf[n:]
	return n, nil
}

// parseTime parses DATEX II date-time values (xs:dateTime). Values without time zone are taken
// as UTC.
func parseTime(s string) (time.Time, bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}, false
	}
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05.999999999", "2006-01-02T15:04:05.999999999Z0700"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t.UTC(), true
		}
	}
	return time.Time{}, false
}

// Elements carrying the type-specific enumeration of a situation record.
var subTypeElements = []string{
	"accidentType", "abnormalTrafficType", "roadOrCarriagewayOrLaneManagementType", "roadMaintenanceType",
	"constructionWorkType", "vehicleObstructionType", "obstructionType", "animalPresenceType",
	"environmentalObstructionType", "infrastructureDamageType", "poorEnvironmentType",
	"nonWeatherRelatedRoadConditionType", "weatherRelatedRoadConditionType", "speedManagementType",
	"authorityOperationType",
}

func situationFrom(n *node) Situation {
	s := Situation{ID: strings.TrimSpace(n.attr("id")), Version: n.attr("version"), Urgency: n.textAt("headerInformation", "urgency")}
	for _, rn := range n.childrenNamed("situationRecord") {
		s.Records = append(s.Records, recordFrom(rn))
	}
	return s
}

func recordFrom(n *node) Record {
	r := Record{
		ID:          strings.TrimSpace(n.attr("id")),
		Version:     n.attr("version"),
		Type:        n.xsiType(),
		Probability: n.textAt("probabilityOfOccurrence"),
	}
	for _, name := range subTypeElements {
		if c := n.child(name); c != nil {
			r.SubType = c.text
			break
		}
	}
	if v := n.child("validity"); v != nil {
		r.ValidityStatus = v.textAt("validityStatus")
		spec := v.child("validityTimeSpecification")
		r.OverallStart, _ = parseTime(spec.textAt("overallStartTime"))
		r.OverallEnd, _ = parseTime(spec.textAt("overallEndTime"))
		for _, p := range spec.childrenNamed("validPeriod") {
			var per Period
			per.Start, _ = parseTime(p.textAt("startOfPeriod"))
			per.End, _ = parseTime(p.textAt("endOfPeriod"))
			r.Periods = append(r.Periods, per)
		}
	}
	if lc := n.path("management", "lifeCycleManagement"); lc != nil {
		r.Cancelled = lc.textAt("cancel") == "true" || lc.textAt("end") == "true"
	}
	if imp := n.child("impact"); imp != nil {
		r.LanesRestricted, _ = strconv.Atoi(imp.textAt("numberOfLanesRestricted"))
		if d, err := strconv.ParseFloat(imp.textAt("delays", "delayTimeValue"), 64); err == nil && d > 0 && !math.IsInf(d, 0) {
			r.DelaySeconds = d
		}
	}
	if v, err := strconv.ParseFloat(n.textAt("temporarySpeedLimit"), 64); err == nil && v > 0 && v < 200 {
		r.SpeedLimit = int(math.Round(v))
	}
	for _, c := range n.childrenNamed("generalPublicComment") {
		cm := Comment{Type: c.textAt("commentType"), Text: c.child("comment").multilingual()}
		if cm.Type == "" {
			cm.Type = c.find("commentType2").textAt()
		}
		if cm.Text != "" {
			r.Comments = append(r.Comments, cm)
		}
	}
	loc := n.child("groupOfLocations") // v2
	if loc == nil {
		loc = n.child("locationReference") // v3
	}
	if loc != nil {
		r.Location = locationFrom(loc)
	}
	return r
}

// Member elements of location groups and itineraries (v2 and v3).
var memberElements = []string{"locationContainedInGroup", "locationContainedInItinerary"}

// locationFrom extracts the geometry of a location. Groups and itineraries contribute the
// geometry of their first member that has coordinates; road numbers and unresolvable references
// are collected from all members.
func locationFrom(n *node) Location {
	var members []*node
	for _, name := range memberElements {
		for _, m := range n.childrenNamed(name) {
			if inner := m.child("location"); inner != nil { // itinerary (v2/v3) wraps the location
				m = inner
			}
			members = append(members, m)
		}
	}
	if len(members) == 0 {
		l := singleLocation(n)
		l.Area = n.xsiType() == "Area" || n.xsiType() == "AreaLocation"
		return l
	}
	var res Location
	for _, m := range members {
		l := singleLocation(m)
		if res.Kind == GeometryNone || (l.Kind != GeometryNone && l.Kind < res.Kind) {
			res.Points, res.Kind, res.BothDirections = l.Points, l.Kind, l.BothDirections
		}
		if res.RoadNumber == "" {
			res.RoadNumber = l.RoadNumber
		}
		if res.RoadName == "" {
			res.RoadName = l.RoadName
		}
		if res.FRC == "" {
			res.FRC = l.FRC
		}
		res.HasAlertC = res.HasAlertC || l.HasAlertC
		res.HasLinearReference = res.HasLinearReference || l.HasLinearReference
	}
	return res
}

func singleLocation(n *node) Location {
	l := Location{
		RoadNumber:         firstText(n.findAll("roadNumber")),
		RoadName:           n.find("roadName").multilingual(),
		FRC:                n.find("openlrFunctionalRoadClass").textAt(),
		HasAlertC:          n.has("alertC"),
		HasLinearReference: n.find("linearWithinLinearElement") != nil || n.find("linearElement") != nil,
	}
	l.BothDirections = n.find("bothDirections").textAt() == "true" ||
		n.find("tpegDirection").textAt() == "bothWays" ||
		n.find("alertCAffectedDirection").textAt() == "both"

	switch {
	case setPoints(&l, GeometryLine, gmlPoints(n.find("gmlLineString"))):
	case setPoints(&l, GeometryLine, linearByCoordinates(n.find("linearByCoordinates"))):
	case setPoints(&l, GeometryLine, linearElementsByPoints(n)):
	case setPoints(&l, GeometryEndpoints, tpegLinear(n.find("tpegLinearLocation"))):
	case setPoints(&l, GeometryEndpoints, openlrLinear(n)):
	case setPoints(&l, GeometryPoint, pointLocation(n)):
	default:
		disp := n.find("locationForDisplay")
		if disp == nil {
			disp = n.find("coordinatesForDisplay")
		}
		setPoints(&l, GeometryDisplay, coordinate(disp))
	}
	return l
}

// setPoints cleans pts (invalid and repeated points are dropped) and stores them in l. It
// reports whether any point was left.
func setPoints(l *Location, kind GeometryKind, pts []traff.LatLon) bool {
	var res []traff.LatLon
	for _, p := range pts {
		if !p.Valid() || (p.Lat == 0 && p.Lon == 0) {
			continue
		}
		if n := len(res); n > 0 && res[n-1] == p {
			continue
		}
		res = append(res, p)
	}
	if len(res) == 0 {
		return false
	}
	l.Points, l.Kind = res, kind
	return true
}

// coordinate reads an element with latitude/longitude children.
func coordinate(n *node) []traff.LatLon {
	if n == nil {
		return nil
	}
	lat, err1 := strconv.ParseFloat(n.textAt("latitude"), 64)
	lon, err2 := strconv.ParseFloat(n.textAt("longitude"), 64)
	if err1 != nil || err2 != nil {
		return nil
	}
	return []traff.LatLon{{Lat: lat, Lon: lon}}
}

// firstText returns the first non-empty text of the nodes.
func firstText(nodes []*node) string {
	for _, n := range nodes {
		if n.text != "" {
			return n.text
		}
	}
	return ""
}

// reUTM matches the reference systems ETRS89 / UTM zone 32N and 33N (EPSG:25832, EPSG:25833),
// which German municipalities use instead of WGS84.
var reUTM = regexp.MustCompile(`2583([23])\b`)

// gmlPoints reads a GML line string. posList holds "lat lon" pairs (EPSG:4326 axis order, as in
// the MDM profile and v3) or "northing easting" in ETRS89/UTM, the older coordinates element
// "lon,lat" tuples (GML 2 x,y order).
func gmlPoints(n *node) []traff.LatLon {
	if n == nil {
		return nil
	}
	var res []traff.LatLon
	if pl := n.child("posList"); pl != nil {
		srs := n.attr("srsName") + " " + n.textAt("srsName") + " " + pl.attr("srsName")
		zone := 0
		if m := reUTM.FindStringSubmatch(srs); m != nil {
			zone = 30 + int(m[1][0]-'0')
		}
		f := strings.Fields(pl.text)
		for i := 0; i+1 < len(f); i += 2 {
			a, err1 := strconv.ParseFloat(f[i], 64)
			b, err2 := strconv.ParseFloat(f[i+1], 64)
			if err1 != nil || err2 != nil {
				return nil
			}
			if zone != 0 {
				res = append(res, utmToLatLon(zone, a, b))
				continue
			}
			res = append(res, orient(a, b))
		}
		return res
	}
	for _, tuple := range strings.Fields(n.textAt("coordinates")) {
		xy := strings.Split(tuple, ",")
		if len(xy) < 2 {
			return nil
		}
		x, err1 := strconv.ParseFloat(xy[0], 64)
		y, err2 := strconv.ParseFloat(xy[1], 64)
		if err1 != nil || err2 != nil {
			return nil
		}
		res = append(res, orient(y, x))
	}
	return res
}

// orient returns (lat, lon), swapping the values if lat is out of range.
func orient(lat, lon float64) traff.LatLon {
	if math.Abs(lat) > 90 && math.Abs(lon) <= 90 {
		lat, lon = lon, lat
	}
	return traff.LatLon{Lat: lat, Lon: lon}
}

func linearByCoordinates(n *node) []traff.LatLon {
	if n == nil {
		return nil
	}
	res := coordinate(n.child("start"))
	for _, im := range n.childrenNamed("intermediate") {
		res = append(res, coordinate(im.child("pointCoordinates"))...)
	}
	return append(res, coordinate(n.child("end"))...)
}

// linearElementsByPoints concatenates the points of LinearElementByPoints elements (MDM
// "ByPoints" method): start, intermediate and end point of each linear element.
func linearElementsByPoints(n *node) []traff.LatLon {
	var res []traff.LatLon
	for _, le := range n.findAll("linearElement") {
		res = append(res, coordinate(le.path("startPointOfLinearElement", "pointCoordinates"))...)
		for _, im := range le.childrenNamed("intermediatePointOnLinearElement") {
			res = append(res, coordinate(im.find("pointCoordinates"))...)
		}
		res = append(res, coordinate(le.path("endPointOfLinearElement", "pointCoordinates"))...)
	}
	if len(res) < 2 {
		return nil
	}
	return res
}

func tpegLinear(n *node) []traff.LatLon {
	if n == nil {
		return nil
	}
	from := coordinate(n.path("from", "pointCoordinates"))
	to := coordinate(n.path("to", "pointCoordinates"))
	if len(from) == 0 || len(to) == 0 {
		return nil
	}
	return append(from, to...)
}

// openlrLinear reads the coordinates of the location reference points of an OpenLR line
// location (first direction only).
func openlrLinear(n *node) []traff.LatLon {
	scope := n.find("firstDirection")
	if scope == nil {
		scope = n.find("openlrLinear")
	}
	if scope == nil {
		return nil
	}
	var res []traff.LatLon
	for _, lrp := range scope.findAll("openlrLocationReferencePoint") {
		res = append(res, coordinate(lrp.child("openlrCoordinate"))...)
	}
	last := coordinate(scope.find("openlrLastLocationReferencePoint").child("openlrCoordinate"))
	if len(res) == 0 || len(last) == 0 {
		return nil
	}
	return append(res, last...)
}

func pointLocation(n *node) []traff.LatLon {
	if p := n.find("pointByCoordinates"); p != nil {
		if c := coordinate(p.child("pointCoordinates")); c != nil {
			return c
		}
	}
	if p := n.find("tpegPointLocation"); p != nil {
		return coordinate(p.find("pointCoordinates"))
	}
	return nil
}
