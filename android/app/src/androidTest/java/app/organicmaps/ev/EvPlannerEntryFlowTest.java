package app.organicmaps.ev;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.SplashActivity;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.ev.EvPlanner;
import app.organicmaps.sdk.routing.RoutingController;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The way into the charging planner for a new user, in the real app: a car route in Berlin (the emulator has the map)
 * shows the charging planner button although the planner is off; the button opens "My vehicle", and the planner opens
 * on the way back once a car is chosen. The user's settings of the planner are put back after each test.
 */
@RunWith(AndroidJUnit4.class)
public class EvPlannerEntryFlowTest
{
  private static final String ROUTE =
      "cm://route?sll=52.5250,13.3694&saddr=Hauptbahnhof&dll=52.5219,13.4132&daddr=Alexanderplatz&type=vehicle";
  private static final long TIMEOUT_MS = 60_000;

  private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private final Map<String, Object> mSavedPrefs = new HashMap<>();

  @NonNull
  private SharedPreferences prefs()
  {
    return mContext.getSharedPreferences(EvSettings.PREFS_NAME, Context.MODE_PRIVATE);
  }

  @NonNull
  private EvSettings settings()
  {
    return EvSettings.from(mContext);
  }

  @Before
  public void setUp()
  {
    mSavedPrefs.putAll(prefs().getAll());
    // A new user: the planner is off and there is no vehicle.
    prefs().edit().clear().commit();

    final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(ROUTE), mContext, SplashActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    mContext.startActivity(intent);
    try
    {
      waitDisplayed(withId(R.id.btn__ev_planning), 20_000);
    }
    catch (RuntimeException | AssertionError e)
    {
      // A second route link in the same app session sets the points but does not always build the route (also
      // without this change); build it like the "rebuild" of the route planner.
      InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> RoutingController.get().rebuildLastRoute());
      waitDisplayed(withId(R.id.btn__ev_planning));
    }
  }

  @After
  public void tearDown()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> RoutingController.get().cancel());
    final SharedPreferences.Editor editor = prefs().edit().clear();
    for (Map.Entry<String, ?> e : mSavedPrefs.entrySet())
    {
      final Object v = e.getValue();
      if (v instanceof Boolean)
        editor.putBoolean(e.getKey(), (Boolean) v);
      else if (v instanceof Integer)
        editor.putInt(e.getKey(), (Integer) v);
      else if (v instanceof Long)
        editor.putLong(e.getKey(), (Long) v);
      else if (v instanceof Float)
        editor.putFloat(e.getKey(), (Float) v);
      else if (v instanceof String)
        editor.putString(e.getKey(), (String) v);
      else if (v instanceof Set)
      {
        @SuppressWarnings("unchecked")
        final Set<String> set = (Set<String>) v;
        editor.putStringSet(e.getKey(), set);
      }
    }
    editor.commit();
  }

  /** Waits for a view of the app, e.g. while the route is built or a screen opens. */
  private static void waitDisplayed(@NonNull Matcher<View> matcher)
  {
    waitDisplayed(matcher, TIMEOUT_MS);
  }

  private static void waitDisplayed(@NonNull Matcher<View> matcher, long timeoutMs)
  {
    final long deadline = SystemClock.uptimeMillis() + timeoutMs;
    while (true)
    {
      try
      {
        onView(matcher).check(matches(isDisplayed()));
        return;
      }
      catch (RuntimeException | AssertionError e)
      {
        if (SystemClock.uptimeMillis() > deadline)
          throw e;
        SystemClock.sleep(250);
      }
    }
  }

  private void openSetup()
  {
    assertFalse(settings().isAvailable(Router.Vehicle));
    onView(withId(R.id.btn__ev_planning)).perform(click());
    waitDisplayed(withId(R.id.ev_vehicle_hint));
    onView(withText(R.string.ev_setup_hint)).check(matches(isDisplayed()));
    onView(withId(R.id.ev_vehicle_enabled)).check(matches(isNotChecked()));
  }

  private void checkPlannerOpen()
  {
    waitDisplayed(withText(R.string.ev_battery_now));
    assertTrue(settings().isAvailable(Router.Vehicle));
  }

  @Test
  public void buttonOnCarRouteOpensSetupThenPlanner()
  {
    openSetup();
    // One tap with the preselected car: back to the route with the planner open.
    onView(withId(R.id.ev_vehicle_plan_button)).perform(scrollTo(), click());
    checkPlannerOpen();
    final EvVehicleProfile profile = settings().getProfile();
    assertNotNull(profile);
    assertTrue(profile.id, EvVehicleFragment.DEFAULT_TEMPLATE.equals(profile.id));
  }

  @Test
  public void choosingTemplateEnablesPlanner()
  {
    openSetup();
    final List<EvVehicleProfile> templates = EvVehicleProfile.listFromJson(EvPlanner.getVehicleTemplates());
    EvVehicleProfile other = null;
    for (EvVehicleProfile t : templates)
      if (!t.id.equals(EvVehicleFragment.DEFAULT_TEMPLATE))
        other = t;
    assertNotNull(other);

    onView(withId(R.id.ev_vehicle_template)).perform(click());
    onData(allOf(is(instanceOf(String.class)), is(other.name))).perform(click());
    onView(withId(R.id.ev_vehicle_enabled)).check(matches(isChecked()));
    pressBack();

    checkPlannerOpen();
    final EvVehicleProfile profile = settings().getProfile();
    assertNotNull(profile);
    assertTrue(profile.id, other.id.equals(profile.id));
  }

  @Test
  public void backWithoutEnablingReturnsToRoute()
  {
    openSetup();
    pressBack();

    waitDisplayed(withId(R.id.btn__ev_planning));
    onView(withText(R.string.ev_battery_now)).check(doesNotExist());
    assertFalse(settings().isEnabled());
  }
}
