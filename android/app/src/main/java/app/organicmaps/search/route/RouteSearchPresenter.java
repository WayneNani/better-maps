package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.SearchResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Logic of the search along the route: categories and corridor width for the router, the search requests and their
 * results on the map and in the list, adding results as stops. Without Android, so it can be tested on its own.
 * <p>
 * The results are shown on the map first, the list is a panel that can be opened. The search stays open after adding
 * a stop, so that more stops can be added; the results are searched again along the new route. Closing the search
 * removes the results from the map.
 */
public class RouteSearchPresenter
{
  /** What the search shows: the results on the map (the list panel is collapsed) or the list. */
  public enum Display
  {
    MAP,
    LIST
  }

  /** The native search and map, see SearchEngine. */
  public interface Engine
  {
    /** @return false if there is no route to search along. */
    boolean search(@NonNull String query, boolean isCategory, long timestamp, int halfWidthMeters,
                   @NonNull OpeningHoursFilter filter);

    void cancel();

    /** Shows the result of the last search with the index on the map, with its place page. */
    void showResult(int index);

    /** Shows the results of the last search on the map, except those with the given indices. */
    void showResultsOnMap(@NonNull int[] hiddenIndices);

    /** Moves the map to the route ahead and the places, see {@link RouteSearchMapArea}. */
    void showOnMap(@NonNull RouteSearchMapArea area);

    /** The search is opened: the map stays where the results are, also during the navigation. */
    void onStart();

    /** The search is closed: no results on the map, the map follows the navigation again. */
    void onFinish();
  }

  /** The stops of the route, see RoutingController. */
  public interface Stops
  {
    boolean canAddStop();

    /** @return true if the place is already a point of the route (a stop or the destination). */
    boolean isStop(@NonNull SearchResult result);

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

    /** The map or the list. */
    void showDisplay(@NonNull Display display);

    /** The map is about to show the results: e.g. the part of the map that is not covered is updated. */
    void prepareMap();

    /** A result was added as a stop, the route is built again. */
    void showStopAdded(@NonNull SearchResult result);

    /** Leaves the search, back to the route planner or the navigation. */
    void close();
  }

  /** Something that is shown changed, e.g. for the place page of a result. */
  public interface ChangeListener
  {
    void onRouteSearchChanged();
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
  @NonNull
  private final List<ChangeListener> mListeners = new ArrayList<>();

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
  private boolean mNoRoute;
  @NonNull
  private List<SearchResult> mResults = List.of();
  @NonNull
  private Display mDisplay = Display.MAP;
  private boolean mStarted;
  private boolean mClosed;

  public RouteSearchPresenter(@NonNull Router router, @NonNull RouteSearchSettings settings, @NonNull Engine engine,
                              @NonNull Stops stops, @NonNull LongSupplier clock)
  {
    mRouter = router;
    mSettings = settings;
    mEngine = engine;
    mStops = stops;
    mClock = clock;
  }

  /** Opens the search, once. @param query searched right away if not empty, e.g. a category name. */
  public void start(@Nullable String query, boolean isCategory)
  {
    if (mStarted || mClosed)
      return;
    mStarted = true;
    mEngine.onStart();
    if (query != null && !query.trim().isEmpty())
      setQuery(query, isCategory);
  }

  public void attach(@NonNull View view)
  {
    mView = view;
    view.showCategories(RouteSearchCategory.forRouter(mRouter));
    view.showHalfWidth(getHalfWidth(), RouteSearchSettings.getHalfWidthOptions(mRouter));
    view.showResults(mResults);
    view.showProgress(mSearching);
    if (mNoRoute)
      view.showNoRoute();
    view.showDisplay(mDisplay);
  }

  public void detach()
  {
    mView = null;
  }

  public void addListener(@NonNull ChangeListener listener)
  {
    if (!mListeners.contains(listener))
      mListeners.add(listener);
  }

  public void removeListener(@NonNull ChangeListener listener)
  {
    mListeners.remove(listener);
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

  @NonNull
  public Display getDisplay()
  {
    return mDisplay;
  }

  @NonNull
  public List<SearchResult> getResults()
  {
    return mResults;
  }

  public boolean isSearching()
  {
    return mSearching;
  }

  public boolean isClosed()
  {
    return mClosed;
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
    if (mClosed || timestamp != mTimestamp || mQuery.trim().isEmpty())
      return;
    mResults = Arrays.asList(results);
    if (mView != null)
      mView.showResults(mResults);
    showResultsOnMap();
    notifyChanged();
  }

  public void onResultsEnd(long timestamp, int hiddenWithoutOpeningHours)
  {
    if (mClosed || timestamp != mTimestamp)
      return;
    mSearching = false;
    if (mView != null)
    {
      mView.showProgress(false);
      mView.showHiddenWithoutOpeningHours(hiddenWithoutOpeningHours);
    }
    showMapArea();
  }

  /** The part of the map that is not covered changed (e.g. the list was opened): the results are shown in it. */
  public void onMapAreaChanged()
  {
    if (mClosed || mSearching)
      return;
    showMapArea();
  }

  /** The route ahead and the places: the first ones if there are many. */
  private void showMapArea()
  {
    final RouteSearchMapArea area = RouteSearchMapArea.of(getShownResults());
    if (area == null)
      return;
    if (mView != null)
      mView.prepareMap();
    mEngine.showOnMap(area);
  }

  /** The list or the map was chosen. */
  public void onDisplaySelected(@NonNull Display display)
  {
    if (display == mDisplay)
      return;
    mDisplay = display;
    if (mView != null)
      mView.showDisplay(display);
  }

  /** A result in the list was tapped: show it on the map with its place page. */
  public void onResultClicked(int index)
  {
    if (mClosed || index < 0 || index >= mResults.size())
      return;
    onDisplaySelected(Display.MAP);
    mEngine.showResult(index);
    // The other results stay on the map.
    showResultsOnMap();
  }

  public boolean canAddStop()
  {
    return !mClosed && mStops.canAddStop();
  }

  /** @return true if the result is already a point of the route, so it cannot be added again. */
  public boolean isStop(@NonNull SearchResult result)
  {
    return mStops.isStop(result);
  }

  /**
   * "Add to route" of a result was tapped, in the list or on its place page. The search stays open: the results are
   * searched again along the new route when it is built, see {@link #onRouteChanged()}.
   *
   * @return true if the result was added.
   */
  public boolean onAddStopClicked(@NonNull SearchResult result)
  {
    if (!canAddStop() || mStops.isStop(result))
      return false;
    mStops.addStop(result);
    if (mView != null)
    {
      mView.showStopAdded(result);
      // The button of the added result changes.
      mView.showResults(mResults);
    }
    // The added result is a stop on the map now.
    showResultsOnMap();
    notifyChanged();
    return true;
  }

  /** The route was built again, e.g. with a new stop: the results along the new route. */
  public void onRouteChanged()
  {
    if (mClosed || mQuery.trim().isEmpty())
      return;
    search();
  }

  /**
   * The result of the last search at the place of a place page, if any.
   *
   * @param featureId the feature of the place page, if it has one.
   */
  @Nullable
  public SearchResult findResult(@Nullable FeatureId featureId, double lat, double lon)
  {
    if (mClosed)
      return null;
    for (SearchResult result : mResults)
    {
      if (RouteSearchPlaces.isSamePlace(result, featureId, lat, lon))
        return result;
    }
    return null;
  }

  /** Closes the search: back to the route planner or the navigation, without the results on the map. */
  public void close()
  {
    if (mClosed)
      return;
    mClosed = true;
    mEngine.cancel();
    ++mTimestamp;
    mSearching = false;
    mResults = List.of();
    if (mStarted)
      mEngine.onFinish();
    notifyChanged();
    mListeners.clear();
    if (mView != null)
      mView.close();
  }

  /** The results not yet on the route. */
  @NonNull
  private List<SearchResult> getShownResults()
  {
    final List<SearchResult> shown = new ArrayList<>(mResults.size());
    for (SearchResult result : mResults)
    {
      if (!mStops.isStop(result))
        shown.add(result);
    }
    return shown;
  }

  private void showResultsOnMap()
  {
    if (mClosed)
      return;
    final List<Integer> hidden = new ArrayList<>();
    for (int i = 0; i < mResults.size(); ++i)
    {
      if (mStops.isStop(mResults.get(i)))
        hidden.add(i);
    }
    final int[] indices = new int[hidden.size()];
    for (int i = 0; i < indices.length; ++i)
      indices[i] = hidden.get(i);
    mEngine.showResultsOnMap(indices);
  }

  private void notifyChanged()
  {
    for (ChangeListener listener : new ArrayList<>(mListeners))
      listener.onRouteSearchChanged();
  }

  private void setQuery(@NonNull String query, boolean isCategory)
  {
    final String trimmed = query.trim();
    if (trimmed.equals(mQuery.trim()) && isCategory == mIsCategory)
      return;
    mQuery = query;
    mIsCategory = isCategory && !trimmed.isEmpty();
    // The results of a new search are shown on the map first.
    onDisplaySelected(Display.MAP);
    search();
  }

  private void search()
  {
    if (mClosed)
      return;
    mEngine.cancel();
    if (mQuery.trim().isEmpty())
    {
      ++mTimestamp;
      mSearching = false;
      mNoRoute = false;
      mResults = List.of();
      showResultsOnMap();
      if (mView != null)
      {
        mView.showResults(mResults);
        mView.showProgress(false);
        mView.showHiddenWithoutOpeningHours(0);
      }
      notifyChanged();
      return;
    }

    mTimestamp = Math.max(mTimestamp + 1, mClock.getAsLong());
    if (!mEngine.search(mQuery, mIsCategory, mTimestamp, getHalfWidth(), mFilter))
    {
      mSearching = false;
      mNoRoute = true;
      if (mView != null)
      {
        mView.showProgress(false);
        mView.showNoRoute();
      }
      return;
    }
    mSearching = true;
    mNoRoute = false;
    if (mView != null)
    {
      mView.showProgress(true);
      mView.showHiddenWithoutOpeningHours(0);
    }
  }
}
