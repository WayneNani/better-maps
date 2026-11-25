#include "drape_frontend/indoor_data.hpp"

#include "geometry/mercator.hpp"

#include "base/logging.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <limits>
#include <map>

#include "glaze/json.hpp"

namespace df::indoor
{
// Not in an anonymous namespace: glaze's reflection needs types with linkage (clang).
namespace indoor_json
{
struct LevelDto
{
  double level = 0.0;
  std::string label;
};

struct VenueInfoDto
{
  std::string id;
  std::string name;
  std::string kind;
  std::vector<double> bbox;
  std::vector<LevelDto> levels;
  double default_level = 0.0;
  uint32_t features = 0;
  std::string etag;
};

struct VenueListDto
{
  std::string data_timestamp;
  std::vector<VenueInfoDto> venues;
};

struct FeatureDto
{
  std::string id;
  std::string t;
  std::string k;
  std::string n;
  std::string r;
  std::vector<double> lv;
  glz::json_t g;
  std::map<std::string, std::string> tg;
};

struct VenueDto
{
  std::string id;
  std::string name;
  std::string kind;
  std::vector<double> bbox;
  std::vector<LevelDto> levels;
  double default_level = 0.0;
  std::string data_timestamp;
  std::string attribution;
  std::string license;
  std::vector<FeatureDto> features;
};
}  // namespace indoor_json

namespace
{
using namespace indoor_json;

struct KindName
{
  Kind m_kind;
  std::string_view m_name;
};

std::array<KindName, 25> constexpr kKindNames = {{
    {Kind::Level, "level"},       {Kind::Corridor, "corridor"},
    {Kind::Room, "room"},         {Kind::Area, "area"},
    {Kind::Shop, "shop"},         {Kind::Food, "food"},
    {Kind::Toilets, "toilets"},   {Kind::Stairs, "stairs"},
    {Kind::Elevator, "elevator"}, {Kind::Gate, "gate"},
    {Kind::Service, "service"},   {Kind::Wall, "wall"},
    {Kind::Platform, "platform"}, {Kind::Restricted, "restricted"},
    {Kind::Steps, "steps"},       {Kind::Escalator, "escalator"},
    {Kind::Footway, "footway"},   {Kind::Door, "door"},
    {Kind::Entrance, "entrance"}, {Kind::Info, "info"},
    {Kind::Atm, "atm"},           {Kind::Pharmacy, "pharmacy"},
    {Kind::Luggage, "luggage"},   {Kind::Ticket, "ticket"},
    {Kind::Poi, "poi"},
}};

bool DecodeValue(std::string_view s, size_t & pos, int64_t & value)
{
  int64_t result = 0;
  int shift = 0;
  while (true)
  {
    if (pos >= s.size() || shift > 60)
      return false;
    int const b = static_cast<unsigned char>(s[pos++]) - 63;
    if (b < 0 || b > 63)
      return false;
    result |= static_cast<int64_t>(b & 0x1f) << shift;
    shift += 5;
    if (b < 0x20)
      break;
  }
  value = (result & 1) ? ~(result >> 1) : (result >> 1);
  return true;
}

bool ToRect(std::vector<double> const & bbox, m2::RectD & rect)
{
  if (bbox.size() != 4)
    return false;
  double const minLon = bbox[0], minLat = bbox[1], maxLon = bbox[2], maxLat = bbox[3];
  if (!(minLon <= maxLon && minLat <= maxLat && minLon >= -180 && maxLon <= 180 && minLat >= -90 && maxLat <= 90))
    return false;
  rect = m2::RectD(mercator::FromLatLon(minLat, minLon), mercator::FromLatLon(maxLat, maxLon));
  return true;
}

void ToLevels(std::vector<LevelDto> const & dtos, std::vector<LevelInfo> & levels)
{
  levels.clear();
  for (auto const & dto : dtos)
  {
    if (!std::isfinite(dto.level))
      continue;
    levels.push_back({dto.level, dto.label.empty() ? FormatLevel(dto.level) : dto.label});
  }
  std::sort(levels.begin(), levels.end(),
            [](LevelInfo const & a, LevelInfo const & b) { return a.m_level < b.m_level; });
  levels.erase(std::unique(levels.begin(), levels.end(),
                           [](LevelInfo const & a, LevelInfo const & b) { return SameLevel(a.m_level, b.m_level); }),
               levels.end());
}

bool FillInfo(std::string const & id, std::string const & name, std::string const & kind,
              std::vector<double> const & bbox, std::vector<LevelDto> const & levels, double defaultLevel,
              VenueInfo & info)
{
  if (!IsValidVenueId(id) || !ToRect(bbox, info.m_rect))
    return false;
  info.m_id = id;
  info.m_name = name;
  info.m_kind = kind;
  ToLevels(levels, info.m_levels);
  if (info.m_levels.empty())
    return false;
  info.m_defaultLevel = info.m_levels.front().m_level;
  for (auto const & l : info.m_levels)
    if (SameLevel(l.m_level, defaultLevel))
      info.m_defaultLevel = l.m_level;
  return true;
}

bool ReadLine(glz::json_t const & v, std::vector<m2::PointD> & points)
{
  if (!v.holds<std::string>())
    return false;
  return DecodePolyline6(v.get<std::string>(), points);
}

bool ReadGeometry(FeatureDto const & dto, Feature & f)
{
  using Array = glz::json_t::array_t;
  if (dto.t == "p")
  {
    std::vector<m2::PointD> pts;
    if (!ReadLine(dto.g, pts) || pts.size() != 1)
      return false;
    f.m_type = GeomType::Point;
    f.m_point = pts.front();
    f.m_rect = m2::RectD(f.m_point, f.m_point);
    return true;
  }
  if (!dto.g.holds<Array>())
    return false;
  auto const & arr = dto.g.get<Array>();
  if (dto.t == "l")
  {
    f.m_type = GeomType::Line;
    for (auto const & line : arr)
    {
      std::vector<m2::PointD> pts;
      if (!ReadLine(line, pts))
        return false;
      if (pts.size() < 2)
        continue;
      for (auto const & p : pts)
        f.m_rect.Add(p);
      f.m_lines.push_back(std::move(pts));
    }
    return !f.m_lines.empty();
  }
  if (dto.t == "a")
  {
    f.m_type = GeomType::Area;
    for (auto const & poly : arr)
    {
      if (!poly.holds<Array>())
        return false;
      std::vector<std::vector<m2::PointD>> rings;
      for (auto const & ring : poly.get<Array>())
      {
        std::vector<m2::PointD> pts;
        if (!ReadLine(ring, pts))
          return false;
        // Closed rings are accepted as well.
        if (pts.size() > 1 && pts.front().EqualDxDy(pts.back(), 1e-12))
          pts.pop_back();
        if (pts.size() < 3)
        {
          if (rings.empty())
            break;  // no outer ring: skip the polygon
          continue;
        }
        rings.push_back(std::move(pts));
      }
      if (rings.empty())
        continue;
      for (auto const & p : rings.front())
        f.m_rect.Add(p);
      f.m_polygons.push_back(std::move(rings));
    }
    return !f.m_polygons.empty();
  }
  return false;
}

double SignedArea(std::vector<m2::PointD> const & ring)
{
  double a = 0;
  for (size_t i = 0, j = ring.size() - 1; i < ring.size(); j = i++)
    a += (ring[j].x - ring[i].x) * (ring[j].y + ring[i].y);
  return a / 2;
}

bool IsPointInRing(std::vector<m2::PointD> const & ring, m2::PointD const & pt)
{
  bool inside = false;
  for (size_t i = 0, j = ring.size() - 1; i < ring.size(); j = i++)
  {
    auto const & a = ring[i];
    auto const & b = ring[j];
    if ((a.y > pt.y) != (b.y > pt.y) && pt.x < (b.x - a.x) * (pt.y - a.y) / (b.y - a.y) + a.x)
      inside = !inside;
  }
  return inside;
}

bool IsAreaTappable(Kind kind)
{
  switch (kind)
  {
  case Kind::Level:
  case Kind::Corridor:
  case Kind::Area:
  case Kind::Wall:
  case Kind::Platform: return false;
  default: return true;
  }
}
}  // namespace

Kind KindFromString(std::string_view s)
{
  for (auto const & kn : kKindNames)
    if (kn.m_name == s)
      return kn.m_kind;
  return Kind::Unknown;
}

std::string_view ToString(Kind kind)
{
  for (auto const & kn : kKindNames)
    if (kn.m_kind == kind)
      return kn.m_name;
  return "unknown";
}

std::string DebugPrint(Kind kind)
{
  return std::string(ToString(kind));
}

std::string DebugPrint(GeomType type)
{
  switch (type)
  {
  case GeomType::Area: return "Area";
  case GeomType::Line: return "Line";
  case GeomType::Point: return "Point";
  }
  return "?";
}

std::string DebugPrint(LevelInfo const & level)
{
  return FormatLevel(level.m_level) + " \"" + level.m_label + "\"";
}

std::string FormatLevel(double level)
{
  if (std::abs(level) < kLevelEps)
    return "0";
  std::string s = FormatFixed(level, 3);
  while (!s.empty() && s.back() == '0')
    s.pop_back();
  if (!s.empty() && s.back() == '.')
    s.pop_back();
  return s;
}

std::string FormatFixed(double value, int decimals)
{
  int64_t scale = 1;
  for (int i = 0; i < decimals; ++i)
    scale *= 10;
  int64_t const n = std::llround(value * static_cast<double>(scale));
  uint64_t const a = n < 0 ? static_cast<uint64_t>(-n) : static_cast<uint64_t>(n);
  std::string s = n < 0 ? "-" : "";
  s += std::to_string(a / static_cast<uint64_t>(scale));
  if (decimals > 0)
  {
    std::string frac = std::to_string(a % static_cast<uint64_t>(scale));
    s += '.';
    s += std::string(static_cast<size_t>(decimals) - frac.size(), '0') + frac;
  }
  return s;
}

bool Feature::IsOnLevel(double level) const
{
  return std::any_of(m_levels.begin(), m_levels.end(), [level](double l) { return SameLevel(l, level); });
}

std::string_view Feature::GetTag(std::string_view key) const
{
  for (auto const & [k, v] : m_tags)
    if (k == key)
      return v;
  return {};
}

std::string Venue::LevelLabel(double level) const
{
  for (auto const & l : m_info.m_levels)
    if (SameLevel(l.m_level, level))
      return l.m_label;
  return FormatLevel(level);
}

bool Venue::HasLevel(double level) const
{
  return std::any_of(m_info.m_levels.begin(), m_info.m_levels.end(),
                     [level](LevelInfo const & l) { return SameLevel(l.m_level, level); });
}

bool IsValidVenueId(std::string_view id)
{
  if (id.empty() || id.size() > 40)
    return false;
  return std::all_of(id.begin(), id.end(), [](char c)
  { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-'; });
}

bool DecodePolyline6(std::string_view encoded, std::vector<m2::PointD> & points)
{
  int64_t lat = 0, lon = 0;
  size_t pos = 0;
  while (pos < encoded.size())
  {
    int64_t dLat, dLon;
    if (!DecodeValue(encoded, pos, dLat) || !DecodeValue(encoded, pos, dLon))
      return false;
    lat += dLat;
    lon += dLon;
    double const la = lat / 1e6, lo = lon / 1e6;
    if (std::abs(la) > 90.0 || std::abs(lo) > 180.0)
      return false;
    points.push_back(mercator::FromLatLon(la, lo));
  }
  return true;
}

bool ParseVenueList(std::string const & json, std::vector<VenueInfo> & venues)
{
  VenueListDto dto;
  if (auto const error = glz::read<glz::opts{.error_on_unknown_keys = false}>(dto, json))
  {
    LOG(LWARNING, ("Indoor: can't read the venue list:", glz::format_error(error, json).substr(0, 200)));
    return false;
  }
  venues.clear();
  for (auto const & v : dto.venues)
  {
    VenueInfo info;
    if (!FillInfo(v.id, v.name, v.kind, v.bbox, v.levels, v.default_level, info))
      continue;
    info.m_etag = v.etag;
    info.m_featureCount = v.features;
    venues.push_back(std::move(info));
  }
  return true;
}

bool ParseVenue(std::string const & json, Venue & venue)
{
  VenueDto dto;
  if (auto const error = glz::read<glz::opts{.error_on_unknown_keys = false}>(dto, json))
  {
    LOG(LWARNING, ("Indoor: can't read a venue:", glz::format_error(error, json).substr(0, 200)));
    return false;
  }
  if (!FillInfo(dto.id, dto.name, dto.kind, dto.bbox, dto.levels, dto.default_level, venue.m_info))
    return false;
  venue.m_attribution = dto.attribution;
  venue.m_dataTimestamp = dto.data_timestamp;
  venue.m_features.clear();
  venue.m_features.reserve(dto.features.size());
  size_t dropped = 0;
  for (auto & fd : dto.features)
  {
    Feature f;
    f.m_id = std::move(fd.id);
    f.m_kind = KindFromString(fd.k);
    f.m_name = std::move(fd.n);
    f.m_ref = std::move(fd.r);
    for (double l : fd.lv)
      if (std::isfinite(l))
        f.m_levels.push_back(l);
    std::sort(f.m_levels.begin(), f.m_levels.end());
    if (f.m_levels.empty() || !ReadGeometry(fd, f))
    {
      ++dropped;
      continue;
    }
    f.m_tags.reserve(fd.tg.size());
    for (auto & [k, v] : fd.tg)
      f.m_tags.emplace_back(k, std::move(v));
    venue.m_features.push_back(std::move(f));
  }
  if (dropped > 0)
    LOG(LINFO, ("Indoor: dropped", dropped, "malformed features"));
  return true;
}

bool IsPointInPolygon(std::vector<std::vector<m2::PointD>> const & polygon, m2::PointD const & pt)
{
  if (polygon.empty() || !IsPointInRing(polygon.front(), pt))
    return false;
  for (size_t i = 1; i < polygon.size(); ++i)
    if (IsPointInRing(polygon[i], pt))
      return false;
  return true;
}

std::optional<m2::PointD> LabelPoint(Feature const & feature)
{
  if (feature.m_type == GeomType::Point)
    return feature.m_point;
  if (feature.m_type != GeomType::Area)
    return {};
  std::vector<std::vector<m2::PointD>> const * best = nullptr;
  double bestArea = 0;
  for (auto const & poly : feature.m_polygons)
  {
    double const a = std::abs(SignedArea(poly.front()));
    if (best == nullptr || a > bestArea)
    {
      best = &poly;
      bestArea = a;
    }
  }
  if (best == nullptr)
    return {};
  auto const & outer = best->front();
  // Area centroid of the outer ring (relative to the first point for precision).
  double cx = 0, cy = 0, a2 = 0;
  m2::PointD const o = outer.front();
  for (size_t i = 0, j = outer.size() - 1; i < outer.size(); j = i++)
  {
    m2::PointD const p = outer[j] - o, q = outer[i] - o;
    double const cross = p.x * q.y - q.x * p.y;
    a2 += cross;
    cx += (p.x + q.x) * cross;
    cy += (p.y + q.y) * cross;
  }
  m2::PointD c;
  if (std::abs(a2) > 1e-30)
    c = o + m2::PointD(cx / (3 * a2), cy / (3 * a2));
  else
  {
    m2::RectD r;
    for (auto const & p : outer)
      r.Add(p);
    c = r.Center();
  }
  if (IsPointInPolygon(*best, c))
    return c;

  // Widest chord on the horizontal line through c (and, if that fails, through the middle of the bbox).
  m2::RectD bbox;
  for (auto const & p : outer)
    bbox.Add(p);
  for (double y : {c.y, bbox.Center().y})
  {
    std::vector<double> xs;
    for (auto const & ring : *best)
    {
      for (size_t i = 0, j = ring.size() - 1; i < ring.size(); j = i++)
      {
        auto const & p = ring[j];
        auto const & q = ring[i];
        if ((p.y > y) != (q.y > y))
          xs.push_back(p.x + (y - p.y) * (q.x - p.x) / (q.y - p.y));
      }
    }
    std::sort(xs.begin(), xs.end());
    double bestWidth = 0;
    std::optional<m2::PointD> res;
    for (size_t i = 0; i + 1 < xs.size(); i += 2)
    {
      if (xs[i + 1] - xs[i] > bestWidth)
      {
        bestWidth = xs[i + 1] - xs[i];
        res = m2::PointD((xs[i] + xs[i + 1]) / 2, y);
      }
    }
    if (res)
      return res;
  }
  return c;
}

bool IsTappable(Feature const & feature)
{
  if (feature.m_type == GeomType::Line)
    return false;
  if (feature.m_type == GeomType::Area)
  {
    if (feature.m_kind == Kind::Level || feature.m_kind == Kind::Wall)
      return false;
    return IsAreaTappable(feature.m_kind) || feature.HasLabel();
  }
  // Doors and entrances only with a name.
  if (feature.m_kind == Kind::Door || feature.m_kind == Kind::Entrance)
    return feature.HasLabel();
  return true;
}

std::optional<HitResult> HitTest(Venue const & venue, double level, m2::PointD const & pt, double pointRadius,
                                 bool withAreas)
{
  std::optional<HitResult> best;
  for (size_t i = 0; i < venue.m_features.size(); ++i)
  {
    auto const & f = venue.m_features[i];
    if (f.m_type != GeomType::Point || !IsTappable(f) || !f.IsOnLevel(level))
      continue;
    double const d = f.m_point.Length(pt);
    if (d <= pointRadius && (!best || d < best->m_distance))
      best = HitResult{i, d};
  }
  if (best || !withAreas)
    return best;

  double bestArea = std::numeric_limits<double>::max();
  for (size_t i = 0; i < venue.m_features.size(); ++i)
  {
    auto const & f = venue.m_features[i];
    if (f.m_type != GeomType::Area || !IsTappable(f) || !f.IsOnLevel(level) || !f.m_rect.IsPointInside(pt))
      continue;
    for (auto const & poly : f.m_polygons)
    {
      if (!IsPointInPolygon(poly, pt))
        continue;
      double const a = std::abs(SignedArea(poly.front()));
      if (a < bestArea)
      {
        bestArea = a;
        best = HitResult{i, 0.0};
      }
    }
  }
  return best;
}
}  // namespace df::indoor
