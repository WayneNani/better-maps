#include "crowd_traffic/randomness.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <stdexcept>

#if defined(__ANDROID__) || defined(__APPLE__)
#include <stdlib.h>  // arc4random_buf
#else
#include <cerrno>

#include <sys/random.h>
#endif

namespace crowd_traffic
{
uint64_t RandomSource::NextUint64()
{
  uint8_t b[8];
  Fill(b, sizeof(b));
  uint64_t v = 0;
  for (uint8_t const c : b)
    v = (v << 8) | c;
  return v;
}

double RandomSource::Uniform()
{
  return static_cast<double>(NextUint64() >> 11) * 0x1.0p-53;
}

uint32_t RandomSource::IntN(uint32_t n)
{
  if (n == 0)
    throw std::invalid_argument("IntN: n must be positive");
  // Rejection sampling: accept only values below the largest multiple of n.
  uint64_t const limit = (static_cast<uint64_t>(1) << 32) / n * n;
  while (true)
  {
    uint64_t const v = NextUint64() & 0xffffffffULL;
    if (v < limit)
      return static_cast<uint32_t>(v % n);
  }
}

void SecureRandom::Fill(uint8_t * out, size_t size)
{
#if defined(__ANDROID__) || defined(__APPLE__)
  arc4random_buf(out, size);
#else
  while (size > 0)
  {
    ssize_t const n = getrandom(out, size, 0);
    if (n < 0)
    {
      if (errno == EINTR)
        continue;
      throw std::runtime_error("getrandom failed");
    }
    out += n;
    size -= static_cast<size_t>(n);
  }
#endif
}

void SeededRandom::Fill(uint8_t * out, size_t size)
{
  while (size > 0)
  {
    uint64_t const v = m_engine();
    size_t const n = std::min<size_t>(size, sizeof(v));
    std::memcpy(out, &v, n);
    out += n;
    size -= n;
  }
}

RandomizedResponse::RandomizedResponse(int k, double epsilon) : m_k(k)
{
  if (k < 2 || epsilon <= 0)
    throw std::invalid_argument("RandomizedResponse: need k >= 2 and epsilon > 0");
  double const e = std::exp(epsilon);
  double const d = e + k - 1;
  m_p = e / d;
  m_q = 1 / d;
}

int RandomizedResponse::Perturb(int bucket, RandomSource & rnd) const
{
  if (rnd.Uniform() < m_p)
    return bucket;
  int j = static_cast<int>(rnd.IntN(static_cast<uint32_t>(m_k - 1)));
  if (j >= bucket)
    ++j;
  return j;
}
}  // namespace crowd_traffic
