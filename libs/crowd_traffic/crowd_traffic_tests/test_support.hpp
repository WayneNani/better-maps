#pragma once

#include "crowd_traffic/client.hpp"
#include "crowd_traffic/protocol.hpp"
#include "crowd_traffic/randomness.hpp"
#include "crowd_traffic/segmenter.hpp"

#include "crowd_traffic/crowd_traffic_tests/go_vectors.hpp"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace crowd_traffic::tests
{
// The deterministic source of the Go vectors (cmd/crowd-vectors, fixedRand): every uniform draw
// is m_uniform, every IntN is 0.
class FixedRandom final : public RandomSource
{
public:
  explicit FixedRandom(double uniform = 0.5) : m_uniform(uniform) {}
  void Fill(uint8_t * out, size_t size) override;
  double Uniform() override { return m_uniform; }
  uint32_t IntN(uint32_t) override { return 0; }

private:
  double m_uniform;
};

Side SideFromChar(char c);
RoadClass ClassFromInt(int c);
TracePoint ToTracePoint(go_vectors::Pt const & p);
Report ToReport(go_vectors::Rep const & r);

// Day function independent of the time zone of the test machine.
int64_t UtcDay(double unixSeconds);

// Drives a trip through the client like the navigation would: the remaining route distance is
// the rest of the trace, the destination its last point. Due bundles are taken after every
// sample (virtual time), then the trip ends and the remaining windows are sent at their send
// times.
std::vector<Bundle> RunTrip(Client & client, std::vector<TracePoint> const & points);

std::unique_ptr<Client> MakeClient(std::unique_ptr<RandomSource> rnd, ClientConfig const & config = {},
                                   std::string const & countersPath = {});

// A straight trip from (lat1, lon1) to (lat2, lon2) at a constant speed with one sample per
// second.
std::vector<TracePoint> StraightTrip(double lat1, double lon1, double lat2, double lon2, double kmh, double t0,
                                     RoadClass cls);

std::string ToHex(std::vector<uint8_t> const & data);
}  // namespace crowd_traffic::tests
