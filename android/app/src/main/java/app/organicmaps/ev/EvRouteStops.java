package app.organicmaps.ev;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The charging plan that is part of the current route: its stops are intermediate points of the route. The route
 * planner and the navigation add the charging time of the stops ahead to the route time.
 */
@MainThread
public final class EvRouteStops
{
  public static final EvRouteStops INSTANCE = new EvRouteStops();

  /** A route point is a charging stop within this distance. */
  static final double MATCH_DISTANCE_M = 150;

  @Nullable
  private EvPlanResult.Plan mPlan;
  @Nullable
  private String mProfileJson;
  @Nullable
  private EvPlanOptions mOptions;

  @Nullable
  public EvPlanResult.Plan getPlan()
  {
    return mPlan;
  }

  @Nullable
  public String getProfileJson()
  {
    return mProfileJson;
  }

  @Nullable
  public EvPlanOptions getOptions()
  {
    return mOptions;
  }

  @NonNull
  public List<EvPlanResult.Stop> getStops()
  {
    return mPlan == null ? Collections.emptyList() : mPlan.stops;
  }

  public void set(@NonNull EvPlanResult.Plan plan, @NonNull String profileJson, @NonNull EvPlanOptions options)
  {
    mPlan = plan;
    mProfileJson = profileJson;
    mOptions = options;
  }

  public void clear()
  {
    mPlan = null;
    mProfileJson = null;
    mOptions = null;
  }

  public boolean hasStops()
  {
    return mPlan != null && !mPlan.stops.isEmpty();
  }

  static double distanceM(double lat1, double lon1, double lat2, double lon2)
  {
    final double r = 6378137.0;
    final double dLat = Math.toRadians(lat2 - lat1);
    final double dLon = Math.toRadians(lon2 - lon1);
    final double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  /** @return the stop of the plan at the route point, null if it is none. */
  @Nullable
  public static EvPlanResult.Stop findStop(@NonNull List<EvPlanResult.Stop> stops, double lat, double lon)
  {
    for (EvPlanResult.Stop stop : stops)
      if (distanceM(stop.lat, stop.lon, lat, lon) < MATCH_DISTANCE_M)
        return stop;
    return null;
  }

  public boolean isChargingStop(@NonNull RouteMarkData point)
  {
    return point.mPointType == RouteMarkType.Intermediate && findStop(getStops(), point.mLat, point.mLon) != null;
  }

  /** The stops of the plan that are route points not passed yet, in the order of the route. */
  @NonNull
  public List<EvPlanResult.Stop> getStopsAhead(@NonNull RouteMarkData[] points)
  {
    final List<EvPlanResult.Stop> ahead = new ArrayList<>();
    for (RouteMarkData point : points)
    {
      if (point.mPointType != RouteMarkType.Intermediate || point.mIsPassed)
        continue;
      final EvPlanResult.Stop stop = findStop(getStops(), point.mLat, point.mLon);
      if (stop != null)
        ahead.add(stop);
    }
    return ahead;
  }

  /** Time at the charging stops ahead, to add to the remaining route time. */
  public double getRemainingStopTimeSec(@NonNull RouteMarkData[] points)
  {
    double sec = 0;
    for (EvPlanResult.Stop stop : getStopsAhead(points))
      sec += stop.stopTimeSec;
    return sec;
  }

  /** The time to the next intermediate point plus the charging there, if it is a charging stop. */
  @Nullable
  public EvPlanResult.Stop getNextStop(@NonNull RouteMarkData[] points)
  {
    final List<EvPlanResult.Stop> ahead = getStopsAhead(points);
    return ahead.isEmpty() ? null : ahead.get(0);
  }
}
