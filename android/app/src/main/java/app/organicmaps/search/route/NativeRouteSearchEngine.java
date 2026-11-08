package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.SearchEngine;

/** The search along the route of the native search engine, for {@link RouteSearchPresenter}. */
public class NativeRouteSearchEngine implements RouteSearchPresenter.Engine
{
  @NonNull
  private final String mLocale;

  public NativeRouteSearchEngine(@NonNull String locale)
  {
    mLocale = locale;
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
  public void showResultsOnMap(@NonNull String query, boolean isCategory)
  {
    SearchEngine.INSTANCE.showResultsOnMap();
    // The search button on the map shows that results are shown, and hides them.
    SearchEngine.INSTANCE.setQuery(query, isCategory);
  }
}
