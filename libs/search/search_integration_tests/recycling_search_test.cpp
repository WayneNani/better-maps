#include "testing/testing.hpp"

#include "search/result.hpp"
#include "search/search_params.hpp"
#include "search/search_tests_support/helpers.hpp"
#include "search/search_tests_support/test_results_matching.hpp"
#include "search/search_tests_support/test_search_request.hpp"

#include "generator/generator_tests_support/test_feature.hpp"
#include "generator/generator_tests_support/test_mwm_builder.hpp"

#include "indexer/classificator.hpp"
#include "indexer/feature_meta.hpp"

#include "i18n/localisation_translation.hpp"

#include "geometry/rect2d.hpp"

#include <string>
#include <vector>

// better-maps: nameless recycling containers show their materials and are kept apart unless they are real duplicates.
namespace recycling_search_test
{
using namespace generator::tests_support;
using namespace search::tests_support;
using namespace search;
using namespace std;

class RecyclingSearchTest : public SearchTest
{
public:
  RecyclingSearchTest()
    : m_glass({0.0, 0.0}, "", "en")
    , m_clothes({0.00001, 0.0}, "", "en")
    , m_paper1({0.001, 0.0}, "", "en")
    , m_paper2({0.001, 0.00001}, "", "en")
  {
    // Two containers at the same place, like at Belderberg in Bonn: glass, and clothes with shoes of an operator.
    m_glass.SetTypes({{"amenity", "recycling", "container"}, {"recycling", "glass_bottles"}});
    m_clothes.SetTypes({{"amenity", "recycling", "container"}, {"recycling", "clothes"}, {"recycling", "shoes"}});
    m_clothes.GetMetadata().Set(feature::Metadata::FMD_OPERATOR, "REMONDIS");
    // Real duplicates: the same container mapped twice.
    m_paper1.SetTypes({{"amenity", "recycling", "container"}, {"recycling", "paper"}});
    m_paper2.SetTypes({{"amenity", "recycling", "container"}, {"recycling", "paper"}});

    m_mwmId = BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
    {
      builder.Add(m_glass);
      builder.Add(m_clothes);
      builder.Add(m_paper1);
      builder.Add(m_paper2);
    });
    SetViewport(m2::RectD(-0.01, -0.01, 0.01, 0.01));
  }

  vector<Result> Search(string const & query, string const & locale, bool categorial)
  {
    SearchParams params = GetDefaultSearchParams(query, locale);
    params.m_categorialRequest = categorial;
    return MakeRequest(params)->Results();
  }

  Rule Match(TestFeature const & feature) const { return ExactMatch(m_mwmId, feature); }
  Rule MatchPaper() const { return AlternativesMatch({Match(m_paper1), Match(m_paper2)}); }

  static string Translated(base::StringIL const & path)
  {
    return localisation::TranslatedFeatureType(classif().GetReadableObjectName(classif().GetTypeByPath(path)));
  }

  Result const * Find(vector<Result> const & results, TestFeature const & feature)
  {
    for (auto const & r : results)
      if (ResultsMatch({r}, {Match(feature)}))
        return &r;
    return nullptr;
  }

protected:
  TestPOI m_glass;
  TestPOI m_clothes;
  TestPOI m_paper1;
  TestPOI m_paper2;

  MwmSet::MwmId m_mwmId;
};

UNIT_CLASS_TEST(RecyclingSearchTest, ContainersWithDifferentDetailsAreKept)
{
  for (bool const categorial : {true, false})
  {
    auto const results = Search(categorial ? "recycling " : "recycling", "en", categorial);
    Rules const rules = {Match(m_glass), Match(m_clothes), MatchPaper()};
    TEST(ResultsMatch(results, rules), (categorial, results));
    TEST_EQUAL(results.size(), 3, (categorial, results));
  }
}

UNIT_CLASS_TEST(RecyclingSearchTest, MaterialsAndOperatorInDescription)
{
  auto const results = Search("recycling ", "en", true /* categorial */);

  auto const * glass = Find(results, m_glass);
  TEST(glass, (results));
  TEST_EQUAL(glass->GetString(), "", ());
  // The main type is the title of a nameless result, the materials are its description.
  TEST_EQUAL(glass->GetLocalizedFeatureType(), Translated({"amenity", "recycling", "container"}), ());
  TEST_EQUAL(glass->GetFeatureDescription(), Translated({"recycling", "glass_bottles"}), ());

  auto const * clothes = Find(results, m_clothes);
  TEST(clothes, (results));
  auto const description = clothes->GetFeatureDescription();
  TEST_NOT_EQUAL(description.find(Translated({"recycling", "clothes"})), string::npos, (description));
  TEST_NOT_EQUAL(description.find(Translated({"recycling", "shoes"})), string::npos, (description));
  TEST_NOT_EQUAL(description.find("REMONDIS"), string::npos, (description));
}

UNIT_CLASS_TEST(RecyclingSearchTest, GermanMaterialWords)
{
  for (auto const * query : {"Altglas", "Glascontainer", "Altglascontainer"})
    TEST(ResultsMatch(Search(query, "de", false /* categorial */), {Match(m_glass)}), (query));
  for (auto const * query : {"Altkleider", "Kleidercontainer", "Altkleidercontainer"})
    TEST(ResultsMatch(Search(query, "de", false /* categorial */), {Match(m_clothes)}), (query));
  for (auto const * query : {"Altpapier", "Papiercontainer"})
    TEST(ResultsMatch(Search(query, "de", false /* categorial */), {MatchPaper()}), (query));
  // All containers.
  TEST(ResultsMatch(Search("Wertstoffcontainer", "de", false /* categorial */),
                    {Match(m_glass), Match(m_clothes), MatchPaper()}),
       ());
}
}  // namespace recycling_search_test
