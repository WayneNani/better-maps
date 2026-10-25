#include "app/organicmaps/sdk/Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "map/crowd_traffic_recorder.hpp"

#include "crowd_traffic/protocol.hpp"

#include <cmath>
#include <vector>

// JNI of app.organicmaps.sdk.crowdtraffic.CrowdTraffic: the native reporting client
// (libs/crowd_traffic) collects and prepares the bundles, Java encrypts (OHTTP) and sends them.
namespace
{
CrowdTrafficRecorder & Recorder()
{
  return frm()->GetRoutingManager().GetCrowdTrafficRecorder();
}
}  // namespace

extern "C"
{
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_crowdtraffic_CrowdTraffic_nativeSetActive(JNIEnv *, jclass,
                                                                                          jboolean active)
{
  Recorder().SetActive(active);
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_crowdtraffic_CrowdTraffic_nativeHasPendingWork(JNIEnv *, jclass)
{
  return Recorder().GetClient().HasPendingWork();
}

// Returns the bundles due at nowMs as CrowdTrafficRequest objects (padded binary HTTP request,
// deadline). The Date header of the requests is nowMs.
JNIEXPORT jobjectArray JNICALL Java_app_organicmaps_sdk_crowdtraffic_CrowdTraffic_nativeTakeDueRequests(JNIEnv * env,
                                                                                                        jclass,
                                                                                                        jlong nowMs)
{
  static jclass const requestClass =
      jni::GetGlobalClassRef(env, "app/organicmaps/sdk/crowdtraffic/CrowdTrafficRequest");
  static jmethodID const ctor = jni::GetConstructorID(env, requestClass, "([BJI)V");

  double const now = static_cast<double>(nowMs) / 1000.0;
  std::vector<std::pair<std::vector<uint8_t>, crowd_traffic::Bundle>> requests;
  for (auto & b : Recorder().GetClient().TakeDue(now))
  {
    auto req = crowd_traffic::EncodeRequest(b.m_reports, static_cast<int64_t>(std::floor(now)));
    if (!req.empty())
      requests.emplace_back(std::move(req), std::move(b));
  }

  jobjectArray result = env->NewObjectArray(static_cast<jsize>(requests.size()), requestClass, nullptr);
  for (size_t i = 0; i < requests.size(); ++i)
  {
    auto const & [req, bundle] = requests[i];
    jni::TScopedLocalByteArrayRef data(env, env->NewByteArray(static_cast<jsize>(req.size())));
    env->SetByteArrayRegion(data.get(), 0, static_cast<jsize>(req.size()), reinterpret_cast<jbyte const *>(req.data()));
    jni::TScopedLocalRef obj(
        env, env->NewObject(requestClass, ctor, data.get(), static_cast<jlong>(bundle.m_deadline * 1000.0),
                            static_cast<jint>(bundle.m_reports.size())));
    env->SetObjectArrayElement(result, static_cast<jsize>(i), obj.get());
  }
  return result;
}
}  // extern "C"
