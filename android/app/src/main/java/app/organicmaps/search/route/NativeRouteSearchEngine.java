package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.location.LocationState;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.SearchEngine;

/** The search along the route of the native search engine and the map, for {@link RouteSearchPresenter}. */
public class NativeRouteSearchEngine implements RouteSearchPresenter.Engine
{
  @NonNull
  private final String mLocale;
  private final boolean mWithMap;

  /** @param withMap the results are shown on the map; otherwise only in a list (e.g. in Android Auto). */
  public NativeRouteSearchEngine(@NonNull String locale, boolean withMap)
  {
    mLocale = locale;
    mWithMap = withMap;
  }

  @Override
  public boolean search(@NonNull String query, boolean isCategory, long timestamp, int halfWidthMeters,
                        @NonNull OpeningHoursFilter filter)
  {
    return SearchEngine.INSTANCE.searchAlongRoute(query, isCategory, mLocale, timestamp, halfWidthMeters, filter,
                                                  false /* showOnMap */);
  }

  @Override
  public void cancel()
  {
    SearchEngine.INSTANCE.cancel();
  }

  @Override
  public void showResult(int index)
  {
    SearchEngine.INSTANCE.showResult(index);
  }

  @Override
  public void showResultsOnMap(@NonNull int[] hiddenIndices)
  {
    if (!mWithMap)
      return;
    SearchEngine.INSTANCE.showResultsOnMap(hiddenIndices);
  }

  @Override
  public void showOnMap(@NonNull RouteSearchMapArea area)
  {
    if (!mWithMap)
      return;
    SearchEngine.INSTANCE.showAlongRoute(area.getLatLons(), area.distanceAlongRouteMeters, area.wholeRoute);
  }

  @Override
  public void onStart()
  {
    if (!mWithMap)
      return;
    // During the navigation the map would go back to the position after some seconds.
    Framework.nativeHoldMapPositionInRouting(true);
  }

  @Override
  public void onFinish()
  {
    if (!mWithMap)
      return;
    SearchEngine.INSTANCE.clearResultsOnMap();
    Framework.nativeHoldMapPositionInRouting(false);
    // The navigation follows the position again.
    if (RoutingController.get().isNavigating() && Map.isEngineCreated()
        && LocationState.getMode() == LocationState.NOT_FOLLOW)
      LocationState.nativeSwitchToNextMode();
  }
}
