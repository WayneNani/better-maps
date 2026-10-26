package app.organicmaps.transit.ui;

import android.app.Activity;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;

/**
 * The part of the map that is not covered by the route planner (its toolbar at the top and the connection sheet at
 * the bottom), like the place page does: the map shows routes and places within this part.
 */
final class TransitMapViewport
{
  // Below this height the map would be useless; then the whole map is used.
  private static final int MIN_HEIGHT_DP = 100;

  private TransitMapViewport() {}

  /**
   * Restricts the map to the area between the route planner's toolbar and the top of {@code sheet}.
   *
   * @return false if there is no map (e.g. in tests) or the area is too small.
   */
  static boolean fitAbove(@NonNull Activity activity, @NonNull View sheet)
  {
    final View map = map(activity);
    if (map == null || !sheet.isShown())
      return false;
    final int[] mapLocation = new int[2];
    map.getLocationOnScreen(mapLocation);
    final int[] location = new int[2];
    sheet.getLocationOnScreen(location);
    final int bottom = location[1] - mapLocation[1];
    int top = 0;
    final View toolbar = activity.findViewById(R.id.routing_plan_frame);
    if (toolbar != null && toolbar.isShown())
    {
      toolbar.getLocationOnScreen(location);
      top = Math.max(0, location[1] + toolbar.getHeight() - mapLocation[1]);
    }
    final float density = activity.getResources().getDisplayMetrics().density;
    if (bottom - top < MIN_HEIGHT_DP * density)
      return false;
    Framework.nativeSetVisibleRect(0, top, map.getWidth(), Math.min(bottom, map.getHeight()));
    return true;
  }

  /** The whole map again, e.g. when the route planner closes. */
  static void reset(@NonNull Activity activity)
  {
    final View map = map(activity);
    if (map != null && map.getWidth() > 0 && map.getHeight() > 0)
      Framework.nativeSetVisibleRect(0, 0, map.getWidth(), map.getHeight());
  }

  @Nullable
  private static View map(@NonNull Activity activity)
  {
    // Without the core (e.g. in tests) even asking for the map engine would crash.
    final MwmApplication app = MwmApplication.from(activity);
    if (!app.getOrganicMaps().arePlatformAndCoreInitialized() || !Map.isEngineCreated()
        || app.getDisplayManager().isCarDisplayUsed())
      return null;
    return activity.findViewById(R.id.map);
  }
}
