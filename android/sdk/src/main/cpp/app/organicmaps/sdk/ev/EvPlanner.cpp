// better-maps: charging planner for electric vehicles, see libs/map/ev_planning_api.hpp.
#include "app/organicmaps/sdk/Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "map/ev_planning_api.hpp"

#include <memory>
#include <string>

namespace
{
EvPlanningApi & GetApi()
{
  return g_framework->NativeFramework()->GetEvPlanningApi();
}
}  // namespace

extern "C"
{
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_ev_EvPlanner_nativePlan(JNIEnv * env, jclass, jstring profileJson,
                                                                        jstring optionsJson, jobject listener)
{
  auto const listenerRef = jni::make_global_ref(listener);
  GetApi().Plan(jni::ToNativeString(env, profileJson), jni::ToNativeString(env, optionsJson),
                [listenerRef](std::string const & json)
  {
    JNIEnv * env = jni::GetEnv();
    static jmethodID const onPlan = jni::GetMethodID(env, *listenerRef, "onPlan", "(Ljava/lang/String;)V");
    jni::TScopedLocalRef const jsonRef(env, jni::ToJavaString(env, json));
    env->CallVoidMethod(*listenerRef, onPlan, jsonRef.get());
    jni::HandleJavaException(env);
  });
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_ev_EvPlanner_nativeCancel(JNIEnv *, jclass)
{
  GetApi().Cancel();
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_ev_EvPlanner_nativeEvaluate(JNIEnv * env, jclass,
                                                                               jstring profileJson, jstring optionsJson,
                                                                               jstring stopsJson,
                                                                               jboolean fromCurrentPosition)
{
  auto const json = GetApi().Evaluate(jni::ToNativeString(env, profileJson), jni::ToNativeString(env, optionsJson),
                                      jni::ToNativeString(env, stopsJson), static_cast<bool>(fromCurrentPosition));
  return jni::ToJavaString(env, json);
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_ev_EvPlanner_nativeGetVehicleTemplates(JNIEnv * env, jclass)
{
  return jni::ToJavaString(env, EvPlanningApi::GetVehicleTemplatesJson());
}
}  // extern "C"
