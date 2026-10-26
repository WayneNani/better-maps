#include "search/street_vicinity_loader.hpp"

#include "search/cancel_exception.hpp"
#include "search/mwm_context.hpp"

#include "indexer/cell_id.hpp"
#include "indexer/data_header.hpp"
#include "indexer/feature.hpp"
#include "indexer/feature_covering.hpp"
#include "indexer/feature_decl.hpp"
#include "indexer/ftypes_matcher.hpp"
#include "indexer/mwm_set.hpp"

#include "geometry/mercator.hpp"
#include "geometry/point2d.hpp"

#include "base/assert.hpp"
#include "base/math.hpp"

#include <algorithm>
#include <limits>

#include "3party/ankerl/unordered_dense.h"

namespace search
{
StreetVicinityLoader::StreetVicinityLoader(int scale, double offsetMeters)
  : m_context(nullptr)
  , m_scale(scale)
  , m_offsetMeters(offsetMeters)
  , m_cache("Streets")
{}

void StreetVicinityLoader::SetContext(MwmContext * context)
{
  ASSERT(context, ());
  if (m_context == context)
    return;

  m_context = context;
  auto const scaleRange = m_context->m_value.GetHeader().GetScaleRange();
  m_scale = math::Clamp(m_scale, scaleRange.first, scaleRange.second);
}

void StreetVicinityLoader::OnQueryFinished()
{
  m_cache.ClearIfNeeded();
}

StreetVicinityLoader::Street const & StreetVicinityLoader::GetStreet(uint32_t featureId, bool ignoreEditedStatus)
{
  auto r = m_cache.Get(featureId);
  if (!r.second)
    return r.first;

  LoadStreet(featureId, r.first, ignoreEditedStatus);
  return r.first;
}

bool StreetVicinityLoader::IsStreet(FeatureType & ft)
{
  auto const geomType = ft.GetGeomType();
  // Highway should be line or area.
  bool const isLineOrArea = (geomType == feature::GeomType::Line || geomType == feature::GeomType::Area);
  // Square also maybe a point (besides line or area).
  return ((isLineOrArea && ftypes::IsWayChecker::Instance()(ft)) || ftypes::IsSquareChecker::Instance()(ft));
}

bool StreetVicinityLoader::GetStreetRect(uint32_t featureId, bool ignoreEditedStatus, m2::RectD & rect) const
{
  auto feature = m_context->GetFeature(featureId, ignoreEditedStatus);
  if (!feature || !IsStreet(*feature))
    return false;

  /// @todo Can be optimized here. Do not aggregate rect, but aggregate covering intervals for each segment, instead.
  auto const sumRect = [&rect, this](m2::PointD const & pt)
  { rect.Add(mercator::RectByCenterXYAndSizeInMeters(pt, m_offsetMeters)); };

  if (feature->GetGeomType() == feature::GeomType::Area)
  {
    feature->ForEachTriangle([&sumRect](m2::PointD const & p1, m2::PointD const & p2, m2::PointD const & p3)
    {
      sumRect(p1);
      sumRect(p2);
      sumRect(p3);
    }, FeatureType::BEST_GEOMETRY);
  }
  else
    feature->ForEachPoint(sumRect, FeatureType::BEST_GEOMETRY);
  ASSERT(rect.IsValid(), ());
  return true;
}

void StreetVicinityLoader::LoadStreet(uint32_t featureId, Street & street, bool ignoreEditedStatus)
{
  if (!GetStreetRect(featureId, ignoreEditedStatus, street.m_rect))
    return;

  covering::CoveringGetter coveringGetter(street.m_rect, covering::ViewportWithLowLevels);
  auto const & intervals = coveringGetter.Get<RectId::DEPTH_LEVELS>(m_scale);
  m_context->ForEachIndex(intervals, m_scale, [this, &street, featureId](uint32_t id)
  {
    if (m_context->GetStreet(id) == featureId)
      street.m_features.push_back(id);
  });

  // street.m_calculator = std::make_unique<ProjectionOnStreetCalculator>(points);
}

void StreetVicinityLoader::LoadStreets(std::vector<uint32_t> const & streetIds, base::Cancellable const & cancellable)
{
  struct Pending
  {
    uint32_t m_featureId;
    Street m_street;
    covering::Intervals m_intervals;
  };

  std::vector<Pending> pending;
  // Street feature id -> index in |pending|.
  ankerl::unordered_dense::map<uint32_t, uint32_t> streetToPending;
  for (uint32_t const id : streetIds)
  {
    if (m_cache.Has(id) || streetToPending.contains(id))
      continue;

    BailIfCancelled(cancellable);

    Pending p;
    p.m_featureId = id;
    if (GetStreetRect(id, false /* ignoreEditedStatus */, p.m_street.m_rect))
    {
      covering::CoveringGetter coveringGetter(p.m_street.m_rect, covering::ViewportWithLowLevels);
      p.m_intervals = coveringGetter.Get<RectId::DEPTH_LEVELS>(m_scale);
    }
    streetToPending.emplace(id, static_cast<uint32_t>(pending.size()));
    pending.push_back(std::move(p));
  }

  if (pending.empty())
    return;

  covering::Intervals allIntervals;
  for (auto const & p : pending)
    allIntervals.insert(allIntervals.end(), p.m_intervals.begin(), p.m_intervals.end());
  covering::Intervals const mergedIntervals = covering::SortAndMergeIntervals(std::move(allIntervals));

  // Reads the index once for the union of all vicinities and keeps only the features of the pending streets, in the
  // order they are read: by bucket and then by key (as LoadStreet() reads them for each interval).
  struct Entry
  {
    uint64_t m_key;
    uint32_t m_featureId;
    uint32_t m_pending;
  };
  uint32_t constexpr kNotPending = std::numeric_limits<uint32_t>::max();

  size_t const numBuckets = m_context->GetNumIndexBuckets(m_scale);
  std::vector<std::vector<Entry>> entries(numBuckets);
  // Feature id -> index of its street in |pending| (as checked in LoadStreet()) or kNotPending.
  ankerl::unordered_dense::map<uint32_t, uint32_t> featureToPending;
  uint32_t numRead = 0;
  for (size_t bucket = 0; bucket < numBuckets; ++bucket)
  {
    for (auto const & interval : mergedIntervals)
    {
      m_context->ForEachIndexInBucket(interval.first, interval.second, bucket, [&](uint64_t key, uint32_t id)
      {
        if ((++numRead & 0xFFF) == 0)
          BailIfCancelled(cancellable);

        auto const [it, inserted] = featureToPending.try_emplace(id, kNotPending);
        if (inserted && !m_context->IsDeleted(id))
        {
          if (auto const street = m_context->GetStreet(id))
          {
            auto const pendingIt = streetToPending.find(*street);
            if (pendingIt != streetToPending.end())
              it->second = pendingIt->second;
          }
        }
        if (it->second != kNotPending)
          entries[bucket].push_back({key, id, it->second});
      });
    }
  }

  ankerl::unordered_dense::set<uint32_t> added;
  for (uint32_t i = 0; i < pending.size(); ++i)
  {
    auto & p = pending[i];
    added.clear();
    for (auto const & interval : p.m_intervals)
    {
      for (auto const & bucketEntries : entries)
      {
        auto it = std::lower_bound(bucketEntries.begin(), bucketEntries.end(), static_cast<uint64_t>(interval.first),
                                   [](Entry const & e, uint64_t key) { return e.m_key < key; });
        for (; it != bucketEntries.end() && it->m_key < static_cast<uint64_t>(interval.second); ++it)
        {
          if (it->m_pending == i && added.insert(it->m_featureId).second)
            p.m_street.m_features.push_back(it->m_featureId);
        }
      }
    }

    m_cache.Get(p.m_featureId).first = std::move(p.m_street);
  }
}
}  // namespace search
