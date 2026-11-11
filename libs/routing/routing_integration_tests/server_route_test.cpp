#include "testing/testing.hpp"

#include "routing/routing_integration_tests/routing_test_tools.hpp"

#include "routing/index_router.hpp"
#include "routing/route.hpp"
#include "routing/server_route.hpp"

#include "geometry/mercator.hpp"

#include "base/scope_guard.hpp"

#include <memory>
#include <optional>
#include <vector>

// Server-assisted car routing (IndexRouter::SetServerRouteSource) with fake servers: the route of the server is used
// when it fits, and every failure falls back to exactly the local route. Needs the maps of Germany.
namespace server_route_test
{
using namespace routing;
using namespace std;

class FakeServer : public ServerRouteSource
{
public:
  bool IsEnabled() const override { return m_enabled; }
  optional<ServerRoute> GetRoute(vector<ms::LatLon> const & points, base::Cancellable const &) override
  {
    ++m_calls;
    m_lastPoints = points;
    return m_route;
  }

  bool m_enabled = true;
  optional<ServerRoute> m_route;
  int m_calls = 0;
  vector<ms::LatLon> m_lastPoints;
};

ms::LatLon const kBonn(50.7295477, 7.1057999);
ms::LatLon const kNuremberg(49.453861, 11.0772885);

IndexRouter & GetCarRouter()
{
  return dynamic_cast<IndexRouter &>(integration::GetVehicleComponents(VehicleType::Car).GetRouter());
}

Route CalcRoute()
{
  auto const [route, code] = integration::CalculateRoute(integration::GetVehicleComponents(VehicleType::Car),
                                                         mercator::FromLatLon(kBonn), {0.0, 0.0},
                                                         mercator::FromLatLon(kNuremberg));
  TEST_EQUAL(code, RouterResultCode::NoError, ());
  TEST(route, ());
  return *route;
}

ServerRoute ToServerRoute(Route const & route)
{
  ServerRoute r;
  for (auto const & p : route.GetPoly().GetPoints())
    r.m_polyline.push_back(mercator::ToLatLon(p));
  r.m_distanceM = route.GetTotalDistanceMeters();
  r.m_durationSec = route.GetTotalTimeSec();
  return r;
}

void TestSameRoute(Route const & a, Route const & b)
{
  TEST_EQUAL(a.GetPoly().GetPoints(), b.GetPoly().GetPoints(), ());
  TEST_ALMOST_EQUAL_ABS(a.GetTotalTimeSec(), b.GetTotalTimeSec(), 1e-6, ());
}

UNIT_TEST(ServerRoute_BonnNuremberg_UsedOrFallback)
{
  auto & router = GetCarRouter();
  SCOPE_GUARD(resetSource, [&router] { router.SetServerRouteSource(nullptr); });

  router.SetServerRouteSource(nullptr);
  Route const local = CalcRoute();

  auto server = make_shared<FakeServer>();
  router.SetServerRouteSource(server);
  auto const & stats = router.GetServerRoutingStats();

  // Switched off: not asked, same route.
  server->m_enabled = false;
  TestSameRoute(CalcRoute(), local);
  TEST_EQUAL(server->m_calls, 0, ());
  server->m_enabled = true;

  // The server follows the same way (here: the local route itself): its leaps are refined to the same route.
  server->m_route = ToServerRoute(local);
  auto used = stats.m_used;
  Route const assisted = CalcRoute();
  TEST_EQUAL(server->m_calls, 1, ());
  TEST_EQUAL(stats.m_used, used + 1, (stats.m_lastFallback));
  TEST(server->m_lastPoints.front().EqualDxDy(kBonn, 1e-6), ());
  TEST(server->m_lastPoints.back().EqualDxDy(kNuremberg, 1e-6), ());
  integration::TestRouteLength(assisted, local.GetTotalDistanceMeters(), 0.01);
  integration::TestRouteTime(assisted, local.GetTotalTimeSec(), 0.01);
  // A normal route with turn instructions.
  size_t turns = 0;
  for (auto const & s : assisted.GetRouteSegments())
    if (!s.GetTurn().IsTurnNone())
      ++turns;
  TEST_GREATER(turns, 10, ());

  // Every failure gives exactly the local route.
  auto const testFallback = [&](optional<ServerRoute> serverRoute)
  {
    server->m_route = std::move(serverRoute);
    auto const fallbacks = stats.m_fallbacks;
    TestSameRoute(CalcRoute(), local);
    TEST_EQUAL(stats.m_fallbacks, fallbacks + 1, ());
    LOG(LINFO, ("Fallback:", stats.m_lastFallback));
  };

  // No answer (unreachable, timeout, HTTP error).
  testFallback(nullopt);

  // A straight line across the fields: no transitions where it crosses the borders.
  ServerRoute straight;
  straight.m_polyline = {kBonn, kNuremberg};
  straight.m_distanceM = 330000;
  straight.m_durationSec = 12000;
  testFallback(straight);

  // The right way, but the server claims a much shorter route: implausible.
  auto implausible = ToServerRoute(local);
  implausible.m_distanceM /= 2;
  testFallback(implausible);

  // The server's route leaves the maps (south into the Alps and back).
  auto outside = ToServerRoute(local);
  outside.m_polyline.insert(outside.m_polyline.begin() + outside.m_polyline.size() / 2, ms::LatLon(46.0, 9.0));
  testFallback(outside);
}
}  // namespace server_route_test
