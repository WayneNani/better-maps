package app.organicmaps.transit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasChildCount;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.fragment.app.testing.FragmentScenario;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.OnlineTransitService;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import app.organicmaps.sdk.transit.provider.TransitousProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Other departures for the first ride in the detail view: a tap on the ride shows them (one request), a tap on one
 * changes the connection, one that misses the next ride searches the rest again (one request) and shows a banner
 * with "Undo". The provider is the real Transitous client talking to a local mock server: the found connection
 * (S5 to Alexanderplatz, U2 to Klosterstraße), the direct S-Bahn trips to Alexanderplatz and the rest after a
 * missed U2 are recorded responses, so no request leaves the device.
 */
@RunWith(AndroidJUnit4.class)
public class LegAlternativesFlowTest
{
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.5250, 13.3694);
  private static final TransitLocation KLOSTER = TransitLocation.fromCoordinates(52.5165, 13.4130);
  private static final String ALEX_S = "de-VBB_de:11000:900100003:2:52";
  private static final long TIMEOUT_MS = 10_000;

  private final List<String> mDisplayed = new ArrayList<>();
  private MockWebServer mServer;
  private TransitServices mServices;
  private Context mContext;

  @Before
  public void setUp() throws Exception
  {
    mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    mServer = new MockWebServer();
    mServer.start();
    final String url = mServer.url("/").toString();
    onMain(() -> {
      mServices = TransitServices.get(mContext);
      mServices.getConsent().grant(System.currentTimeMillis());
      mServices.setProviderForTesting(new TransitousProvider(
          new OnlineTransitService(TransitServiceConfig.builder("better-maps-test").setBaseUrl(url).build(),
                                   androidx.core.content.ContextCompat.getMainExecutor(mContext))));
      mServices.setRouteDisplayForTesting(new TransitConnectionsPresenter.RouteDisplay() {
        @Override
        public void show(@NonNull TransitItinerary itinerary)
        {
          mDisplayed.add("show " + itinerary.getId());
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
      mServices.getConsent().reset();
      return null;
    });
    mServer.shutdown();
  }

  @Test
  public void chooseAnotherFirstRideAndUndoAMissedConnection() throws Exception
  {
    mServer.enqueue(json("plan_two_rides.json"));
    mServer.enqueue(json("plan_first_ride_alternatives.json"));
    mServer.enqueue(json("plan_rest_alexanderplatz.json"));
    final FragmentScenario<OnlineTransitFlowTest.PanelHostFragment> scenario = FragmentScenario.launchInContainer(
        OnlineTransitFlowTest.PanelHostFragment.class, null, R.style.MwmTheme);
    final TransitConnectionsPresenter presenter = onMain(() -> mServices.getConnectionsPresenter());
    onMain(() -> {
      presenter.search(HBF, KLOSTER);
      return null;
    });
    waitFor(() -> onMain(() -> presenter.getState().getItems().size() == 1));
    mServer.takeRequest();
    final AtomicReference<RecyclerView> list = new AtomicReference<>();
    scenario.onFragment(fragment -> list.set(fragment.requireView().findViewById(R.id.transit_list)));
    waitFor(() -> onMain(() -> list.get().getChildCount() > 0));

    // Open the connection: nothing is requested for other departures yet.
    onMain(() -> {
      presenter.select(0);
      return null;
    });
    onView(withId(R.id.transit_back)).check(matches(isDisplayed()));
    final String foundTitle = onMain(() -> TransitSheet.title(presenter.getState().getSelected()));
    onView(withId(R.id.transit_detail_subtitle)).check(matches(not(isDisplayed())));
    assertEquals(1, mServer.getRequestCount());

    // A tap on the S5 shows its other departures: one direct request to the S-Bahn platform at Alexanderplatz.
    onView(withText("S+U Berlin Hauptbahnhof")).perform(click());
    try
    {
      waitFor(() -> onMain(() -> {
        final AlternativesItem alternatives = presenter.getState().getAlternatives();
        return alternatives != null && !alternatives.isLoading();
      }));
    }
    catch (AssertionError e)
    {
      final AlternativesItem alternatives = onMain(() -> presenter.getState().getAlternatives());
      throw new AssertionError("requests=" + mServer.getRequestCount() + " alternatives=" + alternatives
                               + (alternatives != null ? " loading=" + alternatives.isLoading() : "")
                               + " screen=" + onMain(() -> presenter.getState().getScreen()), e);
    }
    assertEquals(2, mServer.getRequestCount());
    final HttpUrl request = mServer.takeRequest().getRequestUrl();
    assertEquals("0", request.queryParameter("maxTransfers"));
    assertEquals(ALEX_S, request.queryParameter("toPlace"));
    onView(withText(mContext.getString(R.string.transit_alt_title_next, "U2"))).check(matches(isDisplayed()));
    // The S5 itself (tight), the S3 that still reaches the U2, and two that miss it.
    onView(withId(R.id.alt_chips)).check(matches(hasChildCount(4)));
    onView(withContentDescription(startsWith("S9 "))).check(matches(withContentDescription(containsString("U2"))));

    // The S3: the connection starts later, same U2.
    onView(allOf(withContentDescription(startsWith("S3 ")), isDisplayed())).perform(click());
    final String changedTitle = onMain(() -> TransitSheet.title(presenter.getState().getSelected()));
    assertFalse(foundTitle.equals(changedTitle));
    onView(withId(R.id.transit_detail_title)).check(matches(withText(changedTitle)));
    onView(withId(R.id.transit_detail_subtitle))
        .check(matches(withText(mContext.getString(R.string.transit_changed_same,
                                                   mContext.getString(R.string.transit_ride_first)))));
    assertTrue(mDisplayed.get(mDisplayed.size() - 1).startsWith("show test-two-rides|"));
    assertEquals(2, mServer.getRequestCount());

    // The S9 misses the U2: the rest is searched from Alexanderplatz.
    onView(withContentDescription(startsWith("S9 "))).perform(scrollTo(), click());
    waitFor(() -> onMain(() -> {
      final ItineraryItem selected = presenter.getState().getSelected();
      return selected != null && selected.getChange() != null && selected.getChange().isReplanned();
    }));
    assertEquals(3, mServer.getRequestCount());
    assertEquals(ALEX_S, mServer.takeRequest().getRequestUrl().queryParameter("fromPlace"));
    final ChangeItem change = onMain(() -> presenter.getState().getSelected().getChange());
    assertEquals("U2", change.getMissedLine());
    assertEquals(10, change.getDelayMinutes());
    onMain(() -> {
      list.get().scrollToPosition(list.get().getAdapter().getItemCount() - 1);
      return null;
    });
    onView(withId(R.id.banner_text)).check(matches(withText(containsString("U2"))));

    // Undo: the connection as found.
    onView(withId(R.id.banner_undo)).perform(click());
    waitFor(() -> onMain(() -> presenter.getState().getSelected().getChange() == null));
    onView(withId(R.id.transit_detail_title)).check(matches(withText(foundTitle)));
    onView(withId(R.id.transit_detail_subtitle)).check(matches(not(isDisplayed())));
    assertNotNull(onMain(() -> presenter.getState().getAlternatives()));

    // Tapping the ride again hides its departures.
    onMain(() -> {
      list.get().scrollToPosition(0);
      return null;
    });
    onView(withText("S+U Berlin Hauptbahnhof")).perform(click());
    assertNull(onMain(() -> presenter.getState().getAlternatives()));
    assertEquals(3, mServer.getRequestCount());
  }

  @NonNull
  private static MockResponse json(@NonNull String name) throws IOException
  {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(asset(name));
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
