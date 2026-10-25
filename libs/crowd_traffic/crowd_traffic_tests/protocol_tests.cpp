#include "testing/testing.hpp"

#include "crowd_traffic/crowd_traffic_tests/test_support.hpp"
#include "crowd_traffic/protocol.hpp"

#include <string>
#include <vector>

namespace protocol_tests
{
using namespace crowd_traffic;
using namespace crowd_traffic::tests;

std::vector<Report> ExampleReports()
{
  std::vector<Report> res;
  for (auto const & r : go_vectors::kExampleReports)
    res.push_back(ToReport(r));
  return res;
}

UNIT_TEST(CrowdTraffic_BucketsMatchGo)
{
  for (auto const & b : go_vectors::kBuckets)
    TEST_EQUAL(BucketOf(b.kmh), b.bucket, (b.kmh));
  TEST_EQUAL(BucketOf(-1), 0, ());
}

UNIT_TEST(CrowdTraffic_JsonMatchesGo)
{
  TEST_EQUAL(EncodeBundleJson(ExampleReports()), go_vectors::kExampleJson, ());
  TEST_EQUAL(EncodeBundleJson({}), "{\"version\":1,\"reports\":[]}", ());
}

UNIT_TEST(CrowdTraffic_BinaryHttpMatchesGo)
{
  auto const req = EncodeRequest(ExampleReports(), go_vectors::kExampleDate);
  TEST_EQUAL(req.size(), go_vectors::kExampleBhttpSize, ());
  TEST_EQUAL(req.size(), kPadTo, ());
  std::string const expected = go_vectors::kExampleBhttpHex;
  std::string const hex = ToHex(req);
  TEST_EQUAL(hex.substr(0, expected.size()), expected, ());
  // The rest is zero padding.
  TEST_EQUAL(hex.find_first_not_of('0', expected.size()), std::string::npos, ());
}

UNIT_TEST(CrowdTraffic_AllRequestsHaveTheSameSize)
{
  auto reports = ExampleReports();
  TEST_EQUAL(EncodeRequest({reports[0]}, 0).size(), kPadTo, ());
  // Twelve reports with the longest field values still fit.
  Report big = reports[0];
  big.m_tile = {131071, 131071};
  big.m_class = RoadClass::Secondary;
  big.m_entry.m_offset = big.m_exit.m_offset = 255;
  big.m_window = 9999999900;
  std::vector<Report> many(kMaxReportsPerBundle, big);
  TEST_EQUAL(EncodeRequest(many, 0).size(), kPadTo, ());
  // Anything that would not fit is not encoded at all.
  std::vector<Report> tooMany(40, big);
  TEST(EncodeRequest(tooMany, 0).empty(), ());
}

UNIT_TEST(CrowdTraffic_HttpDate)
{
  TEST_EQUAL(FormatHttpDate(go_vectors::kExampleDate), "Mon, 26 Oct 2026 16:01:30 GMT", ());
  TEST_EQUAL(FormatHttpDate(0), "Thu, 01 Jan 1970 00:00:00 GMT", ());
  TEST_EQUAL(FormatHttpDate(951782400), "Tue, 29 Feb 2000 00:00:00 GMT", ());
}

UNIT_TEST(CrowdTraffic_Varint)
{
  auto enc = [](uint64_t v)
  {
    std::vector<uint8_t> out;
    AppendVarint(out, v);
    return ToHex(out);
  };
  // RFC 9000, appendix A.1.
  TEST_EQUAL(enc(37), "25", ());
  TEST_EQUAL(enc(15293), "7bbd", ());
  TEST_EQUAL(enc(494878333), "9d7f3e7d", ());
  TEST_EQUAL(enc(151288809941952652ULL), "c2197c5eff14e88c", ());
  TEST_EQUAL(enc(63), "3f", ());
  TEST_EQUAL(enc(64), "4040", ());
}

UNIT_TEST(CrowdTraffic_WindowsAndKeys)
{
  TEST_EQUAL(WindowStart(1793029500.0), 1793029500, ());
  TEST_EQUAL(WindowStart(1793030399.9), 1793029500, ());
  TEST_EQUAL(WindowStart(1793030400.0), 1793030400, ());
  TEST_EQUAL(WindowStart(-1.0), -900, ());
  TEST_EQUAL(WindowStart(125.0, 60), 120, ());

  auto const r = ExampleReports()[1];
  TEST_EQUAL(DebugPrint(r.Key()), "17/70395/43011/N-E/MOTORWAY", ());
  TEST(IsReportable(RoadClass::Motorway) && IsReportable(RoadClass::Tertiary), ());
  TEST(!IsReportable(RoadClass::None), ());
  for (auto [cls, name] : std::vector<std::pair<RoadClass, std::string>>{{RoadClass::Motorway, "MOTORWAY"},
                                                                         {RoadClass::Trunk, "TRUNK"},
                                                                         {RoadClass::Primary, "PRIMARY"},
                                                                         {RoadClass::Secondary, "SECONDARY"},
                                                                         {RoadClass::Tertiary, "TERTIARY"},
                                                                         {RoadClass::None, ""}})
  {
    TEST_EQUAL(RoadClassName(cls), name, ());
  }
}
}  // namespace protocol_tests
