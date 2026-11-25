#pragma once

// better-maps: indoor maps of large venues (airports, stations, malls) from OpenStreetMap indoor tagging, served by
// services/indoor-server. Pure data model of a venue and the parsing of the server's JSON, without graphics.
//
// Coordinates are converted to the engine's mercator plane while parsing.

#include "geometry/point2d.hpp"
#include "geometry/rect2d.hpp"

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace df::indoor
{
// Indoor maps are drawn from this zoom on; venues are looked up from kMinListZoom on.
int constexpr kMinDrawZoom = 16;
int constexpr kMinListZoom = 15;

enum class GeomType : uint8_t
{
  Area,
  Line,
  Point
};

// Rendering class of a feature ("k" in the server's JSON).
enum class Kind : uint8_t
{
  Level,
  Corridor,
  Room,
  Area,
  Shop,
  Food,
  Toilets,
  Stairs,
  Elevator,
  Gate,
  Service,
  Wall,
  Platform,
  Restricted,
  Steps,
  Escalator,
  Footway,
  Door,
  Entrance,
  Info,
  Atm,
  Pharmacy,
  Luggage,
  Ticket,
  Poi,
  Unknown
};

Kind KindFromString(std::string_view s);
std::string_view ToString(Kind kind);
std::string DebugPrint(Kind kind);
std::string DebugPrint(GeomType type);

// Levels are numbers (may be fractional, e.g. 0.5); two levels are the same if they differ by less than this.
double constexpr kLevelEps = 1e-6;
inline bool SameLevel(double a, double b)
{
  return a - b < kLevelEps && b - a < kLevelEps;
}
// "0", "-1", "0.5": without trailing zeros, at most 3 decimals.
std::string FormatLevel(double level);
// |value| with exactly |decimals| decimals and a '.', independent of the C locale.
std::string FormatFixed(double value, int decimals);

struct Feature
{
  bool IsOnLevel(double level) const;
  // A name or a ref to show.
  bool HasLabel() const { return !m_name.empty() || !m_ref.empty(); }
  // Value of a tag of the subset sent by the server, empty if absent.
  std::string_view GetTag(std::string_view key) const;

  std::string m_id;  // OSM id, e.g. "w123"
  GeomType m_type = GeomType::Point;
  Kind m_kind = Kind::Unknown;
  std::string m_name;
  std::string m_ref;
  std::vector<double> m_levels;  // sorted
  // GeomType::Area: polygons, each outer ring then holes (open rings, mercator).
  std::vector<std::vector<std::vector<m2::PointD>>> m_polygons;
  // GeomType::Line
  std::vector<std::vector<m2::PointD>> m_lines;
  // GeomType::Point
  m2::PointD m_point;
  std::vector<std::pair<std::string, std::string>> m_tags;
  m2::RectD m_rect;
};

struct LevelInfo
{
  bool operator==(LevelInfo const & other) const = default;

  double m_level = 0.0;
  std::string m_label;
};
std::string DebugPrint(LevelInfo const & level);

// One entry of GET /v1/venues.
struct VenueInfo
{
  std::string m_id;
  std::string m_name;
  std::string m_kind;  // airport | station | mall | building | other
  m2::RectD m_rect;    // mercator
  std::vector<LevelInfo> m_levels;
  double m_defaultLevel = 0.0;
  std::string m_etag;
  uint32_t m_featureCount = 0;
};

// GET /v1/venues/{id}.
struct Venue
{
  // Label of |level|, or the formatted number if the venue does not know it.
  std::string LevelLabel(double level) const;
  bool HasLevel(double level) const;

  VenueInfo m_info;
  std::string m_attribution;
  std::string m_dataTimestamp;
  std::vector<Feature> m_features;
};
using VenuePtr = std::shared_ptr<Venue const>;

// Venue ids as sent by the server: [A-Za-z0-9_-], at most 40 characters.
bool IsValidVenueId(std::string_view id);

// Google encoded polyline with precision 6, latitude first. Appends mercator points; false on malformed input.
bool DecodePolyline6(std::string_view encoded, std::vector<m2::PointD> & points);

bool ParseVenueList(std::string const & json, std::vector<VenueInfo> & venues);
bool ParseVenue(std::string const & json, Venue & venue);

// A point inside the largest polygon of an area feature for its label (centroid, or the middle of the widest
// horizontal chord through it if the centroid is outside), or the point of a point feature.
std::optional<m2::PointD> LabelPoint(Feature const & feature);

bool IsPointInPolygon(std::vector<std::vector<m2::PointD>> const & polygon, m2::PointD const & pt);

// Result of tapping the indoor map.
struct HitResult
{
  size_t m_featureIndex = 0;
  double m_distance = 0.0;  // mercator, 0 for a tap inside an area
};

// The feature of |level| tapped at |pt|: the nearest point feature with an icon or label within |pointRadius|
// (mercator), else (if |withAreas|) the smallest tappable area (rooms, shops, gates, ..., not floors and corridors)
// containing |pt|.
std::optional<HitResult> HitTest(Venue const & venue, double level, m2::PointD const & pt, double pointRadius,
                                 bool withAreas);

// Whether a tap on the feature opens its details (named or a POI kind).
bool IsTappable(Feature const & feature);
}  // namespace df::indoor
