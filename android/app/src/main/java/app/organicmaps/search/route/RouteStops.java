package app.organicmaps.search.route;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.SearchResult;

/** Stops of the current route, for {@link RouteSearchPresenter}. */
public class RouteStops implements RouteSearchPresenter.Stops
{
  @NonNull
  private final Runnable mOnAdded;

  /** @param onAdded called after a stop was added, e.g. to tell that the route is recalculated. */
  public RouteStops(@NonNull Runnable onAdded)
  {
    mOnAdded = onAdded;
  }

  @Override
  public boolean canAddStop()
  {
    return RoutingController.get().isStopPointAllowed();
  }

  @Override
  public void addStop(@NonNull SearchResult result)
  {
    RoutingController.get().addStop(toMapObject(result));
    mOnAdded.run();
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
