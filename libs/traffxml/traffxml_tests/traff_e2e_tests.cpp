// End-to-end tests against a running TraFF service, see tools/better-maps/traffic-e2e.sh.
//
// These tests are skipped unless the following environment variables are set:
//   TRAFF_E2E_URL           URL of the TraFF service, e.g. http://127.0.0.1:18081/
//   TRAFF_E2E_MAPS_DIR      directory with the MWMs to use (see TRAFF_E2E_MWMS)
// Optional:
//   TRAFF_E2E_MWMS          comma-separated MWM names, default "Germany_Berlin"
//   TRAFF_E2E_RESTART_CMD   shell command which restarts the service (all subscriptions are lost)
//                           and returns once it is ready again
//   TRAFF_E2E_EXPECT_FIXTURE if "1", check the messages of the offline fixture used by the script

#include "testing/testing.hpp"

#include "traffxml/traffxml_tests/traff_test_support.hpp"

#include "traffxml/traff_decoder.hpp"
#include "traffxml/traff_model.hpp"
#include "traffxml/traff_source.hpp"

#include "storage/country_info_getter.hpp"
#include "storage/country_parent_getter.hpp"

#include "indexer/classificator_loader.hpp"
#include "indexer/data_source.hpp"

#include "platform/local_country_file.hpp"
#include "platform/platform.hpp"

#include "base/file_name_utils.hpp"
#include "base/logging.hpp"
#include "base/string_utils.hpp"

#include <chrono>
#include <cstdlib>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <thread>

namespace traff_e2e_tests
{
using namespace std::chrono;
using traffxml_tests::TestSourceManager;

auto constexpr kResponseTimeout = seconds(60);

std::string GetEnv(char const * name)
{
  char const * value = std::getenv(name);
  return value ? value : "";
}

/// A `HttpTraffSource` talking to a real service, with access to its state.
class E2eHttpTraffSource : public traffxml::HttpTraffSource
{
public:
  E2eHttpTraffSource(traffxml::TraffSourceManager & manager, std::string const & url)
    : traffxml::HttpTraffSource(manager, url)
  {}

  std::string GetSubscriptionId()
  {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_subscriptionId;
  }

  Availability GetAvailability() const { return m_lastAvailability; }

  /// Unsubscribes and waits for the response, so no request is in flight when the source is destroyed.
  void UnsubscribeAndWait()
  {
    Unsubscribe();
    for (int i = 0; i < 300 && !GetSubscriptionId().empty(); ++i)
      std::this_thread::sleep_for(milliseconds(100));
    // let the request thread return
    std::this_thread::sleep_for(milliseconds(200));
  }
};

class TraffE2eTest
{
public:
  TraffE2eTest()
    : m_url(GetEnv("TRAFF_E2E_URL"))
    , m_mapsDir(GetEnv("TRAFF_E2E_MAPS_DIR"))
  {
    if (!IsEnabled())
    {
      LOG(LWARNING, ("TRAFF_E2E_URL or TRAFF_E2E_MAPS_DIR not set, skipping end-to-end test"));
      return;
    }
    classificator::Load();

    std::string mwmNames = GetEnv("TRAFF_E2E_MWMS");
    if (mwmNames.empty())
      mwmNames = "Germany_Berlin";
    for (auto const & name : strings::Tokenize(mwmNames, ","))
    {
      platform::LocalCountryFile file(m_mapsDir, platform::CountryFile(std::string(name)), 0 /* version */);
      auto const result = m_dataSource.RegisterMap(file);
      TEST_EQUAL(result.second, MwmSet::RegResult::Success, ("Cannot register", file));
      m_mwms.insert(result.first);
    }
    m_manager.SetActiveMwms(m_mwms);
  }

  bool IsEnabled() const { return !m_url.empty() && !m_mapsDir.empty(); }

  /// Decodes all messages of `feed`, returns the number of messages with decoded segments.
  size_t Decode(traffxml::TraffFeed & feed)
  {
    if (!m_decoder)
    {
      m_countryInfoGetter = storage::CountryInfoReader::CreateCountryInfoGetter(GetPlatform());
      m_countryParentGetter = std::make_unique<storage::CountryParentGetter>();
      m_decoder = std::make_unique<traffxml::DefaultTraffDecoder>(
          m_dataSource, [this]() -> storage::CountryInfoGetter const & { return *m_countryInfoGetter; },
          [this](std::string const & id) { return (*m_countryParentGetter)(id); }, m_messageCache);
    }

    size_t decodedCount = 0;
    for (auto & message : feed)
    {
      if (message.m_cancellation)
        continue;
      m_decoder->DecodeMessage(message);
      size_t segments = 0;
      for (auto const & [mwmId, coloring] : message.m_decoded)
        segments += coloring.size();
      LOG(LINFO, ("Decoded", message.m_id, "to", segments, "segment(s)"));
      if (segments > 0)
        ++decodedCount;
      m_messageCache.insert_or_assign(message.m_id, message);
    }
    return decodedCount;
  }

protected:
  std::string const m_url;
  std::string const m_mapsDir;
  FrozenDataSource m_dataSource;
  std::set<MwmSet::MwmId> m_mwms;
  TestSourceManager m_manager;
  std::unique_ptr<storage::CountryInfoGetter> m_countryInfoGetter;
  std::unique_ptr<storage::CountryParentGetter> m_countryParentGetter;
  std::map<std::string, traffxml::TraffMessage> m_messageCache;
  std::unique_ptr<traffxml::DefaultTraffDecoder> m_decoder;
};

// Subscribes to the service, receives the first feed and decodes its messages to map segments.
UNIT_CLASS_TEST(TraffE2eTest, TraffE2E_SubscribeAndDecode)
{
  if (!IsEnabled())
    return;

  E2eHttpTraffSource source(m_manager, m_url);
  source.SubscribeOrChangeSubscription(m_mwms);
  TEST(m_manager.WaitForFeeds(1, kResponseTimeout), ("No feed received from", m_url));
  TEST(!source.GetSubscriptionId().empty(), ());
  TEST(source.GetAvailability() == traffxml::TraffSource::Availability::IsAvailable, ());
  // the first feed was not empty, so the next poll is due after the poll interval
  TEST(!source.IsPollNeeded(), ());

  auto feed = m_manager.GetFeeds().front();
  LOG(LINFO, ("Received", feed.size(), "message(s)"));
  TEST(!feed.empty(), ());

  bool hasClosure = false;
  bool hasCongestion = false;
  for (auto & message : feed)
  {
    auto const impact = message.GetTrafficImpact();
    TEST(impact, (message));
    if (impact->m_speedGroup == traffic::SpeedGroup::TempBlock)
      hasClosure = true;
    else if (impact->m_speedGroup != traffic::SpeedGroup::Unknown)
      hasCongestion = true;
  }

  size_t const decoded = Decode(feed);
  LOG(LINFO, ("Decoded", decoded, "of", feed.size(), "message(s)"));
  TEST_GREATER(decoded, 0, ());

  if (GetEnv("TRAFF_E2E_EXPECT_FIXTURE") == "1")
  {
    // tools/better-maps/traffic-e2e/autobahn: closure and queue on A100 in Berlin
    TEST(hasClosure, ());
    TEST(hasCongestion, ());
    TEST_EQUAL(decoded, feed.size(), ());
    for (auto const & message : feed)
    {
      TEST_EQUAL(message.m_decoded.size(), 1, (message.m_id));
      auto const & [mwmId, coloring] = *message.m_decoded.begin();
      TEST_EQUAL(mwmId.GetInfo()->GetCountryName(), "Germany_Berlin", ());
      TEST(!coloring.empty(), (message.m_id));
      bool const isClosure =
          traffxml::TraffMessage(message).GetTrafficImpact()->m_speedGroup == traffic::SpeedGroup::TempBlock;
      for (auto const & [segment, speedGroup] : coloring)
      {
        // closures block the road, congestion reduces the speed (delay may lower the speed group)
        if (isClosure)
          TEST_EQUAL(speedGroup, traffic::SpeedGroup::TempBlock, (message.m_id));
        else
          TEST(speedGroup != traffic::SpeedGroup::TempBlock && speedGroup != traffic::SpeedGroup::Unknown,
               (message.m_id, speedGroup));
      }
    }
  }

  source.UnsubscribeAndWait();
  TEST(source.GetSubscriptionId().empty(), ());
}

// After the service has been restarted, the subscription is unknown to it. The source must
// subscribe again and receive data.
UNIT_CLASS_TEST(TraffE2eTest, TraffE2E_ResubscribeAfterServerRestart)
{
  if (!IsEnabled())
    return;
  std::string const restartCmd = GetEnv("TRAFF_E2E_RESTART_CMD");
  if (restartCmd.empty())
  {
    LOG(LWARNING, ("TRAFF_E2E_RESTART_CMD not set, skipping restart test"));
    return;
  }

  E2eHttpTraffSource source(m_manager, m_url);
  source.SubscribeOrChangeSubscription(m_mwms);
  TEST(m_manager.WaitForFeeds(1, kResponseTimeout), ("No feed received from", m_url));
  std::string const firstId = source.GetSubscriptionId();
  TEST(!firstId.empty(), ());

  LOG(LINFO, ("Restarting service:", restartCmd));
  TEST_EQUAL(std::system(restartCmd.c_str()), 0, (restartCmd));

  // The traffic manager polls when the poll interval has elapsed; do it right away here.
  source.Poll();
  TEST(m_manager.WaitForFeeds(2, kResponseTimeout), ("No feed after restart"));

  std::string const secondId = source.GetSubscriptionId();
  TEST(!secondId.empty(), ());
  TEST_NOT_EQUAL(secondId, firstId, ());
  TEST(source.GetAvailability() == traffxml::TraffSource::Availability::IsAvailable, ());

  auto const feeds = m_manager.GetFeeds();
  // the new subscription delivers the full feed again
  TEST_EQUAL(feeds[1].size(), feeds[0].size(), ());

  source.UnsubscribeAndWait();
}
}  // namespace traff_e2e_tests
