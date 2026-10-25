#pragma once

#include <cstddef>
#include <cstdint>
#include <random>

namespace crowd_traffic
{
// Source of randomness for all client decisions: start trimming, send delay, sampling,
// randomized response and the salt of the daily counters.
class RandomSource
{
public:
  virtual ~RandomSource() = default;

  virtual void Fill(uint8_t * out, size_t size) = 0;
  // Uniform in [0, 1) with 53 random bits.
  virtual double Uniform();
  // Uniform in {0, ..., n - 1} without modulo bias; n > 0.
  virtual uint32_t IntN(uint32_t n);
  double Uniform(double lo, double hi) { return lo + (hi - lo) * Uniform(); }

protected:
  uint64_t NextUint64();
};

// Cryptographically secure randomness of the operating system (arc4random_buf on Android and
// Apple platforms, getrandom/urandom on Linux).
class SecureRandom final : public RandomSource
{
public:
  void Fill(uint8_t * out, size_t size) override;
};

// Deterministic pseudo random numbers for tests and simulations only.
class SeededRandom final : public RandomSource
{
public:
  explicit SeededRandom(uint64_t seed) : m_engine(seed) {}
  void Fill(uint8_t * out, size_t size) override;

private:
  std::mt19937_64 m_engine;
};

// k-ary randomized response (generalized randomized response) over the speed buckets
// (docs/CROWD_TRAFFIC_PRIVACY.md, sections 5 and 13.3): the true bucket is kept with probability
// p = e^ε / (e^ε + k − 1), every other bucket is reported with probability q = 1 / (e^ε + k − 1).
class RandomizedResponse
{
public:
  RandomizedResponse(int k, double epsilon);

  int GetK() const { return m_k; }
  double GetP() const { return m_p; }
  double GetQ() const { return m_q; }

  int Perturb(int bucket, RandomSource & rnd) const;

private:
  int m_k;
  double m_p;
  double m_q;
};
}  // namespace crowd_traffic
