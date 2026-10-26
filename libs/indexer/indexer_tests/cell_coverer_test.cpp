#include "testing/testing.hpp"

#include "geometry/covering_utils.hpp"

#include "indexer/cell_coverer.hpp"
#include "indexer/feature_covering.hpp"
#include "indexer/indexer_tests/bounds.hpp"

#include "coding/hex.hpp"

#include "geometry/latlon.hpp"
#include "geometry/mercator.hpp"

#include "base/logging.hpp"

#include <vector>

using namespace std;

// Unit test uses m2::CellId<30> for historical reasons, the actual production code uses RectId.
using CellId = m2::CellId<30>;

UNIT_TEST(CellIdToStringRecode)
{
  char const kTest[] = "21032012203";
  TEST_EQUAL(CellId::FromString(kTest).ToString(), kTest, ());
}

UNIT_TEST(GoldenCoverRect)
{
  vector<CellId> cells;
  CoverRect<OrthoBounds>({27.43, 53.83, 27.70, 53.96}, 4, RectId::DEPTH_LEVELS - 1, cells);

  TEST_EQUAL(cells.size(), 4, ());

  TEST_EQUAL(cells[0].ToString(), "32012211300", ());
  TEST_EQUAL(cells[1].ToString(), "32012211301", ());
  TEST_EQUAL(cells[2].ToString(), "32012211302", ());
  TEST_EQUAL(cells[3].ToString(), "32012211303", ());
}

UNIT_TEST(ArtificialCoverRect)
{
  typedef Bounds<0, 0, 16, 16> TestBounds;

  vector<CellId> cells;
  CoverRect<TestBounds>({5, 5, 11, 11}, 4, RectId::DEPTH_LEVELS - 1, cells);

  TEST_EQUAL(cells.size(), 4, ());

  TEST_EQUAL(cells[0].ToString(), "03", ());
  TEST_EQUAL(cells[1].ToString(), "12", ());
  TEST_EQUAL(cells[2].ToString(), "21", ());
  TEST_EQUAL(cells[3].ToString(), "30", ());
}

UNIT_TEST(MaxDepthCoverSpiral)
{
  using TestBounds = Bounds<0, 0, 8, 8>;

  for (auto levelMax = 0; levelMax <= 2; ++levelMax)
  {
    auto cells = vector<m2::CellId<3>>{};

    CoverSpiral<TestBounds, m2::CellId<3>>({2.1, 4.1, 2.1, 4.1}, levelMax, cells);

    TEST_EQUAL(cells.size(), 1, ());
    TEST_EQUAL(cells[0].Level(), levelMax, ());
  }
}

UNIT_TEST(CoverViewportAndAppendLowerLevels_SameAsAllLowerLevels)
{
  // The reference: lower levels of every cell, as in AppendLowerLevels().
  auto const reference = [](m2::RectD const & r, int cellDepth)
  {
    std::vector<RectId> ids;
    CoverRect<mercator::Bounds, RectId>(r, SPLIT_RECT_CELLS_COUNT, cellDepth - 1, ids);
    covering::Intervals intervals;
    for (auto const & id : ids)
      covering::AppendLowerLevels<RectId::DEPTH_LEVELS>(id, cellDepth, [&](covering::Interval const & i)
      { intervals.push_back(i); });
    return covering::SortAndMergeIntervals(intervals);
  };

  for (double const sizeM : {10.0, 500.0, 1000.0, 3000.0, 50000.0})
  {
    for (auto const & ll : {ms::LatLon(50.7374, 7.0982), ms::LatLon(52.52, 13.405), ms::LatLon(-33.86, 151.2),
                            ms::LatLon(0.0, 0.0)})
    {
      auto const rect = mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(ll), sizeM);
      for (int const scale : {10, 14, 17})
      {
        int const cellDepth = covering::GetCodingDepth<RectId::DEPTH_LEVELS>(scale);
        covering::Intervals res;
        covering::CoverViewportAndAppendLowerLevels<RectId::DEPTH_LEVELS>(rect, cellDepth, res);
        TEST(!res.empty(), ());
        TEST_EQUAL(res, reference(rect, cellDepth), (sizeM, ll, scale));
      }
    }
  }
}
