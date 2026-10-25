#include "testing/testing.hpp"

#include "crowd_traffic/protocol.hpp"
#include "crowd_traffic/randomness.hpp"

#include <array>
#include <cmath>
#include <set>
#include <stdexcept>
#include <vector>

namespace randomness_tests
{
using namespace crowd_traffic;
using std::invalid_argument;

UNIT_TEST(CrowdTraffic_RandomizedResponseParameters)
{
  RandomizedResponse const rr(kNumSpeedBuckets, kEpsilon);
  // docs/CROWD_TRAFFIC_PRIVACY.md, section 5: p = e³/(e³+6) = 0.770, q = 1/(e³+6) = 0.038.
  TEST_ALMOST_EQUAL_ABS(rr.GetP(), std::exp(3.0) / (std::exp(3.0) + 6), 1e-12, ());
  TEST_ALMOST_EQUAL_ABS(rr.GetP(), 0.770, 1e-3, ());
  TEST_ALMOST_EQUAL_ABS(rr.GetQ(), 0.038, 1e-3, ());
  TEST_ALMOST_EQUAL_ABS(rr.GetP() + 6 * rr.GetQ(), 1.0, 1e-12, ());
  // ε-LDP: the ratio of the output probabilities of two true values is at most e^ε.
  TEST_ALMOST_EQUAL_ABS(rr.GetP() / rr.GetQ(), std::exp(kEpsilon), 1e-9, ());
  TEST_EQUAL(rr.GetK(), 7, ());
  TEST_THROW(RandomizedResponse(1, 3.0), invalid_argument, ());
  TEST_THROW(RandomizedResponse(7, 0.0), invalid_argument, ());
}

// The output distribution matches p and q for every true bucket (fixed seed, n = 200000 per
// bucket; tolerance 5 standard errors).
UNIT_TEST(CrowdTraffic_RandomizedResponseDistribution)
{
  RandomizedResponse const rr(kNumSpeedBuckets, kEpsilon);
  SeededRandom rnd(20261025);
  int constexpr n = 200000;
  for (int v = 0; v < kNumSpeedBuckets; ++v)
  {
    std::array<int, kNumSpeedBuckets> counts{};
    for (int i = 0; i < n; ++i)
    {
      int const out = rr.Perturb(v, rnd);
      TEST(out >= 0 && out < kNumSpeedBuckets, (out));
      ++counts[out];
    }
    double chi2 = 0;
    for (int j = 0; j < kNumSpeedBuckets; ++j)
    {
      double const prob = j == v ? rr.GetP() : rr.GetQ();
      double const se = std::sqrt(prob * (1 - prob) / n);
      double const observed = static_cast<double>(counts[j]) / n;
      TEST_LESS(std::abs(observed - prob), 5 * se, (v, j, observed, prob));
      double const expected = prob * n;
      chi2 += (counts[j] - expected) * (counts[j] - expected) / expected;
    }
    // χ² with 6 degrees of freedom: P(χ² > 22.46) = 0.001.
    TEST_LESS(chi2, 22.46, (v));
  }
}

// The unbiased estimator of the server recovers the true distribution from noisy reports.
UNIT_TEST(CrowdTraffic_RandomizedResponseEstimator)
{
  RandomizedResponse const rr(kNumSpeedBuckets, kEpsilon);
  SeededRandom rnd(7);
  std::array<double, kNumSpeedBuckets> const truth = {0.5, 0.3, 0.0, 0.0, 0.0, 0.2, 0.0};
  int constexpr n = 100000;
  std::array<int, kNumSpeedBuckets> counts{};
  for (int i = 0; i < n; ++i)
  {
    double u = rnd.Uniform();
    int v = 0;
    while (v < kNumSpeedBuckets - 1 && u >= truth[v])
      u -= truth[v++];
    ++counts[rr.Perturb(v, rnd)];
  }
  for (int j = 0; j < kNumSpeedBuckets; ++j)
  {
    double const f = (static_cast<double>(counts[j]) / n - rr.GetQ()) / (rr.GetP() - rr.GetQ());
    TEST_LESS(std::abs(f - truth[j]), 0.015, (j, f));
  }
}

UNIT_TEST(CrowdTraffic_UniformAndIntN)
{
  SeededRandom rnd(1);
  double sum = 0;
  for (int i = 0; i < 100000; ++i)
  {
    double const u = rnd.Uniform();
    TEST(u >= 0 && u < 1, (u));
    sum += u;
  }
  TEST_ALMOST_EQUAL_ABS(sum / 100000, 0.5, 0.01, ());
  std::array<int, 6> counts{};
  for (int i = 0; i < 60000; ++i)
  {
    auto const v = rnd.IntN(6);
    TEST_LESS(v, 6u, ());
    ++counts[v];
  }
  for (int c : counts)
    TEST_LESS(std::abs(c - 10000), 500, (c));
  TEST_EQUAL(rnd.IntN(1), 0u, ());
  TEST_THROW(rnd.IntN(0), invalid_argument, ());
  double const x = rnd.Uniform(30, 240);
  TEST(x >= 30 && x < 240, (x));
}

UNIT_TEST(CrowdTraffic_SecureRandom)
{
  SecureRandom rnd;
  std::set<std::vector<uint8_t>> seen;
  for (int i = 0; i < 100; ++i)
  {
    std::vector<uint8_t> b(16);
    rnd.Fill(b.data(), b.size());
    seen.insert(b);
  }
  // 100 different 128-bit values.
  TEST_EQUAL(seen.size(), 100, ());
  std::array<int, kNumSpeedBuckets> counts{};
  RandomizedResponse const rr(kNumSpeedBuckets, kEpsilon);
  for (int i = 0; i < 20000; ++i)
    ++counts[rr.Perturb(3, rnd)];
  TEST_ALMOST_EQUAL_ABS(counts[3] / 20000.0, rr.GetP(), 0.02, ());
  std::vector<uint8_t> large(1000);
  rnd.Fill(large.data(), large.size());
  rnd.Fill(large.data(), 0);
}
}  // namespace randomness_tests
