#include "traffxml/traff_source.hpp"

#include "traffxml/traff_model_xml.hpp"
#include "traffxml/traff_storage.hpp"

#include "geometry/rect2d.hpp"

#include "platform/platform.hpp"

#include <functional>
#include <thread>
#include <vector>

namespace traffxml {
TraffSource::TraffSource(TraffSourceManager & manager)
  : m_manager(manager)
{}

void TraffSource::SubscribeOrChangeSubscription(std::set<MwmSet::MwmId> & mwms)
{
  std::lock_guard<std::mutex> lock(m_mutex);
  if (!IsSubscribed())
    Subscribe(mwms);
  else
    ChangeSubscription(mwms);
}

std::string TraffSource::GetMwmFilters(std::set<MwmSet::MwmId> & mwms)
{
  std::vector<m2::RectD> rects;
  for (auto mwmId : mwms)
    rects.push_back(mwmId.GetInfo()->m_bordersRect);
  return traffxml::FiltersToXml(rects);
}

void MockTraffSource::Create(TraffSourceManager & manager)
{
  std::unique_ptr<MockTraffSource> source = std::unique_ptr<MockTraffSource>(new MockTraffSource(manager));
  manager.RegisterSource(std::move(source));
}

MockTraffSource::MockTraffSource(TraffSourceManager & manager)
  : TraffSource(manager)
{}

void MockTraffSource::Subscribe(std::set<MwmSet::MwmId> & mwms)
{
  std::string filterList = GetMwmFilters(mwms);
  LOG(LINFO, ("Would subscribe to:\n", filterList));
  m_subscriptionId = "placeholder_subscription_id";
  m_nextRequestTime = std::chrono::steady_clock::now(); // would be in the future if we got a feed here
}

void MockTraffSource::ChangeSubscription(std::set<MwmSet::MwmId> & mwms)
{
  if (!IsSubscribed())
    return;
  std::string filterList = GetMwmFilters(mwms);
  LOG(LINFO, ("Would change subscription", m_subscriptionId, "to:\n", filterList));
  m_nextRequestTime = std::chrono::steady_clock::now(); // would be in the future if we got a feed here
}

void MockTraffSource::Unsubscribe()
{
  std::lock_guard<std::mutex> lock(m_mutex);
  if (!IsSubscribed())
    return;
  LOG(LINFO, ("Would unsubscribe from", m_subscriptionId));
  m_subscriptionId.clear();
}

bool MockTraffSource::IsPollNeeded()
{
  return m_nextRequestTime.load() <= std::chrono::steady_clock::now();
}

void MockTraffSource::Poll()
{
  //std::string fileName("test_data/traff/PL-A18-Krzyzowa-Lipiany.xml");
  std::string fileName("test_data/traff/PL-A18-Krzyzowa-Lipiany-bidir.xml");
  //std::string fileName("test_data/traff/LT-A1-Vezaiciai-Endriejavas.xml");
  traffxml::LocalStorage storage(fileName);
  pugi::xml_document document;
  auto const load_result = storage.Load(document);
  if (!load_result)
    return;

  m_lastRequestTime = std::chrono::steady_clock::now();
  std::setlocale(LC_ALL, "en_US.UTF-8");
  traffxml::TraffFeed feed;
  if (traffxml::ParseTraff(document, std::nullopt /* dataSource */, feed))
  {
    m_lastResponseTime = std::chrono::steady_clock::now();
    m_nextRequestTime = std::chrono::steady_clock::now() + m_updateInterval;
    m_lastAvailability = Availability::IsAvailable;
    m_manager.ReceiveFeed(feed);
  }
  else
  {
    LOG(LWARNING, ("An error occurred parsing the TraFF feed"));
    m_lastAvailability = Availability::Error;
    /*
     * TODO how should we deal with future requests?
     * Static files usually don’t change.
     */
    m_nextRequestTime = std::chrono::steady_clock::now() + m_updateInterval;
  }
}

TraffResponse HttpPost(std::string const & url, std::string data)
{
  platform::HttpClient request(url);
  request.SetBodyData(data, "application/xml");

  if (!request.RunHttpRequest() || request.ErrorCode() != 200)
  {
    TraffResponse result;
    result.m_status = ResponseStatus::InternalError;
    return result;
  }

  LOG(LDEBUG, ("Got response, status", request.ErrorCode()));

  TraffResponse result = ParseResponse(request.ServerResponse());
  return result;
}

void HttpTraffSource::Create(TraffSourceManager & manager, std::string const & url)
{
  std::unique_ptr<HttpTraffSource> source = std::unique_ptr<HttpTraffSource>(new HttpTraffSource(manager, url));
  manager.RegisterSource(std::move(source));
}

HttpTraffSource::HttpTraffSource(TraffSourceManager & manager, std::string const & url)
  : TraffSource(manager)
  , m_url(url)
{}

TraffResponse HttpTraffSource::Post(std::string const & data)
{
  return HttpPost(m_url, data);
}

void HttpTraffSource::RunAsync(std::function<void()> && task)
{
  threads::SimpleThread thread(std::move(task));
  thread.detach();
}

std::chrono::steady_clock::duration HttpTraffSource::GetPollInterval() const
{
  std::chrono::steady_clock::duration interval = m_updateInterval;
  uint32_t const timeout = m_timeout;
  if (timeout > 0)
    // poll well before the service considers the subscription inactive
    interval = std::min(interval, std::chrono::duration_cast<std::chrono::steady_clock::duration>(
                                      std::chrono::seconds(timeout)) / 2);
  return interval;
}

void HttpTraffSource::Close()
{
  std::string data;
  {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (m_subscriptionId.empty())
      return;
    data = "<request operation=\"UNSUBSCRIBE\" subscription_id=\"" + m_subscriptionId + "\"/>";
    m_subscriptionId.clear();
  }

  LOG(LDEBUG, ("Sending request:\n", data));

  // The source may be destroyed before the request completes, do not access any members.
  threads::SimpleThread thread([url = m_url, data]() {
    TraffResponse response = HttpPost(url, data);
    return;
  });
  thread.detach();
}

void HttpTraffSource::Subscribe(std::set<MwmSet::MwmId> & mwms)
{
  std::string data = "<request operation=\"SUBSCRIBE\">\n<filter_list>\n"
      + GetMwmFilters(mwms)
      + "</filter_list>\n"
      + "</request>";
  LOG(LDEBUG, ("Sending request:\n", data));

  m_isSubscribePending = true;
  m_lastRequestTime = std::chrono::steady_clock::now();
  RunAsync([this, data]() {
    // TODO sometimes the request gets sent (and processed) twice
    TraffResponse response = Post(data);
    OnSubscribeResponse(response);
  });
}

void HttpTraffSource::SubscribeToActiveMwms()
{
  // retrieve MWMs before locking, as this locks the manager
  std::set<MwmSet::MwmId> mwms;
  m_manager.GetActiveMwms(mwms);

  std::lock_guard<std::mutex> lock(m_mutex);
  if (IsSubscribed() || m_isSubscribePending)
    return;
  if (mwms.empty())
  {
    LOG(LDEBUG, ("No active MWMs, not subscribing yet"));
    return;
  }
  Subscribe(mwms);
}

void HttpTraffSource::OnSubscriptionUnknown()
{
  {
    std::lock_guard<std::mutex> lock(m_mutex);
    LOG(LWARNING, ("Subscription", m_subscriptionId, "unknown to service, subscribing again"));
    m_subscriptionId.clear();
  }
  SubscribeToActiveMwms();
}

void HttpTraffSource::OnResponseOk()
{
  m_lastResponseTime = std::chrono::steady_clock::now();
  m_nextRequestTime = std::chrono::steady_clock::now() + GetPollInterval();
  m_retriesCount = 0;
  m_lastAvailability = Availability::IsAvailable;
}

void HttpTraffSource::OnResponseFailed(ResponseStatus status)
{
  m_lastResponseTime = std::chrono::steady_clock::now();
  switch (status)
  {
  case ResponseStatus::NotCovered:
    m_lastAvailability = Availability::NotCovered;
    break;
  case ResponseStatus::SubscriptionRejected:
    m_lastAvailability = Availability::SubscriptionRejected;
    break;
  default:
    m_lastAvailability = Availability::Error;
    ++m_retriesCount;
  }
}

void HttpTraffSource::OnFeedReceived(TraffFeed & feed)
{
  OnResponseOk();
  m_manager.ReceiveFeed(feed);
}

void HttpTraffSource::OnSubscribeResponse(TraffResponse & response)
{
  if (response.m_status == ResponseStatus::Ok
      || response.m_status == ResponseStatus::PartiallyCovered)
  {
    if (response.m_subscriptionId.empty())
    {
      LOG(LWARNING, ("Server replied with", response.m_status, "but subscription ID is empty; ignoring"));
      {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_isSubscribePending = false;
      }
      OnResponseFailed(ResponseStatus::Invalid);
      // retry with the next poll
      m_nextRequestTime = std::chrono::steady_clock::now() + m_updateInterval;
    }
    else
    {
      {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_isSubscribePending = false;
        m_subscriptionId = response.m_subscriptionId;
      }
      m_timeout = response.m_timeout;
      if (response.m_feed && !response.m_feed.value().empty())
        OnFeedReceived(response.m_feed.value());
      else
      {
        OnResponseOk();
        Poll();
      }
    }
  }
  else
  {
    LOG(LWARNING, ("Subscribe request failed:", response.m_status));
    {
      std::lock_guard<std::mutex> lock(m_mutex);
      m_isSubscribePending = false;
    }
    OnResponseFailed(response.m_status);
    // retry with the next poll (or earlier, if the set of active MWMs changes)
    m_nextRequestTime = std::chrono::steady_clock::now() + m_updateInterval;
  }
}

void HttpTraffSource::ChangeSubscription(std::set<MwmSet::MwmId> & mwms)
{
  std::string data = "<request operation=\"SUBSCRIPTION_CHANGE\" subscription_id=\"" + m_subscriptionId + "\">\n"
      + "<filter_list>\n"
      + GetMwmFilters(mwms)
      + "</filter_list>\n"
      + "</request>";
  LOG(LDEBUG, ("Sending request:\n", data));

  m_lastRequestTime = std::chrono::steady_clock::now();
  RunAsync([this, data]() {
    TraffResponse response = Post(data);
    OnChangeSubscriptionResponse(response);
  });
}

void HttpTraffSource::OnChangeSubscriptionResponse(TraffResponse & response)
{
  if (response.m_status == ResponseStatus::Ok
      || response.m_status == ResponseStatus::PartiallyCovered)
  {
    if (response.m_timeout > 0)
      m_timeout = response.m_timeout;
    if (response.m_feed && !response.m_feed.value().empty())
      OnFeedReceived(response.m_feed.value());
    else
    {
      OnResponseOk();
      Poll();
    }
  }
  else if (response.m_status == ResponseStatus::SubscriptionUnknown)
    OnSubscriptionUnknown();
  else
  {
    LOG(LWARNING, ("Change Subscription request failed:", response.m_status));
    OnResponseFailed(response.m_status);
  }
}

void HttpTraffSource::Unsubscribe()
{
  std::string data;
  {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (m_subscriptionId.empty())
      return;
    data = "<request operation=\"UNSUBSCRIBE\" subscription_id=\"" + m_subscriptionId + "\"/>";
  }

  LOG(LDEBUG, ("Sending request:\n", data));

  RunAsync([this, data]() {
    TraffResponse response = Post(data);
    OnUnsubscribeResponse(response);
  });
}

void HttpTraffSource::OnUnsubscribeResponse(TraffResponse & response)
{
  if (response.m_status != ResponseStatus::Ok
      && response.m_status != ResponseStatus::SubscriptionUnknown)
  {
    LOG(LWARNING, ("Unsubscribe returned", response.m_status, " – removing subscription"));
  }
  {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_subscriptionId.clear();
  }
}

bool HttpTraffSource::IsPollNeeded()
{
  return m_nextRequestTime.load() <= std::chrono::steady_clock::now();
}

void HttpTraffSource::Poll()
{
  std::string data;
  {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (!m_subscriptionId.empty())
      data = "<request operation=\"POLL\" subscription_id=\"" + m_subscriptionId + "\"/>";
  }

  if (data.empty())
  {
    // not subscribed (yet or any more), e.g. because a previous subscription attempt failed
    SubscribeToActiveMwms();
    return;
  }

  LOG(LDEBUG, ("Sending request:\n", data));

  m_lastRequestTime = std::chrono::steady_clock::now();
  RunAsync([this, data]() {
    // TODO sometimes the request gets sent (and processed) twice
    TraffResponse response = Post(data);
    OnPollResponse(response);
  });
}

void HttpTraffSource::OnPollResponse(TraffResponse & response)
{
  if (response.m_status == ResponseStatus::Ok
      || response.m_status == ResponseStatus::PartiallyCovered)
  {
    if (response.m_feed && !response.m_feed.value().empty())
      OnFeedReceived(response.m_feed.value());
    else
      // an empty feed is a successful response, too: wait for the poll interval to elapse
      OnResponseOk();
  }
  else if (response.m_status == ResponseStatus::SubscriptionUnknown)
    OnSubscriptionUnknown();
  else
  {
    LOG(LWARNING, ("Poll returned", response.m_status));
    OnResponseFailed(response.m_status);
  }
}
}  // namespace traffxml
