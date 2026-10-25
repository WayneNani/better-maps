#include "testing/testing.hpp"

#include "traffxml/traffxml_tests/traff_test_support.hpp"

#include "traffxml/traff_source.hpp"

#include "indexer/data_source.hpp"

#include "platform/local_country_file.hpp"

#include <chrono>
#include <set>
#include <string>

namespace traff_source_tests
{
using namespace std::chrono;
using traffxml::TraffSource;
using traffxml_tests::MakeMessageXml;
using traffxml_tests::MakeResponseXml;
using traffxml_tests::ScriptedHttpTraffSource;
using traffxml_tests::TestSourceManager;

// Tolerance for comparing points in time computed by the source with points in time computed here.
auto constexpr kTolerance = seconds(5);

std::string const kCongestion = R"(<event class="CONGESTION" type="CONGESTION_STATIONARY_TRAFFIC"/>)";

class HttpTraffSourceTest
{
public:
  HttpTraffSourceTest()
  {
    auto const result = m_dataSource.RegisterMap(platform::LocalCountryFile::MakeForTesting("minsk-pass"));
    TEST_EQUAL(result.second, MwmSet::RegResult::Success, ());
    m_mwmId = result.first;
    m_mwms = {m_mwmId};
    m_manager.SetActiveMwms(m_mwms);
  }

  /// Subscribes and returns once the subscription with ID `subscriptionId` is established.
  void Subscribe(std::string const & subscriptionId, std::string const & timeoutAttr = R"(timeout="3600")")
  {
    m_source.QueueResponse(MakeResponseXml("OK", R"(subscription_id=")" + subscriptionId + R"(" )" + timeoutAttr,
                                           MakeMessageXml("test:" + subscriptionId, kCongestion)));
    m_source.SubscribeOrChangeSubscription(m_mwms);
    m_source.RunPendingTasks();
    TEST_EQUAL(m_source.GetSubscriptionId(), subscriptionId, ());
  }

  static bool IsAbout(steady_clock::time_point actual, steady_clock::time_point expected)
  {
    return actual >= expected - kTolerance && actual <= expected + kTolerance;
  }

protected:
  FrozenDataSource m_dataSource;
  MwmSet::MwmId m_mwmId;
  std::set<MwmSet::MwmId> m_mwms;
  TestSourceManager m_manager;
  ScriptedHttpTraffSource m_source{m_manager};
};

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_SubscribeDeliversFeed)
{
  Subscribe("sub1");

  auto const & requests = m_source.GetRequests();
  TEST_EQUAL(requests.size(), 1, ());
  TEST_EQUAL(m_source.GetOperation(0), "SUBSCRIBE", ());
  // filter list generated from the active MWM
  TEST(requests[0].find("<filter bbox=\"") != std::string::npos, (requests[0]));

  auto const feeds = m_manager.GetFeeds();
  TEST_EQUAL(feeds.size(), 1, ());
  TEST_EQUAL(feeds[0].size(), 1, ());
  TEST_EQUAL(feeds[0][0].m_id, "test:sub1", ());
  TEST(m_source.GetAvailability() == TraffSource::Availability::IsAvailable, ());
  TEST(!m_source.IsPollNeeded(), ());
  TEST(IsAbout(m_source.GetNextRequestTime(), steady_clock::now() + minutes(5)), ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_SubscribeWithoutFeedPollsImmediately)
{
  m_source.QueueResponse(MakeResponseXml("OK", R"(subscription_id="sub1" timeout="3600")"));
  m_source.QueueResponse(MakeResponseXml("OK", "", MakeMessageXml("test:1", kCongestion)));
  m_source.SubscribeOrChangeSubscription(m_mwms);
  m_source.RunPendingTasks();

  TEST_EQUAL(m_source.GetRequests().size(), 2, ());
  TEST_EQUAL(m_source.GetOperation(1), "POLL", ());
  TEST_EQUAL(m_source.GetRequestSubscriptionId(1), "sub1", ());
  TEST_EQUAL(m_manager.GetFeeds().size(), 1, ());
}

// Bug fix: an empty feed is a successful response and must not cause polling at every opportunity.
UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_EmptyPollFeedSetsNextRequestTime)
{
  Subscribe("sub1");

  // `<feed/>` and a response without a feed element are both empty
  for (auto const & response : {MakeResponseXml("OK", "", std::string()), MakeResponseXml("OK")})
  {
    m_source.ExpireNextRequestTime();
    TEST(m_source.IsPollNeeded(), ());

    m_source.QueueResponse(response);
    m_source.Poll();
    m_source.RunPendingTasks();

    TEST(!m_source.IsPollNeeded(), (response));
    TEST(IsAbout(m_source.GetNextRequestTime(), steady_clock::now() + minutes(5)), (response));
    TEST(m_source.GetAvailability() == TraffSource::Availability::IsAvailable, ());
  }
  // only the subscribe response carried messages
  TEST_EQUAL(m_manager.GetFeeds().size(), 1, ());
  TEST_EQUAL(m_source.GetRequests().size(), 3, ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_PollFeedIsDelivered)
{
  Subscribe("sub1");
  m_source.ExpireNextRequestTime();
  m_source.QueueResponse(MakeResponseXml("OK", "", MakeMessageXml("test:2", kCongestion)));
  m_source.Poll();
  m_source.RunPendingTasks();

  auto const feeds = m_manager.GetFeeds();
  TEST_EQUAL(feeds.size(), 2, ());
  TEST_EQUAL(feeds[1][0].m_id, "test:2", ());
  TEST(!m_source.IsPollNeeded(), ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_PollPartiallyCoveredIsSuccess)
{
  Subscribe("sub1");
  m_source.ExpireNextRequestTime();
  m_source.QueueResponse(MakeResponseXml("PARTIALLY_COVERED", "", MakeMessageXml("test:2", kCongestion)));
  m_source.Poll();
  m_source.RunPendingTasks();

  TEST_EQUAL(m_manager.GetFeeds().size(), 2, ());
  TEST(m_source.GetAvailability() == TraffSource::Availability::IsAvailable, ());
  TEST(!m_source.IsPollNeeded(), ());
}

// The poll interval is limited by the subscription timeout reported by the service.
UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_TimeoutLimitsPollInterval)
{
  struct Case
  {
    std::string m_timeoutAttr;
    steady_clock::duration m_expected;
  };
  Case const cases[] = {
      {"", minutes(5)},                     // no timeout: default interval
      {R"(timeout="0")", minutes(5)},       // 0 means no timeout
      {R"(timeout="3600")", minutes(5)},    // long timeout: default interval
      {R"(timeout="600")", minutes(5)},     // half the timeout equals the default interval
      {R"(timeout="120")", seconds(60)},    // short timeout: poll at half the timeout
  };

  for (auto const & c : cases)
  {
    TestSourceManager manager;
    manager.SetActiveMwms(m_mwms);
    ScriptedHttpTraffSource source(manager);
    source.QueueResponse(MakeResponseXml("OK", R"(subscription_id="sub" )" + c.m_timeoutAttr,
                                         MakeMessageXml("test:1", kCongestion)));
    source.SubscribeOrChangeSubscription(m_mwms);
    source.RunPendingTasks();

    TEST_EQUAL(source.GetPollInterval(), c.m_expected, (c.m_timeoutAttr));
    TEST(IsAbout(source.GetNextRequestTime(), steady_clock::now() + c.m_expected), (c.m_timeoutAttr));

    // the interval also applies after polls with empty feeds
    source.ExpireNextRequestTime();
    source.QueueResponse(MakeResponseXml("OK"));
    source.Poll();
    source.RunPendingTasks();
    TEST(IsAbout(source.GetNextRequestTime(), steady_clock::now() + c.m_expected), (c.m_timeoutAttr));
  }
}

// Bug fix: after SUBSCRIPTION_UNKNOWN (e.g. service restarted), the source subscribes again.
UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_ResubscribeAfterPollSubscriptionUnknown)
{
  Subscribe("sub1");

  m_source.ExpireNextRequestTime();
  m_source.QueueResponse(MakeResponseXml("SUBSCRIPTION_UNKNOWN"));
  m_source.QueueResponse(MakeResponseXml("OK", R"(subscription_id="sub2" timeout="3600")",
                                         MakeMessageXml("test:after-restart", kCongestion)));
  m_source.Poll();
  m_source.RunPendingTasks();

  auto const & requests = m_source.GetRequests();
  TEST_EQUAL(requests.size(), 3, ());
  TEST_EQUAL(m_source.GetOperation(1), "POLL", ());
  TEST_EQUAL(m_source.GetRequestSubscriptionId(1), "sub1", ());
  TEST_EQUAL(m_source.GetOperation(2), "SUBSCRIBE", ());
  TEST(requests[2].find("<filter bbox=\"") != std::string::npos, (requests[2]));
  TEST_EQUAL(m_source.GetSubscriptionId(), "sub2", ());

  auto const feeds = m_manager.GetFeeds();
  TEST_EQUAL(feeds.size(), 2, ());
  TEST_EQUAL(feeds[1][0].m_id, "test:after-restart", ());
  TEST(!m_source.IsPollNeeded(), ());

  // subsequent polls use the new subscription
  m_source.ExpireNextRequestTime();
  m_source.QueueResponse(MakeResponseXml("OK"));
  m_source.Poll();
  m_source.RunPendingTasks();
  TEST_EQUAL(m_source.GetOperation(3), "POLL", ());
  TEST_EQUAL(m_source.GetRequestSubscriptionId(3), "sub2", ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_ResubscribeAfterChangeSubscriptionUnknown)
{
  Subscribe("sub1");

  m_source.QueueResponse(MakeResponseXml("SUBSCRIPTION_UNKNOWN"));
  m_source.QueueResponse(MakeResponseXml("OK", R"(subscription_id="sub2")",
                                         MakeMessageXml("test:after-restart", kCongestion)));
  m_source.SubscribeOrChangeSubscription(m_mwms);
  m_source.RunPendingTasks();

  TEST_EQUAL(m_source.GetRequests().size(), 3, ());
  TEST_EQUAL(m_source.GetOperation(1), "SUBSCRIPTION_CHANGE", ());
  TEST_EQUAL(m_source.GetRequestSubscriptionId(1), "sub1", ());
  TEST_EQUAL(m_source.GetOperation(2), "SUBSCRIBE", ());
  TEST_EQUAL(m_source.GetSubscriptionId(), "sub2", ());
  TEST_EQUAL(m_manager.GetFeeds().size(), 2, ());
}

// Without active MWMs there is nothing to subscribe to; the next poll subscribes once there are.
UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_ResubscribeWaitsForActiveMwms)
{
  Subscribe("sub1");

  m_manager.SetActiveMwms({});
  m_source.ExpireNextRequestTime();
  m_source.QueueResponse(MakeResponseXml("SUBSCRIPTION_UNKNOWN"));
  m_source.Poll();
  m_source.RunPendingTasks();
  TEST_EQUAL(m_source.GetRequests().size(), 2, ());
  TEST_EQUAL(m_source.GetSubscriptionId(), "", ());

  // still no active MWMs: polling does not send anything
  m_source.Poll();
  TEST_EQUAL(m_source.RunPendingTasks(), 0, ());
  TEST_EQUAL(m_source.GetRequests().size(), 2, ());

  m_manager.SetActiveMwms(m_mwms);
  m_source.QueueResponse(MakeResponseXml("OK", R"(subscription_id="sub2")", MakeMessageXml("test:2", kCongestion)));
  m_source.Poll();
  m_source.RunPendingTasks();
  TEST_EQUAL(m_source.GetRequests().size(), 3, ());
  TEST_EQUAL(m_source.GetOperation(2), "SUBSCRIBE", ());
  TEST_EQUAL(m_source.GetSubscriptionId(), "sub2", ());
}

// A failed subscription attempt (e.g. service unreachable) is repeated by a later poll.
UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_RetrySubscribeAfterTransportError)
{
  m_source.QueueTransportError();
  m_source.SubscribeOrChangeSubscription(m_mwms);
  m_source.RunPendingTasks();

  TEST_EQUAL(m_source.GetSubscriptionId(), "", ());
  TEST(m_source.GetAvailability() == TraffSource::Availability::Error, ());
  // do not retry immediately
  TEST(!m_source.IsPollNeeded(), ());
  TEST(IsAbout(m_source.GetNextRequestTime(), steady_clock::now() + minutes(5)), ());

  m_source.ExpireNextRequestTime();
  TEST(m_source.IsPollNeeded(), ());
  m_source.QueueResponse(MakeResponseXml("OK", R"(subscription_id="sub1")", MakeMessageXml("test:1", kCongestion)));
  m_source.Poll();
  m_source.RunPendingTasks();

  TEST_EQUAL(m_source.GetRequests().size(), 2, ());
  TEST_EQUAL(m_source.GetOperation(1), "SUBSCRIBE", ());
  TEST_EQUAL(m_source.GetSubscriptionId(), "sub1", ());
  TEST(m_source.GetAvailability() == TraffSource::Availability::IsAvailable, ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_NoSecondSubscribeWhilePending)
{
  m_source.SubscribeOrChangeSubscription(m_mwms);
  TEST_EQUAL(m_source.GetPendingTaskCount(), 1, ());

  // a poll while the subscribe request is in flight must not subscribe a second time
  m_source.Poll();
  TEST_EQUAL(m_source.GetPendingTaskCount(), 1, ());

  m_source.QueueResponse(MakeResponseXml("OK", R"(subscription_id="sub1")", MakeMessageXml("test:1", kCongestion)));
  m_source.RunPendingTasks();
  TEST_EQUAL(m_source.GetRequests().size(), 1, ());
  TEST_EQUAL(m_source.GetSubscriptionId(), "sub1", ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_SubscribeNotCovered)
{
  m_source.QueueResponse(MakeResponseXml("NOT_COVERED"));
  m_source.SubscribeOrChangeSubscription(m_mwms);
  m_source.RunPendingTasks();

  TEST_EQUAL(m_source.GetSubscriptionId(), "", ());
  TEST(m_source.GetAvailability() == TraffSource::Availability::NotCovered, ());
  TEST(!m_source.IsPollNeeded(), ());
  TEST(m_manager.GetFeeds().empty(), ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_PollErrorIsRetried)
{
  Subscribe("sub1");
  m_source.ExpireNextRequestTime();
  m_source.QueueResponse(MakeResponseXml("INTERNAL_ERROR"));
  m_source.Poll();
  m_source.RunPendingTasks();

  TEST(m_source.GetAvailability() == TraffSource::Availability::Error, ());
  // subscription is kept, poll is retried at the next opportunity
  TEST_EQUAL(m_source.GetSubscriptionId(), "sub1", ());
  TEST(m_source.IsPollNeeded(), ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_ChangeSubscription)
{
  Subscribe("sub1");
  m_source.QueueResponse(MakeResponseXml("OK", "", MakeMessageXml("test:changed", kCongestion)));
  m_source.SubscribeOrChangeSubscription(m_mwms);
  m_source.RunPendingTasks();

  TEST_EQUAL(m_source.GetOperation(1), "SUBSCRIPTION_CHANGE", ());
  TEST_EQUAL(m_source.GetRequestSubscriptionId(1), "sub1", ());
  TEST_EQUAL(m_source.GetSubscriptionId(), "sub1", ());
  TEST_EQUAL(m_manager.GetFeeds().size(), 2, ());
}

UNIT_CLASS_TEST(HttpTraffSourceTest, HttpTraffSource_Unsubscribe)
{
  Subscribe("sub1");
  m_source.QueueResponse(MakeResponseXml("OK"));
  m_source.Unsubscribe();
  m_source.RunPendingTasks();

  TEST_EQUAL(m_source.GetOperation(1), "UNSUBSCRIBE", ());
  TEST_EQUAL(m_source.GetRequestSubscriptionId(1), "sub1", ());
  TEST_EQUAL(m_source.GetSubscriptionId(), "", ());

  // unsubscribing again is a no-op
  m_source.Unsubscribe();
  TEST_EQUAL(m_source.RunPendingTasks(), 0, ());
}
}  // namespace traff_source_tests
