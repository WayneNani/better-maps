#include "traffxml/traffxml_tests/traff_test_support.hpp"

#include "traffxml/traff_model_xml.hpp"

#include "testing/testing.hpp"

#include <pugixml.hpp>

#include <ctime>
#include <utility>

namespace traffxml_tests
{
namespace
{
std::string FormatUtc(std::chrono::system_clock::time_point time)
{
  std::time_t const t = std::chrono::system_clock::to_time_t(time);
  std::tm tm{};
  gmtime_r(&t, &tm);
  char buf[32];
  std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &tm);
  return buf;
}
}  // namespace

// TestSourceManager -------------------------------------------------------------------------------
void TestSourceManager::GetActiveMwms(std::set<MwmSet::MwmId> & activeMwms)
{
  std::lock_guard<std::mutex> lock(m_mutex);
  activeMwms.insert(m_activeMwms.cbegin(), m_activeMwms.cend());
}

void TestSourceManager::ReceiveFeed(traffxml::TraffFeed feed)
{
  {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_feeds.push_back(std::move(feed));
  }
  m_condition.notify_all();
}

void TestSourceManager::RegisterSource(std::unique_ptr<traffxml::TraffSource> source)
{
  m_sources.push_back(std::move(source));
}

void TestSourceManager::SetActiveMwms(std::set<MwmSet::MwmId> const & activeMwms)
{
  std::lock_guard<std::mutex> lock(m_mutex);
  m_activeMwms = activeMwms;
}

std::vector<traffxml::TraffFeed> TestSourceManager::GetFeeds()
{
  std::lock_guard<std::mutex> lock(m_mutex);
  return m_feeds;
}

bool TestSourceManager::WaitForFeeds(size_t count, std::chrono::milliseconds timeout)
{
  std::unique_lock<std::mutex> lock(m_mutex);
  return m_condition.wait_for(lock, timeout, [this, count]() { return m_feeds.size() >= count; });
}

// ScriptedHttpTraffSource -------------------------------------------------------------------------
ScriptedHttpTraffSource::ScriptedHttpTraffSource(traffxml::TraffSourceManager & manager)
  : traffxml::HttpTraffSource(manager, "http://traff.invalid/")
{}

void ScriptedHttpTraffSource::QueueResponse(std::string const & responseXml)
{
  m_responses.emplace_back(responseXml);
}

void ScriptedHttpTraffSource::QueueTransportError()
{
  m_responses.emplace_back(std::nullopt);
}

size_t ScriptedHttpTraffSource::RunPendingTasks()
{
  size_t count = 0;
  while (!m_tasks.empty())
  {
    auto task = std::move(m_tasks.front());
    m_tasks.pop_front();
    task();
    ++count;
  }
  return count;
}

std::string ScriptedHttpTraffSource::GetOperation(size_t index) const
{
  TEST_LESS(index, m_requests.size(), ());
  return GetRootAttribute(m_requests[index], "operation");
}

std::string ScriptedHttpTraffSource::GetRequestSubscriptionId(size_t index) const
{
  TEST_LESS(index, m_requests.size(), ());
  return GetRootAttribute(m_requests[index], "subscription_id");
}

std::string ScriptedHttpTraffSource::GetSubscriptionId()
{
  std::lock_guard<std::mutex> lock(m_mutex);
  return m_subscriptionId;
}

traffxml::TraffResponse ScriptedHttpTraffSource::Post(std::string const & data)
{
  m_requests.push_back(data);
  TEST(!m_responses.empty(), ("Unexpected request:", data));
  if (m_responses.empty())
  {
    traffxml::TraffResponse response;
    response.m_status = traffxml::ResponseStatus::InternalError;
    return response;
  }
  auto response = std::move(m_responses.front());
  m_responses.pop_front();
  if (!response)
  {
    // same as HttpPost() on a network or HTTP error
    traffxml::TraffResponse result;
    result.m_status = traffxml::ResponseStatus::InternalError;
    return result;
  }
  return traffxml::ParseResponse(*response);
}

void ScriptedHttpTraffSource::RunAsync(std::function<void()> && task)
{
  m_tasks.push_back(std::move(task));
}

// Helpers -----------------------------------------------------------------------------------------
std::string MakeMessageXml(std::string const & id, std::string const & eventsXml)
{
  auto const now = std::chrono::system_clock::now();
  std::string const receiveTime = FormatUtc(now - std::chrono::minutes(10));
  std::string const expirationTime = FormatUtc(now + std::chrono::hours(24));
  return "<message id=\"" + id + "\" receive_time=\"" + receiveTime + "\" update_time=\"" + receiveTime
         + "\" expiration_time=\"" + expirationTime + "\" cancellation=\"false\" forecast=\"false\">\n"
         + "  <location country=\"DE\" destination=\"Neukölln\" directionality=\"ONE_DIRECTION\""
           " origin=\"Wilmersdorf\" road_class=\"MOTORWAY\" road_ref=\"A 100\">\n"
           "    <from>+52.473796 +13.352521</from>\n"
           "    <to>+52.470269 +13.374053</to>\n"
           "  </location>\n"
           "  <events>\n"
         + eventsXml + "\n  </events>\n</message>\n";
}

std::string MakeResponseXml(std::string const & status, std::string const & attributes,
                            std::optional<std::string> const & feedContent)
{
  std::string xml = "<response status=\"" + status + "\"";
  if (!attributes.empty())
    xml += " " + attributes;
  if (!feedContent)
    return xml + "/>";
  return xml + ">\n<feed>\n" + *feedContent + "</feed>\n</response>";
}

std::string GetRootAttribute(std::string const & xml, std::string const & attribute)
{
  pugi::xml_document doc;
  if (!doc.load_string(xml.c_str()))
    return {};
  return doc.document_element().attribute(attribute.c_str()).as_string();
}
}  // namespace traffxml_tests
