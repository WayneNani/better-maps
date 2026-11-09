package app.organicmaps.ev;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.organicmaps.R;
import app.organicmaps.debug.FragmentTestHostActivity;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The charging planner over the map: options → plan → stop in detail. The native planner and the route are fakes
 * that answer at once; the map is checked on the emulator.
 */
@RunWith(AndroidJUnit4.class)
public class EvPlanPanelTest
{
  static Engine sEngine;
  static Route sRoute;

  static class Engine implements EvPlanPresenter.Engine
  {
    final List<String> mOptions = new ArrayList<>();
    final List<String> mEvaluated = new ArrayList<>();
    String mResult = EvAndroidTestData.RESULT_JSON;
    String mEvaluation = EvAndroidTestData.EVALUATED_JSON;

    @Override
    public void plan(@NonNull String profileJson, @NonNull String optionsJson, @NonNull Callback callback)
    {
      mOptions.add(optionsJson);
      callback.onResult(mResult);
    }

    @Override
    public void cancel()
    {}

    @Nullable
    @Override
    public String evaluate(@NonNull String profileJson, @NonNull String optionsJson, @NonNull String stopsJson)
    {
      mEvaluated.add(stopsJson);
      return mEvaluation;
    }
  }

  static class Route implements EvPlanPresenter.Route
  {
    final List<List<EvPlanResult.Stop>> mAdded = new ArrayList<>();
    boolean mStarted;

    @Override
    public void setChargingStops(@NonNull List<EvPlanResult.Stop> oldStops, @NonNull List<EvPlanResult.Stop> newStops,
                                 @NonNull Runnable onBuilt)
    {
      mAdded.add(new ArrayList<>(newStops));
      onBuilt.run();
    }

    @Override
    public void startNavigation()
    {
      mStarted = true;
    }
  }

  /** The planner with fakes, its views clear of the system bars (the test activity is drawn edge-to-edge). */
  public static class TestEvPlanFragment extends EvPlanFragment
  {
    @Nullable
    @Override
    protected EvPlanPresenter createPresenter(@NonNull Context context)
    {
      final EvVehicleProfile profile = EvVehicleProfile.fromJson(EvAndroidTestData.ELROQ_JSON);
      return new EvPlanPresenter(new EvSettings(new EvAndroidTestStore()), profile, sEngine, sRoute,
                                 new EvRouteStops());
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

  @Before
  public void setUp()
  {
    sEngine = new Engine();
    sRoute = new Route();
  }

  @NonNull
  private ActivityScenario<FragmentTestHostActivity> launch()
  {
    final ActivityScenario<FragmentTestHostActivity> scenario = ActivityScenario.launch(FragmentTestHostActivity.class);
    scenario.onActivity(a -> {
      // A new presenter for each test.
      new androidx.lifecycle.ViewModelProvider(a).get(EvPlanFragment.EvPlanViewModel.class).mPresenter = null;
      a.getSupportFragmentManager()
          .beginTransaction()
          .replace(FragmentTestHostActivity.CONTAINER_ID, new TestEvPlanFragment(), EvPlanFragment.TAG)
          .commitNow();
    });
    return scenario;
  }

  private void plan()
  {
    onView(withText(R.string.ev_battery_now)).check(matches(isDisplayed()));
    // The providers along the route from the first plan.
    onView(withText("EnBW")).perform(scrollTo()).check(matches(isDisplayed()));
    onView(withText("Ionity")).perform(scrollTo(), click());
    onView(withText("150 kW")).perform(scrollTo(), click());
    onView(withText(R.string.ev_amenity_playground)).perform(scrollTo(), click());
    onView(withId(R.id.ev_plan_button)).perform(scrollTo(), click());
  }

  @Test
  public void optionsShowThePlan()
  {
    try (ActivityScenario<FragmentTestHostActivity> scenario = launch())
    {
      plan();

      // The options went to the planner.
      final String options = sEngine.mOptions.get(sEngine.mOptions.size() - 1);
      assertTrue(options, options.contains("\"preferredProviders\":[\"ionity\"]"));
      assertTrue(options, options.contains("\"minPowerKw\":150"));
      assertTrue(options, options.contains("\"preferredAmenities\":[\"playground\"]"));

      // The plan, evaluated with the route through its stop.
      onView(withId(R.id.ev_result_total)).check(matches(withText("4:48 h")));
      onView(withId(R.id.ev_result_summary)).check(matches(withText(containsString("25 min charging"))));
      onView(withId(R.id.ev_result_bar)).check(matches(isDisplayed()));
      onView(withText("EnBW Aurach")).check(matches(isDisplayed()));
      onView(withText(containsString("18 % → 70 %"))).check(matches(isDisplayed()));
      onView(withText(containsString("Ionity Montabaur"))).perform(scrollTo()).check(matches(isDisplayed()));
      assertEquals(1, sRoute.mAdded.get(sRoute.mAdded.size() - 1).size());
      assertEquals(1, sEngine.mEvaluated.size());

      onView(withId(R.id.ev_start_button)).perform(scrollTo(), click());
      assertTrue(sRoute.mStarted);
    }
  }

  @Test
  public void stopInDetail()
  {
    try (ActivityScenario<FragmentTestHostActivity> scenario = launch())
    {
      plan();
      onView(withText("EnBW Aurach")).perform(click());

      onView(withId(R.id.ev_plan_title)).check(matches(withText("EnBW Aurach")));
      onView(withText(containsString("EnBW · 24/7"))).check(matches(isDisplayed()));
      onView(withText("4 × 300 kW")).check(matches(isDisplayed()));
      onView(withText("2 × 22 kW")).check(matches(isDisplayed()));
      onView(withText(R.string.ev_availability_unknown)).check(matches(isDisplayed()));
      onView(withText(containsString("90 m"))).perform(scrollTo()).check(matches(isDisplayed()));
      onView(withId(R.id.ev_detail_charge)).perform(scrollTo()).check(matches(withText("25 min")));

      // Charge longer: evaluated again with 80 %.
      onView(withId(R.id.ev_detail_longer)).perform(scrollTo(), click());
      assertEquals(2, sEngine.mEvaluated.size());
      assertTrue(sEngine.mEvaluated.get(1).contains("\"departureSoc\":80.0"));

      // Keep it: back to the plan.
      onView(withId(R.id.ev_keep_stop_button)).perform(scrollTo(), click());
      onView(withId(R.id.ev_result_total)).check(matches(isDisplayed()));

      // Another stop: plans again without this station.
      onView(withText("EnBW Aurach")).perform(click());
      onView(withId(R.id.ev_other_stop_button)).perform(scrollTo(), click());
      final String options = sEngine.mOptions.get(sEngine.mOptions.size() - 1);
      assertTrue(options, options.contains("\"excludedStations\":[\"Germany:1\"]"));
    }
  }

  @Test
  public void chargeLongerLeavesOutAStopNotNeeded()
  {
    sEngine.mResult = EvAndroidTestData.TWO_STOPS_RESULT_JSON;
    sEngine.mEvaluation = EvAndroidTestData.twoStopsPlan();
    try (ActivityScenario<FragmentTestHostActivity> scenario = launch())
    {
      plan();
      onView(withText("Ionity Haidt")).perform(scrollTo()).check(matches(isDisplayed()));
      assertEquals(2, sRoute.mAdded.get(sRoute.mAdded.size() - 1).size());

      // The first stop: charging longer there makes the second one unnecessary.
      onView(withText("EnBW Aurach")).perform(click());
      onView(withId(R.id.ev_detail_longer))
          .perform(scrollTo())
          .check(matches(withText(containsString("Ionity Haidt"))))
          .check(matches(withText(containsString("25 %"))));
      sEngine.mEvaluation = EvAndroidTestData.ONE_STOP_EVALUATED_JSON;
      final int evaluations = sEngine.mEvaluated.size();
      onView(withId(R.id.ev_detail_longer)).perform(scrollTo(), click());

      // The route is built again without the second stop and evaluated with 80 % at the first one.
      final List<EvPlanResult.Stop> stops = sRoute.mAdded.get(sRoute.mAdded.size() - 1);
      assertEquals(1, stops.size());
      assertEquals("Germany:1", stops.get(0).key);
      assertEquals(evaluations + 1, sEngine.mEvaluated.size());
      final String evaluated = sEngine.mEvaluated.get(evaluations);
      assertTrue(evaluated, evaluated.contains("\"departureSoc\":80.0"));
      assertFalse(evaluated, evaluated.contains("Germany:3"));

      // Back at the stop, then the plan with one stop and the time of the new route.
      onView(withId(R.id.ev_plan_title)).check(matches(withText("EnBW Aurach")));
      onView(withId(R.id.ev_detail_charge)).perform(scrollTo()).check(matches(withText("36 min")));
      onView(withId(R.id.ev_keep_stop_button)).perform(scrollTo(), click());
      onView(withId(R.id.ev_result_total)).check(matches(withText("4:56 h")));
      onView(withText("Ionity Haidt")).check(doesNotExist());
    }
  }
}
