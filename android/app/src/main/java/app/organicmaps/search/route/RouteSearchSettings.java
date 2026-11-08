package app.organicmaps.search.route;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.Router;
import app.organicmaps.transit.KeyValueStore;

/**
 * The width of the corridor around the route in which the search along the route finds places: the distance from
 * the route on both sides. It depends on the router and can be changed, the choice is kept per router.
 */
public class RouteSearchSettings
{
  public static final String PREFS_NAME = "route_search";
  private static final String KEY_HALF_WIDTH_PREFIX = "half_width_m_";

  @NonNull
  private final KeyValueStore mStore;

  public RouteSearchSettings(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  @NonNull
  public static RouteSearchSettings from(@NonNull Context context)
  {
    return new RouteSearchSettings(KeyValueStore.of(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)));
  }

  /** The widths to choose from, in meters; the default is in the middle. */
  @NonNull
  public static int[] getHalfWidthOptions(@NonNull Router router)
  {
    return switch (router)
    {
      case Vehicle -> new int[] {500, 1000, 2000, 5000};
      case Bicycle -> new int[] {100, 300, 500, 1000};
      case Pedestrian, Transit -> new int[] {50, 150, 300, 500};
      case Ruler -> new int[] {};
    };
  }

  public static int getDefaultHalfWidth(@NonNull Router router)
  {
    return switch (router)
    {
      case Vehicle -> 1000;
      case Bicycle -> 300;
      case Pedestrian, Transit -> 150;
      case Ruler -> 0;
    };
  }

  /** Whether one can search along a route of the router. */
  public static boolean isSupported(@NonNull Router router)
  {
    return router == Router.Vehicle || router == Router.Bicycle || router == Router.Pedestrian;
  }

  public int getHalfWidth(@NonNull Router router)
  {
    final int halfWidth = mStore.getInt(KEY_HALF_WIDTH_PREFIX + router.name(), getDefaultHalfWidth(router));
    for (int option : getHalfWidthOptions(router))
    {
      if (option == halfWidth)
        return halfWidth;
    }
    return getDefaultHalfWidth(router);
  }

  public void setHalfWidth(@NonNull Router router, int halfWidthMeters)
  {
    mStore.putInt(KEY_HALF_WIDTH_PREFIX + router.name(), halfWidthMeters);
  }
}
