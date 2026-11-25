// Package venues builds indoor venues (airport terminals, main stations, malls, ...) from the
// OpenStreetMap Simple Indoor Tagging features of an osmium GeoJSONSeq export:
//
//  1. classify: every exported object becomes a feature (with a rendering kind and its levels),
//     a naming container (named terminal/station/mall/building/aerodrome) and/or a station node,
//     or is ignored.
//  2. cluster: indoor areas (indoor=room|corridor|area|level, the "seeds") whose bounding boxes
//     are at most ClusterGapM apart form one venue (union-find over a grid).
//  3. split: huge clusters (and clusters spanning several terminals) are split by the enclosing
//     terminal/building objects.
//  4. attach: walls, lines, points and level-tagged POIs within AttachM of a venue's indoor
//     areas join that venue; everything else is ignored.
//  5. filter (thresholds), name, and encode (simplified geometry, encoded polylines).
//
// See README.md ("Datenaufbereitung") for the rules and the thresholds.
package venues

import (
	"cmp"
	"math"
	"slices"
	"strconv"

	"github.com/WayneNani/better-maps/services/indoor-server/internal/geo"
	"github.com/WayneNani/better-maps/services/indoor-server/internal/levels"
)

// Options are the tuning knobs of the builder; DefaultOptions holds the production values.
type Options struct {
	ClusterGapM float64 // indoor areas at most this far apart (bbox gap) belong to one venue
	AttachM     float64 // other features within this distance of a venue's indoor areas join it
	SimplifyM   float64 // Douglas-Peucker tolerance
	DoorSnapM   float64 // a door without level on an indoor outline (within this) gets its levels
	// A cluster wider/higher than SplitExtentM or with more than SplitSeeds indoor areas is split
	// by the enclosing terminal/station/mall/building objects. A cluster in which at least two
	// terminals each hold TerminalShare of the indoor areas is split by terminals.
	SplitExtentM  float64
	SplitSeeds    int
	TerminalShare float64
	// Indoor areas outside every splitting object join the nearest part within SplitJoinM;
	// the others form parts of their own.
	SplitJoinM float64
	// NameShare: a container names a venue if it contains more than this share of its indoor
	// areas (by the centre of their bounding boxes).
	NameShare float64
	// Thresholds: a venue is kept if it has MinIndoorAreas indoor areas with MinRoomAreaM2 of
	// room/corridor/area surface together, or MinLevels levels with MinFeaturesPerLevel features
	// each.
	MinIndoorAreas      int
	MinRoomAreaM2       float64
	MinLevels           int
	MinFeaturesPerLevel int
}

// Production values (README "Datenaufbereitung").
const (
	DefaultClusterGapM         = 30
	DefaultAttachM             = 20
	DefaultSimplifyM           = 0.15
	DefaultDoorSnapM           = 0.5
	DefaultSplitExtentM        = 3000
	DefaultSplitSeeds          = 40000
	DefaultTerminalShare       = 0.10
	DefaultSplitJoinM          = 50
	DefaultNameShare           = 0.5
	DefaultMinIndoorAreas      = 8
	DefaultMinRoomAreaM2       = 1500
	DefaultMinLevels           = 2
	DefaultMinFeaturesPerLevel = 4
)

// DefaultOptions returns the production values.
func DefaultOptions() Options {
	return Options{
		ClusterGapM: DefaultClusterGapM, AttachM: DefaultAttachM, SimplifyM: DefaultSimplifyM, DoorSnapM: DefaultDoorSnapM,
		SplitExtentM: DefaultSplitExtentM, SplitSeeds: DefaultSplitSeeds, TerminalShare: DefaultTerminalShare,
		SplitJoinM: DefaultSplitJoinM, NameShare: DefaultNameShare,
		MinIndoorAreas: DefaultMinIndoorAreas, MinRoomAreaM2: DefaultMinRoomAreaM2,
		MinLevels: DefaultMinLevels, MinFeaturesPerLevel: DefaultMinFeaturesPerLevel,
	}
}

// Stats counts what happened during a build.
type Stats struct {
	Read              int // features read
	Features          int // classified as indoor features
	Seeds             int // of which indoor areas
	BadLevel          int // dropped: unparseable level/repeat_on
	DuplicateLines    int // dropped: closed way exported as line and area
	Containers        int // naming objects
	Stations          int // station nodes
	Clusters          int // clusters of indoor areas
	SplitClusters     int // clusters that were split
	Unattached        int // features outside every venue
	DoorsWithoutLevel int // doors/entrances without level not on an indoor outline (dropped)
	Venues            int // venues kept
	SkippedSmall      int // venues below the thresholds
	SkippedFeatures   int // features of those venues
	VenueFeatures     int // features in the kept venues
}

// Builder collects features (Add) and builds the venues (Build).
type Builder struct {
	opt        Options
	features   []*Feature
	containers []*container
	stations   []*stationNode
	Stats      Stats
}

// NewBuilder returns a builder with the given options.
func NewBuilder(opt Options) *Builder { return &Builder{opt: opt} }

// Add classifies one raw feature.
func (b *Builder) Add(r *Raw) {
	b.Stats.Read++
	res := classify(r)
	if res.badLevel {
		b.Stats.BadLevel++
	}
	if res.feature != nil {
		b.features = append(b.features, res.feature)
		b.Stats.Features++
		if res.feature.Seed {
			b.Stats.Seeds++
		}
	}
	if res.container != nil {
		b.containers = append(b.containers, res.container)
		b.Stats.Containers++
	}
	if res.station != nil {
		b.stations = append(b.stations, res.station)
		b.Stats.Stations++
	}
}

// Venue is a built venue.
type Venue struct {
	ID           string
	Name         string
	Kind         string
	BBox         geo.BBox
	Levels       []Level
	DefaultLevel float64
	Features     []*Feature

	seeds      []*Feature
	seedBBox   geo.BBox
	roomAreaM2 float64
}

// Level is one entry of a venue's level list.
type Level struct {
	Level float64 `json:"level"`
	Label string  `json:"label"`
}

// Build clusters, splits, attaches, filters and names the venues. The result is sorted by
// number of features (descending), then id.
func (b *Builder) Build() []*Venue {
	b.dropDuplicateLines()
	var seeds, others []*Feature
	for _, f := range b.features {
		if f.Seed {
			seeds = append(seeds, f)
		} else {
			others = append(others, f)
		}
	}
	cgrid := newGrid(0.01)
	for i, c := range b.containers {
		cgrid.insert(i, c.bbox)
	}

	var groups [][]*Feature
	for _, cl := range cluster(seeds, b.opt.ClusterGapM) {
		b.Stats.Clusters++
		parts := b.split(cl, cgrid)
		if len(parts) > 1 {
			b.Stats.SplitClusters++
		}
		groups = append(groups, parts...)
	}
	vs := make([]*Venue, len(groups))
	for i, g := range groups {
		v := &Venue{seeds: g, seedBBox: geo.EmptyBBox()}
		for _, f := range g {
			v.seedBBox = v.seedBBox.Union(f.BBox)
			if f.Indoor == "room" || f.Indoor == "corridor" || f.Indoor == "area" {
				v.roomAreaM2 += f.AreaM2
			}
		}
		v.Features = append(v.Features, g...)
		vs[i] = v
	}
	b.attach(vs, others)

	var kept []*Venue
	for _, v := range vs {
		b.levelsFromOutlines(v)
		if !b.bigEnough(v) {
			b.Stats.SkippedSmall++
			b.Stats.SkippedFeatures += len(v.Features)
			continue
		}
		kept = append(kept, v)
	}
	// Larger venues pick their name first (a container shared by two venues names the larger).
	slices.SortStableFunc(kept, func(a, c *Venue) int {
		if n := cmp.Compare(len(c.seeds), len(a.seeds)); n != 0 {
			return n
		}
		return compareIDs(smallestID(a.seeds), smallestID(c.seeds))
	})
	used := map[string]bool{}
	for _, v := range kept {
		b.name(v, cgrid, used)
		finish(v)
		b.Stats.VenueFeatures += len(v.Features)
	}
	b.Stats.Venues = len(kept)
	slices.SortStableFunc(kept, func(a, c *Venue) int {
		if n := cmp.Compare(len(c.Features), len(a.Features)); n != 0 {
			return n
		}
		return cmp.Compare(a.ID, c.ID)
	})
	return kept
}

// dropDuplicateLines removes lines of closed ways that osmium also exported as areas.
func (b *Builder) dropDuplicateLines() {
	areas := map[string]bool{}
	for _, f := range b.features {
		if f.T == GeomArea {
			areas[f.ID] = true
		}
	}
	out := b.features[:0]
	for _, f := range b.features {
		if f.T == GeomLine && areas[f.ID] {
			b.Stats.DuplicateLines++
			b.Stats.Features--
			continue
		}
		out = append(out, f)
	}
	b.features = out
}

// cluster groups features whose bounding boxes are at most gapM apart.
func cluster(fs []*Feature, gapM float64) [][]*Feature {
	g := newGrid(0.002)
	uf := newUnionFind(len(fs))
	exp := make([]geo.BBox, len(fs))
	for i, f := range fs {
		exp[i] = f.BBox.Expand(gapM / 2)
		g.query(exp[i], func(j int) {
			if exp[i].Intersects(exp[j]) {
				uf.union(i, j)
			}
		})
		g.insert(i, exp[i])
	}
	byRoot := map[int][]*Feature{}
	var roots []int
	for i, f := range fs {
		r := uf.find(i)
		if _, ok := byRoot[r]; !ok {
			roots = append(roots, r)
		}
		byRoot[r] = append(byRoot[r], f)
	}
	out := make([][]*Feature, len(roots))
	for i, r := range roots {
		out[i] = byRoot[r]
	}
	return out
}

func bboxOfFeatures(fs []*Feature) geo.BBox {
	bb := geo.EmptyBBox()
	for _, f := range fs {
		bb = bb.Union(f.BBox)
	}
	return bb
}

// candidates returns the containers whose box intersects bb.
func (b *Builder) candidates(cgrid *grid, bb geo.BBox) []*container {
	var out []*container
	cgrid.query(bb, func(i int) {
		if b.containers[i].bbox.Intersects(bb) {
			out = append(out, b.containers[i])
		}
	})
	return out
}

// split divides a cluster by the terminal/station/mall/building objects around its indoor areas
// if it is huge or spans several terminals; otherwise it returns the cluster unchanged.
func (b *Builder) split(cl []*Feature, cgrid *grid) [][]*Feature {
	bb := bboxOfFeatures(cl)
	cands := b.candidates(cgrid, bb)
	inside := make([][]*container, len(cl)) // containers holding each seed's centre
	share := map[*container]int{}
	for i, f := range cl {
		c := f.BBox.Center()
		for _, k := range cands {
			if k.class != classAerodrome && k.contains(c) {
				inside[i] = append(inside[i], k)
				share[k]++
			}
		}
	}
	w, h := bb.SizeMeters()
	maxClass := -1
	if math.Max(w, h) > b.opt.SplitExtentM || len(cl) > b.opt.SplitSeeds {
		maxClass = classBuilding
	} else {
		terminals := 0
		for k, n := range share {
			if k.class == classTerminal && float64(n) >= b.opt.TerminalShare*float64(len(cl)) {
				terminals++
			}
		}
		if terminals >= 2 {
			maxClass = classTerminal
		}
	}
	if maxClass < 0 {
		return [][]*Feature{cl}
	}

	parts := map[*container][]*Feature{}
	var order []*container
	var rest []*Feature
	for i, f := range cl {
		var best *container
		for _, k := range inside[i] {
			if k.class > maxClass {
				continue
			}
			// The outermost object of the most important class: a terminal, not the shop in it.
			if best == nil || k.class < best.class || (k.class == best.class && k.areaM2 > best.areaM2) {
				best = k
			}
		}
		if best == nil {
			rest = append(rest, f)
			continue
		}
		if _, ok := parts[best]; !ok {
			order = append(order, best)
		}
		parts[best] = append(parts[best], f)
	}
	out := make([][]*Feature, 0, len(order))
	boxes := make([]geo.BBox, 0, len(order))
	for _, k := range order {
		out = append(out, parts[k])
		boxes = append(boxes, bboxOfFeatures(parts[k]))
	}
	var left []*Feature
	for _, f := range rest {
		c := f.BBox.Center()
		best, bestD := -1, math.Inf(1)
		for i, bx := range boxes {
			if d := bx.DistanceMeters(c); d < bestD {
				best, bestD = i, d
			}
		}
		if best >= 0 && bestD <= b.opt.SplitJoinM {
			out[best] = append(out[best], f)
		} else {
			left = append(left, f)
		}
	}
	out = append(out, cluster(left, b.opt.ClusterGapM)...)
	if len(out) < 2 {
		return [][]*Feature{cl}
	}
	return out
}

// attach adds every non-seed feature to the venue whose indoor areas are nearest (within AttachM).
func (b *Builder) attach(vs []*Venue, others []*Feature) {
	g := newGrid(0.005)
	exp := make([]geo.BBox, len(vs))
	for i, v := range vs {
		exp[i] = v.seedBBox.Expand(b.opt.AttachM)
		g.insert(i, exp[i])
	}
	for _, f := range others {
		c := f.BBox.Center()
		best, bestD, bestA := -1, math.Inf(1), math.Inf(1)
		g.query(geo.BBox{MinLon: c.Lon, MinLat: c.Lat, MaxLon: c.Lon, MaxLat: c.Lat}, func(i int) {
			if !exp[i].Contains(c) {
				return
			}
			d := vs[i].seedBBox.DistanceMeters(c)
			a := vs[i].seedBBox.AreaM2()
			if d < bestD || (d == bestD && a < bestA) {
				best, bestD, bestA = i, d, a
			}
		})
		if best < 0 {
			b.Stats.Unattached++
			continue
		}
		vs[best].Features = append(vs[best].Features, f)
	}
}

// levelsFromOutlines gives doors and entrances without level the levels of the indoor areas whose
// outline they lie on (a door node is usually a vertex of the room's way); the others are dropped.
func (b *Builder) levelsFromOutlines(v *Venue) {
	var g *grid
	out := v.Features[:0]
	for _, f := range v.Features {
		if !f.LevelFromOutline {
			out = append(out, f)
			continue
		}
		if g == nil {
			g = newGrid(0.0005)
			for i, s := range v.seeds {
				g.insert(i, s.BBox)
			}
		}
		// Rooms, corridors and areas first; the outline of a whole level only if no room is
		// there (level outlines of several floors often share the same ways).
		var lv, lvOutline []float64
		pb := geo.BBox{MinLon: f.Point.Lon, MinLat: f.Point.Lat, MaxLon: f.Point.Lon, MaxLat: f.Point.Lat}.Expand(b.opt.DoorSnapM)
		g.query(pb, func(i int) {
			s := v.seeds[i]
			if !s.BBox.Intersects(pb) {
				return
			}
			for _, poly := range s.Polys {
				for _, r := range poly {
					if geo.DistanceToRing(f.Point, r) <= b.opt.DoorSnapM {
						if s.Indoor == "level" {
							lvOutline = levels.Union(lvOutline, s.Levels)
						} else {
							lv = levels.Union(lv, s.Levels)
						}
						return
					}
				}
			}
		})
		if len(lv) == 0 {
			lv = lvOutline
		}
		if len(lv) == 0 {
			b.Stats.DoorsWithoutLevel++
			continue
		}
		f.Levels = lv
		f.LevelFromOutline = false
		out = append(out, f)
	}
	v.Features = out
}

func (b *Builder) bigEnough(v *Venue) bool {
	if len(v.seeds) >= b.opt.MinIndoorAreas && v.roomAreaM2 >= b.opt.MinRoomAreaM2 {
		return true
	}
	perLevel := map[float64]int{}
	for _, f := range v.Features {
		for _, l := range f.Levels {
			perLevel[l]++
		}
	}
	n := 0
	for _, c := range perLevel {
		if c >= b.opt.MinFeaturesPerLevel {
			n++
		}
	}
	return n >= b.opt.MinLevels
}

// name picks the venue's name, kind and id: the most important container (terminal, station,
// mall, building, aerodrome; the smallest of a class) holding more than NameShare of the indoor
// areas; else a station node in the venue; else the most common name of its indoor=level areas.
func (b *Builder) name(v *Venue, cgrid *grid, used map[string]bool) {
	var best *container
	for _, k := range b.candidates(cgrid, v.seedBBox) {
		n := 0
		for _, s := range v.seeds {
			if k.contains(s.BBox.Center()) {
				n++
			}
		}
		if float64(n) <= b.opt.NameShare*float64(len(v.seeds)) {
			continue
		}
		if best == nil || k.class < best.class || (k.class == best.class && k.areaM2 < best.areaM2) ||
			(k.class == best.class && k.areaM2 == best.areaM2 && compareIDs(k.id, best.id) < 0) {
			best = k
		}
	}
	id := ""
	switch {
	case best != nil:
		v.Name, v.Kind, id = best.name, classKind[best.class], best.id
	default:
		area := v.seedBBox.Expand(b.opt.AttachM)
		center := v.seedBBox.Center()
		var st *stationNode
		bestD := math.Inf(1)
		for _, s := range b.stations {
			if !area.Contains(s.pt) {
				continue
			}
			bx := geo.BBox{MinLon: s.pt.Lon, MinLat: s.pt.Lat, MaxLon: s.pt.Lon, MaxLat: s.pt.Lat}
			if d := bx.DistanceMeters(center); st == nil || d < bestD || (d == bestD && compareIDs(s.id, st.id) < 0) {
				st, bestD = s, d
			}
		}
		if st != nil {
			v.Name, v.Kind, id = st.name, "station", st.id
		} else {
			v.Kind = "other"
			counts := map[string]int{}
			for _, s := range v.seeds {
				if s.LevelName != "" {
					counts[s.LevelName]++
				}
			}
			for n, c := range counts {
				if c > counts[v.Name] || (c == counts[v.Name] && n < v.Name) {
					v.Name = n
				}
			}
		}
	}
	if id == "" || used[id] || !validID(id) {
		id = "c" + smallestID(v.seeds)
	}
	used[id] = true
	v.ID = id
}

// finish computes levels, default level and bbox and sorts the features into drawing order.
func finish(v *Venue) {
	perLevel := map[float64]int{}
	labels := map[float64]map[string]int{}
	v.BBox = geo.EmptyBBox()
	for _, f := range v.Features {
		v.BBox = v.BBox.Union(f.BBox)
		for _, l := range f.Levels {
			perLevel[l]++
		}
		if f.LevelLabel != "" && len(f.Levels) == 1 {
			l := f.Levels[0]
			if labels[l] == nil {
				labels[l] = map[string]int{}
			}
			labels[l][f.LevelLabel]++
		}
	}
	v.Levels = v.Levels[:0]
	for l := range perLevel {
		label := levels.Format(l)
		best := 0
		for s, c := range labels[l] {
			if c > best || (c == best && s < label) {
				label, best = s, c
			}
		}
		v.Levels = append(v.Levels, Level{Level: l, Label: label})
	}
	slices.SortFunc(v.Levels, func(a, b Level) int { return cmp.Compare(a.Level, b.Level) })
	v.DefaultLevel = defaultLevel(perLevel)
	slices.SortStableFunc(v.Features, compareFeatures)
}

// defaultLevel: 0 if the venue has it, else the level with the most features (ties: closest to
// 0, then the upper one).
func defaultLevel(perLevel map[float64]int) float64 {
	if _, ok := perLevel[0]; ok || len(perLevel) == 0 {
		return 0
	}
	best, bestN := math.Inf(1), -1
	for l, n := range perLevel {
		switch {
		case n > bestN,
			n == bestN && math.Abs(l) < math.Abs(best),
			n == bestN && math.Abs(l) == math.Abs(best) && l > best:
			best, bestN = l, n
		}
	}
	return best
}

func compareFeatures(a, b *Feature) int {
	geomOrder := func(t GeomKind) int {
		switch t {
		case GeomArea:
			return 0
		case GeomLine:
			return 1
		}
		return 2
	}
	if n := cmp.Compare(geomOrder(a.T), geomOrder(b.T)); n != 0 {
		return n
	}
	if n := cmp.Compare(kindRank[a.T][a.Kind], kindRank[b.T][b.Kind]); n != 0 {
		return n
	}
	la, lb := math.Inf(1), math.Inf(1)
	if len(a.Levels) > 0 {
		la = a.Levels[0]
	}
	if len(b.Levels) > 0 {
		lb = b.Levels[0]
	}
	if n := cmp.Compare(la, lb); n != 0 {
		return n
	}
	return compareIDs(a.ID, b.ID)
}

// compareIDs orders OSM ids: nodes, ways, relations, each by number.
func compareIDs(a, b string) int {
	ta, na := splitID(a)
	tb, nb := splitID(b)
	if n := cmp.Compare(ta, tb); n != 0 {
		return n
	}
	return cmp.Compare(na, nb)
}

func splitID(s string) (int, int64) {
	if s == "" {
		return 9, 0
	}
	n, _ := strconv.ParseInt(s[1:], 10, 64)
	switch s[0] {
	case 'n':
		return 0, n
	case 'w':
		return 1, n
	case 'r':
		return 2, n
	}
	return 3, n
}

func smallestID(fs []*Feature) string {
	best := ""
	for _, f := range fs {
		if best == "" || compareIDs(f.ID, best) < 0 {
			best = f.ID
		}
	}
	return best
}

// validID reports whether id is usable as venue id: [A-Za-z0-9_-], 1..40 characters.
func validID(id string) bool {
	if id == "" || len(id) > 40 {
		return false
	}
	for _, c := range id {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

// ValidID is exported for the gateway (same rule as the ids written by the importer).
func ValidID(id string) bool { return validID(id) }
