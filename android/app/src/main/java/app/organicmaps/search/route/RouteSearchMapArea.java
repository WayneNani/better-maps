package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.SearchResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The part of the map that shows the results of the search along the route: the route ahead and the places. With many
 * places (e.g. all fuel stations of a long route) only the next ones and the route up to them, otherwise the map would
 * show the whole country and the places would cover each other.
 */
public final class RouteSearchMapArea
{
  /** With more places, only the next ones are shown. */
  static final int MAX_PLACES = 8;

  /** The places to show. */
  @NonNull
  public final List<SearchResult> places;
  /** How much of the route ahead to show, unless {@link #wholeRoute}. */
  public final double distanceAlongRouteMeters;
  /** Show the whole route ahead: there are only a few places. */
  public final boolean wholeRoute;

  private RouteSearchMapArea(@NonNull List<SearchResult> places, double distanceAlongRouteMeters, boolean wholeRoute)
  {
    this.places = places;
    this.distanceAlongRouteMeters = distanceAlongRouteMeters;
    this.wholeRoute = wholeRoute;
  }

  /** @return null if there is nothing to show. */
  @Nullable
  public static RouteSearchMapArea of(@NonNull List<SearchResult> results)
  {
    if (results.isEmpty())
      return null;
    final List<SearchResult> ahead = new ArrayList<>(results);
    // Already ordered along the route by the search; places without a position (should not happen) last.
    ahead.sort(Comparator.comparingDouble(RouteSearchMapArea::distanceAlongRoute));
    final boolean wholeRoute = ahead.size() <= MAX_PLACES;
    final List<SearchResult> places = wholeRoute ? ahead : ahead.subList(0, MAX_PLACES);
    double distance = 0;
    for (SearchResult place : places)
    {
      if (place.routePosition != null)
        distance = Math.max(distance, place.routePosition.distanceAlongRouteMeters);
    }
    return new RouteSearchMapArea(List.copyOf(places), distance, wholeRoute);
  }

  /** Latitude and longitude of each place, one after the other. */
  @NonNull
  public double[] getLatLons()
  {
    final double[] latLons = new double[places.size() * 2];
    for (int i = 0; i < places.size(); ++i)
    {
      latLons[2 * i] = places.get(i).lat;
      latLons[2 * i + 1] = places.get(i).lon;
    }
    return latLons;
  }

  private static double distanceAlongRoute(@NonNull SearchResult result)
  {
    return result.routePosition != null ? result.routePosition.distanceAlongRouteMeters : Double.MAX_VALUE;
  }
}
