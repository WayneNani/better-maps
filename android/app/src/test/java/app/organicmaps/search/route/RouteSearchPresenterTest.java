package app.organicmaps.search.route;

import static app.organicmaps.search.route.RouteSearchTestData.position;
import static app.organicmaps.search.route.RouteSearchTestData.result;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.SearchResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class RouteSearchPresenterTest
{
  private static class Search
  {
    final String mQuery;
    final boolean mIsCategory;
    final long mTimestamp;
    final int mHalfWidth;
    final OpeningHoursFilter mFilter;

    Search(String query, boolean isCategory, long timestamp, int halfWidth, OpeningHoursFilter filter)
    {
      mQuery = query;
      mIsCategory = isCategory;
      mTimestamp = timestamp;
      mHalfWidth = halfWidth;
      mFilter = filter;
    }
  }

  private static class FakeEngine implements RouteSearchPresenter.Engine
  {
    final List<Search> mSearches = new ArrayList<>();
    final List<String> mCalls = new ArrayList<>();
    final List<RouteSearchMapArea> mAreas = new ArrayList<>();
    int[] mHidden;
    boolean mHasRoute = true;
    boolean mStarted;
    boolean mFinished;

    @Override
    public boolean search(@NonNull String query, boolean isCategory, long timestamp, int halfWidthMeters,
                          @NonNull OpeningHoursFilter filter)
    {
      if (!mHasRoute)
        return false;
      mSearches.add(new Search(query, isCategory, timestamp, halfWidthMeters, filter));
      return true;
    }

    @Override
    public void cancel()
    {
      mCalls.add("cancel");
    }

    @Override
    public void showResult(int index)
    {
      mCalls.add("show " + index);
    }

    @Override
    public void showResultsOnMap(@NonNull int[] hiddenIndices)
    {
      mHidden = hiddenIndices;
      mCalls.add("marks");
    }

    @Override
    public void showOnMap(@NonNull RouteSearchMapArea area)
    {
      mAreas.add(area);
    }

    @Override
    public void onStart()
    {
      mStarted = true;
    }

    @Override
    public void onFinish()
    {
      mFinished = true;
      mHidden = null;
    }

    Search last()
    {
      return mSearches.get(mSearches.size() - 1);
    }
  }

  private static class FakeStops implements RouteSearchPresenter.Stops
  {
    final List<SearchResult> mAdded = new ArrayList<>();
    boolean mCanAdd = true;

    @Override
    public boolean canAddStop()
    {
      return mCanAdd;
    }

    @Override
    public boolean isStop(@NonNull SearchResult result)
    {
      return mAdded.contains(result);
    }

    @Override
    public void addStop(@NonNull SearchResult result)
    {
      mAdded.add(result);
    }
  }

  private static class FakeView implements RouteSearchPresenter.View
  {
    List<RouteSearchCategory> mCategories;
    int mHalfWidth;
    List<SearchResult> mResults = List.of();
    boolean mProgress;
    boolean mNoRoute;
    int mHidden;
    boolean mClosed;
    RouteSearchPresenter.Display mDisplay;
    int mPrepared;
    final List<SearchResult> mStopsAdded = new ArrayList<>();

    @Override
    public void showCategories(@NonNull List<RouteSearchCategory> categories)
    {
      mCategories = categories;
    }

    @Override
    public void showHalfWidth(int halfWidthMeters, @NonNull int[] options)
    {
      mHalfWidth = halfWidthMeters;
    }

    @Override
    public void showResults(@NonNull List<SearchResult> results)
    {
      mResults = results;
    }

    @Override
    public void showProgress(boolean show)
    {
      mProgress = show;
    }

    @Override
    public void showNoRoute()
    {
      mNoRoute = true;
    }

    @Override
    public void showHiddenWithoutOpeningHours(int count)
    {
      mHidden = count;
    }

    @Override
    public void showDisplay(@NonNull RouteSearchPresenter.Display display)
    {
      mDisplay = display;
    }

    @Override
    public void prepareMap()
    {
      ++mPrepared;
    }

    @Override
    public void showStopAdded(@NonNull SearchResult result)
    {
      mStopsAdded.add(result);
    }

    @Override
    public void close()
    {
      mClosed = true;
    }
  }

  private final RouteSearchTestData.Store mStore = new RouteSearchTestData.Store();
  private final FakeEngine mEngine = new FakeEngine();
  private final FakeStops mStops = new FakeStops();
  private final FakeView mView = new FakeView();
  private long mNow = 1000;
  private RouteSearchPresenter mPresenter;

  @Before
  public void setUp()
  {
    mPresenter = new RouteSearchPresenter(Router.Vehicle, new RouteSearchSettings(mStore), mEngine, mStops, () -> mNow);
    mPresenter.attach(mView);
  }

  @Test
  public void attachShowsCategoriesAndWidthOfTheRouter()
  {
    assertEquals(RouteSearchCategory.forRouter(Router.Vehicle), mView.mCategories);
    assertEquals(1000, mView.mHalfWidth);
    assertTrue(mEngine.mSearches.isEmpty());

    final FakeView bicycleView = new FakeView();
    new RouteSearchPresenter(Router.Bicycle, new RouteSearchSettings(mStore), mEngine, mStops, () -> mNow)
        .attach(bicycleView);
    assertEquals(RouteSearchCategory.forRouter(Router.Bicycle), bicycleView.mCategories);
    assertEquals(300, bicycleView.mHalfWidth);
  }

  @Test
  public void categorySearchesAlongTheRoute()
  {
    mPresenter.onCategorySelected("Tankstelle");

    final Search search = mEngine.last();
    assertEquals("Tankstelle", search.mQuery);
    assertTrue(search.mIsCategory);
    assertEquals(1000, search.mHalfWidth);
    assertEquals(OpeningHoursFilter.OFF, search.mFilter);
    assertTrue(mView.mProgress);

    final SearchResult[] results = {result("Aral", SearchResult.OPEN_NOW_YES, position(2, 60)),
                                    result("Shell", SearchResult.OPEN_NOW_NO, position(12, 120))};
    mPresenter.onResultsUpdate(results, search.mTimestamp);
    mPresenter.onResultsEnd(search.mTimestamp, 3);
    assertEquals(List.of(results), mView.mResults);
    assertFalse(mView.mProgress);
    assertEquals(3, mView.mHidden);
  }

  @Test
  public void resultsOfAnOlderSearchAreIgnored()
  {
    mPresenter.onCategorySelected("Tankstelle");
    final long first = mEngine.last().mTimestamp;
    mPresenter.onQueryChanged("Aral", false);
    final long second = mEngine.last().mTimestamp;
    assertTrue(second > first);
    assertFalse(mEngine.last().mIsCategory);

    mPresenter.onResultsUpdate(new SearchResult[] {result("Shell", SearchResult.OPEN_NOW_UNKNOWN, null)}, first);
    mPresenter.onResultsEnd(first, 0);
    assertTrue(mView.mResults.isEmpty());
    assertTrue(mView.mProgress);
  }

  @Test
  public void widthChangeIsKeptAndSearchesAgain()
  {
    mPresenter.onCategorySelected("Tankstelle");
    mPresenter.onHalfWidthSelected(5000);

    assertEquals(2, mEngine.mSearches.size());
    assertEquals(5000, mEngine.last().mHalfWidth);
    assertEquals(5000, mView.mHalfWidth);
    assertEquals(5000, new RouteSearchSettings(mStore).getHalfWidth(Router.Vehicle));

    // The same width again: nothing to do.
    mPresenter.onHalfWidthSelected(5000);
    assertEquals(2, mEngine.mSearches.size());
  }

  @Test
  public void openingHoursFilterSearchesAgain()
  {
    mPresenter.onCategorySelected("Bäckerei");
    mPresenter.onOpeningHoursFilterChanged(OpeningHoursFilter.OPEN_NOW);
    assertEquals(OpeningHoursFilter.OPEN_NOW, mEngine.last().mFilter);
    assertEquals(2, mEngine.mSearches.size());
  }

  @Test
  public void emptyQueryClearsTheResults()
  {
    mPresenter.onCategorySelected("Tankstelle");
    mPresenter.onResultsUpdate(new SearchResult[] {result("Aral", SearchResult.OPEN_NOW_YES, position(2, 0))},
                               mEngine.last().mTimestamp);
    mPresenter.onQueryChanged("", false);

    assertTrue(mView.mResults.isEmpty());
    assertFalse(mView.mProgress);
    assertEquals(1, mEngine.mSearches.size());
    assertTrue(mEngine.mCalls.contains("cancel"));
    // No marks left on the map.
    assertEquals(List.of("cancel", "marks"), mEngine.mCalls.subList(mEngine.mCalls.size() - 2, mEngine.mCalls.size()));
    assertEquals(0, mEngine.mHidden.length);
  }

  @Test
  public void withoutRouteNothingIsSearched()
  {
    mEngine.mHasRoute = false;
    mPresenter.onCategorySelected("Tankstelle");
    assertTrue(mView.mNoRoute);
    assertFalse(mView.mProgress);
  }

  @NonNull
  private SearchResult[] searchAndFind(@NonNull String category, @NonNull SearchResult... results)
  {
    mPresenter.onCategorySelected(category);
    final long timestamp = mEngine.last().mTimestamp;
    mPresenter.onResultsUpdate(results, timestamp);
    mPresenter.onResultsEnd(timestamp, 0);
    return results;
  }

  @Test
  public void startOpensTheSearchOnTheMap()
  {
    assertEquals(RouteSearchPresenter.Display.MAP, mView.mDisplay);
    mPresenter.start("Tankstelle", true);
    assertTrue(mEngine.mStarted);
    assertEquals("Tankstelle", mEngine.last().mQuery);
    assertTrue(mEngine.last().mIsCategory);

    // Only once.
    mPresenter.start("Café", true);
    assertEquals(1, mEngine.mSearches.size());
  }

  @Test
  public void resultsAreShownOnTheMapFirst()
  {
    mPresenter.onDisplaySelected(RouteSearchPresenter.Display.LIST);
    assertEquals(RouteSearchPresenter.Display.LIST, mView.mDisplay);

    final SearchResult[] results =
        searchAndFind("Tankstelle", result("Aral", SearchResult.OPEN_NOW_YES, position(2, 60)),
                      result("Shell", SearchResult.OPEN_NOW_NO, position(12, 120)));
    // A new search shows its results on the map: marks and the route with them.
    assertEquals(RouteSearchPresenter.Display.MAP, mView.mDisplay);
    assertEquals(0, mEngine.mHidden.length);
    assertEquals(1, mEngine.mAreas.size());
    assertEquals(List.of(results), mEngine.mAreas.get(0).places);
    assertTrue(mEngine.mAreas.get(0).wholeRoute);
    assertEquals(1, mView.mPrepared);

    // The list can be opened and closed again; the map then shows the results in its new free part.
    mPresenter.onDisplaySelected(RouteSearchPresenter.Display.LIST);
    assertEquals(RouteSearchPresenter.Display.LIST, mView.mDisplay);
    mPresenter.onMapAreaChanged();
    assertEquals(2, mEngine.mAreas.size());
    mPresenter.onDisplaySelected(RouteSearchPresenter.Display.MAP);
    assertEquals(RouteSearchPresenter.Display.MAP, mView.mDisplay);
  }

  @Test
  public void resultOfTheListIsShownOnTheMapWithTheOthers()
  {
    searchAndFind("Tankstelle", result("Aral", SearchResult.OPEN_NOW_YES, position(2, 0)),
                  result("Shell", SearchResult.OPEN_NOW_YES, position(4, 0)));
    mPresenter.onDisplaySelected(RouteSearchPresenter.Display.LIST);
    mEngine.mCalls.clear();

    mPresenter.onResultClicked(1);
    assertEquals(List.of("show 1", "marks"), mEngine.mCalls);
    assertEquals(RouteSearchPresenter.Display.MAP, mView.mDisplay);
    // The search stays open.
    assertFalse(mView.mClosed);
  }

  @Test
  public void addStopKeepsTheSearchAndTheOtherResults()
  {
    final SearchResult aral = result("Aral", SearchResult.OPEN_NOW_YES, position(12, 120));
    final SearchResult shell = result("Shell", SearchResult.OPEN_NOW_YES, position(20, 60));
    searchAndFind("Tankstelle", aral, shell);
    final int changes[] = {0};
    mPresenter.addListener(() -> ++changes[0]);

    assertTrue(mPresenter.canAddStop());
    assertTrue(mPresenter.onAddStopClicked(aral));
    assertEquals(List.of(aral), mStops.mAdded);
    assertEquals(List.of(aral), mView.mStopsAdded);
    assertFalse(mView.mClosed);
    assertTrue(changes[0] > 0);
    // Aral is a stop on the map now, Shell still a result.
    assertArrayEquals(new int[] {0}, mEngine.mHidden);
    assertTrue(mPresenter.isStop(aral));
    assertFalse(mPresenter.isStop(shell));

    // Not twice.
    assertFalse(mPresenter.onAddStopClicked(aral));
    assertEquals(1, mStops.mAdded.size());

    // The new route: the results along it, without the stop on the map.
    final int searches = mEngine.mSearches.size();
    mPresenter.onRouteChanged();
    assertEquals(searches + 1, mEngine.mSearches.size());
    assertEquals("Tankstelle", mEngine.last().mQuery);
    final SearchResult esso = result("Esso", SearchResult.OPEN_NOW_YES, position(8, 60));
    mPresenter.onResultsUpdate(new SearchResult[] {esso, aral, shell}, mEngine.last().mTimestamp);
    mPresenter.onResultsEnd(mEngine.last().mTimestamp, 0);
    assertArrayEquals(new int[] {1}, mEngine.mHidden);
    assertEquals(List.of(esso, shell), mEngine.mAreas.get(mEngine.mAreas.size() - 1).places);

    // A second stop.
    assertTrue(mPresenter.onAddStopClicked(shell));
    assertEquals(List.of(aral, shell), mStops.mAdded);
    assertArrayEquals(new int[] {1, 2}, mEngine.mHidden);
  }

  @Test
  public void noStopWhenTheRouteHasTheMaximumOfStops()
  {
    mStops.mCanAdd = false;
    assertFalse(mPresenter.canAddStop());
    assertFalse(mPresenter.onAddStopClicked(result("Aral", SearchResult.OPEN_NOW_YES, position(12, 120))));
    assertTrue(mStops.mAdded.isEmpty());
    assertFalse(mView.mClosed);
  }

  @Test
  public void placeOfAPlacePageIsFound()
  {
    final SearchResult aral = result("Aral", SearchResult.OPEN_NOW_YES, position(12, 120));
    searchAndFind("Tankstelle", aral);

    // The same feature.
    assertSame(aral, mPresenter.findResult(new FeatureId("Germany_Bonn", 261018, 42), 0, 0));
    assertNull(mPresenter.findResult(new FeatureId("Germany_Bonn", 261018, 43), 50.8, 7.2));
    // Without a feature: the coordinates.
    assertSame(aral, mPresenter.findResult(null, 50.8, 7.2));
    assertSame(aral, mPresenter.findResult(FeatureId.EMPTY, 50.800001, 7.2));
    assertNull(mPresenter.findResult(null, 50.81, 7.2));
  }

  @Test
  public void closeRemovesTheResultsFromTheMap()
  {
    mPresenter.start(null, false);
    final SearchResult aral = result("Aral", SearchResult.OPEN_NOW_YES, position(12, 120));
    searchAndFind("Tankstelle", aral);
    final int changes[] = {0};
    mPresenter.addListener(() -> ++changes[0]);

    mPresenter.close();
    assertTrue(mView.mClosed);
    assertTrue(mEngine.mFinished);
    assertTrue(mEngine.mCalls.contains("cancel"));
    assertTrue(mPresenter.isClosed());
    assertEquals(1, changes[0]);
    // No place page has "Add to route" any more, and late results are ignored.
    assertNull(mPresenter.findResult(null, 50.8, 7.2));
    assertFalse(mPresenter.canAddStop());
    assertFalse(mPresenter.onAddStopClicked(aral));
    final int searches = mEngine.mSearches.size();
    mPresenter.onRouteChanged();
    mPresenter.onCategorySelected("Café");
    assertEquals(searches, mEngine.mSearches.size());
  }

  @Test
  public void closeWithoutStartDoesNotTouchTheMap()
  {
    mPresenter.close();
    assertTrue(mView.mClosed);
    assertFalse(mEngine.mFinished);
  }
}
