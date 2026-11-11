#include "testing/testing.hpp"

#include "routing/server_route.hpp"
#include "routing/server_route_matcher.hpp"

#include "geometry/mercator.hpp"

#include "base/cancellable.hpp"

#include <cmath>
#include <functional>
#include <string>
#include <vector>

namespace server_route_tests
{
using namespace routing;
using namespace std;

// Polyline encoding (the inverse of DecodePolyline) for round trips.
string EncodePolyline(vector<ms::LatLon> const & points, int precision)
{
  string out;
  auto const encode = [&out](int64_t v)
  {
    uint64_t u = v < 0 ? ~(static_cast<uint64_t>(v) << 1) : (static_cast<uint64_t>(v) << 1);
    while (u >= 0x20)
    {
      out += static_cast<char>((0x20 | (u & 0x1f)) + 63);
      u >>= 5;
    }
    out += static_cast<char>(u + 63);
  };
  double const factor = pow(10.0, precision);
  int64_t lat = 0, lon = 0;
  for (auto const & p : points)
  {
    int64_t const la = llround(p.m_lat * factor);
    int64_t const lo = llround(p.m_lon * factor);
    encode(la - lat);
    encode(lo - lon);
    lat = la;
    lon = lo;
  }
  return out;
}

UNIT_TEST(ServerRoute_DecodePolyline)
{
  // The example of the documentation of the algorithm.
  vector<ms::LatLon> points;
  TEST(DecodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5, points), ());
  TEST_EQUAL(points.size(), 3, ());
  TEST(points[0].EqualDxDy(ms::LatLon(38.5, -120.2), 1e-9), (points[0]));
  TEST(points[1].EqualDxDy(ms::LatLon(40.7, -120.95), 1e-9), (points[1]));
  TEST(points[2].EqualDxDy(ms::LatLon(43.252, -126.453), 1e-9), (points[2]));

  vector<ms::LatLon> const route = {{50.7295477, 7.1057999}, {50.729601, 7.105912}, {49.453861, 11.0772885},
                                    {-33.9, 151.2}, {0, -0.000001}};
  TEST(DecodePolyline(EncodePolyline(route, 6), 6, points), ());
  TEST_EQUAL(points.size(), route.size(), ());
  for (size_t i = 0; i < route.size(); ++i)
    TEST(points[i].EqualDxDy(route[i], 1e-6), (i, points[i], route[i]));

  TEST(DecodePolyline("", 6, points), ());
  TEST(points.empty(), ());
  // Truncated (latitude without longitude), unfinished chunk, invalid characters, out of range.
  TEST(!DecodePolyline("_p~iF", 5, points), ());
  TEST(!DecodePolyline("_p~iF~ps|U_", 5, points), ());
  TEST(!DecodePolyline("_p~iF ps|U", 5, points), ());
  TEST(!DecodePolyline(EncodePolyline({{95.0, 0.0}}, 6), 6, points), ());
  TEST(!DecodePolyline("_p~iF~ps|U", 0, points), ());
}

UNIT_TEST(ServerRoute_ParseResponse)
{
  vector<ms::LatLon> const line = {{50.7295477, 7.1057999}, {49.453861, 11.0772885}};
  string const geometry = EncodePolyline(line, 6);
  auto const route = HttpServerRouteSource::ParseResponse(
      R"({"geometry":")" + geometry +
      R"(","precision":6,"distance_m":410123.5,"duration_s":15000.1,"data_timestamp":"2026-11-08T21:21:00Z","x":1})");
  TEST(route, ());
  TEST_EQUAL(route->m_polyline.size(), 2, ());
  TEST(route->m_polyline[1].EqualDxDy(line[1], 1e-6), ());
  TEST_ALMOST_EQUAL_ABS(route->m_distanceM, 410123.5, 1e-9, ());
  TEST_ALMOST_EQUAL_ABS(route->m_durationSec, 15000.1, 1e-9, ());
  TEST_EQUAL(route->m_dataTimestamp, "2026-11-08T21:21:00Z", ());

  // Precision 5, no timestamp.
  auto const route5 = HttpServerRouteSource::ParseResponse(R"({"geometry":"_p~iF~ps|U_ulLnnqC","precision":5,
      "distance_m":1000,"duration_s":60})");
  TEST(route5, ());
  TEST(route5->m_polyline[0].EqualDxDy(ms::LatLon(38.5, -120.2), 1e-9), ());

  TEST(!HttpServerRouteSource::ParseResponse("not json"), ());
  TEST(!HttpServerRouteSource::ParseResponse(R"({"error":"rate_limited"})"), ());
  TEST(!HttpServerRouteSource::ParseResponse(R"({"geometry":"_p~iF~ps|U","distance_m":10,"duration_s":5})"),
       ("a single point"));
  TEST(!HttpServerRouteSource::ParseResponse(R"({"geometry":")" + geometry + R"(","distance_m":0,"duration_s":5})"),
       ());
  TEST(!HttpServerRouteSource::ParseResponse(R"({"geometry":")" + geometry + R"(","distance_m":10})"), ());
  TEST(!HttpServerRouteSource::ParseResponse(R"({"geometry":"_p~iF~ps|U_","distance_m":10,"duration_s":5})"), ());
}

UNIT_TEST(ServerRoute_Request)
{
  TEST_EQUAL(HttpServerRouteSource::MakeRequestUrl("https://route.example.org"),
             "https://route.example.org/v1/route", ());
  TEST_EQUAL(HttpServerRouteSource::MakeRequestUrl("https://route.example.org//"),
             "https://route.example.org/v1/route", ());
  TEST_EQUAL(HttpServerRouteSource::MakeRequestUrl("https://example.org/bm/v1/route"),
             "https://example.org/bm/v1/route", ());
  TEST_EQUAL(HttpServerRouteSource::MakeRequestUrl("http://10.0.2.2:18100/bm"), "http://10.0.2.2:18100/bm/v1/route",
             ());
  TEST_EQUAL(HttpServerRouteSource::MakeRequestBody({{50.7295477, 7.1057999}, {49.453861, -11.0772885}}),
             R"({"points":[[50.7295477,7.1057999],[49.4538610,-11.0772885]]})", ());
}

UNIT_TEST(ServerRoute_HttpSourceEnabled)
{
  HttpServerRouteSource source;
  TEST(!source.IsEnabled(), ());
  source.SetServer("https://route.example.org", "");
  TEST(!source.IsEnabled(), ("no token"));
  source.SetServer("  ", "token");
  TEST(!source.IsEnabled(), ("no url"));
  source.SetServer(" https://route.example.org ", " token ");
  TEST(source.IsEnabled(), ());
  source.SetServer("", "");
  TEST(!source.IsEnabled(), ());
  // Disabled: no request at all.
  base::Cancellable const cancellable;
  TEST(!source.GetRoute({{50.0, 7.0}, {49.0, 11.0}}, cancellable), ());
}

// A fake routing graph near the equator: mercator x (about longitude) selects the mwm, by default mwm 1 for x < 0,
// mwm 2 for 0 <= x < 0.1, mwm 3 for x >= 0.1.
class FakeGraph : public ServerRouteMatcher::Graph
{
public:
  struct Exit
  {
    Segment m_segment;
    m2::PointD m_back;
    m2::PointD m_front;
    vector<Segment> m_twins;
  };

  function<NumMwmId(m2::PointD const &)> m_mwmOf = [](m2::PointD const & p) -> NumMwmId
  { return p.x < 0.0 ? 1 : (p.x < 0.1 ? 2 : 3); };
  vector<Exit> m_exits;
  size_t m_mwmCalls = 0;

  NumMwmId GetMwmId(m2::PointD const & point) override
  {
    ++m_mwmCalls;
    return m_mwmOf(point);
  }

  void ForEachExit(NumMwmId mwmId, vector<m2::RectD> const & rects, function<void(Segment const &)> const & fn) override
  {
    for (auto const & e : m_exits)
    {
      if (e.m_segment.GetMwmId() != mwmId)
        continue;
      m2::RectD r(e.m_back, e.m_front);
      for (auto const & rect : rects)
      {
        if (rect.IsIntersect(r))
        {
          fn(e.m_segment);
          break;
        }
      }
    }
  }

  m2::PointD GetPoint(Segment const & segment, bool front) override
  {
    auto const & e = Find(segment);
    return front ? e.m_front : e.m_back;
  }

  void GetTwins(Segment const & exit, vector<Segment> & twins) override { twins = Find(exit).m_twins; }

private:
  Exit const & Find(Segment const & s) const
  {
    for (auto const & e : m_exits)
      if (e.m_segment == s)
        return e;
    CHECK(false, (s));
    UNREACHABLE();
  }
};

// About 1 m in mercator units at the equator.
double constexpr kM = 1.0 / 111320.0;

// West to east from x = -0.05 to 0.15 (22 km), a vertex every 0.004 (445 m).
vector<m2::PointD> MakePolyline()
{
  vector<m2::PointD> line;
  for (int i = 0; i <= 50; ++i)
    line.emplace_back(-0.05 + 0.004 * i, 0.0);
  return line;
}

Segment const kExitA(1, 10, 0, true);
Segment const kEnterA(2, 20, 0, true);
Segment const kExitB(2, 30, 5, true);
Segment const kEnterB(3, 40, 7, false);

void AddRealExits(FakeGraph & g)
{
  // Mwm 1 -> 2 at x = 0, 70 m long. Mwm 2 -> 3 at x = 0.1.
  g.m_exits.push_back({kExitA, {-30 * kM, 0.0}, {40 * kM, 0.0}, {kEnterA}});
  g.m_exits.push_back({kExitB, {0.1 - 20 * kM, 0.0}, {0.1 + 25 * kM, 0.0}, {kEnterB}});
}

void AddDecoys(FakeGraph & g)
{
  // A parallel road 30 m away, a crossing road, the twin of the crossing in mwm 2 is no exit of mwm 1.
  g.m_exits.push_back({Segment(1, 12, 0, true), {-30 * kM, 30 * kM}, {40 * kM, 30 * kM}, {Segment(2, 22, 0, true)}});
  g.m_exits.push_back({Segment(1, 13, 3, true), {-5 * kM, -300 * kM}, {5 * kM, 300 * kM}, {Segment(2, 23, 3, true)}});
  // An exit of mwm 2 at the second border leading into mwm 4.
  g.m_exits.push_back({Segment(2, 31, 0, true), {0.1 - 20 * kM, 3 * kM}, {0.1 + 25 * kM, 3 * kM}, {Segment(4, 1, 0, true)}});
}

UNIT_TEST(ServerRouteMatcher_TwoBorders)
{
  FakeGraph g;
  AddDecoys(g);
  AddRealExits(g);
  vector<ServerRouteMatcher::Crossing> crossings;
  auto const line = MakePolyline();
  TEST_EQUAL(ServerRouteMatcher::Match(line, g, crossings), ServerRouteMatcher::Result::Ok, ());
  TEST_EQUAL(crossings.size(), 2, ());
  TEST_EQUAL(crossings[0].m_exit, kExitA, ());
  TEST_EQUAL(crossings[0].m_enter, kEnterA, ());
  TEST_EQUAL(crossings[1].m_exit, kExitB, ());
  TEST_EQUAL(crossings[1].m_enter, kEnterB, ());
  TEST_ALMOST_EQUAL_ABS(crossings[0].m_distanceM, mercator::DistanceOnEarth(line[0], {5 * kM, 0.0}), 1.0, ());
  TEST_LESS(crossings[0].m_distanceM, crossings[1].m_distanceM, ());
  // The mwm is sampled, not looked up for each vertex.
  TEST_LESS(g.m_mwmCalls, line.size(), ());
}

UNIT_TEST(ServerRouteMatcher_TransitionNotFound)
{
  {
    // No exit at the second border.
    FakeGraph g;
    g.m_exits.push_back({kExitA, {-30 * kM, 0.0}, {40 * kM, 0.0}, {kEnterA}});
    vector<ServerRouteMatcher::Crossing> crossings;
    TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings),
               ServerRouteMatcher::Result::TransitionNotFound, ());
    TEST(crossings.empty(), ());
  }
  {
    // Only decoys: too far away, crossing, twin in another mwm.
    FakeGraph g;
    AddDecoys(g);
    vector<ServerRouteMatcher::Crossing> crossings;
    TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings),
               ServerRouteMatcher::Result::TransitionNotFound, ());
  }
  {
    // The exit goes against the direction of the route.
    FakeGraph g;
    AddRealExits(g);
    swap(g.m_exits[0].m_back, g.m_exits[0].m_front);
    vector<ServerRouteMatcher::Crossing> crossings;
    TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings),
               ServerRouteMatcher::Result::TransitionNotFound, ());
  }
  {
    // The route goes west: the exits of mwm 1 to 2 don't fit.
    FakeGraph g;
    AddRealExits(g);
    auto line = MakePolyline();
    reverse(line.begin(), line.end());
    vector<ServerRouteMatcher::Crossing> crossings;
    TEST_EQUAL(ServerRouteMatcher::Match(line, g, crossings), ServerRouteMatcher::Result::TransitionNotFound, ());
  }
}

UNIT_TEST(ServerRouteMatcher_SimplifiedBorders)
{
  // The polygons put the border 400 m east of the real one (where the transition is).
  FakeGraph g;
  AddRealExits(g);
  g.m_mwmOf = [](m2::PointD const & p) -> NumMwmId { return p.x < 400 * kM ? 1 : (p.x < 0.1 ? 2 : 3); };
  vector<ServerRouteMatcher::Crossing> crossings;
  TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings), ServerRouteMatcher::Result::Ok, ());
  TEST_EQUAL(crossings.size(), 2, ());
  TEST_EQUAL(crossings[0].m_exit, kExitA, ());

  // Too far (1.5 km): not found.
  g.m_mwmOf = [](m2::PointD const & p) -> NumMwmId { return p.x < 1500 * kM ? 1 : (p.x < 0.1 ? 2 : 3); };
  TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings), ServerRouteMatcher::Result::TransitionNotFound,
             ());
}

UNIT_TEST(ServerRouteMatcher_PhantomExcursion)
{
  // The simplified polygons cut a corner: 1.5 km in mwm 2 look like mwm 1, without transitions on the road.
  FakeGraph g;
  AddRealExits(g);
  g.m_mwmOf = [](m2::PointD const & p) -> NumMwmId
  {
    if (p.x >= 0.05 && p.x < 0.0635)
      return 1;
    return p.x < 0.0 ? 1 : (p.x < 0.1 ? 2 : 3);
  };
  vector<ServerRouteMatcher::Crossing> crossings;
  TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings), ServerRouteMatcher::Result::Ok, ());
  TEST_EQUAL(crossings.size(), 2, ());
  TEST_EQUAL(crossings[0].m_exit, kExitA, ());
  TEST_EQUAL(crossings[1].m_exit, kExitB, ());

  // A real excursion: transitions into mwm 1 and back are found and kept.
  Segment const exitC(2, 50, 0, true), enterC(1, 51, 0, true), exitD(1, 52, 1, true), enterD(2, 53, 1, true);
  g.m_mwmOf = [](m2::PointD const & p) -> NumMwmId
  {
    if (p.x >= 0.05 && p.x < 0.07)
      return 1;
    return p.x < 0.0 ? 1 : (p.x < 0.1 ? 2 : 3);
  };
  g.m_exits.push_back({exitC, {0.05 - 10 * kM, 0.0}, {0.05 + 10 * kM, 0.0}, {enterC}});
  g.m_exits.push_back({exitD, {0.07 - 10 * kM, 0.0}, {0.07 + 10 * kM, 0.0}, {enterD}});
  TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings), ServerRouteMatcher::Result::Ok, ());
  TEST_EQUAL(crossings.size(), 4, ());
  TEST_EQUAL(crossings[1].m_exit, exitC, ());
  TEST_EQUAL(crossings[1].m_enter, enterC, ());
  TEST_EQUAL(crossings[2].m_exit, exitD, ());
  TEST_EQUAL(crossings[2].m_enter, enterD, ());
}

UNIT_TEST(ServerRouteMatcher_GapsBetweenBorders)
{
  // The simplified polygons don't touch: points between them (and at the start) belong to no mwm.
  FakeGraph g;
  AddRealExits(g);
  g.m_mwmOf = [](m2::PointD const & p) -> NumMwmId
  {
    if (p.x < -0.045 || (p.x >= -0.002 && p.x < 0.003) || (p.x >= 0.03 && p.x < 0.04))
      return ServerRouteMatcher::kUnknownMwmId;
    return p.x < 0.0 ? 1 : (p.x < 0.1 ? 2 : 3);
  };
  vector<ServerRouteMatcher::Crossing> crossings;
  TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings), ServerRouteMatcher::Result::Ok, ());
  TEST_EQUAL(crossings.size(), 2, ());
  TEST_EQUAL(crossings[0].m_exit, kExitA, ());
  TEST_EQUAL(crossings[1].m_exit, kExitB, ());

  // Only gaps: outside of the maps.
  g.m_mwmOf = [](m2::PointD const &) -> NumMwmId { return ServerRouteMatcher::kUnknownMwmId; };
  TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings), ServerRouteMatcher::Result::OutsideMaps, ());
}

UNIT_TEST(ServerRouteMatcher_OutsideMapsAndTooShort)
{
  FakeGraph g;
  AddRealExits(g);
  g.m_mwmOf = [](m2::PointD const & p) -> NumMwmId { return p.x < 0.0 ? 1 : (p.x < 0.12 ? 2 : kFakeNumMwmId); };
  vector<ServerRouteMatcher::Crossing> crossings;
  TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings), ServerRouteMatcher::Result::OutsideMaps, ());
  TEST_EQUAL(ServerRouteMatcher::Match({{0.0, 0.0}}, g, crossings), ServerRouteMatcher::Result::TooShort, ());

  // Within one mwm: no crossings.
  g.m_mwmOf = [](m2::PointD const &) -> NumMwmId { return 7; };
  TEST_EQUAL(ServerRouteMatcher::Match(MakePolyline(), g, crossings), ServerRouteMatcher::Result::Ok, ());
  TEST(crossings.empty(), ());
}
}  // namespace server_route_tests
