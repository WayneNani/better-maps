package app.organicmaps.transit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.actionWithAssertions;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.testing.FragmentScenario;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralLocation;
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Swipe;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.OnlineTransitService;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import app.organicmaps.sdk.transit.provider.TransitousProvider;
import app.organicmaps.transit.ui.OnlineTransitPanel;
import app.organicmaps.transit.ui.SavedConnectionCard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The draggable connection sheet, start/destination with swapping, and the card with the next saved connection.
 * The provider is the real Transitous client talking to a local mock server with a recorded response (Berlin Hbf ->
 * Alexanderplatz); nothing leaves the device.
 * <p>
 * The route planner's points live in the core (RoutingController), which needs the initialized map; so does
 * MwmActivity. The sheet is therefore hosted at the bottom of a plain screen with fake route points that behave like
 * the route planner (a change rebuilds the route through the online router's search), and the card is hosted alone
 * with a fixed clock. Its placement in MwmActivity is checked on the emulator.
 */
@RunWith(AndroidJUnit4.class)
public class TransitSheetAndCardTest
{
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.5250, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);
  private static final long TIMEOUT_MS = 10_000;

  /** Route points like the route planner's: a change searches again, as OnlineTransitRouter.build() does. */
  static final class FakeRoutePoints implements OnlineTransitPanel.RoutePoints
  {
    TransitLocation mFrom = HBF;
    TransitLocation mTo = ALEX;
    String mFromName = "Hauptbahnhof";
    String mToName = "Alexanderplatz";
    final List<Boolean> mChosen = new CopyOnWriteArrayList<>();

    void search(@NonNull Context context)
    {
      TransitServices.get(context).getConnectionsPresenter().search(mFrom, mTo, mFromName, mToName);
    }

    @Override
    public void choose(boolean start)
    {
      mChosen.add(start);
    }

    @Override
    public void swap()
    {
      final TransitLocation from = mFrom;
      mFrom = mTo;
      mTo = from;
      final String fromName = mFromName;
      mFromName = mToName;
      mToName = fromName;
      search(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }
  }

  static FakeRoutePoints sPoints;

  /** The connection sheet at the bottom of the screen, as in the route planner. */
  public static class SheetHostFragment extends Fragment
  {
    OnlineTransitPanel mPanel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
      final FrameLayout root = new FrameLayout(inflater.getContext());
      final View panel = inflater.inflate(R.layout.routing_bottom_panel_online_transit, root, false);
      root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                       ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
      return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
      padForSystemBars(view);
      mPanel = new OnlineTransitPanel(requireActivity(), view.findViewById(R.id.online_transit_panel), sPoints);
      mPanel.show();
    }

    @Override
    public void onDestroyView()
    {
      mPanel.hide();
      super.onDestroyView();
    }
  }

  static final AtomicReference<Instant> sNow = new AtomicReference<>();
  static final List<String> sOpened = new CopyOnWriteArrayList<>();

  /** The card with the next saved connection, above the bottom of the screen as on the map. */
  public static class CardHostFragment extends Fragment
  {
    SavedConnectionCard mCard;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
      final FrameLayout root = new FrameLayout(inflater.getContext());
      final View card = inflater.inflate(R.layout.transit_saved_card, root, false);
      root.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                      ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
      return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
      padForSystemBars(view);
      mCard = new SavedConnectionCard(requireActivity(),
                                      view.findViewById(R.id.transit_saved_card), new SavedConnectionCard.Host() {
                                        @Override
                                        public boolean canShowSavedConnection()
                                        {
                                          return true;
                                        }

                                        @Override
                                        public void openSavedConnection(@NonNull SavedConnection saved)
                                        {
                                          sOpened.add(saved.getKey());
                                        }

                                        @Override
                                        public void onSavedConnectionCardChanged()
                                        {}
                                      }, sNow::get);
      mCard.start();
    }

    @Override
    public void onDestroyView()
    {
      mCard.stop();
      super.onDestroyView();
    }
  }

  /** The test activity is drawn edge-to-edge (Android 15+): keep the content clear of the system bars. */
  static void padForSystemBars(@NonNull View view)
  {
    ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
      final Insets bars =
          insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    ViewCompat.requestApplyInsets(view);
  }

  private final List<String> mSavedKeys = new ArrayList<>();
  private MockWebServer mServer;
  private TransitServices mServices;
  private TransitConsent.State mConsentBefore;
  private String mDefaultViewBefore;
  private boolean mShowOnMapBefore;
  private Context mContext;

  @Before
  public void setUp() throws Exception
  {
    mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    sPoints = new FakeRoutePoints();
    sOpened.clear();
    mServer = new MockWebServer();
    mServer.start();
    final String url = mServer.url("/").toString();
    mDefaultViewBefore = prefs().getString(TransitSettings.KEY_DEFAULT_VIEW, null);
    mShowOnMapBefore = prefs().getBoolean(TransitSettings.KEY_SHOW_ON_MAP, true);
    prefs().edit().putString(TransitSettings.KEY_DEFAULT_VIEW, "LIST").commit();
    onMain(() -> {
      mServices = TransitServices.get(mContext);
      mConsentBefore = mServices.getConsent().getState();
      mServices.getConsent().grant(System.currentTimeMillis());
      mServices.setProviderForTesting(new TransitousProvider(
          new OnlineTransitService(TransitServiceConfig.builder("better-maps-test").setBaseUrl(url).build(),
                                   androidx.core.content.ContextCompat.getMainExecutor(mContext))));
      mServices.setRouteDisplayForTesting(new TransitConnectionsPresenter.RouteDisplay() {
        @Override
        public void show(@NonNull TransitItinerary itinerary)
        {}

        @Override
        public void clear()
        {}
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
    prefs()
        .edit()
        .putString(TransitSettings.KEY_DEFAULT_VIEW, mDefaultViewBefore)
        .putBoolean(TransitSettings.KEY_SHOW_ON_MAP, mShowOnMapBefore)
        .commit();
    mServer.shutdown();
  }

  private void enqueuePlan() throws IOException
  {
    mServer.enqueue(
        new MockResponse().setHeader("Content-Type", "application/json").setBody(asset("plan_hbf_alex.json")));
  }

  @NonNull
  private FragmentScenario<SheetHostFragment> searchInSheet() throws Exception
  {
    enqueuePlan();
    final FragmentScenario<SheetHostFragment> scenario =
        FragmentScenario.launchInContainer(SheetHostFragment.class, null, R.style.MwmTheme);
    onMain(() -> {
      sPoints.search(mContext);
      return null;
    });
    waitFor(() -> onMain(() -> mServices.getConnectionsPresenter().getState().getItems().size() == 7));
    return scenario;
  }

  @Test
  public void sheetCollapsesAndExpands() throws Exception
  {
    final FragmentScenario<SheetHostFragment> scenario = searchInSheet();
    final AtomicReference<OnlineTransitPanel> panel = new AtomicReference<>();
    scenario.onFragment(fragment -> panel.set(fragment.mPanel));
    onView(withId(R.id.transit_list)).check(matches(isDisplayed()));
    onView(withId(R.id.transit_summary)).check(matches(not(isDisplayed())));
    assertEquals(TransitSheet.State.HALF, onMain(() -> panel.get().getSheetState()));

    // Tap on the handle: only the summary is left.
    onView(withId(R.id.transit_handle)).perform(click());
    waitUntilDisplayed(R.id.transit_summary);
    onView(withId(R.id.transit_summary_text)).check(matches(withText("7 connections · Depart now")));
    onView(withId(R.id.transit_list)).check(matches(not(isDisplayed())));
    onView(withId(R.id.transit_header)).check(matches(not(isDisplayed())));
    onView(withId(R.id.transit_points)).check(matches(not(isDisplayed())));
    assertEquals(TransitSheet.State.COLLAPSED, onMain(() -> panel.get().getSheetState()));

    // Tap on the summary: the list again.
    onView(withId(R.id.transit_summary)).perform(click());
    waitUntilDisplayed(R.id.transit_list);
    onView(withId(R.id.transit_summary)).check(matches(not(isDisplayed())));
    onView(withId(R.id.transit_header)).check(matches(isDisplayed()));

    // Drag the handle down: collapsed.
    onView(withId(R.id.transit_handle)).perform(dragTo(0.97f));
    waitUntilDisplayed(R.id.transit_summary);
    onView(withId(R.id.transit_list)).check(matches(not(isDisplayed())));
    assertEquals(TransitSheet.State.COLLAPSED, onMain(() -> panel.get().getSheetState()));

    // Drag the summary far up: open again, with more of the list than collapsed.
    onView(withId(R.id.transit_summary)).perform(dragTo(0.1f));
    waitUntilDisplayed(R.id.transit_list);
    assertNotEquals(TransitSheet.State.COLLAPSED, onMain(() -> panel.get().getSheetState()));
    // Nothing was searched again.
    assertEquals(1, mServer.getRequestCount());
  }

  @Test
  public void collapsedConnectionShowsItsTimes() throws Exception
  {
    final FragmentScenario<SheetHostFragment> scenario = searchInSheet();
    final AtomicReference<RecyclerView> list = new AtomicReference<>();
    scenario.onFragment(fragment -> list.set(fragment.requireView().findViewById(R.id.transit_list)));
    waitFor(() -> onMain(() -> list.get().getChildCount() > 1));
    onMain(() -> {
      list.get().findViewHolderForAdapterPosition(1).itemView.performClick();
      return null;
    });
    onView(withId(R.id.transit_back)).check(matches(isDisplayed()));
    final String title = onMain(() -> TransitSheet.title(mServices.getConnectionsPresenter().getState().getSelected()));

    onView(withId(R.id.transit_handle)).perform(dragTo(0.97f));
    waitUntilDisplayed(R.id.transit_summary);
    onView(withId(R.id.transit_summary_text)).check(matches(withText(title)));
    // The legs and the buttons of the detail view are hidden.
    onView(withId(R.id.transit_list)).check(matches(not(isDisplayed())));
    onView(withId(R.id.transit_back)).check(matches(not(isDisplayed())));

    // Back still leads from the detail view to the list.
    assertTrue(onMain(() -> mServices.getConnectionsPresenter().back()));
    onView(withId(R.id.transit_summary_text)).check(matches(withText("7 connections · Depart now")));
  }

  @Test
  public void swappingStartAndDestinationSearchesAgain() throws Exception
  {
    searchInSheet();
    onView(withId(R.id.transit_from_name)).check(matches(withText("Hauptbahnhof")));
    onView(withId(R.id.transit_to_name)).check(matches(withText("Alexanderplatz")));
    final RecordedRequest first = mServer.takeRequest();
    assertEquals(HBF.toApiString(), first.getRequestUrl().queryParameter("fromPlace"));

    enqueuePlan();
    onView(withId(R.id.transit_swap)).perform(click());
    onView(withId(R.id.transit_from_name)).check(matches(withText("Alexanderplatz")));
    onView(withId(R.id.transit_to_name)).check(matches(withText("Hauptbahnhof")));
    // A new (debounced) request, the other way round.
    waitFor(() -> mServer.getRequestCount() == 2);
    final RecordedRequest second = mServer.takeRequest();
    assertEquals(ALEX.toApiString(), second.getRequestUrl().queryParameter("fromPlace"));
    assertEquals(HBF.toApiString(), second.getRequestUrl().queryParameter("toPlace"));
    waitFor(() -> onMain(() -> mServices.getConnectionsPresenter().getState().getItems().size() == 7));
    waitUntilDisplayed(R.id.transit_list);

    // Tapping start or destination opens the route planner's point picker.
    onView(withId(R.id.transit_from)).perform(click());
    onView(withId(R.id.transit_to)).perform(click());
    assertEquals(List.of(true, false), sPoints.mChosen);
  }

  @Test
  public void cardShowsTheNextSavedConnectionAndOpensIt() throws Exception
  {
    // Save the first connection of the recorded search (departs 16:29 UTC).
    final FragmentScenario<SheetHostFragment> sheet = searchInSheet();
    final String key = onMain(() -> {
      final TransitConnectionsPresenter presenter = mServices.getConnectionsPresenter();
      presenter.select(0);
      presenter.toggleSaved();
      return TransitItemFactory.key(presenter.getSelectedItinerary());
    });
    mSavedKeys.add(key);
    assertTrue(onMain(() -> mServices.getSavedConnections().contains(key)));
    sheet.close();

    sNow.set(Instant.parse("2026-10-25T16:04:00Z"));
    final FragmentScenario<CardHostFragment> scenario =
        FragmentScenario.launchInContainer(CardHostFragment.class, null, R.style.MwmTheme);
    onView(withId(R.id.transit_saved_card)).check(matches(isDisplayed()));
    onView(withId(R.id.transit_card_when)).check(matches(withText("in 25 min")));
    onView(withId(R.id.transit_card_route)).check(matches(withText(" · Hauptbahnhof → Alexanderplatz")));

    onView(withId(R.id.transit_card_content)).perform(click());
    assertEquals(List.of(key), sOpened);

    // Switched off in the settings: no card.
    prefs().edit().putBoolean(TransitSettings.KEY_SHOW_ON_MAP, false).commit();
    scenario.onFragment(fragment -> fragment.mCard.update());
    onView(withId(R.id.transit_saved_card)).check(matches(not(isDisplayed())));
    prefs().edit().putBoolean(TransitSettings.KEY_SHOW_ON_MAP, true).commit();

    // On the way, then arrived: past connections are not shown.
    sNow.set(Instant.parse("2026-10-25T16:35:00Z"));
    scenario.onFragment(fragment -> fragment.mCard.update());
    onView(withId(R.id.transit_saved_card)).check(matches(isDisplayed()));
    sNow.set(Instant.parse("2026-10-25T16:42:00Z"));
    scenario.onFragment(fragment -> fragment.mCard.update());
    final boolean shownAfterArrival = onMain(() -> {
      // Another saved connection of the device may still be ahead; this one must not be shown.
      final SavedConnection next = UpcomingConnection.next(mServices.getSavedConnections().getAll(), sNow.get(),
                                                           mServices.getSettings().getHiddenOnMap());
      return next != null && next.getKey().equals(key);
    });
    assertFalse(shownAfterArrival);
    // Nothing was sent for the card.
    assertEquals(1, mServer.getRequestCount());
  }

  /** Drags vertically from the view's center to a height on the screen (0: top, 1: bottom). */
  @NonNull
  private static ViewAction dragTo(float screenFraction)
  {
    return actionWithAssertions(new GeneralSwipeAction(Swipe.SLOW, GeneralLocation.CENTER, view -> {
      final int[] location = new int[2];
      view.getLocationOnScreen(location);
      final View root = view.getRootView();
      final int[] rootLocation = new int[2];
      root.getLocationOnScreen(rootLocation);
      return new float[] {location[0] + view.getWidth() / 2f, rootLocation[1] + root.getHeight() * screenFraction};
    }, Press.FINGER));
  }

  private static void waitUntilDisplayed(int id) throws Exception
  {
    waitFor(() -> {
      try
      {
        onView(withId(id)).check(matches(isDisplayed()));
        return true;
      }
      catch (AssertionError | RuntimeException e)
      {
        return false;
      }
    });
  }

  @NonNull
  private static SharedPreferences prefs()
  {
    return InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences(
        TransitSettings.PREFS_NAME, Context.MODE_PRIVATE);
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
