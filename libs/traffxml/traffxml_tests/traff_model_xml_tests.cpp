#include "testing/testing.hpp"

#include "traffxml/traffxml_tests/traff_test_support.hpp"

#include "traffxml/traff_model.hpp"
#include "traffxml/traff_model_xml.hpp"

#include "platform/platform.hpp"

#include "geometry/mercator.hpp"

#include "base/file_name_utils.hpp"

#include <pugixml.hpp>

#include <sstream>
#include <string>
#include <vector>

namespace traff_model_xml_tests
{
using traffxml::ResponseStatus;
using traffxml_tests::MakeMessageXml;
using traffxml_tests::MakeResponseXml;

UNIT_TEST(TraffXml_ParseResponseStatus)
{
  struct Case
  {
    std::string m_status;
    ResponseStatus m_expected;
  };
  Case const cases[] = {
      {"OK", ResponseStatus::Ok},
      {"INVALID", ResponseStatus::InvalidOperation},
      {"SUBSCRIPTION_REJECTED", ResponseStatus::SubscriptionRejected},
      {"NOT_COVERED", ResponseStatus::NotCovered},
      {"PARTIALLY_COVERED", ResponseStatus::PartiallyCovered},
      {"SUBSCRIPTION_UNKNOWN", ResponseStatus::SubscriptionUnknown},
      {"PUSH_REJECTED", ResponseStatus::PushRejected},
      {"INTERNAL_ERROR", ResponseStatus::InternalError},
      {"SOMETHING_ELSE", ResponseStatus::Invalid},
  };
  for (auto const & c : cases)
  {
    auto const response = traffxml::ParseResponse(MakeResponseXml(c.m_status));
    TEST_EQUAL(response.m_status, c.m_expected, (c.m_status));
    TEST(!response.m_feed, (c.m_status));
  }
}

UNIT_TEST(TraffXml_ParseResponseInvalidXml)
{
  TEST_EQUAL(traffxml::ParseResponse("").m_status, ResponseStatus::Invalid, ());
  TEST_EQUAL(traffxml::ParseResponse("<html>Bad gateway</html>").m_status, ResponseStatus::Invalid, ());
  TEST_EQUAL(traffxml::ParseResponse("<response/>").m_status, ResponseStatus::Invalid, ());
}

UNIT_TEST(TraffXml_ParseSubscribeResponse)
{
  // as sent by services/traff-server
  auto const response = traffxml::ParseResponse(
      MakeResponseXml("OK", R"(subscription_id="3f0c9a1e3f0c9a1e3f0c9a1e3f0c9a1e" timeout="3600")",
                      MakeMessageXml("de.autobahn.warning:1", R"(
      <event class="CONGESTION" type="CONGESTION_STATIONARY_TRAFFIC"></event>
      <event class="DELAY" type="DELAY_DELAY" q_duration="00:12"></event>)")));

  TEST_EQUAL(response.m_status, ResponseStatus::Ok, ());
  TEST_EQUAL(response.m_subscriptionId, "3f0c9a1e3f0c9a1e3f0c9a1e3f0c9a1e", ());
  TEST_EQUAL(response.m_timeout, 3600, ());
  TEST(response.m_feed, ());
  auto feed = response.m_feed.value();
  TEST_EQUAL(feed.size(), 1, ());

  auto & message = feed[0];
  TEST_EQUAL(message.m_id, "de.autobahn.warning:1", ());
  TEST(!message.m_cancellation, ());
  TEST(message.m_location, ());
  TEST(message.m_location->m_from, ());
  TEST(message.m_location->m_to, ());
  TEST_EQUAL(message.m_location->m_roadRef.value_or(""), "A 100", ());
  TEST_EQUAL(message.m_events.size(), 2, ());
  TEST(!message.IsExpired(traffic::IsoTime::Now()), ());

  auto const impact = message.GetTrafficImpact();
  TEST(impact, ());
  TEST_EQUAL(impact->m_speedGroup, traffic::SpeedGroup::G1, ());
  TEST_EQUAL(impact->m_delayMins, 12, ());
}

UNIT_TEST(TraffXml_ClosureImpactIsTempBlock)
{
  auto const response = traffxml::ParseResponse(MakeResponseXml(
      "OK", "",
      MakeMessageXml("de.autobahn.closure:1", R"(
      <event class="CONSTRUCTION" type="CONSTRUCTION_ROADWORKS"></event>
      <event class="RESTRICTION" type="RESTRICTION_CLOSED"></event>)")));
  TEST(response.m_feed, ());
  auto feed = response.m_feed.value();
  TEST_EQUAL(feed.size(), 1, ());
  // the unknown CONSTRUCTION_ROADWORKS event is skipped, the closure remains
  TEST_EQUAL(feed[0].m_events.size(), 1, ());
  auto const impact = feed[0].GetTrafficImpact();
  TEST(impact, ());
  TEST_EQUAL(impact->m_speedGroup, traffic::SpeedGroup::TempBlock, ());
}

// Documents current behavior (see FEATURES.md, Feature 4): event types the client does not know
// are skipped, and a message without any known event is dropped by the parser.
UNIT_TEST(TraffXml_MessageWithOnlyUnknownEventsIsDropped)
{
  auto const response = traffxml::ParseResponse(MakeResponseXml(
      "OK", "",
      MakeMessageXml("de.autobahn.roadworks:1", R"(
      <event class="CONSTRUCTION" type="CONSTRUCTION_ROADWORKS"></event>)")
          + MakeMessageXml("de.autobahn.warning:2", R"(
      <event class="CONGESTION" type="CONGESTION_QUEUE"></event>)")));
  TEST_EQUAL(response.m_status, ResponseStatus::Ok, ());
  TEST(response.m_feed, ());
  auto const & feed = response.m_feed.value();
  TEST_EQUAL(feed.size(), 1, ());
  TEST_EQUAL(feed[0].m_id, "de.autobahn.warning:2", ());
}

UNIT_TEST(TraffXml_EmptyFeed)
{
  auto const response = traffxml::ParseResponse(MakeResponseXml("OK", "", std::string()));
  TEST_EQUAL(response.m_status, ResponseStatus::Ok, ());
  TEST(response.m_feed, ());
  TEST(response.m_feed->empty(), ());
}

UNIT_TEST(TraffXml_FiltersToXml)
{
  std::vector<m2::RectD> rects = {
      m2::RectD(mercator::FromLatLon(52.3, 13.0), mercator::FromLatLon(52.7, 13.8)),
  };
  auto const xml = traffxml::FiltersToXml(rects);

  pugi::xml_document doc;
  TEST(doc.load_string(xml.c_str()), (xml));
  std::string const bbox = doc.child("filter").attribute("bbox").as_string();
  // minLat minLon maxLat maxLon
  std::vector<double> values;
  std::istringstream is(bbox);
  for (double v; is >> v;)
    values.push_back(v);
  TEST_EQUAL(values.size(), 4, (bbox));
  TEST_ALMOST_EQUAL_ABS(values[0], 52.3, 1e-4, (bbox));
  TEST_ALMOST_EQUAL_ABS(values[1], 13.0, 1e-4, (bbox));
  TEST_ALMOST_EQUAL_ABS(values[2], 52.7, 1e-4, (bbox));
  TEST_ALMOST_EQUAL_ABS(values[3], 13.8, 1e-4, (bbox));
}

// All sample feeds in data/test_data/traff must parse, and every message must be usable.
UNIT_TEST(TraffXml_ParseSampleFeeds)
{
  std::string const dir = base::JoinPath(GetPlatform().WritableDir(), "test_data", "traff");
  Platform::FilesList files;
  Platform::GetFilesByExt(dir, ".xml", files);
  TEST_GREATER_OR_EQUAL(files.size(), 5, (dir));

  for (auto const & file : files)
  {
    pugi::xml_document doc;
    TEST(doc.load_file(base::JoinPath(dir, file).c_str()), (file));
    traffxml::TraffFeed feed;
    TEST(traffxml::ParseTraff(doc, std::nullopt /* dataSource */, feed), (file));
    TEST(!feed.empty(), (file));
    for (auto & message : feed)
    {
      TEST(!message.m_id.empty(), (file));
      TEST(message.m_location, (file, message.m_id));
      TEST(!message.m_events.empty(), (file, message.m_id));
      TEST(message.GetTrafficImpact(), (file, message.m_id));
    }

    // round trip: generating XML and parsing it again yields the same messages
    pugi::xml_document generated;
    traffxml::GenerateTraff(feed, generated);
    traffxml::TraffFeed reparsed;
    TEST(traffxml::ParseTraff(generated, std::nullopt /* dataSource */, reparsed), (file));
    TEST_EQUAL(reparsed.size(), feed.size(), (file));
    for (size_t i = 0; i < feed.size(); ++i)
    {
      TEST_EQUAL(reparsed[i].m_id, feed[i].m_id, (file));
      TEST_EQUAL(reparsed[i].m_events.size(), feed[i].m_events.size(), (file));
    }
  }
}
}  // namespace traff_model_xml_tests
