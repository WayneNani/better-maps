package venues

import (
	"unicode/utf8"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/levels"
)

// Feature is an indoor feature as it ends up in a venue document.
type Feature struct {
	ID     string
	T      GeomKind
	Kind   string
	Name   string
	Ref    string
	Levels []float64 // sorted; empty while LevelFromOutline is pending
	Tags   map[string]string
	Point  geo.Point
	Lines  [][]geo.Point
	Polys  []geo.Polygon
	BBox   geo.BBox

	// Seed: an indoor=room|corridor|area|level area. Seeds form the venues (clustering); all
	// other features are attached to the venue they lie in.
	Seed bool
	// Indoor is the indoor=* value.
	Indoor string
	// LevelLabel is the short label of an indoor=level feature (level:ref, level_ref or name).
	LevelLabel string
	// LevelFromOutline: a door or entrance without level; it gets the levels of the indoor
	// areas whose outline it lies on, or is dropped.
	LevelFromOutline bool
	// AreaM2 of an area feature.
	AreaM2 float64
	// LevelName is the name of an indoor=level feature (venue name of last resort).
	LevelName string
}

// Kinds of the venue document ("k"), in drawing order per geometry type.
var (
	areaKindOrder  = []string{"level", "corridor", "area", "platform", "room", "restricted", "service", "shop", "food", "toilets", "stairs", "elevator", "gate", "wall"}
	lineKindOrder  = []string{"footway", "steps", "escalator", "elevator", "wall"}
	pointKindOrder = []string{"door", "entrance", "toilets", "elevator", "stairs", "escalator", "gate", "ticket", "info", "luggage", "atm", "pharmacy", "food", "shop", "poi"}
)

var kindRank = func() map[GeomKind]map[string]int {
	m := map[GeomKind]map[string]int{}
	for t, order := range map[GeomKind][]string{GeomArea: areaKindOrder, GeomLine: lineKindOrder, GeomPoint: pointKindOrder} {
		m[t] = map[string]int{}
		for i, k := range order {
			m[t][k] = i
		}
	}
	return m
}()

var (
	foodAmenity = set("cafe", "restaurant", "fast_food", "bar", "pub", "ice_cream", "food_court", "biergarten")
	foodShop    = set("bakery", "pastry", "confectionery", "coffee", "deli", "chocolate", "tea")
	// Amenities that are not worth a marker of their own.
	noiseAmenity = set("bench", "waste_basket", "waste_disposal", "recycling", "bicycle_parking", "parking",
		"parking_space", "parking_entrance", "motorcycle_parking", "clock", "grit_bin", "lounger", "vending_machine_slot")
	luggageAmenity = set("luggage_locker", "left_luggage", "baggage_claim")
	footwayHighway = set("footway", "corridor", "path", "pedestrian", "living_street")
	// Tags copied to "tg".
	sheetKeys = []string{"amenity", "shop", "cuisine", "opening_hours", "wheelchair", "operator", "brand", "website",
		"aeroway", "highway", "door", "access", "toilets:wheelchair", "changing_table", "fee", "description"}
)

// maxTagValue bounds the copied tag values (description: 200 characters as in the API spec).
const maxTagValue = 200

func set(vs ...string) map[string]bool {
	m := make(map[string]bool, len(vs))
	for _, v := range vs {
		m[v] = true
	}
	return m
}

func yes(v string) bool { return v != "" && v != "no" }

// classifyResult says what a raw feature is good for; any combination is possible.
type classifyResult struct {
	feature   *Feature
	container *container
	station   *stationNode
	// badLevel: a relevant feature whose level or repeat_on could not be parsed (dropped).
	badLevel bool
}

// classify turns a raw export feature into a venue feature, a naming container and/or a
// station node.
func classify(r *Raw) classifyResult {
	var res classifyResult
	t := r.Tags
	switch r.Kind {
	case GeomArea:
		res.container = asContainer(r)
	case GeomPoint:
		if n := t["name"]; n != "" && (t["railway"] == "station" || t["railway"] == "halt" || t["public_transport"] == "station") {
			res.station = &stationNode{id: r.ID, name: n, pt: r.Point}
		}
	}

	var kind string
	seed := false
	switch r.Kind {
	case GeomArea:
		kind, seed = areaKind(t)
	case GeomLine:
		kind = lineKind(t)
	case GeomPoint:
		kind = pointKind(t)
	}
	if kind == "" {
		return res
	}

	indoor := t["indoor"]
	isIndoor := yes(indoor)
	f := &Feature{ID: r.ID, T: r.Kind, Kind: kind, Seed: seed, Indoor: indoor, Name: clip(t["name"]), Ref: clip(t["ref"])}
	lv, hasLevel := t["level"]
	rep, hasRepeat := t["repeat_on"]
	switch {
	case hasLevel || hasRepeat:
		var a, b []float64
		var err error
		if hasLevel {
			if a, err = levels.Parse(lv); err != nil {
				res.badLevel = true
				return res
			}
		}
		if hasRepeat {
			if b, err = levels.Parse(rep); err != nil {
				res.badLevel = true
				return res
			}
		}
		f.Levels = levels.Union(a, b)
	case isIndoor:
		f.Levels = []float64{0}
	case r.Kind == GeomPoint && (kind == "door" || kind == "entrance"):
		f.LevelFromOutline = true
	default:
		// Not indoor and no level: not part of an indoor map.
		return res
	}

	for _, k := range sheetKeys {
		if v := t[k]; v != "" {
			if f.Tags == nil {
				f.Tags = map[string]string{}
			}
			f.Tags[k] = clip(v)
		}
	}
	if indoor == "level" {
		for _, k := range []string{"level:ref", "level_ref", "name"} {
			if v := t[k]; v != "" && utf8.RuneCountInString(v) <= 8 {
				f.LevelLabel = v
				break
			}
		}
		f.LevelName = t["name"]
	}
	f.Point, f.Lines, f.Polys = r.Point, r.Lines, r.Polys
	f.BBox = bboxOf(r)
	if r.Kind == GeomArea {
		for _, p := range r.Polys {
			f.AreaM2 += geo.PolygonArea(p)
		}
	}
	res.feature = f
	return res
}

func clip(s string) string {
	if utf8.RuneCountInString(s) <= maxTagValue {
		return s
	}
	n := 0
	for i := range s {
		if n == maxTagValue {
			return s[:i]
		}
		n++
	}
	return s
}

func bboxOf(r *Raw) geo.BBox {
	b := geo.EmptyBBox()
	switch r.Kind {
	case GeomPoint:
		b.Extend(r.Point)
	case GeomLine:
		for _, l := range r.Lines {
			for _, p := range l {
				b.Extend(p)
			}
		}
	case GeomArea:
		for _, p := range r.Polys {
			for _, q := range p[0] {
				b.Extend(q)
			}
		}
	}
	return b
}

// areaKind returns the kind of an area and whether it is a seed (indoor room/corridor/area/level).
func areaKind(t map[string]string) (string, bool) {
	switch t["indoor"] {
	case "level":
		return "level", true
	case "corridor":
		return "corridor", true
	case "wall":
		return "wall", false
	case "room", "area":
		if k := areaPOIKind(t); k != "" {
			return k, true
		}
		return t["indoor"], true
	}
	// Not an indoor area: only level-tagged platforms and POI areas (shops mapped as areas
	// in a mall without indoor=room). Whole buildings, terminals, malls and stations are
	// containers, not features.
	if yes(t["building"]) || t["aeroway"] == "terminal" || t["aeroway"] == "aerodrome" ||
		t["shop"] == "mall" || t["railway"] == "station" || t["public_transport"] == "station" {
		return "", false
	}
	if t["railway"] == "platform" || t["public_transport"] == "platform" {
		return "platform", false
	}
	if (t["highway"] == "pedestrian" || t["highway"] == "footway") && (t["level"] != "" || t["repeat_on"] != "") {
		// Walkways of a mall or station mapped as pedestrian areas.
		return "corridor", false
	}
	if k := areaPOIKind(t); k != "" && k != "restricted" {
		return k, false
	}
	return "", false
}

// areaPOIKind is the kind of a room or area with POI tags ("" if it has none).
func areaPOIKind(t map[string]string) string {
	amenity, shop, room := t["amenity"], t["shop"], t["room"]
	switch {
	case amenity == "toilets" || room == "toilets" || room == "toilet":
		return "toilets"
	case t["highway"] == "elevator" || room == "elevator" || t["elevator"] == "yes":
		return "elevator"
	case room == "stairs" || t["stairs"] == "yes" || t["highway"] == "steps":
		return "stairs"
	case t["aeroway"] == "gate":
		return "gate"
	case foodAmenity[amenity] || foodShop[shop]:
		return "food"
	case yes(shop) && shop != "vacant":
		return "shop"
	case t["access"] == "private" || t["access"] == "no":
		return "restricted"
	case (yes(amenity) && !noiseAmenity[amenity]) || yes(t["office"]) || yes(t["healthcare"]) || yes(t["tourism"]) || yes(t["craft"]):
		return "service"
	}
	return ""
}

func lineKind(t map[string]string) string {
	if yes(t["conveying"]) {
		return "escalator"
	}
	switch hw := t["highway"]; {
	case hw == "steps":
		return "steps"
	case hw == "elevator":
		return "elevator"
	case footwayHighway[hw]:
		if yes(t["indoor"]) || t["level"] != "" || t["repeat_on"] != "" {
			return "footway"
		}
		return ""
	}
	switch t["indoor"] {
	case "wall":
		return "wall"
	case "corridor", "yes", "area":
		return "footway"
	}
	return ""
}

func pointKind(t map[string]string) string {
	amenity, shop := t["amenity"], t["shop"]
	switch {
	case amenity == "toilets":
		return "toilets"
	case t["highway"] == "elevator":
		return "elevator"
	case yes(t["conveying"]):
		return "escalator"
	case t["highway"] == "steps" || t["stairs"] == "yes":
		return "stairs"
	case t["aeroway"] == "gate":
		return "gate"
	case shop == "ticket" || amenity == "ticket_validator" || t["vending"] == "public_transport_tickets":
		return "ticket"
	case amenity == "pharmacy" || t["healthcare"] == "pharmacy":
		return "pharmacy"
	case amenity == "atm":
		return "atm"
	case luggageAmenity[amenity]:
		return "luggage"
	case foodAmenity[amenity] || foodShop[shop]:
		return "food"
	case t["tourism"] == "information":
		return "info"
	case yes(shop) && shop != "vacant" && shop != "mall":
		return "shop"
	case yes(t["entrance"]):
		return "entrance"
	case yes(t["door"]) || t["indoor"] == "door":
		return "door"
	case t["railway"] == "station" || t["railway"] == "halt" || t["public_transport"] == "station" ||
		t["public_transport"] == "stop_position" || noiseAmenity[amenity]:
		return ""
	case t["name"] != "" || yes(amenity) || yes(t["office"]) || yes(t["tourism"]) || yes(t["healthcare"]):
		return "poi"
	}
	return ""
}

// Container classes in naming priority (lower wins).
const (
	classTerminal  = iota // aeroway=terminal
	classStation          // building=train_station|transportation, railway|public_transport=station area
	classMall             // shop=mall|department_store
	classBuilding         // any other named building
	classAerodrome        // aeroway=aerodrome
)

var classKind = []string{"airport", "station", "mall", "building", "airport"}

// container is a named area that can give a venue its name (and split huge clusters).
type container struct {
	id     string
	name   string
	class  int
	polys  []geo.Polygon
	bbox   geo.BBox
	areaM2 float64
}

func asContainer(r *Raw) *container {
	t := r.Tags
	name := t["name"]
	if name == "" {
		return nil
	}
	class := -1
	switch {
	case t["aeroway"] == "terminal":
		class = classTerminal
	case t["building"] == "train_station" || t["building"] == "transportation" ||
		t["railway"] == "station" || t["public_transport"] == "station":
		class = classStation
	case t["shop"] == "mall" || t["shop"] == "department_store":
		class = classMall
	case yes(t["building"]):
		class = classBuilding
	case t["aeroway"] == "aerodrome":
		class = classAerodrome
	default:
		return nil
	}
	c := &container{id: r.ID, name: clip(name), class: class, polys: r.Polys, bbox: bboxOf(r)}
	for _, p := range r.Polys {
		c.areaM2 += geo.PolygonArea(p)
	}
	return c
}

func (c *container) contains(p geo.Point) bool {
	if !c.bbox.Contains(p) {
		return false
	}
	for _, poly := range c.polys {
		if geo.PointInPolygon(p, poly) {
			return true
		}
	}
	return false
}

// within reports whether p is at most m metres from the outer outline of c.
func (c *container) within(p geo.Point, m float64) bool {
	for _, poly := range c.polys {
		if geo.DistanceToRing(p, poly[0]) <= m {
			return true
		}
	}
	return false
}

type stationNode struct {
	id   string
	name string
	pt   geo.Point
}
