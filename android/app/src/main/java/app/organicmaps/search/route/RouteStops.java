package app.organicmaps.search.route;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.SearchResult;

/** Stops of the current route, for {@link RouteSearchPresenter}. */
public class RouteStops implements RouteSearchPresenter.Stops
{
  @Override
  public boolean canAddStop()
  {
    return RoutingController.get().isStopPointAllowed();
  }

  @Override
  public boolean isStop(@NonNull SearchResult result)
  {
    final RouteMarkData[] points = Framework.nativeGetRoutePoints();
    if (points == null)
      return false;
    for (RouteMarkData point : points)
    {
      // The start is where one comes from, e.g. the current position.
      if (point.mPointType != RouteMarkType.Start && RouteSearchPlaces.isAt(result, point.mLat, point.mLon))
        return true;
    }
    return false;
  }

  @Override
  public void addStop(@NonNull SearchResult result)
  {
    RoutingController.get().addStop(toMapObject(result));
  }

  @NonNull
  public static MapObject toMapObject(@NonNull SearchResult result)
  {
    final String type = result.description != null ? result.description.localizedFeatureType : "";
    final String title = TextUtils.isEmpty(result.name) ? type : result.name;
    final FeatureId featureId = result.description != null && result.description.featureId != null
                                  ? result.description.featureId
                                  : FeatureId.EMPTY;
    return MapObject.createMapObject(featureId, MapObject.SEARCH, title, type, result.lat, result.lon);
  }
}
