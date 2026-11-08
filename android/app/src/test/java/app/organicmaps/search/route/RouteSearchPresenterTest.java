package app.organicmaps.search.route;

import static app.organicmaps.search.route.RouteSearchTestData.position;
import static app.organicmaps.search.route.RouteSearchTestData.result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.Router;
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
    boolean mHasRoute = true;

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
    public void showResultsOnMap(@NonNull String query, boolean isCategory)
    {
      mCalls.add("map " + query + " " + isCategory);
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
    assertEquals("cancel", mEngine.mCalls.get(mEngine.mCalls.size() - 1));
  }

  @Test
  public void withoutRouteNothingIsSearched()
  {
    mEngine.mHasRoute = false;
    mPresenter.onCategorySelected("Tankstelle");
    assertTrue(mView.mNoRoute);
    assertFalse(mView.mProgress);
  }

  @Test
  public void addStopAddsTheResultAndGoesBackToTheNavigation()
  {
    mPresenter.onCategorySelected("Tankstelle");
    final SearchResult aral = result("Aral", SearchResult.OPEN_NOW_YES, position(12, 120));
    mPresenter.onResultsUpdate(new SearchResult[] {aral}, mEngine.last().mTimestamp);

    assertTrue(mPresenter.canAddStop());
    mPresenter.onAddStopClicked(aral);

    assertEquals(1, mStops.mAdded.size());
    assertSame(aral, mStops.mAdded.get(0));
    assertTrue(mEngine.mCalls.contains("cancel"));
    assertTrue(mView.mClosed);
  }

  @Test
  public void noStopWhenTheRouteHasTheMaximumOfStops()
  {
    mStops.mCanAdd = false;
    assertFalse(mPresenter.canAddStop());
    mPresenter.onAddStopClicked(result("Aral", SearchResult.OPEN_NOW_YES, position(12, 120)));
    assertTrue(mStops.mAdded.isEmpty());
    assertFalse(mView.mClosed);
  }

  @Test
  public void resultAndAllResultsAreShownOnTheMap()
  {
    mPresenter.onCategorySelected("Tankstelle");
    mPresenter.onResultsUpdate(new SearchResult[] {result("Aral", SearchResult.OPEN_NOW_YES, position(2, 0)),
                                                   result("Shell", SearchResult.OPEN_NOW_YES, position(4, 0))},
                               mEngine.last().mTimestamp);

    mPresenter.onResultClicked(1);
    assertTrue(mEngine.mCalls.contains("show 1"));
    assertTrue(mView.mClosed);

    mView.mClosed = false;
    mPresenter.onShowOnMapClicked();
    assertTrue(mEngine.mCalls.contains("map Tankstelle true"));
    assertTrue(mView.mClosed);
  }
}
