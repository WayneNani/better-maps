#include "search/route_corridor.hpp"

#include "geometry/mercator.hpp"
#include "geometry/parametrized_segment.hpp"

#include "base/assert.hpp"
#include "base/logging.hpp"
#include "base/math.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <sstream>

namespace search
{
namespace
{
// A chunk of the route is about this many corridor half widths long, within these limits.
double constexpr kChunkLengthInHalfWidths = 4.0;
double constexpr kMinChunkLengthM = 1000.0;
double constexpr kMaxChunkLengthM = 10000.0;
size_t constexpr kMaxChunkSegments = 64;

// Mercator units per meter at the latitude of |y|. The projection is conformal, so this is the same in all
// directions, and it is nearly constant within a few kilometers.
double MercatorPerMeter(double y)
{
  double const cosLat = std::cos(math::DegToRad(mercator::YToLat(y)));
  return mercator::MetersToMercator(1.0) / std::max(cosLat, 1e-3);
}

double Interpolate(std::vector<double> const & values, size_t i, double fraction)
{
  return values[i] + (values[i + 1] - values[i]) * fraction;
}
}  // namespace

std::string DebugPrint(RoutePosition const & position)
{
  std::ostringstream os;
  os << "RoutePosition [fromRoute: " << position.m_distanceFromRouteM
     << " m, alongRoute: " << position.m_distanceAlongRouteM << " m, " << position.m_timeAlongRouteSec
     << " s, detour: " << position.m_detourSec << " s]";
  return os.str();
}

RouteCorridor::RouteCorridor(Params const & params)
  : m_halfWidthM(params.m_halfWidthM)
  , m_detourSpeedMps(params.m_detourSpeedMps)
  , m_detourCircuity(params.m_detourCircuity)
{
  auto const & points = params.m_points;
  bool const hasTimes = !params.m_timesSec.empty();
  ASSERT_GREATER(m_detourSpeedMps, 0.0, ());
  if (hasTimes && params.m_timesSec.size() != points.size())
  {
    LOG(LWARNING, ("Route times do not match the points:", params.m_timesSec.size(), points.size()));
    return;
  }
  if (points.size() < 2)
    return;

  // Cut the route at the corridor start: the point at |m_passedDistanceM| along the route.
  double passedM = 0.0;
  double const startM = std::max(params.m_passedDistanceM, 0.0);
  double startTimeSec = 0.0;
  for (size_t i = 0; i + 1 < points.size(); ++i)
  {
    double const segmentM = mercator::DistanceOnEarth(points[i], points[i + 1]);
    if (m_points.empty())
    {
      if (passedM + segmentM <= startM && i + 2 < points.size())
      {
        passedM += segmentM;
        continue;
      }
      double const fraction = segmentM > 0.0 ? std::clamp((startM - passedM) / segmentM, 0.0, 1.0) : 0.0;
      m_points.push_back(points[i] + (points[i + 1] - points[i]) * fraction);
      m_distancesM.push_back(0.0);
      if (hasTimes)
      {
        startTimeSec = Interpolate(params.m_timesSec, i, fraction);
        m_timesSec.push_back(0.0);
      }
      double const restM = segmentM * (1.0 - fraction);
      if (restM <= 0.0)
        continue;
      m_points.push_back(points[i + 1]);
      m_distancesM.push_back(restM);
      if (hasTimes)
        m_timesSec.push_back(params.m_timesSec[i + 1] - startTimeSec);
      continue;
    }

    m_points.push_back(points[i + 1]);
    m_distancesM.push_back(m_distancesM.back() + segmentM);
    if (hasTimes)
      m_timesSec.push_back(params.m_timesSec[i + 1] - startTimeSec);
  }

  if (m_points.size() < 2)
  {
    m_points.clear();
    m_distancesM.clear();
    m_timesSec.clear();
    return;
  }

  BuildChunks();
}

void RouteCorridor::BuildChunks()
{
  double const chunkLengthM = std::clamp(kChunkLengthInHalfWidths * m_halfWidthM, kMinChunkLengthM, kMaxChunkLengthM);

  size_t begin = 0;
  while (begin + 1 < m_points.size())
  {
    size_t end = begin + 1;
    while (end + 1 < m_points.size() && end - begin < kMaxChunkSegments &&
           m_distancesM[end] - m_distancesM[begin] < chunkLengthM)
    {
      ++end;
    }

    Chunk chunk;
    chunk.m_begin = begin;
    chunk.m_end = end;
    double maxAbsY = 0.0;
    for (size_t i = begin; i <= end; ++i)
    {
      chunk.m_rect.Add(m_points[i]);
      maxAbsY = std::max(maxAbsY, std::abs(m_points[i].y));
    }
    // The scale grows with the latitude: inflate with the scale of the point nearest to a pole, and a bit more.
    double const inflation = 1.01 * m_halfWidthM * MercatorPerMeter(maxAbsY);
    chunk.m_rect.Inflate(inflation, inflation);
    m_limitRect.Add(chunk.m_rect);
    m_chunks.push_back(chunk);

    begin = end;
  }
}

std::vector<m2::RectD> RouteCorridor::GetCoveringRects() const
{
  std::vector<m2::RectD> rects;
  rects.reserve(m_chunks.size());
  for (auto const & chunk : m_chunks)
    rects.push_back(chunk.m_rect);
  return rects;
}

std::optional<RoutePosition> RouteCorridor::Locate(m2::PointD const & point) const
{
  if (IsEmpty() || !m_limitRect.IsPointInside(point))
    return {};

  double const mercatorPerMeter = MercatorPerMeter(point.y);
  double const halfWidth = m_halfWidthM * mercatorPerMeter;
  double const behindTolerance = kBehindStartToleranceM * mercatorPerMeter;

  struct Candidate
  {
    size_t m_segment;
    double m_fraction;
    double m_distance;
  };
  std::vector<Candidate> candidates;
  double minDistance = std::numeric_limits<double>::max();

  for (auto const & chunk : m_chunks)
  {
    if (!chunk.m_rect.IsPointInside(point))
      continue;

    for (size_t i = chunk.m_begin; i < chunk.m_end; ++i)
    {
      m2::PointD const & a = m_points[i];
      m2::PointD const & b = m_points[i + 1];
      m2::PointD const ab = b - a;
      double const lengthSq = ab.SquaredLength();
      double const t = lengthSq > 0.0 ? m2::DotProduct(point - a, ab) / lengthSq : 0.0;

      // Behind the corridor start: already passed.
      if (i == 0 && t < 0.0 && -t * std::sqrt(lengthSq) > behindTolerance)
        continue;

      double const fraction = std::clamp(t, 0.0, 1.0);
      double const distance = point.Length(a + ab * fraction);
      if (distance > halfWidth)
        continue;

      candidates.push_back({i, fraction, distance});
      minDistance = std::min(minDistance, distance);
    }
  }

  if (candidates.empty())
    return {};

  // Chunks are ordered along the route, so are the candidates: take the first one near enough to the nearest.
  double const firstPassTolerance = kFirstPassToleranceM * mercatorPerMeter;
  auto const it = std::find_if(candidates.begin(), candidates.end(),
                               [&](Candidate const & c) { return c.m_distance <= minDistance + firstPassTolerance; });
  CHECK(it != candidates.end(), ());

  RoutePosition position;
  position.m_distanceFromRouteM = it->m_distance / mercatorPerMeter;
  position.m_distanceAlongRouteM = Interpolate(m_distancesM, it->m_segment, it->m_fraction);
  if (!m_timesSec.empty())
    position.m_timeAlongRouteSec = Interpolate(m_timesSec, it->m_segment, it->m_fraction);
  position.m_detourSec = 2.0 * position.m_distanceFromRouteM * m_detourCircuity / m_detourSpeedMps;
  return position;
}

std::vector<size_t> SpreadAlongRoute(std::vector<double> const & distancesAlongRouteM,
                                     std::vector<double> const & priorities, double lengthM, size_t limit)
{
  CHECK_EQUAL(distancesAlongRouteM.size(), priorities.size(), ());

  size_t const count = distancesAlongRouteM.size();
  std::vector<size_t> selected;
  if (count <= limit)
  {
    selected.resize(count);
    std::iota(selected.begin(), selected.end(), 0);
  }
  else if (limit > 0)
  {
    // At least 4 places per part, at most 50 parts: parts of 8 km for a route of 400 km, a town is in a few of them.
    size_t const numParts = std::clamp<size_t>(limit / 4, 1, 50);
    double const partLengthM = std::max(lengthM, 1.0) / numParts;

    std::vector<std::vector<size_t>> parts(numParts);
    for (size_t i = 0; i < count; ++i)
    {
      auto const part = static_cast<size_t>(std::max(distancesAlongRouteM[i], 0.0) / partLengthM);
      parts[std::min(part, numParts - 1)].push_back(i);
    }
    for (auto & part : parts)
    {
      // Places with equal priorities are taken in order along the route.
      std::sort(part.begin(), part.end(), [&](size_t l, size_t r)
      {
        if (priorities[l] != priorities[r])
          return priorities[l] < priorities[r];
        return distancesAlongRouteM[l] < distancesAlongRouteM[r];
      });
    }

    for (size_t round = 0; selected.size() < limit; ++round)
    {
      bool anyLeft = false;
      for (auto const & part : parts)
      {
        if (round >= part.size())
          continue;
        anyLeft = true;
        selected.push_back(part[round]);
        if (selected.size() == limit)
          break;
      }
      if (!anyLeft)
        break;
    }
  }

  std::stable_sort(selected.begin(), selected.end(),
                   [&](size_t l, size_t r) { return distancesAlongRouteM[l] < distancesAlongRouteM[r]; });
  return selected;
}
}  // namespace search
