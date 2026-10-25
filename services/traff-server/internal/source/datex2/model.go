// Package datex2 reads traffic messages in DATEX II format (SituationPublication, versions 2 and
// 3) and converts them into TraFF messages.
//
// German traffic information centres, the Landesmeldestellen of the police and the road
// authorities publish their messages in DATEX II, either directly (e.g. MobiData BW) or through
// the national access point Mobilithek. The parser is namespace-agnostic: it works on local
// element names, so that the same code reads v2 (one namespace) and v3 (sit:, loc:, com:, ...)
// documents, including the German MDM profile extensions.
package datex2

import (
	"time"

	"github.com/WayneNani/better-maps/services/traff-server/internal/traff"
)

// Publication is a parsed SituationPublication.
type Publication struct {
	// Version is the DATEX II major version (2 or 3).
	Version int
	// Country and NationalIdentifier identify the publication creator.
	Country            string
	NationalIdentifier string
	PublicationTime    time.Time
	// UpdateMethod is the v2 exchange update method (e.g. "allElementUpdate"), if given.
	UpdateMethod string
	Situations   []Situation
}

// Situation groups situation records that describe one traffic event.
type Situation struct {
	ID      string
	Version string
	// Urgency from the header information ("urgent", "extremelyUrgent", ...).
	Urgency string
	Records []Record
}

// Period is a validity period (validPeriod). Zero times are open.
type Period struct {
	Start, End time.Time
}

// Comment is a general public comment.
type Comment struct {
	Type string // commentType (v2/v3) or commentType2 (MDM extension)
	Text string
}

// Record is a situation record, reduced to what the TraFF mapping needs.
type Record struct {
	ID      string
	Version string
	// Type is the xsi:type without prefix, e.g. "Accident", "MaintenanceWorks".
	Type string
	// SubType is the value of the type-specific enumeration, e.g. accidentType or
	// roadMaintenanceType ("" if absent).
	SubType     string
	Probability string
	// ValidityStatus is "active", "suspended", "definedByValidityTimeSpec" or "planned".
	ValidityStatus string
	OverallStart   time.Time
	OverallEnd     time.Time
	Periods        []Period
	// Cancelled is set if the record is cancelled or ended (lifeCycleManagement).
	Cancelled bool
	// Impact.
	LanesRestricted int
	DelaySeconds    float64
	// SpeedLimit is the temporary speed limit of SpeedManagement records in km/h.
	SpeedLimit int
	Comments   []Comment
	Location   Location
}

// GeometryKind tells how the coordinates of a location were obtained.
type GeometryKind int

// Geometry kinds, from most to least precise.
const (
	GeometryNone      GeometryKind = iota
	GeometryLine                   // polyline (GML line string, linearByCoordinates)
	GeometryEndpoints              // start and end point (TPEG, OpenLR)
	GeometryPoint                  // single point
	GeometryDisplay                // only locationForDisplay / coordinatesForDisplay
)

// Location is the geographic part of a situation record.
type Location struct {
	// Points are the coordinates in driving direction (one point for point locations).
	Points []traff.LatLon
	Kind   GeometryKind
	// RoadNumber is the road number as given in the feed (e.g. "A5", "B27", "L1100").
	RoadNumber string
	// RoadName is the road (street) name, if given.
	RoadName string
	// Area is set for area locations (v2 Area, v3 AreaLocation).
	Area bool
	// BothDirections is set if the record affects both driving directions.
	BothDirections bool
	// FRC is the OpenLR functional road class ("FRC0".."FRC7"), if given.
	FRC string
	// HasAlertC and HasLinearReference report location references the parser cannot resolve to
	// coordinates (ALERT-C location codes, linear referencing on a road network such as BISSTRA).
	HasAlertC          bool
	HasLinearReference bool
}
