package app.organicmaps.search;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import app.organicmaps.sdk.search.OpeningHoursFilter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The hint "12 places without opening hours hidden · Show" below the "Open now" chip: a tap turns "Also without
 * opening hours" on and runs the search again. The search screen needs the native map, so a small host fragment shows
 * the filter views with the same controller as the search screen.
 */
@RunWith(AndroidJUnit4.class)
public class OpeningHoursFilterViewTest
{
  private static final String QUERY = "Wertstoffcontainer";

  public static class FilterHostFragment extends Fragment
  {
    final OpeningHoursFilterState mState = new OpeningHoursFilterState();
    OpeningHoursFilterController mController;
    int mFilterChanges;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
      return inflater.inflate(R.layout.search_opening_hours_filter, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
      // The test activity is drawn edge-to-edge (Android 15+): keep the chips clear of the status bar, like below
      // the toolbar of the search screen, otherwise taps on them land on the status bar.
      ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
        final Insets bars =
            insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        return insets;
      });
      ViewCompat.requestApplyInsets(view);
      mController =
          new OpeningHoursFilterController(view, mState, () -> QUERY, new OpeningHoursFilterController.Listener() {
            @Override
            public void onFilterChanged()
            {
              ++mFilterChanges;
            }

            @Override
            public void onQueryFilterRemoved(@NonNull String query)
            {}
          });
      mController.onQueryChanged(QUERY, null);
    }
  }

  @Test
  public void tapOnHintShowsPlacesWithoutOpeningHours()
  {
    final FragmentScenario<FilterHostFragment> scenario =
        FragmentScenario.launchInContainer(FilterHostFragment.class, null, R.style.MwmTheme);

    onView(withId(R.id.opening_hours_filter_hint))
        .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));

    // "Open now" hides 12 containers without opening hours.
    onView(withId(R.id.filter_open_now)).perform(click());
    scenario.onFragment(f -> f.mController.setHiddenWithoutOpeningHours(12));
    final AtomicReference<String> message = new AtomicReference<>();
    scenario.onFragment(f
                        -> message.set(f.getResources().getQuantityString(
                            R.plurals.search_filter_hidden_without_opening_hours, 12, 12)));
    onView(withId(R.id.opening_hours_filter_hint_text)).check(matches(withText(message.get())));
    onView(withId(R.id.opening_hours_filter_hint_action)).check(matches(withText(R.string.search_filter_show_hidden)));
    onView(withId(R.id.filter_include_unknown_opening_hours)).check(matches(isNotChecked()));

    onView(withId(R.id.opening_hours_filter_hint)).check(matches(isDisplayed())).perform(click());

    onView(withId(R.id.filter_include_unknown_opening_hours)).check(matches(isDisplayed())).check(matches(isChecked()));
    scenario.onFragment(f -> {
      assertEquals(OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN, f.mController.getFilter());
      // "Open now" and the tap on the hint.
      assertEquals(2, f.mFilterChanges);
      // The new search shows nothing hidden.
      f.mController.setHiddenWithoutOpeningHours(0);
    });
    onView(withId(R.id.opening_hours_filter_hint))
        .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
  }

  @Test
  public void newQueryHidesFilter()
  {
    final FragmentScenario<FilterHostFragment> scenario =
        FragmentScenario.launchInContainer(FilterHostFragment.class, null, R.style.MwmTheme);
    onView(withId(R.id.filter_open_now)).perform(click());
    onView(withId(R.id.filter_open_now)).check(matches(isChecked()));

    scenario.onFragment(f -> f.mController.onQueryChanged("Café", null));
    onView(withId(R.id.filter_open_now)).check(matches(isNotChecked()));
    onView(withId(R.id.filter_include_unknown_opening_hours))
        .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
    scenario.onFragment(f -> assertEquals(OpeningHoursFilter.OFF, f.mController.getFilter()));
  }
}
