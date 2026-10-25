#pragma once

#include "search/model.hpp"
#include "search/ranking_utils.hpp"
#include "search/token_range.hpp"

#include "editor/yes_no_unknown.hpp"

#include "indexer/ftypes_matcher.hpp"

#include <array>
#include <ostream>
#include <string>

namespace feature
{
class TypesHolder;
}

namespace search
{
/// @note The order is important here (less is better)
enum class PoiType : uint8_t
{
  // Railway/subway stations, airports.
  TransportMajor,
  // Bus/tram stops.
  TransportLocal,
  // Cafes, restaurants, bars.
  Eat,
  // Hotels.
  Hotel,
  // Shop or Amenity.
  ShopOrAmenity,
  // Attractions.
  Attraction,
  // Car Infra
  CarInfra,

  // Factor for *pure category* matched result.
  PureCategory,

  // All other POIs.
  General,
  // Service types: power lines and substations, barrier-fence, etc.
  Service,

  Count
};

using StreetType = ftypes::IsWayChecker::SearchRank;

struct StoredRankingInfo
{
  // Do not change this constant, it is used to normalize distance and takes part in distance rank, accordingly.
  static double constexpr kMaxDistMeters = 2.0E6;

  // Distance from the feature to the pivot point.
  double m_distanceToPivot = kMaxDistMeters;

  // Search type for the feature.
  Model::Type m_type = Model::TYPE_COUNT;

  // Used for non-categorial requests.
  union
  {
    PoiType poi;        // type (food/transport/attraction/etc) for POI results
    StreetType street;  // type (peddestrian/residential/regular/etc) for Street results
  } m_classifType;
};

struct RankingInfo : public StoredRankingInfo
{
  RankingInfo()
    : m_isAltOrOldName(false)
    , m_allTokensUsed(true)
    , m_exactMatch(true)
    , m_pureCats(false)
    , m_falseCats(false)
    , m_categorialRequest(false)
    , m_hasName(false)
    , m_nearbyMatch(false)
    , m_categoryQuery(false)
    , m_secondaryTypeMatch(false)
    , m_hasCategoryType(false)
    , m_hasWikipedia(false)
    , m_hasBrand(false)
  {
    m_classifType.street = StreetType::Default;
  }

  static void PrintCSVHeader(std::ostream & os);

  void ToCSV(std::ostream & os) const;

  /// @param[in]  viewportMode  True, and distance is not included into the final rank.
  /// @return Rank calculated by a linear model, bigger is better.
  double GetLinearModelRank(bool viewportMode = false) const;

  static double GetLinearRankViewportThreshold();

  double GetErrorsMadePerToken() const;

  NameScore GetNameScore() const;
  Model::Type GetTypeScore() const;
  PoiType GetPoiTypeScore() const;

  // True for POIs (and buildings found by a category) if the query asks for a category, e.g. "Café", "Apotheke".
  // The search for a category is a search for places nearby, see GetLocalPoiRank().
  bool IsLocalPoiSearch() const;
  // Rank of POIs for category queries: closer, open and more important POIs are better. Bigger is better.
  double GetLocalPoiRank(bool viewportMode) const;

  // Matched parts of the query.
  // todo(@m) Using TokenType instead of ModelType here would
  //          allow to distinguish postcodes too.
  std::array<TokenRange, Model::TYPE_COUNT> m_tokenRanges;

  // Fraction of characters from original query matched to feature.
  float m_matchedFraction = 0.0;

  // Query tokens number.
  uint16_t m_numTokens = 0;

  // > 0 if matched only common tokens. Bigger is worse, depending on Feature's name tokens count.
  int16_t m_commonTokensFactor = 0;

  // Number of misprints.
  ErrorsMade m_errorsMade;

  // Rank of the feature. Store uint16_t because of possible 'normalization'.
  uint16_t m_rank = 0;

  // Popularity rank of the feature.
  uint8_t m_popularity = 0;

  // Score for the feature's name.
  NameScore m_nameScore = NameScore::ZERO;

  // alt_name or old_name is used.
  bool m_isAltOrOldName : 1;

  // True iff all tokens that are not stop-words
  // were used when retrieving the feature.
  bool m_allTokensUsed : 1;

  // True iff all tokens retrieved from search index were matched without misprints.
  bool m_exactMatch : 1;

  // True if all of the tokens that the feature was matched by
  // correspond to this feature's categories.
  bool m_pureCats : 1;

  // True if none of the tokens that the feature was matched by
  // corresponds to this feature's categories although all of the
  // tokens are categorial ones.
  bool m_falseCats : 1;

  // True iff the request is categorial.
  bool m_categorialRequest : 1;

  // True iff the feature has a name.
  bool m_hasName : 1;

  // Nearby match: POI and Complex POI (planning POI and Street/Suburb/City).
  bool m_nearbyMatch : 1;

  // The query asks for a category: all tokens are category synonyms, e.g. "Café", or the request is categorial.
  bool m_categoryQuery : 1;

  // The feature was matched by a category of its secondary type, e.g. a museum with a cafe for "Café".
  bool m_secondaryTypeMatch : 1;

  // The feature has a type of the query category, also if it's matched by name: a pharmacy named
  // "Apotheke am Markt" for "Apotheke", but not a bar named "Apothekenbar".
  bool m_hasCategoryType : 1;

  // POI importance signals, see GetLocalPoiRank().
  // The feature has a Wikipedia article or Wikimedia Commons page.
  bool m_hasWikipedia : 1;
  // The feature belongs to a brand (chain).
  bool m_hasBrand : 1;
  // Number of informative tags: opening hours, website, phone, contacts, operator, check date, photos.
  uint8_t m_infoTagsCount = 0;

  // Opening state of the POI at the search moment, Unknown if it has no (valid) opening hours.
  osm::YesNoUnknown m_isOpen = osm::Unknown;
};

PoiType GetPoiType(feature::TypesHolder const & th);

std::string DebugPrint(StoredRankingInfo const & info);
std::string DebugPrint(RankingInfo const & info);

std::string DebugPrint(PoiType type);
std::string DebugPrint(StreetType type);
}  // namespace search
