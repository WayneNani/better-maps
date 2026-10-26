#pragma once

#include "geometry/rect2d.hpp"

#include "search/result.hpp"
#include "search/search_params.hpp"

#include "base/timer.hpp"

#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace search
{
class ProcessorHandle;

namespace tests_support
{
class TestSearchEngine;

// This class wraps a search query to a search engine. Note that the
// last token will be handled as a complete token iff it will be
// followed by a space in a query.
class TestSearchRequest
{
public:
  TestSearchRequest(TestSearchEngine & engine, std::string const & query, std::string const & locale, Mode mode,
                    m2::RectD const & viewport);
  TestSearchRequest(TestSearchEngine & engine, SearchParams const & params);

  void SetCategorial() { m_params.m_categorialRequest = true; }

  // Initiates the search and waits for it to finish.
  void Run();

  // Initiates asynchronous search.
  void Start();

  // Cancels the search started by Start(). Wait() still waits for its end.
  void Cancel();

  // Waits for the search to finish.
  void Wait();

  // Call these functions only after call to Wait().
  using TimeDurationT = base::Timer::DurationT;
  TimeDurationT ResponseTime() const;
  // Time until the first non-empty batch of results arrived (the whole response time if none did).
  TimeDurationT FirstResultsTime() const;
  // CPU time of the search thread for the request (less dependent on the machine's load than ResponseTime()).
  TimeDurationT CpuTime() const;
  // Time from Start() until the search started (waiting for other searches of the engine).
  TimeDurationT QueueTime() const;
  std::vector<search::Result> const & Results() const;

  SearchParams const & Params() const { return m_params; }

protected:
  TestSearchRequest(TestSearchEngine & engine, std::string const & query, std::string const & locale, Mode mode,
                    m2::RectD const & viewport, SearchParams::OnStarted const & onStarted,
                    SearchParams::OnResults const & onResults);

  void SetUpCallbacks();
  void SetUpResultParams();

  void OnStarted();
  void OnResults(search::Results const & results);

  // Overrides the default onResults callback.
  void SetCustomOnResults(SearchParams::OnResults const & onResults);

  std::condition_variable m_cv;
  mutable std::mutex m_mu;

  std::vector<search::Result> m_results;
  bool m_done = false;

  base::Timer m_timer;
  TimeDurationT m_startTime, m_endTime;
  std::optional<TimeDurationT> m_firstResultsTime;
  TimeDurationT m_startCpuTime, m_endCpuTime;
  TimeDurationT m_requestTime;
  std::weak_ptr<ProcessorHandle> m_handle;

  TestSearchEngine & m_engine;
  SearchParams m_params;
};
}  // namespace tests_support
}  // namespace search
