package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.RoutePosition;
import app.organicmaps.sdk.search.SearchResult;

/** Formats the results of the search along the route, e.g. "in 12 km · Detour ~2 min". */
public final class RouteSearchFormatter
{
  public static final String SEPARATOR = " · ";

  // A smaller detour is not worth mentioning: the place is next to the route.
  static final int MIN_DETOUR_SEC = 30;

  private RouteSearchFormatter() {}

  /** "in 12 km · Detour ~2 min", or "in 12 km · On the route". */
  @NonNull
  public static String formatPosition(@NonNull RoutePosition position, @NonNull RouteSearchTexts texts)
  {
    final String where = texts.inDistance(texts.distance(position.distanceAlongRoute));
    if (position.detourSec < MIN_DETOUR_SEC)
      return where + SEPARATOR + texts.onTheRoute();
    final int minutes = Math.max(1, Math.round(position.detourSec / 60.0f));
    return where + SEPARATOR + texts.detourMinutes(minutes);
  }

  /**
   * "Open on arrival" or "Closed on arrival": the search evaluates opening hours at the estimated arrival.
   *
   * @return null if the opening hours are unknown.
   */
  @Nullable
  public static String formatOpeningHours(@NonNull SearchResult result, @NonNull RouteSearchTexts texts)
  {
    if (result.description == null)
      return null;
    return switch (result.description.openNow)
    {
      case SearchResult.OPEN_NOW_YES -> texts.openOnArrival(true);
      case SearchResult.OPEN_NOW_NO -> texts.openOnArrival(false);
      default -> null;
    };
  }
}
