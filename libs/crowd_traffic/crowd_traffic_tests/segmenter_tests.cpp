#include "testing/testing.hpp"

#include "crowd_traffic/crowd_traffic_tests/test_support.hpp"
#include "crowd_traffic/segmenter.hpp"

#include <cmath>
#include <string>
#include <vector>

namespace segmenter_tests
{
using namespace crowd_traffic;
using namespace crowd_traffic::tests;

// A point given in global pixel coordinates of zoom 17.
TracePoint AtPixel(double px, double py, double t, RoadClass cls = RoadClass::Primary)
{
  auto const ll = Unproject(px, py);
  return {ll.m_lat, ll.m_lon, t, cls};
}

// Pixel origin of a tile in Berlin, to build synthetic traces on.
double constexpr kX0 = 70400.0 * kTileSize;
double constexpr kY0 = 42980.0 * kTileSize;

UNIT_TEST(CrowdTraffic_SegmentationMatchesGo)
{
  for (auto const & c : go_vectors::kSegCases)
  {
    std::vector<TracePoint> pts;
    for (size_t i = 0; i < c.numPts; ++i)
      pts.push_back(ToTracePoint(c.pts[i]));
    auto const trav = Segment(pts);
    TEST_EQUAL(trav.size(), c.numTrav, (c.name));
    for (size_t i = 0; i < std::min(trav.size(), c.numTrav); ++i)
    {
      auto const & t = trav[i];
      auto const & e = c.trav[i];
      std::string const ctx = std::string(c.name) + " #" + std::to_string(i);
      TEST_EQUAL(t.m_tile, (Tile{e.x, e.y}), (ctx));
      TEST(t.m_entry.m_side == SideFromChar(e.entry), (ctx));
      TEST_EQUAL(t.m_entry.m_offset, e.entryOffset, (ctx));
      TEST(t.m_exit.m_side == SideFromChar(e.exit), (ctx));
      TEST_EQUAL(t.m_exit.m_offset, e.exitOffset, (ctx));
      TEST(t.m_class == ClassFromInt(e.cls), (ctx));
      TEST_ALMOST_EQUAL_ABS(t.m_tIn, e.tIn, 1e-5, (ctx));
      TEST_ALMOST_EQUAL_ABS(t.m_tOut, e.tOut, 1e-5, (ctx));
      TEST_ALMOST_EQUAL_ABS(t.m_lengthM, e.lengthM, 1e-6, (ctx));
      TEST_ALMOST_EQUAL_ABS(t.m_distIn, e.distIn, 1e-6, (ctx));
      TEST_ALMOST_EQUAL_ABS(t.m_distOut, e.distOut, 1e-6, (ctx));
    }
  }
}

UNIT_TEST(CrowdTraffic_GridCrossingsDirections)
{
  // Eastwards over two vertical lines.
  auto c = GridCrossings({10, 10}, {600, 10});
  TEST_EQUAL(c.size(), 2, ());
  TEST(c[0].m_vertical && c[0].m_forward, ());
  TEST_ALMOST_EQUAL_ABS(c[0].m_t, (256.0 - 10) / 590, 1e-12, ());
  TEST_ALMOST_EQUAL_ABS(c[1].m_t, (512.0 - 10) / 590, 1e-12, ());

  // Westwards: lines in reverse order.
  c = GridCrossings({600, 10}, {10, 10});
  TEST_EQUAL(c.size(), 2, ());
  TEST(c[0].m_vertical && !c[0].m_forward, ());
  TEST_ALMOST_EQUAL_ABS(c[0].m_t, (600.0 - 512) / 590, 1e-12, ());
  TEST_ALMOST_EQUAL_ABS(c[1].m_t, (600.0 - 256) / 590, 1e-12, ());

  // Southwards and northwards (horizontal lines).
  c = GridCrossings({10, 10}, {10, 300});
  TEST_EQUAL(c.size(), 1, ());
  TEST(!c[0].m_vertical && c[0].m_forward, ());
  c = GridCrossings({10, 300}, {10, 10});
  TEST_EQUAL(c.size(), 1, ());
  TEST(!c[0].m_vertical && !c[0].m_forward, ());

  // No crossing inside a tile.
  TEST(GridCrossings({10, 10}, {250, 250}).empty(), ());
}

UNIT_TEST(CrowdTraffic_GridCrossingsCornerAndLines)
{
  // Exactly through a tile corner: both crossings at the same position, the vertical one first.
  auto c = GridCrossings({200, 200}, {300, 300});
  TEST_EQUAL(c.size(), 2, ());
  TEST_EQUAL(c[0].m_t, c[1].m_t, ());
  TEST(c[0].m_vertical, ());
  TEST(!c[1].m_vertical, ());
  // Same for the opposite direction.
  c = GridCrossings({300, 300}, {200, 200});
  TEST_EQUAL(c.size(), 2, ());
  TEST(c[0].m_vertical && !c[0].m_forward, ());
  TEST(!c[1].m_vertical && !c[1].m_forward, ());

  // A point on a grid line belongs to the tile east (south) of it: leaving eastwards from the
  // line crosses nothing, leaving westwards crosses the line immediately.
  TEST(GridCrossings({256, 10}, {300, 10}).empty(), ());
  c = GridCrossings({256, 10}, {200, 10});
  TEST_EQUAL(c.size(), 1, ());
  TEST_EQUAL(c[0].m_t, 0.0, ());
  TEST(GridCrossings({10, 512}, {10, 600}).empty(), ());
  c = GridCrossings({10, 512}, {10, 400});
  TEST_EQUAL(c.size(), 1, ());

  // Many lines in one step, ordered along the way.
  c = GridCrossings({0.5, 0.5}, {1000.5, 700.5});
  TEST_EQUAL(c.size(), 3 + 2, ());
  for (size_t i = 1; i < c.size(); ++i)
    TEST_LESS_OR_EQUAL(c[i - 1].m_t, c[i].m_t, ());
}

UNIT_TEST(CrowdTraffic_SegmentStraightEastwards)
{
  // From the middle of tile 0 to the middle of tile 3: tiles 1 and 2 are traversed completely,
  // the first and the last tile are not reported.
  std::vector<TracePoint> pts;
  for (int i = 0; i <= 30; ++i)
    pts.push_back(AtPixel(kX0 + 128 + i * 25.6, kY0 + 100.25, 1000.0 + i));
  auto const trav = Segment(pts);
  TEST_EQUAL(trav.size(), 2, ());
  for (size_t i = 0; i < trav.size(); ++i)
  {
    auto const & t = trav[i];
    TEST_EQUAL(t.m_tile, (Tile{70401 + static_cast<int>(i), 42980}), ());
    TEST(t.m_entry.m_side == Side::West, ());
    TEST(t.m_exit.m_side == Side::East, ());
    TEST_EQUAL(t.m_entry.m_offset, 100, ());
    TEST_EQUAL(t.m_exit.m_offset, 100, ());
    TEST(t.m_class == RoadClass::Primary, ());
    // 10 samples per tile, one second each.
    TEST_ALMOST_EQUAL_ABS(t.m_tOut - t.m_tIn, 10.0, 1e-6, ());
    TEST_GREATER(t.m_lengthM, 180, ());
    TEST_LESS(t.m_lengthM, 190, ());
    TEST_ALMOST_EQUAL_ABS(t.m_distOut - t.m_distIn, t.m_lengthM, 1e-6, ());
    TEST_ALMOST_EQUAL_ABS(t.SpeedKmh(), t.m_lengthM / 10 * 3.6, 1e-6, ());
  }
  // The exit of tile 1 is the entry of tile 2.
  TEST_EQUAL(trav[0].m_tOut, trav[1].m_tIn, ());
  TEST_EQUAL(trav[0].m_distOut, trav[1].m_distIn, ());
}

UNIT_TEST(CrowdTraffic_SegmentNorthwestAndOffsets)
{
  // Diagonal north-west: exits through N or W edges, offsets measured from the NW corner.
  std::vector<TracePoint> pts;
  for (int i = 0; i <= 40; ++i)
    pts.push_back(AtPixel(kX0 + 900 - i * 20.0, kY0 + 700 - i * 13.0, 2000.0 + i));
  auto const trav = Segment(pts);
  TEST_GREATER(trav.size(), 2, ());
  for (auto const & t : trav)
  {
    TEST(t.m_exit.m_side == Side::North || t.m_exit.m_side == Side::West, ());
    TEST(t.m_entry.m_side == Side::South || t.m_entry.m_side == Side::East, ());
    TEST_GREATER_OR_EQUAL(t.m_exit.m_offset, 0, ());
    TEST_LESS(t.m_exit.m_offset, kTileSize, ());
  }
  // Consecutive traversals connect: the next tile is the neighbour across the exit edge and is
  // entered at the same offset through the opposite edge.
  for (size_t i = 1; i < trav.size(); ++i)
  {
    TEST_EQUAL(trav[i].m_tile, trav[i - 1].m_tile.Neighbor(trav[i - 1].m_exit.m_side), ());
    TEST(trav[i].m_entry.m_side == Opposite(trav[i - 1].m_exit.m_side), ());
    TEST_EQUAL(trav[i].m_entry.m_offset, trav[i - 1].m_exit.m_offset, ());
  }
}

UNIT_TEST(CrowdTraffic_SegmentClassChangeAndGap)
{
  // Class changes inside tile 1 → tile 1 is not reportable, tile 2 is.
  std::vector<TracePoint> pts;
  for (int i = 0; i <= 40; ++i)
  {
    double const px = kX0 + 128 + i * 25.6;
    pts.push_back(AtPixel(px, kY0 + 50, 3000.0 + i, px < kX0 + 256 + 100 ? RoadClass::Secondary : RoadClass::Primary));
  }
  auto trav = Segment(pts);
  TEST_EQUAL(trav.size(), 3, ());
  TEST(trav[0].m_class == RoadClass::None, ());
  TEST(trav[1].m_class == RoadClass::Primary, ());
  TEST(trav[2].m_class == RoadClass::Primary, ());

  // A gap (lost map matching) in tile 2.
  Segmenter s;
  std::vector<Traversal> out;
  for (int i = 0; i <= 40; ++i)
  {
    s.Add(AtPixel(kX0 + 128 + i * 25.6, kY0 + 50, 3000.0 + i), out);
    if (i == 20)  // In the middle of tile 2.
      s.MarkGap();
  }
  TEST_EQUAL(out.size(), 3, ());
  TEST(out[0].m_class == RoadClass::Primary, ());
  TEST(out[1].m_class == RoadClass::None, ());
  TEST(out[2].m_class == RoadClass::Primary, ());
  TEST(s.HasPoints(), ());
  TEST_GREATER(s.GetDistanceM(), 4 * 180.0, ());
  s.Reset();
  TEST(!s.HasPoints(), ());
  s.MarkGap();  // No effect without points.
  TEST_EQUAL(s.GetDistanceM(), 0.0, ());
}

UNIT_TEST(CrowdTraffic_SegmentUTurnAndCorner)
{
  // Enter tile 1 from the west and leave through the west edge again (U-turn).
  std::vector<TracePoint> pts = {AtPixel(kX0 + 200, kY0 + 100, 0), AtPixel(kX0 + 400, kY0 + 100, 10),
                                 AtPixel(kX0 + 400, kY0 + 140, 12), AtPixel(kX0 + 200, kY0 + 140, 22)};
  auto trav = Segment(pts);
  TEST_EQUAL(trav.size(), 1, ());
  TEST(trav[0].m_entry.m_side == Side::West && trav[0].m_exit.m_side == Side::West, ());
  TEST_EQUAL(trav[0].m_entry.m_offset, 100, ());
  TEST_EQUAL(trav[0].m_exit.m_offset, 140, ());

  // Cutting a corner of tile (x+1, y): a very short traversal from W to S.
  pts = {AtPixel(kX0 + 200, kY0 + 200, 0), AtPixel(kX0 + 300, kY0 + 300, 10)};
  trav = Segment(pts);
  // The step passes the corner (256, 256): the tile in between (x+1, y or x, y+1, depending on
  // rounding) is crossed with (almost) zero length through two perpendicular edges.
  TEST_EQUAL(trav.size(), 1, ());
  TEST_LESS(trav[0].m_lengthM, 1.0, ());
  TEST(trav[0].m_entry.m_side != trav[0].m_exit.m_side, ());
  TEST(trav[0].m_entry.m_side != Opposite(trav[0].m_exit.m_side), ());

  // A single point and an empty trace produce nothing.
  TEST(Segment({}).empty(), ());
  TEST(Segment({AtPixel(kX0, kY0, 0)}).empty(), ());
}

UNIT_TEST(CrowdTraffic_SegmentIncrementalEqualsBatch)
{
  auto const & c = go_vectors::kSegCases[0];
  std::vector<TracePoint> pts;
  for (size_t i = 0; i < c.numPts; ++i)
    pts.push_back(ToTracePoint(c.pts[i]));
  auto const batch = Segment(pts);
  Segmenter s;
  std::vector<Traversal> inc;
  for (auto const & p : pts)
  {
    size_t const before = inc.size();
    s.Add(p, inc);
    // Every traversal is returned as soon as the tile is left, i.e. its exit time lies between
    // the previous and this sample.
    for (size_t i = before; i < inc.size(); ++i)
      TEST_LESS_OR_EQUAL(inc[i].m_tOut, p.m_time, ());
  }
  TEST_EQUAL(inc.size(), batch.size(), ());
  for (size_t i = 0; i < inc.size(); ++i)
    TEST_EQUAL(inc[i].Key(), batch[i].Key(), ());
  TEST_GREATER(Traversal{}.SpeedKmh(), 1e300, ());
}
}  // namespace segmenter_tests
