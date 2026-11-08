package app.organicmaps.search.route;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.testing.FragmentScenario;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.organicmaps.R;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.search.Popularity;
import app.organicmaps.sdk.search.RoutePosition;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.Distance;
import app.organicmaps.search.OpeningHoursFilterController;
import app.organicmaps.search.OpeningHoursFilterState;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The results of the search along the route: "in 12 km · Detour ~2 min", the opening hours on arrival, the "Add as
 * stop" button, and the "Open on arrival" chip. The search screen needs the native map and route, so a small host
 * fragment shows the list and the filter with the same adapter and controller.
 */
@RunWith(AndroidJUnit4.class)
public class RouteSearchResultsViewTest
{
  public static class ResultsHostFragment extends Fragment implements RouteSearchAdapter.Listener
  {
    final List<Integer> mClicked = new ArrayList<>();
    final List<SearchResult> mAdded = new ArrayList<>();
    boolean mCanAddStop = true;
    RouteSearchAdapter mAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
      final LinearLayout root = new LinearLayout(inflater.getContext());
      root.setOrientation(LinearLayout.VERTICAL);
      inflater.inflate(R.layout.search_opening_hours_filter, root, true);
      final RecyclerView list = new RecyclerView(inflater.getContext());
      list.setId(R.id.recycler);
      root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
      return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
      // The test activity is drawn edge-to-edge (Android 15+): keep the views clear of the system bars.
      ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
        final Insets bars =
            insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        return insets;
      });
      ViewCompat.requestApplyInsets(view);

      final OpeningHoursFilterController filter = new OpeningHoursFilterController(
          view, new OpeningHoursFilterState(), () -> "Tankstelle", new OpeningHoursFilterController.Listener() {
            @Override
            public void onFilterChanged()
            {}

            @Override
            public void onQueryFilterRemoved(@NonNull String query)
            {}
          });
      filter.setOpenNowText(R.string.route_search_filter_open_on_arrival);

      final RecyclerView list = view.findViewById(R.id.recycler);
      list.setLayoutManager(new LinearLayoutManager(requireContext()));
      mAdapter = new RouteSearchAdapter(this, new AndroidRouteSearchTexts(requireContext()));
      list.setAdapter(mAdapter);
    }

    @Override
    public void onResultClicked(int index)
    {
      mClicked.add(index);
    }

    @Override
    public void onAddStopClicked(@NonNull SearchResult result)
    {
      mAdded.add(result);
    }

    @Override
    public boolean canAddStop()
    {
      return mCanAddStop;
    }

    @Override
    public boolean isStop(@NonNull SearchResult result)
    {
      return false;
    }
  }

  @NonNull
  private static SearchResult result(@NonNull String name, int openNow, double km, int detourSec)
  {
    final SearchResult.Description description = new SearchResult.Description(
        new FeatureId("Germany_Berlin", 261018, 1), "Gas Station", "Berlin", Distance.EMPTY, "", openNow, 0, 0, false);
    final RoutePosition position =
        new RoutePosition(new Distance(km * 1000, String.valueOf((int) km), (byte) 1 /* km */), km * 1000, 300,
                          (int) (km * 60), detourSec);
    return new SearchResult(name, description, 52.5, 13.3, new int[0], new int[0], Popularity.defaultInstance(),
                            position);
  }

  @Test
  public void resultsShowDistanceDetourAndOpeningOnArrival()
  {
    final FragmentScenario<ResultsHostFragment> scenario =
        FragmentScenario.launchInContainer(ResultsHostFragment.class, null, R.style.MwmTheme);
    final List<SearchResult> results =
        List.of(result("Aral", SearchResult.OPEN_NOW_YES, 12, 120), result("Shell", SearchResult.OPEN_NOW_NO, 30, 10));
    scenario.onFragment(f -> f.mAdapter.setResults(results));

    final String[] texts = new String[4];
    scenario.onFragment(f -> {
      final AndroidRouteSearchTexts t = new AndroidRouteSearchTexts(f.requireContext());
      texts[0] = RouteSearchFormatter.formatPosition(results.get(0).routePosition, t);
      texts[1] = RouteSearchFormatter.formatPosition(results.get(1).routePosition, t);
      texts[2] = f.getString(R.string.route_search_open_on_arrival);
      texts[3] = f.getString(R.string.route_search_closed_on_arrival);
    });
    onView(withText(texts[0])).check(matches(isDisplayed()));
    onView(withText(texts[1])).check(matches(isDisplayed()));
    // The same text as the "Open on arrival" chip.
    onView(allOf(withId(R.id.open), withText(texts[2]))).check(matches(isDisplayed()));
    onView(allOf(withId(R.id.open), withText(texts[3]))).check(matches(isDisplayed()));
    onView(withId(R.id.filter_open_now)).check(matches(withText(R.string.route_search_filter_open_on_arrival)));

    onView(withText("Shell")).perform(click());
    scenario.onFragment(f -> assertEquals(List.of(1), f.mClicked));
  }

  @Test
  public void addStopButtonAddsTheResult()
  {
    final FragmentScenario<ResultsHostFragment> scenario =
        FragmentScenario.launchInContainer(ResultsHostFragment.class, null, R.style.MwmTheme);
    final SearchResult aral = result("Aral", SearchResult.OPEN_NOW_UNKNOWN, 12, 120);
    scenario.onFragment(f -> f.mAdapter.setResults(List.of(aral)));

    onView(allOf(withId(R.id.add_stop), isDisplayed())).perform(click());
    scenario.onFragment(f -> {
      assertEquals(List.of(aral), f.mAdded);
      assertEquals(0, f.mClicked.size());
    });
  }

  @Test
  public void noAddStopButtonWhenNoMoreStopsAreAllowed()
  {
    final FragmentScenario<ResultsHostFragment> scenario =
        FragmentScenario.launchInContainer(ResultsHostFragment.class, null, R.style.MwmTheme);
    scenario.onFragment(f -> {
      f.mCanAddStop = false;
      f.mAdapter.setResults(List.of(result("Aral", SearchResult.OPEN_NOW_UNKNOWN, 12, 120)));
    });
    onView(withId(R.id.add_stop)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
    // Unknown opening hours are not shown.
    onView(withId(R.id.open)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
  }
}
