package app.organicmaps.search.route;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.debug.FragmentTestHostActivity;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.search.SearchResult;
import com.google.android.material.chip.Chip;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The search along the route over the map: the results are on the map first, the list panel opens and closes, "+"
 * adds a stop and keeps the search open, the back arrow closes it. The native search and route are replaced by fakes;
 * the map itself is checked on the emulator.
 */
@RunWith(AndroidJUnit4.class)
public class RouteSearchPanelTest
{
  static RouteSearchTestFakes.Engine sEngine;
  static RouteSearchTestFakes.Stops sStops;

  /** The search with fakes, its views clear of the system bars (the test activity is drawn edge-to-edge). */
  public static class TestRouteSearchFragment extends RouteSearchFragment
  {
    @NonNull
    @Override
    protected RouteSearchPresenter createPresenter(@NonNull Context context, @Nullable Bundle args)
    {
      return RouteSearchTestFakes.presenter(sEngine, sStops);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
      super.onViewCreated(view, savedInstanceState);
      ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
        final Insets bars =
            insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        return insets;
      });
      ViewCompat.requestApplyInsets(view);
    }
  }

  private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private final SearchResult mAral = RouteSearchTestFakes.result("Aral", 1, 12, 180);
  private final SearchResult mShell = RouteSearchTestFakes.result("Shell", 2, 30, 20);

  @Before
  public void setUp()
  {
    sEngine = new RouteSearchTestFakes.Engine();
    sStops = new RouteSearchTestFakes.Stops();
  }

  @NonNull
  private ActivityScenario<FragmentTestHostActivity> launchWithResults()
  {
    // The search toolbar needs an AppCompatActivity.
    final ActivityScenario<FragmentTestHostActivity> scenario = ActivityScenario.launch(FragmentTestHostActivity.class);
    scenario.onActivity(a -> {
      final TestRouteSearchFragment fragment = new TestRouteSearchFragment();
      fragment.setArguments(RouteSearchFragment.arguments(Router.Vehicle, null, false));
      a.getSupportFragmentManager()
          .beginTransaction()
          .replace(FragmentTestHostActivity.CONTAINER_ID, fragment, RouteSearchFragment.TAG)
          .commitNow();
    });
    onView(withText(R.string.route_search_choose)).check(matches(isDisplayed()));
    onView(withId(R.id.route_search_display)).check(matches(not(isDisplayed())));

    // A category searches along the route.
    final String category = mContext.getString(RouteSearchCategory.forRouter(Router.Vehicle).get(0).mNameResId);
    onView(allOf(withText(category), isAssignableFrom(Chip.class))).perform(click());
    assertEquals(List.of(category), sEngine.mQueries);
    scenario.onActivity(a -> {
      final RouteSearchPresenter presenter = fragment(a).getPresenter();
      presenter.onResultsUpdate(new SearchResult[] {mAral, mShell}, sEngine.mTimestamp);
      presenter.onResultsEnd(sEngine.mTimestamp, 0);
    });
    return scenario;
  }

  @NonNull
  private static RouteSearchFragment fragment(@NonNull FragmentTestHostActivity activity)
  {
    return (RouteSearchFragment) activity.getSupportFragmentManager().findFragmentByTag(RouteSearchFragment.TAG);
  }

  private static void waitForTheSheet()
  {
    SystemClock.sleep(600);
  }

  @Test
  public void resultsOnTheMapAndTheListOpensAndCloses()
  {
    launchWithResults();
    // The map first: the route with the results, the list closed.
    assertTrue(sEngine.mStarted);
    assertEquals(1, sEngine.mAreas.size());
    onView(withText(mContext.getResources().getQuantityString(R.plurals.route_search_places, 2, 2)))
        .check(matches(isDisplayed()));
    onView(withId(R.id.recycler)).check(matches(not(isDisplayed())));

    onView(withText(R.string.route_search_show_list)).perform(click());
    waitForTheSheet();
    onView(withId(R.id.recycler)).check(matches(isDisplayed()));
    onView(withText("Aral")).check(matches(isDisplayed()));
    // The map shows the results in the part above the list.
    assertEquals(2, sEngine.mAreas.size());

    onView(withText(R.string.route_search_show_map)).perform(click());
    waitForTheSheet();
    onView(withId(R.id.recycler)).check(matches(not(isDisplayed())));
    onView(withText(R.string.route_search_show_list)).check(matches(isDisplayed()));
  }

  @Test
  public void addStopInTheListKeepsTheSearchOpen()
  {
    final ActivityScenario<FragmentTestHostActivity> scenario = launchWithResults();
    onView(withText(R.string.route_search_show_list)).perform(click());
    waitForTheSheet();

    final org.hamcrest.Matcher<View> addAral =
        allOf(withId(R.id.add_stop), hasSibling(hasDescendant(withText("Aral"))));
    onView(addAral).perform(click());
    assertEquals(List.of(mAral), sStops.mAdded);
    // Marked as a stop, not twice.
    onView(addAral).check(matches(not(isEnabled())));
    onView(addAral).check(matches(withContentDescription(R.string.route_search_added_to_route)));
    onView(allOf(withId(R.id.add_stop), hasSibling(hasDescendant(withText("Shell"))))).check(matches(isEnabled()));
    onView(withId(R.id.recycler)).check(matches(isDisplayed()));
    scenario.onActivity(a -> assertFalse(fragment(a).getPresenter().isClosed()));

    // The back arrow closes the search: no results on the map any more.
    onView(withId(R.id.back)).perform(click());
    assertTrue(sEngine.mFinished);
    scenario.onActivity(a -> assertTrue(fragment(a).getPresenter().isClosed()));
  }
}
