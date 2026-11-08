package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.search.SearchResult;

/** Whether a result of the search along the route is a place on the map, e.g. of a place page or a route point. */
final class RouteSearchPlaces
{
  // A place page or a route point of a result is at the same coordinates; this allows for rounding.
  private static final double SAME_PLACE_DEGREES = 1e-5;
  // A route point added for a result, at most this far away (the route point may be moved to the road).
  static final double STOP_DISTANCE_METERS = 30;
  private static final double EARTH_RADIUS_METERS = 6_371_000;

  private RouteSearchPlaces() {}

  /** The same feature, or the same coordinates if one of them has no feature. */
  static boolean isSamePlace(@NonNull SearchResult result, @Nullable FeatureId featureId, double lat, double lon)
  {
    final FeatureId resultId = result.description != null ? result.description.featureId : null;
    if (hasFeature(featureId) && hasFeature(resultId))
      return featureId.equals(resultId);
    return Math.abs(result.lat - lat) < SAME_PLACE_DEGREES && Math.abs(result.lon - lon) < SAME_PLACE_DEGREES;
  }

  // Like FeatureId.isRealId(), without Android (TextUtils) for the tests.
  private static boolean hasFeature(@Nullable FeatureId id)
  {
    return id != null && !id.getMwmName().isEmpty() && !FeatureId.EMPTY.equals(id);
  }

  /** A route point at |lat|, |lon| is the result. */
  static boolean isAt(@NonNull SearchResult result, double lat, double lon)
  {
    return distanceMeters(result.lat, result.lon, lat, lon) <= STOP_DISTANCE_METERS;
  }

  /** Equirectangular approximation, good enough for short distances. */
  static double distanceMeters(double lat1, double lon1, double lat2, double lon2)
  {
    final double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
    final double y = Math.toRadians(lat2 - lat1);
    return Math.sqrt(x * x + y * y) * EARTH_RADIUS_METERS;
  }
}
