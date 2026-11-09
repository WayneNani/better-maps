package app.organicmaps.sdk.ev;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

/**
 * better-maps: charging planner for electric vehicles along the current car route (native core, see
 * libs/map/ev_planning_api.hpp). Profiles, options and results are JSON.
 */
public final class EvPlanner
{
  public interface Listener
  {
    /** Called on the main thread with the result of {@link #plan}, e.g. {"status":"ok","plan":{...}}. */
    void onPlan(@NonNull String json);
  }

  private EvPlanner() {}

  /** Plans charging stops along the current route in the background. A new call cancels the previous one. */
  @MainThread
  public static void plan(@NonNull String profileJson, @NonNull String optionsJson, @NonNull Listener listener)
  {
    nativePlan(profileJson, optionsJson, listener);
  }

  @MainThread
  public static void cancel()
  {
    nativeCancel();
  }

  /**
   * Evaluates the charging stops, which are intermediate points of the current route, with this route.
   *
   * @param fromCurrentPosition from the current position (with the start state of charge of the options), e.g.
   *                            after the user corrected the state of charge during the navigation.
   */
  @MainThread
  @NonNull
  public static String evaluate(@NonNull String profileJson, @NonNull String optionsJson, @NonNull String stopsJson,
                                boolean fromCurrentPosition)
  {
    return nativeEvaluate(profileJson, optionsJson, stopsJson, fromCurrentPosition);
  }

  /** JSON array of the vehicle templates. */
  @NonNull
  public static String getVehicleTemplates()
  {
    return nativeGetVehicleTemplates();
  }

  private static native void nativePlan(@NonNull String profileJson, @NonNull String optionsJson,
                                        @NonNull Listener listener);
  private static native void nativeCancel();
  @NonNull
  private static native String nativeEvaluate(@NonNull String profileJson, @NonNull String optionsJson,
                                              @NonNull String stopsJson, boolean fromCurrentPosition);
  @NonNull
  private static native String nativeGetVehicleTemplates();
}
