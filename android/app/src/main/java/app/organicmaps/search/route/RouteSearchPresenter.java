package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.SearchResult;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Logic of the search along the route: categories and corridor width for the router, the search requests and their
 * results, adding a result as a stop. Without Android, so it can be tested on its own.
 */
public class RouteSearchPresenter
{
  /** The native search, see SearchEngine. */
  public interface Engine
  {
    /** @return false if there is no route to search along. */
    boolean search(@NonNull String query, boolean isCategory, long timestamp, int halfWidthMeters,
                   @NonNull OpeningHoursFilter filter);

    void cancel();

    /** Shows the result of the last search with the index on the map, with its place page. */
    void showResult(int index);

    /** Shows all results of the last search on the map. */
    void showResultsOnMap(@NonNull String query, boolean isCategory);
  }

  /** The stops of the route, see RoutingController. */
  public interface Stops
  {
    boolean canAddStop();

    /** Adds the place as a stop and builds the route again; the navigation goes on. */
    void addStop(@NonNull SearchResult result);
  }

  public interface View
  {
    void showCategories(@NonNull List<RouteSearchCategory> categories);

    void showHalfWidth(int halfWidthMeters, @NonNull int[] options);

    void showResults(@NonNull List<SearchResult> results);

    void showProgress(boolean show);

    /** There is no route (any more) to search along. */
    void showNoRoute();

    /** The number of places hidden by the "Open" filter because of unknown opening hours. */
    void showHiddenWithoutOpeningHours(int count);

    /** Leaves the search, back to the map. */
    void close();
  }

  @NonNull
  private final Router mRouter;
  @NonNull
  private final RouteSearchSettings mSettings;
  @NonNull
  private final Engine mEngine;
  @NonNull
  private final Stops mStops;
  @NonNull
  private final LongSupplier mClock;

  @Nullable
  private View mView;
  @NonNull
  private String mQuery = "";
  private boolean mIsCategory;
  @NonNull
  private OpeningHoursFilter mFilter = OpeningHoursFilter.OFF;
  // Timestamp of the last search, results of older ones are ignored.
  private long mTimestamp;
  private boolean mSearching;
  @NonNull
  private List<SearchResult> mResults = List.of();

  public RouteSearchPresenter(@NonNull Router router, @NonNull RouteSearchSettings settings, @NonNull Engine engine,
                              @NonNull Stops stops, @NonNull LongSupplier clock)
  {
    mRouter = router;
    mSettings = settings;
    mEngine = engine;
    mStops = stops;
    mClock = clock;
  }

  public void attach(@NonNull View view)
  {
    mView = view;
    view.showCategories(RouteSearchCategory.forRouter(mRouter));
    view.showHalfWidth(getHalfWidth(), RouteSearchSettings.getHalfWidthOptions(mRouter));
    view.showResults(mResults);
    view.showProgress(mSearching);
  }

  public void detach()
  {
    mView = null;
  }

  @NonNull
  public Router getRouter()
  {
    return mRouter;
  }

  public int getHalfWidth()
  {
    return mSettings.getHalfWidth(mRouter);
  }

  @NonNull
  public String getQuery()
  {
    return mQuery;
  }

  public boolean isCategory()
  {
    return mIsCategory;
  }

  /** A category was tapped: |name| is its text. */
  public void onCategorySelected(@NonNull String name)
  {
    setQuery(name, true);
  }

  /** The text of the search field has changed. */
  public void onQueryChanged(@NonNull String query, boolean isCategory)
  {
    setQuery(query, isCategory);
  }

  public void onHalfWidthSelected(int halfWidthMeters)
  {
    if (halfWidthMeters == getHalfWidth())
      return;
    mSettings.setHalfWidth(mRouter, halfWidthMeters);
    if (mView != null)
      mView.showHalfWidth(getHalfWidth(), RouteSearchSettings.getHalfWidthOptions(mRouter));
    search();
  }

  public void onOpeningHoursFilterChanged(@NonNull OpeningHoursFilter filter)
  {
    if (filter == mFilter)
      return;
    mFilter = filter;
    search();
  }

  public void onResultsUpdate(@NonNull SearchResult[] results, long timestamp)
  {
    if (timestamp != mTimestamp || mQuery.isEmpty())
      return;
    mResults = Arrays.asList(results);
    if (mView != null)
      mView.showResults(mResults);
  }

  public void onResultsEnd(long timestamp, int hiddenWithoutOpeningHours)
  {
    if (timestamp != mTimestamp)
      return;
    mSearching = false;
    if (mView != null)
    {
      mView.showProgress(false);
      mView.showHiddenWithoutOpeningHours(hiddenWithoutOpeningHours);
    }
  }

  /** A result in the list was tapped: show it on the map. */
  public void onResultClicked(int index)
  {
    if (index < 0 || index >= mResults.size())
      return;
    mEngine.showResult(index);
    if (mView != null)
      mView.close();
  }

  public boolean canAddStop()
  {
    return mStops.canAddStop();
  }

  /** "Add as stop" of a result was tapped. */
  public void onAddStopClicked(@NonNull SearchResult result)
  {
    if (!mStops.canAddStop())
      return;
    mEngine.cancel();
    mStops.addStop(result);
    if (mView != null)
      mView.close();
  }

  /** Shows all results on the map along the route. */
  public void onShowOnMapClicked()
  {
    if (mQuery.isEmpty())
      return;
    mEngine.showResultsOnMap(mQuery, mIsCategory);
    if (mView != null)
      mView.close();
  }

  /** The search screen is left without a result. */
  public void onCancel()
  {
    mEngine.cancel();
  }

  private void setQuery(@NonNull String query, boolean isCategory)
  {
    final String trimmed = query.trim();
    if (trimmed.equals(mQuery.trim()) && isCategory == mIsCategory)
      return;
    mQuery = query;
    mIsCategory = isCategory && !trimmed.isEmpty();
    search();
  }

  private void search()
  {
    if (mQuery.trim().isEmpty())
    {
      mEngine.cancel();
      ++mTimestamp;
      mSearching = false;
      mResults = List.of();
      if (mView != null)
      {
        mView.showResults(mResults);
        mView.showProgress(false);
        mView.showHiddenWithoutOpeningHours(0);
      }
      return;
    }

    mEngine.cancel();
    mTimestamp = Math.max(mTimestamp + 1, mClock.getAsLong());
    if (!mEngine.search(mQuery, mIsCategory, mTimestamp, getHalfWidth(), mFilter))
    {
      mSearching = false;
      if (mView != null)
      {
        mView.showProgress(false);
        mView.showNoRoute();
      }
      return;
    }
    mSearching = true;
    if (mView != null)
    {
      mView.showProgress(true);
      mView.showHiddenWithoutOpeningHours(0);
    }
  }
}
