#include "testing/testing.hpp"

#include "search/common.hpp"
#include "search/token_slice.hpp"
#include "search/utils.hpp"

#include "indexer/categories_holder.hpp"
#include "indexer/classificator.hpp"
#include "indexer/classificator_loader.hpp"
#include "indexer/ftypes_matcher.hpp"
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
                       {"Tanke", "amenity-fuel"},
                       {"volltanken", "amenity-fuel"},
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
                       {"Apo", "amenity-pharmacy"},
                       {"Arzt", "amenity-doctors"},
                       {"Hausarzt", "amenity-doctors"},
                       {"Hausärztin", "amenity-doctors"},
                       {"Ärztin", "amenity-doctors"},
                       {"Allgemeinarzt", "amenity-doctors"},
                       {"Doc", "amenity-doctors"},
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
                       {"Muckibude", "leisure-fitness_centre"},
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
                       {"Doc", "amenity-dentist"},
                       {"Tanke", "amenity-charging_station"},
                       {"Notapotheke", "amenity-hospital"},
                       {"Notaufnahme", "amenity-pharmacy"},
                       {"Bürgerbüro", "amenity-community_centre"},
                       {"Banken", "amenity-bench"},
                       {"Zimmer", "tourism-camp_site"},
                       {"Parkdeck", "leisure-park"},
                       {"Park", "amenity-parking"},
                       {"Waschstraße", "shop-laundry"},
                       {"Geld abheben", "amenity-bank"},
                       // A name of the main station, not a category: "Hauptbahnhof" should find "Berlin Hauptbahnhof",
                       // not the closest stations.
                       {"Hauptbahnhof", "railway-station"},
                   },
                   false /* shouldMatch */);
}

// Returns true if |name| is a full category name of |type|, i.e. typing it makes a categorial request
// (see Processor::SetQuery()). National cuisines are found only this way, they are never matched by single
// tokens of a longer query.
bool IsCategoryName(string const & name, string const & lang, string const & type)
{
  return base::IsExist(GetCategoryTypes(name, lang, GetDefaultCategories()), GetType(type));
}

void TestCategoryNames(string const & lang, vector<Expectation> const & expectations, bool shouldMatch)
{
  for (auto const & e : expectations)
    TEST_EQUAL(IsCategoryName(e.m_query, lang, e.m_type), shouldMatch, (lang, e.m_query, e.m_type));
}

UNIT_TEST(CategorySynonyms_German_Cuisines)
{
  classificator::Load();

  TestCategoryNames("de",
                    {
                        {"indisch", "cuisine-indian"},
                        {"Indische", "cuisine-indian"},
                        {"indisches", "cuisine-indian"},
                        {"Inder", "cuisine-indian"},
                        {"indisches Restaurant", "cuisine-indian"},
                        {"indischer Imbiss", "cuisine-indian"},
                        {"Indisches Essen", "cuisine-indian"},
                        // Was "10Indische Küche" in the data, the two-digit prefix length broke the name.
                        {"Indische Küche", "cuisine-indian"},
                        {"italienisch", "cuisine-italian"},
                        {"italienische", "cuisine-italian"},
                        {"Italiener", "cuisine-italian"},
                        {"italienisches Restaurant", "cuisine-italian"},
                        {"Italienische Küche", "cuisine-italian"},
                        {"chinesisch", "cuisine-chinese"},
                        {"chinesische", "cuisine-chinese"},
                        {"Chinese", "cuisine-chinese"},
                        {"Chinarestaurant", "cuisine-chinese"},
                        {"chinesisches Restaurant", "cuisine-chinese"},
                        {"chinesischer Imbiss", "cuisine-chinese"},
                        {"griechisch", "cuisine-greek"},
                        {"griechische", "cuisine-greek"},
                        {"Grieche", "cuisine-greek"},
                        {"türkisch", "cuisine-turkish"},
                        {"türkischer Imbiss", "cuisine-turkish"},
                        {"Thai", "cuisine-thai"},
                        {"thailändisch", "cuisine-thai"},
                        {"thailändische", "cuisine-thai"},
                        {"Thai Restaurant", "cuisine-thai"},
                        {"vietnamesisch", "cuisine-vietnamese"},
                        {"vietnamesische", "cuisine-vietnamese"},
                        {"Vietnamese", "cuisine-vietnamese"},
                        {"Pho", "cuisine-vietnamese"},
                        {"japanisch", "cuisine-japanese"},
                        {"Japaner", "cuisine-japanese"},
                        {"koreanisch", "cuisine-korean"},
                        {"Koreaner", "cuisine-korean"},
                        {"mexikanisch", "cuisine-mexican"},
                        {"Mexikaner", "cuisine-mexican"},
                        {"spanisch", "cuisine-spanish"},
                        {"Spanier", "cuisine-spanish"},
                        {"französisch", "cuisine-french"},
                        {"Franzose", "cuisine-french"},
                        {"libanesisch", "cuisine-lebanese"},
                        {"Libanese", "cuisine-lebanese"},
                        {"persisch", "cuisine-persian"},
                        {"iranisch", "cuisine-persian"},
                        {"äthiopisch", "cuisine-ethiopian"},
                        {"amerikanisch", "cuisine-american"},
                        {"georgisch", "cuisine-georgian"},
                        {"portugiesisch", "cuisine-portuguese"},
                        {"polnisch", "cuisine-polish"},
                        {"russisch", "cuisine-russian"},
                        {"mediterran", "cuisine-mediterranean"},
                        {"orientalisch", "cuisine-oriental"},
                        {"gutbürgerlich", "cuisine-german"},
                        {"Hausmannskost", "cuisine-german"},
                        // "asiatisch" is a group of all Asian cuisines.
                        {"asiatisch", "cuisine-asian"},
                        {"asiatisch", "cuisine-chinese"},
                        {"asiatisch", "cuisine-vietnamese"},
                        {"Asiate", "cuisine-thai"},
                        {"asiatischer Imbiss", "cuisine-japanese"},
                    },
                    true /* shouldMatch */);

  // English names, they are always matched, too (see Processor::GetCategoryLocales()).
  TestCategoryNames("en",
                    {
                        {"Indian", "cuisine-indian"},
                        {"Italian", "cuisine-italian"},
                        {"Italian restaurant", "cuisine-italian"},
                        {"Chinese", "cuisine-chinese"},
                        {"Thai", "cuisine-thai"},
                        {"Vietnamese", "cuisine-vietnamese"},
                        {"Pho", "cuisine-vietnamese"},
                        {"Asian", "cuisine-korean"},
                        {"French restaurant", "cuisine-french"},
                        {"Vietnamese Food", "cuisine-vietnamese"},
                    },
                    true /* shouldMatch */);

  // Ambiguous words stay names: they are too often the beginning of names of other places.
  TestCategoryNames("de",
                    {
                        {"deutsch", "cuisine-german"},
                        {"Deutsche", "cuisine-german"},
                        // "Amerikaner" is a pastry, "Perser" a carpet.
                        {"Amerikaner", "cuisine-american"},
                        {"Perser", "cuisine-persian"},
                        {"Asia", "cuisine-asian"},
                        {"bayerisch", "cuisine-bavarian"},
                        {"Türke", "cuisine-turkish"},
                        {"chinesische Medizin", "cuisine-chinese"},
                        {"Indische Straße", "cuisine-indian"},
                        {"Französische Straße", "cuisine-french"},
                    },
                    false /* shouldMatch */);

  TestCategoryNames("en",
                    {
                        // "French fries", "American Express", ...
                        {"French", "cuisine-french"},
                        {"American", "cuisine-american"},
                        {"Spanish", "cuisine-spanish"},
                        {"German", "cuisine-german"},
                    },
                    false /* shouldMatch */);

  // All of the above are national cuisines, so typing them makes a categorial request.
  for (auto const * name : {"indisch", "Italiener", "Thai", "asiatisch", "gutbürgerlich"})
    TEST(ftypes::IsNationalCuisineChecker::Instance()(GetCategoryTypes(name, "de", GetDefaultCategories())), (name));

  // Other cuisines are also matched by tokens of a longer query (with misprints).
  TestExpectations("de",
                   {
                       {"vegane", "cuisine-vegan"},
                       {"vegetarische", "cuisine-vegetarian"},
                       {"Veggie", "cuisine-vegetarian"},
                       {"Burgerladen", "cuisine-burger"},
                       {"Dönerladen", "cuisine-kebab"},
                       {"Dönerbude", "cuisine-kebab"},
                       {"Currywurst", "cuisine-sausage"},
                       {"Sushibar", "cuisine-sushi"},
                       {"Fischrestaurant", "cuisine-seafood"},
                       {"Steakhouse", "cuisine-steak_house"},
                   },
                   true /* shouldMatch */);

  // New words must not leak into other cuisines through typo tolerance.
  TestExpectations("de",
                   {
                       {"Inder", "cuisine-indonesian"},
                       {"indisch", "cuisine-indonesian"},
                       {"iranisch", "cuisine-indian"},
                       {"Currywurst", "cuisine-curry"},
                       {"Grieche", "cuisine-german"},
                       {"Japaner", "cuisine-sushi"},
                       // "... Restaurant" names are only for national cuisines, which are never matched by single
                       // words: "Restaurant" must not find cafés with vegan food.
                       {"Restaurant", "cuisine-vegan"},
                       {"Restaurant", "cuisine-vegetarian"},
                       {"Imbiss", "cuisine-vegan"},
                   },
                   false /* shouldMatch */);
}

UNIT_TEST(CategorySynonyms_German_Nightlife)
{
  classificator::Load();

  TestExpectations("de",
                   {
                       {"Club", "amenity-nightclub"},
                       {"Clubs", "amenity-nightclub"},
                       {"Disco", "amenity-nightclub"},
                       {"Disko", "amenity-nightclub"},
                       {"Diskothek", "amenity-nightclub"},
                       {"Disse", "amenity-nightclub"},
                       {"Tanzclub", "amenity-nightclub"},
                       {"Technoclub", "amenity-nightclub"},
                       {"Clubbing", "amenity-nightclub"},
                       {"Bar", "amenity-bar"},
                       {"Bars", "amenity-bar"},
                       {"Kneipe", "amenity-pub"},
                       {"Cocktails", "amenity-bar"},
                       {"Weinbar", "amenity-bar"},
                   },
                   true /* shouldMatch */);

  TestExpectations("de",
                   {
                       {"Clubs", "leisure-fitness_centre"},
                       {"Disse", "amenity-dentist"},
                       {"Weinbar", "shop-wine"},
                       {"Tanzclub", "leisure-dance"},
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
