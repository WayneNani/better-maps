#include "testing/testing.hpp"

#include "search/common.hpp"
#include "search/token_slice.hpp"
#include "search/utils.hpp"

#include "indexer/categories_holder.hpp"
#include "indexer/classificator.hpp"
#include "indexer/classificator_loader.hpp"
#include "indexer/search_string_utils.hpp"

#include "base/stl_helpers.hpp"
#include "base/string_utils.hpp"

#include <algorithm>
#include <string>
#include <vector>

// Checks that search synonyms from the real data/categories.txt map to the expected types.
namespace category_synonyms_tests
{
using namespace search;
using namespace std;

struct Expectation
{
  char const * m_query;
  char const * m_type;  // Classificator path joined with '-', as in categories.txt.
};

uint32_t GetType(string const & path)
{
  uint32_t const type = classif().GetTypeByPathSafe(strings::Tokenize(path, "-"));
  TEST_NOT_EQUAL(type, Classificator::INVALID_TYPE, (path));
  return type;
}

// Mirrors Processor::InitParams() for non-categorial requests: every query token is fuzzily
// matched against category synonyms of English and |lang|. A feature of some type is found
// by category only if each token of the query maps to this type.
bool QueryMatchesType(string const & query, string const & lang, uint32_t type)
{
  auto const & categories = GetDefaultCategories();

  Locales locales;
  locales.insert(static_cast<uint64_t>(CategoriesHolder::kEnglishCode));
  locales.insert(static_cast<uint64_t>(CategoriesHolder::MapLocaleToInteger(lang)));

  auto const tokens = NormalizeAndTokenizeString(query);
  TEST(!tokens.empty(), (query));
  vector<vector<uint32_t>> typesPerToken(tokens.size());

  QuerySliceOnRawStrings<vector<strings::UniString>> const slice(tokens, {} /* prefix */);
  ForEachCategoryTypeFuzzy(slice, locales, categories,
                           [&typesPerToken](size_t i, uint32_t t) { typesPerToken[i].push_back(t); });

  return all_of(typesPerToken.begin(), typesPerToken.end(),
                [type](vector<uint32_t> const & types) { return base::IsExist(types, type); });
}

void TestExpectations(string const & lang, vector<Expectation> const & expectations, bool shouldMatch)
{
  for (auto const & e : expectations)
    TEST_EQUAL(QueryMatchesType(e.m_query, lang, GetType(e.m_type)), shouldMatch, (lang, e.m_query, e.m_type));
}

UNIT_TEST(CategorySynonyms_German)
{
  classificator::Load();

  TestExpectations("de",
                   {
                       // Gastronomy.
                       {"Café", "amenity-cafe"},
                       {"Cafe", "amenity-cafe"},
                       {"Kaffee", "amenity-cafe"},
                       {"Frühstück", "amenity-cafe"},
                       {"frühstücken", "amenity-cafe"},
                       {"Frühstück", "shop-bakery"},
                       {"Frühstück", "cuisine-breakfast"},
                       {"Brunch", "amenity-cafe"},
                       {"Restaurant", "amenity-restaurant"},
                       {"Wirtshaus", "amenity-restaurant"},
                       {"Gaststätte", "amenity-restaurant"},
                       {"Gaststätte", "amenity-pub"},
                       {"Mittagessen", "amenity-restaurant"},
                       {"Mittagstisch", "amenity-restaurant"},
                       {"Imbiss", "amenity-fast_food"},
                       {"Imbissbude", "amenity-fast_food"},
                       {"Schnellrestaurant", "amenity-fast_food"},
                       {"Kneipe", "amenity-pub"},
                       {"Eckkneipe", "amenity-pub"},
                       {"Beisl", "amenity-pub"},
                       {"Cocktailbar", "amenity-bar"},
                       {"Eisdiele", "amenity-ice_cream"},
                       {"Eiscafé", "amenity-ice_cream"},
                       {"Gelateria", "amenity-ice_cream"},

                       // Shopping.
                       {"Bäcker", "shop-bakery"},
                       {"Bäckerei", "shop-bakery"},
                       {"Brötchen", "shop-bakery"},
                       {"Semmel", "shop-bakery"},
                       {"Semmeln", "shop-bakery"},
                       {"Schrippe", "shop-bakery"},
                       {"Brezel", "shop-bakery"},
                       {"Backshop", "shop-bakery"},
                       {"Supermarkt", "shop-supermarket"},
                       {"Lebensmittelmarkt", "shop-supermarket"},
                       {"Vollsortimenter", "shop-supermarket"},
                       {"Bauernmarkt", "amenity-marketplace"},

                       // Transport.
                       {"Sprit", "amenity-fuel"},
                       {"tanken", "amenity-fuel"},
                       {"Tankstelle", "amenity-fuel"},
                       {"Tankstopp", "amenity-fuel"},
                       {"Haltestelle", "highway-bus_stop"},
                       {"Haltestellen", "highway-bus_stop"},
                       {"Bushalte", "highway-bus_stop"},
                       {"Bushalt", "highway-bus_stop"},
                       {"Bushäuschen", "highway-bus_stop"},
                       {"Straßenbahn", "railway-tram_stop"},
                       {"Strassenbahn", "railway-tram_stop"},
                       {"Trambahn", "railway-tram_stop"},
                       {"Fernbus", "amenity-bus_station"},
                       {"Busterminal", "amenity-bus_station"},
                       {"Hauptbahnhof", "railway-station"},
                       {"Bahnstation", "railway-station"},
                       {"Zughaltestelle", "railway-station"},
                       {"Sbahn", "railway-station"},
                       {"U-Bahnhof", "railway-station-subway"},
                       {"Ubahn", "railway-station-subway"},
                       {"Parkplatz", "amenity-parking"},
                       {"Parkmöglichkeit", "amenity-parking"},
                       {"Parkdeck", "amenity-parking"},
                       {"Ladesäule", "amenity-charging_station"},
                       {"Elektrotankstelle", "amenity-charging_station"},
                       {"Schnellladestation", "amenity-charging_station"},
                       {"Waschstraße", "amenity-car_wash"},
                       {"Waschanlage", "amenity-car_wash"},
                       {"Leihrad", "amenity-bicycle_rental"},
                       {"Bikesharing", "amenity-bicycle_rental"},

                       // Health.
                       {"Apotheke", "amenity-pharmacy"},
                       {"Apotheken", "amenity-pharmacy"},
                       {"Notapotheke", "amenity-pharmacy"},
                       {"Arzt", "amenity-doctors"},
                       {"Hausarzt", "amenity-doctors"},
                       {"Hausärztin", "amenity-doctors"},
                       {"Ärztin", "amenity-doctors"},
                       {"Allgemeinarzt", "amenity-doctors"},
                       {"Zahnarzt", "amenity-dentist"},
                       {"Zahnärztin", "amenity-dentist"},
                       {"Zahnarztpraxis", "amenity-dentist"},
                       {"Notaufnahme", "amenity-hospital"},
                       {"Klinikum", "amenity-hospital"},

                       // Money.
                       {"Geldautomat", "amenity-atm"},
                       {"Bankomat", "amenity-atm"},
                       {"Geld abheben", "amenity-atm"},
                       {"Geldausgabeautomat", "amenity-atm"},
                       {"Bank", "amenity-bank"},
                       {"Banken", "amenity-bank"},
                       {"Geldinstitut", "amenity-bank"},

                       // Accommodation.
                       {"Hotel", "tourism-hotel"},
                       {"Zimmer", "tourism-hotel"},
                       {"Zimmer", "tourism-guest_house"},
                       {"Gästezimmer", "tourism-guest_house"},
                       {"Übernachtungsmöglichkeit", "tourism-hotel"},

                       // Leisure.
                       {"Spielplatz", "leisure-playground"},
                       {"Kinderspielplatz", "leisure-playground"},
                       {"Badeanstalt", "leisure-sports_centre-sport-swimming"},
                       {"Kinos", "amenity-cinema"},
                       {"Lichtspiele", "amenity-cinema"},
                       {"Trinkwasserbrunnen", "amenity-drinking_water"},

                       // Authorities and services.
                       {"Rathaus", "amenity-townhall"},
                       {"Bürgeramt", "amenity-townhall"},
                       {"Bürgeramt", "office-government"},
                       {"Bürgerbüro", "amenity-townhall"},
                       {"Stadtverwaltung", "amenity-townhall"},
                       {"Landratsamt", "office-government"},
                       {"Polizeiposten", "amenity-police"},
                       {"Paket", "amenity-post_office"},
                       {"Briefmarken", "amenity-post_office"},
                   },
                   true /* shouldMatch */);

  // New synonyms must not leak into other categories, also not through typo tolerance.
  TestExpectations("de",
                   {
                       {"Frühstück", "amenity-pharmacy"},
                       {"Hausarzt", "amenity-dentist"},
                       {"Zahnarzt", "amenity-doctors"},
                       {"Notapotheke", "amenity-hospital"},
                       {"Notaufnahme", "amenity-pharmacy"},
                       {"Bürgerbüro", "amenity-community_centre"},
                       {"Banken", "amenity-bench"},
                       {"Zimmer", "tourism-camp_site"},
                       {"Parkdeck", "leisure-park"},
                       {"Park", "amenity-parking"},
                       {"Waschstraße", "shop-laundry"},
                       {"Geld abheben", "amenity-bank"},
                   },
                   false /* shouldMatch */);
}

UNIT_TEST(CategorySynonyms_German_FullNameIsCategorial)
{
  classificator::Load();
  auto const & categories = GetDefaultCategories();

  auto const contains = [&](string const & name, string const & type)
  { return base::IsExist(GetCategoryTypes(name, "de", categories), GetType(type)); };

  TEST(contains("Geld abheben", "amenity-atm"), ());
  TEST(contains("Frühstück", "amenity-cafe"), ());
  TEST(contains("Frühstück", "shop-bakery"), ());
  TEST(contains("Bushalte", "highway-bus_stop"), ());
  TEST(contains("S-Bahn", "railway-station"), ());
}
}  // namespace category_synonyms_tests
