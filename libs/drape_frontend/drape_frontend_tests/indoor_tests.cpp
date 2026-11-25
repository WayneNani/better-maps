#include "testing/testing.hpp"

#include "drape_frontend/indoor_cache.hpp"
#include "drape_frontend/indoor_data.hpp"
#include "drape_frontend/indoor_triangulation.hpp"

#include "geometry/mercator.hpp"

#include <chrono>
#include <cmath>
#include <filesystem>
#include <random>
#include <string>
#include <thread>
#include <vector>

namespace indoor_tests
{
using namespace df::indoor;
namespace fs = std::filesystem;
using Ring = std::vector<m2::PointD>;

class TempDir
{
public:
  TempDir()
  {
    std::random_device rd;
    m_path = fs::temp_directory_path() / ("bm_indoor_test_" + std::to_string(rd()) + std::to_string(rd()));
    fs::create_directories(m_path);
  }
  ~TempDir()
  {
    std::error_code ec;
    fs::remove_all(m_path, ec);
  }
  std::string Path() const { return m_path.string(); }

private:
  fs::path m_path;
};

// Google polyline encoding (precision 6, lat first), for building test documents.
void EncodeValue(int64_t v, std::string & out)
{
  uint64_t u = v < 0 ? ~(static_cast<uint64_t>(v) << 1) : (static_cast<uint64_t>(v) << 1);
  while (u >= 0x20)
  {
    out.push_back(static_cast<char>((0x20 | (u & 0x1f)) + 63));
    u >>= 5;
  }
  out.push_back(static_cast<char>(u + 63));
}

std::string Encode(std::vector<std::pair<double, double>> const & latLons)
{
  std::string out;
  int64_t plat = 0, plon = 0;
  for (auto const & [lat, lon] : latLons)
  {
    int64_t const la = std::llround(lat * 1e6), lo = std::llround(lon * 1e6);
    EncodeValue(la - plat, out);
    EncodeValue(lo - plon, out);
    plat = la;
    plon = lo;
  }
  return out;
}

double TriangleArea(m2::PointD const & a, m2::PointD const & b, m2::PointD const & c)
{
  return std::abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2;
}

double TrianglesArea(std::vector<m2::PointD> const & t)
{
  double s = 0;
  for (size_t i = 0; i + 2 < t.size(); i += 3)
    s += TriangleArea(t[i], t[i + 1], t[i + 2]);
  return s;
}

double RingArea(Ring const & r)
{
  double a = 0;
  for (size_t i = 0, j = r.size() - 1; i < r.size(); j = i++)
    a += (r[j].x - r[i].x) * (r[j].y + r[i].y);
  return std::abs(a) / 2;
}

UNIT_TEST(Indoor_KindNames)
{
  TEST_EQUAL(KindFromString("shop"), Kind::Shop, ());
  TEST_EQUAL(KindFromString("gate"), Kind::Gate, ());
  TEST_EQUAL(KindFromString("restricted"), Kind::Restricted, ());
  TEST_EQUAL(KindFromString("whatever"), Kind::Unknown, ());
  for (auto k : {Kind::Level, Kind::Corridor, Kind::Poi, Kind::Ticket, Kind::Footway})
    TEST_EQUAL(KindFromString(ToString(k)), k, ());
}

UNIT_TEST(Indoor_FormatLevel)
{
  TEST_EQUAL(FormatLevel(0), "0", ());
  TEST_EQUAL(FormatLevel(-0.0), "0", ());
  TEST_EQUAL(FormatLevel(-1), "-1", ());
  TEST_EQUAL(FormatLevel(0.5), "0.5", ());
  TEST_EQUAL(FormatLevel(-0.5), "-0.5", ());
  TEST_EQUAL(FormatLevel(12), "12", ());
  TEST_EQUAL(FormatLevel(0.25), "0.25", ());
  TEST_EQUAL(FormatFixed(-0.5, 2), "-0.50", ());
  TEST_EQUAL(FormatFixed(8.0000004, 6), "8.000000", ());
  TEST_EQUAL(FormatFixed(-12.3456789, 6), "-12.345679", ());
  TEST_EQUAL(FormatFixed(0.05, 1), "0.1", ());
  TEST(SameLevel(0.3, 0.1 + 0.2), ());
  TEST(!SameLevel(0.3, 0.31), ());
}

UNIT_TEST(Indoor_VenueIds)
{
  TEST(IsValidVenueId("w38212384"), ());
  TEST(IsValidVenueId("cw1-2_3"), ());
  TEST(!IsValidVenueId(""), ());
  TEST(!IsValidVenueId("../etc"), ());
  TEST(!IsValidVenueId("a/b"), ());
  TEST(!IsValidVenueId(std::string(41, 'a')), ());
}

UNIT_TEST(Indoor_DecodePolyline)
{
  // The example of the format's documentation, precision 5 there: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453).
  // With precision 6 the same text means a tenth of the values.
  std::vector<m2::PointD> pts;
  TEST(DecodePolyline6("_p~iF~ps|U_ulLnnqC_mqNvxq`@", pts), ());
  TEST_EQUAL(pts.size(), 3, ());
  auto const ll = mercator::ToLatLon(pts[0]);
  TEST_ALMOST_EQUAL_ABS(ll.m_lat, 3.85, 1e-9, ());
  TEST_ALMOST_EQUAL_ABS(ll.m_lon, -12.02, 1e-9, ());

  pts.clear();
  std::string const enc = Encode({{50.943, 6.9583}, {50.9431, 6.9585}});
  TEST(DecodePolyline6(enc, pts), ());
  TEST_EQUAL(pts.size(), 2, ());
  auto const ll2 = mercator::ToLatLon(pts[1]);
  TEST_ALMOST_EQUAL_ABS(ll2.m_lat, 50.9431, 1e-7, ());
  TEST_ALMOST_EQUAL_ABS(ll2.m_lon, 6.9585, 1e-7, ());

  pts.clear();
  TEST(!DecodePolyline6("_p~iF~ps|U_", pts), ("truncated"));
  pts.clear();
  TEST(!DecodePolyline6("\x01\x02", pts), ("characters out of range"));
  pts.clear();
  TEST(DecodePolyline6("", pts), ());
  TEST(pts.empty(), ());
}

std::string const kList = R"({
  "data_timestamp": "2026-11-24T21:21:02Z",
  "venues": [
    {"id": "w1", "name": "Terminal 1", "kind": "airport", "bbox": [8.56, 50.04, 8.58, 50.05],
     "levels": [{"level": 1, "label": "1"}, {"level": -1, "label": "U1"}, {"level": 0, "label": ""}],
     "default_level": 0, "features": 12, "etag": "\"abc\"", "future_field": [1, 2]},
    {"id": "bad/id", "name": "x", "bbox": [8.56, 50.04, 8.58, 50.05], "levels": [{"level": 0}]},
    {"id": "w2", "name": "inverted", "bbox": [8.58, 50.04, 8.56, 50.05], "levels": [{"level": 0}]},
    {"id": "w3", "name": "no levels", "bbox": [8.56, 50.04, 8.58, 50.05], "levels": []},
    {"id": "w4", "bbox": [6.95, 50.94, 6.96, 50.95], "levels": [{"level": 0.5, "label": "0.5"}], "default_level": 7}
  ]
})";

UNIT_TEST(Indoor_ParseVenueList)
{
  std::vector<VenueInfo> venues;
  TEST(ParseVenueList(kList, venues), ());
  TEST_EQUAL(venues.size(), 2, ());
  auto const & v = venues[0];
  TEST_EQUAL(v.m_id, "w1", ());
  TEST_EQUAL(v.m_name, "Terminal 1", ());
  TEST_EQUAL(v.m_kind, "airport", ());
  TEST_EQUAL(v.m_etag, "\"abc\"", ());
  TEST_EQUAL(v.m_featureCount, 12, ());
  TEST_EQUAL(v.m_levels.size(), 3, ());
  TEST_EQUAL(v.m_levels[0], (LevelInfo{-1, "U1"}), ());
  TEST_EQUAL(v.m_levels[1], (LevelInfo{0, "0"}), ("empty label -> number"));
  TEST_EQUAL(v.m_defaultLevel, 0.0, ());
  TEST(v.m_rect.IsPointInside(mercator::FromLatLon(50.045, 8.57)), ());
  // An unknown default level falls back to the lowest level.
  TEST_EQUAL(venues[1].m_defaultLevel, 0.5, ());

  TEST(!ParseVenueList("not json", venues), ());
  TEST(!ParseVenueList(R"({"venues": 3})", venues), ());
}

std::string MakeVenueJson()
{
  // A 20 m x 20 m floor with a shop (with a hole) and a toilet point on level 0, a gate room on level 1 and stairs on
  // both.
  double const lat = 50.0, lon = 8.0, d = 0.0002;
  std::string const floor = Encode({{lat, lon}, {lat, lon + d}, {lat + d, lon + d}, {lat + d, lon}});
  std::string const shop = Encode({{lat, lon}, {lat, lon + d / 2}, {lat + d / 2, lon + d / 2}, {lat + d / 2, lon}});
  std::string const hole = Encode(
      {{lat + d / 8, lon + d / 8}, {lat + d / 4, lon + d / 8}, {lat + d / 4, lon + d / 4}, {lat + d / 8, lon + d / 4}});
  std::string const gate =
      Encode({{lat + d / 2, lon + d / 2}, {lat + d / 2, lon + d}, {lat + d, lon + d}, {lat + d, lon + d / 2}});
  std::string const toilet = Encode({{lat + 0.75 * d, lon + 0.25 * d}});
  std::string const steps = Encode({{lat, lon + d}, {lat + d, lon + d}});
  return R"({"id": "w9", "name": "Test", "kind": "station", "bbox": [8.0, 50.0, 8.0002, 50.0002],
    "levels": [{"level": 0, "label": "EG"}, {"level": 1, "label": "1"}], "default_level": 0,
    "attribution": "© OpenStreetMap-Mitwirkende", "data_timestamp": "2026-11-24T21:21:02Z",
    "features": [
      {"id": "w10", "t": "a", "k": "level", "lv": [0, 1], "g": [[")" +
         floor + R"("]]},
      {"id": "w11", "t": "a", "k": "shop", "n": "Kiosk", "r": "K1", "lv": [0], "g": [[")" +
         shop + R"(", ")" + hole + R"("]], "tg": {"shop": "kiosk", "opening_hours": "24/7"}},
      {"id": "w12", "t": "a", "k": "gate", "r": "B44", "lv": [1], "g": [[")" +
         gate + R"("]]},
      {"id": "n13", "t": "p", "k": "toilets", "lv": [0], "g": ")" +
         toilet + R"(", "tg": {"amenity": "toilets"}},
      {"id": "w14", "t": "l", "k": "steps", "lv": [1, 0], "g": [")" +
         steps + R"("]},
      {"id": "w15", "t": "p", "k": "poi", "lv": [], "g": ")" +
         toilet + R"("},
      {"id": "w16", "t": "p", "k": "poi", "lv": [0], "g": ["not a point"]},
      {"id": "w17", "t": "x", "k": "poi", "lv": [0], "g": ")" +
         toilet + R"("}
    ]})";
}

UNIT_TEST(Indoor_ParseVenue)
{
  Venue venue;
  TEST(ParseVenue(MakeVenueJson(), venue), ());
  TEST_EQUAL(venue.m_info.m_id, "w9", ());
  TEST_EQUAL(venue.m_attribution, "© OpenStreetMap-Mitwirkende", ());
  TEST_EQUAL(venue.m_features.size(), 5, ("malformed features are dropped"));
  TEST_EQUAL(venue.LevelLabel(0), "EG", ());
  TEST_EQUAL(venue.LevelLabel(2), "2", ());
  TEST(venue.HasLevel(1), ());
  TEST(!venue.HasLevel(0.5), ());

  auto const & shop = venue.m_features[1];
  TEST_EQUAL(shop.m_kind, Kind::Shop, ());
  TEST_EQUAL(shop.m_type, GeomType::Area, ());
  TEST_EQUAL(shop.m_name, "Kiosk", ());
  TEST_EQUAL(shop.m_ref, "K1", ());
  TEST_EQUAL(shop.m_polygons.size(), 1, ());
  TEST_EQUAL(shop.m_polygons[0].size(), 2, ("outer ring and hole"));
  TEST_EQUAL(shop.GetTag("opening_hours"), "24/7", ());
  TEST_EQUAL(shop.GetTag("phone"), "", ());
  TEST(shop.IsOnLevel(0), ());
  TEST(!shop.IsOnLevel(1), ());

  auto const & steps = venue.m_features[4];
  TEST_EQUAL(steps.m_type, GeomType::Line, ());
  TEST_EQUAL(steps.m_levels, (std::vector<double>{0, 1}), ("sorted"));

  auto const & toilet = venue.m_features[3];
  TEST_EQUAL(toilet.m_type, GeomType::Point, ());
  TEST(venue.m_features[0].m_rect.IsPointInside(toilet.m_point), ());

  TEST(!ParseVenue(R"({"id": "../x", "bbox": [0,0,1,1], "levels": [{"level": 0}], "features": []})", venue), ());
  TEST(!ParseVenue("[]", venue), ());
}

UNIT_TEST(Indoor_LabelPointAndHitTest)
{
  Venue venue;
  TEST(ParseVenue(MakeVenueJson(), venue), ());
  auto const & shop = venue.m_features[1];
  auto const lp = LabelPoint(shop);
  TEST(lp, ());
  TEST(IsPointInPolygon(shop.m_polygons[0], *lp), ("label inside the shop, outside its hole"));

  // An L-shaped room: the centroid lies outside.
  Feature l;
  l.m_type = GeomType::Area;
  l.m_polygons = {{{{0, 0}, {10, 0}, {10, 1}, {1, 1}, {1, 10}, {0, 10}}}};
  auto const lp2 = LabelPoint(l);
  TEST(lp2, ());
  TEST(IsPointInPolygon(l.m_polygons[0], *lp2), (*lp2));

  auto const toiletPt = venue.m_features[3].m_point;
  double const r = (venue.m_info.m_rect.SizeX()) / 10;
  // Near the toilet point on level 0: the toilet, not the floor.
  auto hit = HitTest(venue, 0, toiletPt + m2::PointD(r / 2, 0), r, true);
  TEST(hit, ());
  TEST_EQUAL(venue.m_features[hit->m_featureIndex].m_id, "n13", ());
  // On level 1 there is no point: the gate room contains the point? No, the toilet is in the left half: nothing
  // tappable but the floor.
  hit = HitTest(venue, 1, toiletPt, r, true);
  TEST(!hit, ());
  // Inside the gate room on level 1.
  auto const gateCenter = venue.m_features[2].m_rect.Center();
  hit = HitTest(venue, 1, gateCenter, r, true);
  TEST(hit, ());
  TEST_EQUAL(venue.m_features[hit->m_featureIndex].m_ref, "B44", ());
  TEST(!HitTest(venue, 1, gateCenter, r, false), ("areas off"));
  // Inside the shop's hole: not the shop.
  m2::RectD holeRect;
  for (auto const & p : shop.m_polygons[0][1])
    holeRect.Add(p);
  auto const holeCenter = holeRect.Center();
  TEST(!HitTest(venue, 0, holeCenter, r / 100, true), ());
  TEST(HitTest(venue, 0, shop.m_rect.RightTop() - m2::PointD(r / 10, r / 10), r / 100, true), ());
}

UNIT_TEST(Indoor_IsTappable)
{
  Feature f;
  f.m_type = GeomType::Area;
  f.m_kind = Kind::Corridor;
  TEST(!IsTappable(f), ());
  f.m_name = "Verteilerebene";
  TEST(IsTappable(f), ());
  f.m_kind = Kind::Level;
  TEST(!IsTappable(f), ());
  f.m_kind = Kind::Shop;
  f.m_name.clear();
  TEST(IsTappable(f), ());
  f.m_type = GeomType::Point;
  f.m_kind = Kind::Door;
  TEST(!IsTappable(f), ());
  f.m_ref = "D1";
  TEST(IsTappable(f), ());
  f.m_type = GeomType::Line;
  f.m_kind = Kind::Steps;
  TEST(!IsTappable(f), ());
}

UNIT_TEST(Indoor_TriangulateSquare)
{
  Ring sq = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
  auto t = Triangulate({sq});
  TEST_EQUAL(t.size(), 6, ());
  TEST_ALMOST_EQUAL_ABS(TrianglesArea(t), 1.0, 1e-9, ());
  // Clockwise and closed rings give the same.
  Ring cw(sq.rbegin(), sq.rend());
  cw.push_back(cw.front());
  t = Triangulate({cw});
  TEST_EQUAL(t.size(), 6, ());
  TEST_ALMOST_EQUAL_ABS(TrianglesArea(t), 1.0, 1e-9, ());
}

UNIT_TEST(Indoor_TriangulateConcaveAndHoles)
{
  // A comb (concave) with mercator-like coordinates.
  m2::PointD const o(8.5, 55.3);
  double const s = 1e-5;
  // Bottom edge, then ten teeth of 1 x 2 above a 1 high bar.
  Ring teeth = {o, o + m2::PointD(20 * s, 0)};
  for (int i = 9; i >= 0; --i)
  {
    teeth.push_back(o + m2::PointD((i * 2 + 2) * s, 3 * s));
    teeth.push_back(o + m2::PointD((i * 2 + 1) * s, 3 * s));
    teeth.push_back(o + m2::PointD((i * 2 + 1) * s, s));
    teeth.push_back(o + m2::PointD(i * 2 * s, s));
  }
  teeth.pop_back();
  teeth.push_back(o + m2::PointD(0, 3 * s));
  auto t = Triangulate({teeth});
  TEST_GREATER(t.size(), 0, ());
  TEST_ALMOST_EQUAL_ABS(TrianglesArea(t), RingArea(teeth), RingArea(teeth) * 1e-6, ());

  // Square with two holes.
  Ring outer = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
  Ring h1 = {{1, 1}, {3, 1}, {3, 3}, {1, 3}};
  Ring h2 = {{6, 6}, {6, 8}, {8, 8}, {8, 6}};  // other orientation
  t = Triangulate({outer, h1, h2});
  TEST_ALMOST_EQUAL_ABS(TrianglesArea(t), 100.0 - 4 - 4, 1e-9, ());
  // No triangle covers a hole center.
  for (size_t i = 0; i + 2 < t.size(); i += 3)
  {
    for (m2::PointD c : {m2::PointD(2, 2), m2::PointD(7, 7)})
    {
      double const a = TriangleArea(t[i], t[i + 1], t[i + 2]);
      double const sum =
          TriangleArea(c, t[i + 1], t[i + 2]) + TriangleArea(t[i], c, t[i + 2]) + TriangleArea(t[i], t[i + 1], c);
      TEST(std::abs(sum - a) > 1e-9, ("hole covered"));
    }
  }
}

UNIT_TEST(Indoor_TriangulateDegenerate)
{
  TEST(Triangulate({}).empty(), ());
  TEST(Triangulate({{{0, 0}, {1, 1}}}).empty(), ());
  TEST(Triangulate({{{0, 0}, {1, 1}, {0, 0}}}).empty(), ());
  // Collinear: no area.
  TEST_ALMOST_EQUAL_ABS(TrianglesArea(Triangulate({{{0, 0}, {1, 0}, {2, 0}, {3, 0}}})), 0.0, 1e-12, ());
  // Duplicate points and a bow tie (self-intersecting): terminates, some triangles.
  auto t = Triangulate({{{0, 0}, {0, 0}, {2, 0}, {0, 2}, {2, 2}}});
  TEST_LESS_OR_EQUAL(TrianglesArea(t), 4.0, ());
  // A hole touching the outer ring.
  Ring outer = {{0, 0}, {4, 0}, {4, 4}, {0, 4}};
  Ring hole = {{0, 1}, {2, 1}, {2, 3}, {0, 3}};
  t = Triangulate({outer, hole});
  TEST_ALMOST_EQUAL_ABS(TrianglesArea(t), 16.0 - 4, 1e-9, ());
  // Many random-ish points on a circle (convex, 500 points).
  Ring circle;
  for (int i = 0; i < 500; ++i)
    circle.emplace_back(std::cos(i * 2 * M_PI / 500), std::sin(i * 2 * M_PI / 500));
  t = Triangulate({circle});
  TEST_EQUAL(t.size(), 498 * 3, ());
  TEST_ALMOST_EQUAL_ABS(TrianglesArea(t), RingArea(circle), 1e-9, ());
}

UNIT_TEST(Indoor_ListQueryBox)
{
  // A view of ~300 m in Frankfurt.
  auto const view = m2::RectD(mercator::FromLatLon(50.049, 8.568), mercator::FromLatLon(50.052, 8.572));
  auto const box = ListQueryBox(view);
  TEST(box.Contains(ToLatLonBox(view)), ());
  // Snapped to the grid.
  for (double v : {box.m_minLon, box.m_minLat, box.m_maxLon, box.m_maxLat})
    TEST_ALMOST_EQUAL_ABS(v / kListGridDeg, std::round(v / kListGridDeg), 1e-6, (v));
  // Small moves ask the same.
  auto const moved = m2::RectD(mercator::FromLatLon(50.0491, 8.5681), mercator::FromLatLon(50.0521, 8.5721));
  TEST_EQUAL(ListQueryBox(moved), box, ());
  // A huge view is capped.
  auto const huge = ListQueryBox(m2::RectD(mercator::FromLatLon(45, 0), mercator::FromLatLon(55, 15)));
  TEST_LESS_OR_EQUAL(huge.m_maxLon - huge.m_minLon, kMaxListQueryDeg + 2 * kListGridDeg, ());
  TEST_LESS_OR_EQUAL(huge.m_maxLat - huge.m_minLat, kMaxListQueryDeg + 2 * kListGridDeg, ());
  TEST_EQUAL(ToQueryString({8.5, 50, 8.62, 50.1}), "8.500000,50.000000,8.620000,50.100000", ());
}

VenueInfo MakeInfo(std::string const & id, double lat0, double lon0, double lat1, double lon1)
{
  VenueInfo v;
  v.m_id = id;
  v.m_rect = m2::RectD(mercator::FromLatLon(lat0, lon0), mercator::FromLatLon(lat1, lon1));
  v.m_levels = {{0, "0"}};
  return v;
}

UNIT_TEST(Indoor_VenueListCache)
{
  VenueListCache cache;
  auto const now = Clock::now();
  auto const view = m2::RectD(mercator::FromLatLon(50.049, 8.568), mercator::FromLatLon(50.052, 8.572));
  TEST(!cache.Covers(view, now), ());
  auto const box = ListQueryBox(view);
  cache.Add(box, {MakeInfo("a", 50.05, 8.569, 50.051, 8.571), MakeInfo("far", 50.07, 8.60, 50.08, 8.61)}, now);
  TEST(cache.Covers(view, now), ());
  TEST(!cache.Covers(view, now + std::chrono::hours(2)), ("expired"));
  auto const vs = cache.VenuesIntersecting(view, now);
  TEST_EQUAL(vs.size(), 1, ());
  TEST_EQUAL(vs[0].m_id, "a", ());
  TEST(cache.VenuesIntersecting(view, now + std::chrono::hours(2)).empty(), ());

  // A failure blocks repeating the query for a while and has no venues.
  auto const other = m2::RectD(mercator::FromLatLon(50.9, 6.9), mercator::FromLatLon(50.91, 6.91));
  cache.AddFailure(ListQueryBox(other), now);
  TEST(cache.Covers(other, now + std::chrono::seconds(10)), ());
  TEST(!cache.Covers(other, now + std::chrono::minutes(5)), ());
  TEST(cache.VenuesIntersecting(other, now).empty(), ());

  // Same venue from two answers only once.
  cache.Add(ListQueryBox(view), {MakeInfo("a", 50.05, 8.569, 50.051, 8.571)}, now);
  TEST_EQUAL(cache.VenuesIntersecting(view, now).size(), 1, ());
  for (int i = 0; i < 40; ++i)
    cache.AddFailure({0, 0, 0.01 * i, 0.01}, now);
  TEST(!cache.Covers(view, now), ("old entries dropped beyond the limit"));
}

UNIT_TEST(Indoor_SelectVenue)
{
  // A big airport area and a terminal inside it; a station elsewhere.
  std::vector<VenueInfo> venues = {MakeInfo("airport", 50.03, 8.54, 50.06, 8.60),
                                   MakeInfo("t1", 50.045, 8.565, 50.052, 8.575),
                                   MakeInfo("station", 50.94, 6.95, 50.95, 6.96)};
  auto view = [](double lat, double lon, double half)
  { return m2::RectD(mercator::FromLatLon(lat - half, lon - half), mercator::FromLatLon(lat + half, lon + half)); };

  // Center in the terminal: the smaller one.
  auto s = SelectVenue(venues, view(50.048, 8.57, 0.001), "");
  TEST(s, ());
  TEST_EQUAL(venues[*s].m_id, "t1", ());
  // The current one is kept while it contains the center.
  s = SelectVenue(venues, view(50.048, 8.57, 0.001), "airport");
  TEST_EQUAL(venues[*s].m_id, "airport", ());
  // Center in the airport but outside the terminal: the airport, even if the terminal was shown.
  s = SelectVenue(venues, view(50.0535, 8.57, 0.002), "t1");
  TEST_EQUAL(venues[*s].m_id, "airport", ());
  // Center outside all: the current one while it is in view, else the one covering most of the view.
  s = SelectVenue(venues, view(50.0615, 8.57, 0.002), "airport");
  TEST_EQUAL(venues[*s].m_id, "airport", ());
  s = SelectVenue(venues, view(50.0615, 8.57, 0.002), "station");
  TEST_EQUAL(venues[*s].m_id, "airport", ());
  // Nothing in view.
  TEST(!SelectVenue(venues, view(48.0, 11.0, 0.001), "t1"), ());
  s = SelectVenue(venues, view(50.945, 6.955, 0.001), "t1");
  TEST_EQUAL(venues[*s].m_id, "station", ());
}

UNIT_TEST(Indoor_VenueDiskCache)
{
  TempDir dir;
  VenueDiskCache cache(dir.Path(), 3 /* maxFiles */, 1000 /* maxBytes */);
  std::string json, etag;
  TEST(!cache.Get("a", json, etag), ());
  cache.Put("a", "{\"a\":1}", "\"e1\"");
  TEST(cache.Get("a", json, etag), ());
  TEST_EQUAL(json, "{\"a\":1}", ());
  TEST_EQUAL(etag, "\"e1\"", ());
  TEST(!cache.Get("../a", json, etag), ());
  cache.Put("../evil", "x", "y");
  TEST_EQUAL(cache.GetCount(), 1, ());

  // Count limit: the least recently used goes.
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  cache.Put("b", "{}", "");
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  cache.Put("c", "{}", "");
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  cache.Touch("a");
  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  cache.Put("d", "{}", "");
  TEST_EQUAL(cache.GetCount(), 3, ());
  TEST(cache.Get("a", json, etag), ("touched, kept"));
  TEST(!cache.Get("b", json, etag), ("oldest, evicted"));

  // Byte limit.
  cache.Put("big", std::string(990, 'x'), "");
  TEST_LESS_OR_EQUAL(cache.GetBytes(), 1000, ());
  TEST(cache.Get("big", json, etag), ("the newest stays"));
  cache.Remove("big");
  TEST(!cache.Get("big", json, etag), ());

  // Survives a reopen.
  VenueDiskCache reopened(dir.Path(), 3, 1000);
  TEST(reopened.Get("a", json, etag) || reopened.Get("d", json, etag), ());
}
}  // namespace indoor_tests
