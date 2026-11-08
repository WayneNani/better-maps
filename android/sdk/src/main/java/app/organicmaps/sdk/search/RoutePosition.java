package app.organicmaps.sdk.search;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.Distance;

/**
 * better-maps: where a result of the search along the route is relative to the route (search::RoutePosition).
 * <p>
 * Created from native code.
 */
// Used by JNI.
@Keep
@SuppressWarnings("unused")
public final class RoutePosition
{
  /** Distance along the route from the current position to where one leaves the route for the place. */
  @NonNull
  public final Distance distanceAlongRoute;
  public final double distanceAlongRouteMeters;
  /** Distance from the place to the route, as the crow flies. */
  public final double distanceFromRouteMeters;
  /** Estimated travel time from the current position to the place, including half of the detour. */
  public final int timeToPlaceSec;
  /** Rough estimate of the extra travel time to visit the place and come back to the route. */
  public final int detourSec;

  public RoutePosition(@NonNull Distance distanceAlongRoute, double distanceAlongRouteMeters,
                       double distanceFromRouteMeters, int timeToPlaceSec, int detourSec)
  {
    this.distanceAlongRoute = distanceAlongRoute;
    this.distanceAlongRouteMeters = distanceAlongRouteMeters;
    this.distanceFromRouteMeters = distanceFromRouteMeters;
    this.timeToPlaceSec = timeToPlaceSec;
    this.detourSec = detourSec;
  }
}
