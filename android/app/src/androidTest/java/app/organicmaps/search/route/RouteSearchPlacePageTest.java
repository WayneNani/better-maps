package app.organicmaps.search.route;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
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
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.organicmaps.R;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.search.SearchResult;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The place page of a result of the search along the route: "in 12 km · Detour ~3 min", open on arrival, and "Add to
 * route", which adds the place as a stop. The place page needs the native map, so a host shows its preview with the
 * block of the search along the route.
 */
@RunWith(AndroidJUnit4.class)
public class RouteSearchPlacePageTest
{
  public static class PreviewHostFragment extends Fragment
  {
    final RouteSearchTestFakes.Engine mEngine = new RouteSearchTestFakes.Engine();
    final RouteSearchTestFakes.Stops mStops = new RouteSearchTestFakes.Stops();
    final RouteSearchPresenter mPresenter = RouteSearchTestFakes.presenter(mEngine, mStops);
    RouteSearchPlacePage mPlacePage;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
      final LinearLayout root = new LinearLayout(inflater.getContext());
      root.setOrientation(LinearLayout.VERTICAL);
      inflater.inflate(R.layout.place_page_preview, root, true);
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
      mPlacePage = new RouteSearchPlacePage(view.findViewById(R.id.route_search_frame));
    }
  }

  @NonNull
  private static MapObject placeOf(@NonNull SearchResult result, int featureIndex)
  {
    return MapObject.createMapObject(new FeatureId("Germany_Berlin", 261018, featureIndex), MapObject.POI, result.name,
                                     "", result.lat, result.lon);
  }

  @Test
  public void addToRouteAddsTheStop()
  {
    final FragmentScenario<PreviewHostFragment> scenario =
        FragmentScenario.launchInContainer(PreviewHostFragment.class, null, R.style.MwmTheme);
    final SearchResult aral = RouteSearchTestFakes.result("Aral", 1, 12, 180);
    final String[] texts = new String[1];
    scenario.onFragment(f -> {
      f.mPresenter.onCategorySelected("Tankstelle");
      f.mPresenter.onResultsUpdate(new SearchResult[] {aral}, f.mEngine.mTimestamp);
      f.mPresenter.onResultsEnd(f.mEngine.mTimestamp, 0);
      // The place page of the mark of Aral on the map.
      f.mPlacePage.bind(f.mPresenter, placeOf(aral, 1));
      texts[0] =
          RouteSearchFormatter.formatPosition(aral.routePosition, new AndroidRouteSearchTexts(f.requireContext()));
    });

    onView(withText(texts[0])).check(matches(isDisplayed()));
    onView(withText(R.string.route_search_open_on_arrival)).check(matches(isDisplayed()));
    onView(withId(R.id.route_search_add)).check(matches(withText(R.string.route_search_add_to_route)));

    onView(withId(R.id.route_search_add)).perform(click());
    scenario.onFragment(f -> {
      assertEquals(List.of(aral), f.mStops.mAdded);
      // The place page shows that it is a stop now.
      f.mPlacePage.bind(f.mPresenter, placeOf(aral, 1));
    });
    onView(withId(R.id.route_search_add)).check(matches(withText(R.string.route_search_added_to_route)));
    onView(withId(R.id.route_search_add)).check(matches(not(isEnabled())));
  }

  @Test
  public void otherPlacesHaveNoAddToRoute()
  {
    final FragmentScenario<PreviewHostFragment> scenario =
        FragmentScenario.launchInContainer(PreviewHostFragment.class, null, R.style.MwmTheme);
    scenario.onFragment(f -> {
      final SearchResult aral = RouteSearchTestFakes.result("Aral", 1, 12, 180);
      f.mPresenter.onCategorySelected("Tankstelle");
      f.mPresenter.onResultsUpdate(new SearchResult[] {aral}, f.mEngine.mTimestamp);
      // Another place, e.g. tapped on the map.
      f.mPlacePage.bind(f.mPresenter, placeOf(RouteSearchTestFakes.result("Esso", 7, 3, 60), 7));
    });
    onView(withId(R.id.route_search_frame)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));

    // Without an open search neither.
    scenario.onFragment(f -> {
      f.mPresenter.close();
      f.mPlacePage.bind(f.mPresenter, placeOf(RouteSearchTestFakes.result("Aral", 1, 12, 180), 1));
    });
    onView(withId(R.id.route_search_frame)).check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
  }
}
