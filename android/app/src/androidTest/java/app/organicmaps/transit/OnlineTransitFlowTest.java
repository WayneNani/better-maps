package app.organicmaps.transit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
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
import app.organicmaps.transit.ui.OnlineTransitPanel;
import app.organicmaps.transit.ui.TransitConsentDialog;
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
 * Core flow of online public transport: opt-in dialog, connection list, detail view. The provider is the real
 * Transitous client talking to a local mock server with a recorded response (Berlin Hbf -> Alexanderplatz), so no
 * request leaves the device. The map drawing is replaced by a recorder (the map is not initialized here).
 */
@RunWith(AndroidJUnit4.class)
public class OnlineTransitFlowTest
{
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.5250, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);
  private static final long TIMEOUT_MS = 10_000;

  /** Hosts the connection panel of the route planner. */
  public static class PanelHostFragment extends Fragment
  {
    private OnlineTransitPanel mPanel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
      return inflater.inflate(R.layout.routing_bottom_panel_online_transit, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
      mPanel = new OnlineTransitPanel(requireActivity(), view);
      mPanel.show();
    }

    @Override
    public void onDestroyView()
    {
      mPanel.hide();
      super.onDestroyView();
    }
  }

  private final List<String> mDisplayed = new ArrayList<>();
  private MockWebServer mServer;
  private TransitServices mServices;

  @Before
  public void setUp() throws Exception
  {
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    mServer = new MockWebServer();
    mServer.start();
    final String url = mServer.url("/").toString();
    onMain(() -> {
      mServices = TransitServices.get(context);
      mServices.getConsent().reset();
      mServices.setProviderForTesting(new TransitousProvider(
          new OnlineTransitService(TransitServiceConfig.builder("better-maps-test").setBaseUrl(url).build(),
                                   androidx.core.content.ContextCompat.getMainExecutor(context))));
      mServices.setRouteDisplayForTesting(new TransitConnectionsPresenter.RouteDisplay() {
        @Override
        public void show(@NonNull TransitItinerary itinerary)
        {
          mDisplayed.add("show " + itinerary.getLegs().size());
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
      // Leave the app as before the first start: the user is asked again.
      mServices.getConsent().reset();
      return null;
    });
    mServer.shutdown();
  }

  @Test
  public void optInThenConnectionsAndDetails() throws Exception
  {
    mServer.enqueue(
        new MockResponse().setHeader("Content-Type", "application/json").setBody(asset("plan_hbf_alex.json")));
    final FragmentScenario<PanelHostFragment> scenario =
        FragmentScenario.launchInContainer(PanelHostFragment.class, null, R.style.MwmTheme);

    scenario.onFragment(fragment -> TransitConsentDialog.show(fragment.requireActivity(), granted -> {
      if (granted)
        mServices.getConnectionsPresenter().search(HBF, ALEX);
    }));
    onView(withText(R.string.transit_consent_title)).inRoot(isDialog()).check(matches(isDisplayed()));
    onView(withText(R.string.transit_consent_accept)).inRoot(isDialog()).perform(click());
    assertTrue(onMain(() -> mServices.getConsent().isGranted()));

    // The debounced query reaches the (mock) server and the list fills.
    final AtomicReference<RecyclerView> list = new AtomicReference<>();
    scenario.onFragment(fragment -> list.set(fragment.requireView().findViewById(R.id.transit_list)));
    waitFor(() -> onMain(() -> !mServices.getConnectionsPresenter().getState().getItems().isEmpty()));
    assertEquals(1, mServer.getRequestCount());
    assertEquals("/api/v6/plan", mServer.takeRequest().getRequestUrl().encodedPath());

    onView(withId(R.id.transit_time)).check(matches(withText(R.string.transit_depart_now)));
    onView(withId(R.id.transit_attribution)).check(matches(withText(containsString("Transitous"))));
    waitFor(() -> onMain(() -> list.get().getChildCount() > 1));

    // Open the first connection (position 0 is "earlier").
    onMain(() -> {
      final RecyclerView.ViewHolder holder = list.get().findViewHolderForAdapterPosition(1);
      holder.itemView.performClick();
      return null;
    });
    onView(withId(R.id.transit_back)).check(matches(isDisplayed()));
    onView(withText("S+U Berlin Hauptbahnhof")).check(matches(isDisplayed()));
    assertEquals(1, mDisplayed.size());
    assertTrue(mDisplayed.get(0).startsWith("show"));

    onView(withId(R.id.transit_back)).perform(click());
    onView(withId(R.id.transit_time)).check(matches(isDisplayed()));
    assertEquals("clear", mDisplayed.get(1));
    // Everything came from one request.
    assertEquals(1, mServer.getRequestCount());
  }

  @Test
  public void declineSendsNothing() throws Exception
  {
    final FragmentScenario<PanelHostFragment> scenario =
        FragmentScenario.launchInContainer(PanelHostFragment.class, null, R.style.MwmTheme);
    scenario.onFragment(fragment -> TransitConsentDialog.show(fragment.requireActivity(), granted -> {
      if (granted)
        mServices.getConnectionsPresenter().search(HBF, ALEX);
    }));
    onView(withText(R.string.transit_consent_decline)).inRoot(isDialog()).perform(click());
    assertEquals(TransitConsent.State.DECLINED, onMain(() -> mServices.getConsent().getState()));

    // Even a direct query is not sent without consent.
    onMain(() -> {
      mServices.getConnectionsPresenter().search(HBF, ALEX);
      return null;
    });
    waitFor(() -> onMain(() -> mServices.getConnectionsPresenter().getState().getError() != null));
    assertEquals(TransitError.DISABLED, onMain(() -> mServices.getConnectionsPresenter().getState().getError()));
    onView(withId(R.id.transit_status_text)).check(matches(withText(R.string.transit_error_disabled)));
    assertEquals(0, mServer.getRequestCount());
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
    // Assets of the test APK: the SDK's recorded MOTIS responses (see build.gradle.kts).
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
