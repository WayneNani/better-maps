#pragma once

#include "traffxml/traff_model.hpp"
#include "traffxml/traff_source.hpp"

#include "indexer/mwm_set.hpp"

#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <vector>

namespace traffxml_tests
{
/**
 * @brief A `TraffSourceManager` for tests.
 *
 * Active MWMs are set by the test, received feeds are recorded. All methods are thread-safe.
 */
class TestSourceManager : public traffxml::TraffSourceManager
{
public:
  // TraffSourceManager overrides:
  void GetActiveMwms(std::set<MwmSet::MwmId> & activeMwms) override;
  void ReceiveFeed(traffxml::TraffFeed feed) override;
  void RegisterSource(std::unique_ptr<traffxml::TraffSource> source) override;

  void SetActiveMwms(std::set<MwmSet::MwmId> const & activeMwms);

  /// Returns all feeds received so far.
  std::vector<traffxml::TraffFeed> GetFeeds();

  /**
   * @brief Waits until at least `count` feeds have been received.
   * @return true if the feeds were received before the timeout expired.
   */
  bool WaitForFeeds(size_t count, std::chrono::milliseconds timeout);

  /// The sources registered with this manager.
  std::vector<std::unique_ptr<traffxml::TraffSource>> & GetSources() { return m_sources; }

private:
  std::mutex m_mutex;
  std::condition_variable m_condition;
  std::set<MwmSet::MwmId> m_activeMwms;
  std::vector<traffxml::TraffFeed> m_feeds;
  std::vector<std::unique_ptr<traffxml::TraffSource>> m_sources;
};

/**
 * @brief A `HttpTraffSource` which talks to a scripted fake service instead of the network.
 *
 * Requests are recorded and answered with the responses queued by the test, in order. Asynchronous
 * tasks are not run until the test calls `RunPendingTasks()`, which makes the tests deterministic.
 */
class ScriptedHttpTraffSource : public traffxml::HttpTraffSource
{
public:
  explicit ScriptedHttpTraffSource(traffxml::TraffSourceManager & manager);

  /// Queues a response (XML as sent by a TraFF service) for the next request.
  void QueueResponse(std::string const & responseXml);

  /// Queues a transport error (e.g. service unreachable) for the next request.
  void QueueTransportError();

  /**
   * @brief Runs pending asynchronous tasks (including tasks queued by these tasks).
   * @return The number of tasks run.
   */
  size_t RunPendingTasks();

  size_t GetPendingTaskCount() const { return m_tasks.size(); }
  size_t GetQueuedResponseCount() const { return m_responses.size(); }

  /// All requests sent so far, in XML format.
  std::vector<std::string> const & GetRequests() const { return m_requests; }

  /// The `operation` attribute of the n-th request.
  std::string GetOperation(size_t index) const;

  /// The `subscription_id` attribute of the n-th request, empty if none.
  std::string GetRequestSubscriptionId(size_t index) const;

  std::string GetSubscriptionId();
  std::chrono::steady_clock::time_point GetNextRequestTime() const { return m_nextRequestTime; }
  Availability GetAvailability() const { return m_lastAvailability; }

  /// Pretends the poll interval has elapsed.
  void ExpireNextRequestTime() { m_nextRequestTime = std::chrono::steady_clock::now() - std::chrono::seconds(1); }

protected:
  // HttpTraffSource overrides:
  traffxml::TraffResponse Post(std::string const & data) override;
  void RunAsync(std::function<void()> && task) override;

private:
  std::deque<std::optional<std::string>> m_responses;
  std::deque<std::function<void()>> m_tasks;
  std::vector<std::string> m_requests;
};

/**
 * @brief Returns a TraFF message in XML format.
 *
 * The message is valid for one day from now. Its location is a line on a motorway.
 *
 * @param id The message ID.
 * @param eventsXml The `event` elements of the message.
 */
std::string MakeMessageXml(std::string const & id, std::string const & eventsXml);

/// Returns a `response` element in XML format.
std::string MakeResponseXml(std::string const & status, std::string const & attributes = "",
                            std::optional<std::string> const & feedContent = std::nullopt);

/// Returns an XML attribute value of the root element of `xml`, empty if absent.
std::string GetRootAttribute(std::string const & xml, std::string const & attribute);
}  // namespace traffxml_tests
