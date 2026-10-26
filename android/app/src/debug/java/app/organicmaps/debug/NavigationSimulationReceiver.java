package app.organicmaps.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.JunctionInfo;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.log.Logger;

/**
 * Debug builds only: drives the current navigation route with simulated locations (the same simulation as
 * Android Auto's auto drive), for emulator tests of the navigation, e.g. the anonymous congestion reports:
 * <pre>
 * adb shell am broadcast -n app.bettermaps.fdroid.debug/app.organicmaps.debug.NavigationSimulationReceiver \
 *   --ef speed 22.2
 * </pre>
 * "speed" is in m/s (one simulated location per second along the route).
 */
public class NavigationSimulationReceiver extends BroadcastReceiver
{
  private static final String TAG = NavigationSimulationReceiver.class.getSimpleName();

  @Override
  public void onReceive(Context context, Intent intent)
  {
    if (!RoutingController.get().isNavigating())
    {
      Logger.w(TAG, "No navigation running");
      return;
    }
    final float speed = intent.getFloatExtra("speed", 13.9f);
    final JunctionInfo[] points = Framework.nativeGetRouteJunctionPoints(speed);
    if (points == null || points.length == 0)
    {
      Logger.w(TAG, "No route points");
      return;
    }
    Logger.i(TAG, "Simulating " + points.length + " locations at " + speed + " m/s");
    MwmApplication.from(context).getLocationHelper().startNavigationSimulation(points);
  }
}
