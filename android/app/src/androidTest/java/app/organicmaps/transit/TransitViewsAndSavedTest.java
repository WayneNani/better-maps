package app.organicmaps.transit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.actionWithAssertions;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeLeft;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.testing.FragmentScenario;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralClickAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Tap;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.OnlineTransitService;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import app.organicmaps.sdk.transit.provider.TransitousProvider;
import app.organicmaps.transit.ui.SavedConnectionsFragment;
import app.organicmaps.transit.ui.TimeGridView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * List and time grid of the connection panel, and saved connections. The provider talks to a local mock server
 * with a recorded response (Berlin Hbf -> Alexanderplatz); nothing leaves the device. Saved connections of the user
 * on the device are left alone; the consent and the default view are restored afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class TransitViewsAndSavedTest
{
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.5250, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);
  private static final String ROUTE = "Test Hbf → Test Alex";
  private static final long TIMEOUT_MS = 10_000;

  private final List<String> mDisplayed = new ArrayList<>();
  private final List<String> mSavedKeys = new ArrayList<>();
  private MockWebServer mServer;
  private TransitServices mServices;
  private TransitConsent.State mConsentBefore;
  private String mDefaultViewBefore;

  @Before
  public void setUp() throws Exception
  {
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    mServer = new MockWebServer();
    mServer.start();
    mServer.enqueue(
        new MockResponse().setHeader("Content-Type", "application/json").setBody(asset("plan_hbf_alex.json")));
    final String url = mServer.url("/").toString();
    mDefaultViewBefore = prefs().getString(TransitSettings.KEY_DEFAULT_VIEW, null);
    onMain(() -> {
      mServices = TransitServices.get(context);
      mConsentBefore = mServices.getConsent().getState();
      mServices.getConsent().grant(System.currentTimeMillis());
      mServices.setProviderForTesting(new TransitousProvider(
          new OnlineTransitService(TransitServiceConfig.builder("better-maps-test").setBaseUrl(url).build(),
                                   androidx.core.content.ContextCompat.getMainExecutor(context))));
      mServices.setRouteDisplayForTesting(new TransitConnectionsPresenter.RouteDisplay() {
        @Override
        public void show(@NonNull TransitItinerary itinerary)
        {
          mDisplayed.add("show");
        }

        @Override
        public void clear()
        {
          mDisplayed.add("clear");
        }
      });
      mServices.getConnectionsPresenter().clear();
      return null;
    });
  }

  @After
  public void tearDown() throws Exception
  {
    onMain(() -> {
      mServices.getConnectionsPresenter().clear();
      for (String key : mSavedKeys)
        mServices.getSavedConnections().remove(key);
      switch (mConsentBefore)
      {
      case GRANTED: break;
      case DECLINED: mServices.getConsent().decline(); break;
      default: mServices.getConsent().reset();
      }
      return null;
    });
    prefs().edit().putString(TransitSettings.KEY_DEFAULT_VIEW, mDefaultViewBefore).commit();
    mServer.shutdown();
  }

  @NonNull
  private static SharedPreferences prefs()
  {
    return InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences(
        TransitSettings.PREFS_NAME, Context.MODE_PRIVATE);
  }

  @NonNull
  private FragmentScenario<OnlineTransitFlowTest.PanelHostFragment> searchInPanel() throws Exception
  {
    final FragmentScenario<OnlineTransitFlowTest.PanelHostFragment> scenario =
        FragmentScenario.launchInContainer(OnlineTransitFlowTest.PanelHostFragment.class, null, R.style.MwmTheme);
    onMain(() -> {
      mServices.getConnectionsPresenter().search(HBF, ALEX, "Test Hbf", "Test Alex");
      return null;
    });
    waitFor(() -> onMain(() -> mServices.getConnectionsPresenter().getState().getItems().size() == 7));
    return scenario;
  }

  @Test
  public void listToGridToDetailAndBackToGrid() throws Exception
  {
    prefs().edit().putString(TransitSettings.KEY_DEFAULT_VIEW, "LIST").commit();
    final FragmentScenario<OnlineTransitFlowTest.PanelHostFragment> scenario = searchInPanel();
    // The default view: the list with the bars.
    onView(withId(R.id.transit_list)).check(matches(isDisplayed()));
    onView(withId(R.id.transit_grid_scroll)).check(matches(not(isDisplayed())));
    final AtomicReference<RecyclerView> list = new AtomicReference<>();
    scenario.onFragment(fragment -> list.set(fragment.requireView().findViewById(R.id.transit_list)));
    waitFor(() -> onMain(() -> list.get().getChildCount() > 1));
    assertNotNull(onMain(() -> list.get().findViewHolderForAdapterPosition(1).itemView.findViewById(R.id.bar)));

    // Switch to the time grid.
    onView(withId(R.id.transit_view_grid)).perform(click());
    onView(withId(R.id.transit_grid_scroll)).check(matches(isDisplayed()));
    onView(withId(R.id.transit_list)).check(matches(not(isDisplayed())));
    assertEquals(ConnectionsState.ViewMode.GRID, onMain(() -> mServices.getConnectionsPresenter().getViewMode()));

    // Tap the third column: the detail view of that connection.
    onView(withId(R.id.transit_grid)).perform(clickColumn(2));
    onView(withId(R.id.transit_back)).check(matches(isDisplayed()));
    onView(withId(R.id.transit_view_toggle)).check(matches(not(isDisplayed())));
    assertEquals("16:34", onMain(() -> {
                   final TransitItinerary selected = mServices.getConnectionsPresenter().getSelectedItinerary();
                   return selected.getStartTime().atZone(java.time.ZoneOffset.UTC).toLocalTime().toString();
                 }));
    onView(withText("S9")).check(matches(isDisplayed()));
    assertEquals(List.of("show"), mDisplayed);

    // Back: the time grid again.
    onView(withId(R.id.transit_back)).perform(click());
    onView(withId(R.id.transit_grid_scroll)).check(matches(isDisplayed()));
    onView(withId(R.id.transit_list)).check(matches(not(isDisplayed())));
    assertEquals(List.of("show", "clear"), mDisplayed);

    // And to the list.
    onView(withId(R.id.transit_view_list)).perform(click());
    onView(withId(R.id.transit_list)).check(matches(isDisplayed()));
    // Everything came from one request.
    assertEquals(1, mServer.getRequestCount());
  }

  @Test
  public void defaultViewFromSettings() throws Exception
  {
    prefs().edit().putString(TransitSettings.KEY_DEFAULT_VIEW, "GRID").commit();
    searchInPanel();
    onView(withId(R.id.transit_grid_scroll)).check(matches(isDisplayed()));
    onView(withId(R.id.transit_list)).check(matches(not(isDisplayed())));
  }

  @Test
  public void saveOpenInSavedListAndDelete() throws Exception
  {
    prefs().edit().putString(TransitSettings.KEY_DEFAULT_VIEW, "LIST").commit();
    final FragmentScenario<OnlineTransitFlowTest.PanelHostFragment> scenario = searchInPanel();
    final AtomicReference<RecyclerView> list = new AtomicReference<>();
    scenario.onFragment(fragment -> list.set(fragment.requireView().findViewById(R.id.transit_list)));
    waitFor(() -> onMain(() -> list.get().getChildCount() > 1));
    onMain(() -> {
      list.get().findViewHolderForAdapterPosition(1).itemView.performClick();
      return null;
    });

    // Save the first connection.
    onView(withId(R.id.transit_save)).check(matches(withContentDescription(R.string.transit_save)));
    onView(withId(R.id.transit_save)).perform(click());
    final String key = onMain(() -> TransitItemFactory.key(mServices.getConnectionsPresenter().getSelectedItinerary()));
    mSavedKeys.add(key);
    assertTrue(onMain(() -> mServices.getSavedConnections().contains(key)));
    onView(withId(R.id.transit_save)).check(matches(withContentDescription(R.string.transit_unsave)));
    final SavedConnection saved = onMain(() -> mServices.getSavedConnections().get(key));
    assertEquals("Test Hbf", saved.getFromName());
    assertEquals("Test Alex", saved.getToName());
    scenario.close();

    // The list of saved connections shows it. (Its activity needs the initialized core, so the list is hosted alone.)
    try (FragmentScenario<SavedConnectionsFragment> saveds =
             FragmentScenario.launchInContainer(SavedConnectionsFragment.class, null, R.style.MwmTheme))
    {
      // Edge-to-edge test activity: keep the list clear of the status bar.
      saveds.onFragment(fragment -> {
        final View root = fragment.requireView();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
          final Insets bars =
              insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(bars.left, bars.top, bars.right, 0);
          return insets;
        });
        ViewCompat.requestApplyInsets(root);
      });
      onView(withText(ROUTE)).check(matches(isDisplayed()));
      // The fixture is from the past.
      onView(allOf(withId(R.id.saved_past), withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE),
                   withParent(hasSibling(withText(ROUTE)))))
          .check(matches(isDisplayed()));

      // Swipe away, undo, then delete via the menu.
      onView(withText(ROUTE)).perform(swipeLeft());
      waitFor(() -> onMain(() -> !mServices.getSavedConnections().contains(key)));
      // The snackbar slides in.
      waitFor(() -> {
        try
        {
          onView(withText(R.string.transit_undo)).check(matches(isCompletelyDisplayed()));
          return true;
        }
        catch (AssertionError | RuntimeException e)
        {
          return false;
        }
      });
      onView(withText(R.string.transit_undo)).perform(click());
      waitFor(() -> onMain(() -> mServices.getSavedConnections().contains(key)));
      onView(withText(ROUTE)).check(matches(isDisplayed()));

      onView(allOf(withId(R.id.saved_more), hasSibling(ViewMatchers.withChild(withText(ROUTE))))).perform(click());
      onView(withText(R.string.transit_delete)).inRoot(isPlatformPopup()).perform(click());
      waitFor(() -> onMain(() -> !mServices.getSavedConnections().contains(key)));
      onView(withText(ROUTE)).check(doesNotExist());
    }
    // Nothing was sent for the saved connections.
    assertEquals(1, mServer.getRequestCount());
    assertFalse(onMain(() -> mServices.getSavedConnections().contains(key)));
  }

  /** Taps the center of a connection's column in the time grid. */
  @NonNull
  private static ViewAction clickColumn(int index)
  {
    return actionWithAssertions(new GeneralClickAction(Tap.SINGLE, view -> {
      final float[] center = ((TimeGridView) view).getColumnCenter(index);
      if (center == null)
        throw new AssertionError("No column " + index);
      final int[] location = new int[2];
      view.getLocationOnScreen(location);
      return new float[] {location[0] + center[0], location[1] + center[1]};
    }, Press.FINGER, 0, 0));
  }

  private static <T> T onMain(@NonNull Callable<T> callable) throws Exception
  {
    final AtomicReference<T> result = new AtomicReference<>();
    final AtomicReference<Exception> error = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      try
      {
        result.set(callable.call());
      }
      catch (Exception e)
      {
        error.set(e);
      }
    });
    if (error.get() != null)
      throw error.get();
    return result.get();
  }

  private static void waitFor(@NonNull Callable<Boolean> condition) throws Exception
  {
    final long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
    while (SystemClock.uptimeMillis() < end)
    {
      if (condition.call())
        return;
      SystemClock.sleep(100);
    }
    fail("Condition not met within " + TIMEOUT_MS + " ms");
  }

  @NonNull
  private static String asset(@NonNull String name) throws IOException
  {
    try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("transit/" + name))
    {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buffer = new byte[16384];
      int n;
      while ((n = in.read(buffer)) != -1)
        out.write(buffer, 0, n);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }
}
