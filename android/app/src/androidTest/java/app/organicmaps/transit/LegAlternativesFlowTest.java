package app.organicmaps.transit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.actionWithAssertions;
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
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.testing.FragmentScenario;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralClickAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Tap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.OnlineTransitService;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import app.organicmaps.sdk.transit.provider.TransitousProvider;
import app.organicmaps.transit.ui.JourneyBarView;
import app.organicmaps.transit.ui.TimeGridView;
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
 * Other departures for the first ride. In the detail view a tap on the ride shows them (one request), a tap on one
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

  /**
   * Tap and hold in the time grid: a tap on the S5 opens the connection; holding it (without moving) selects it and
   * shows its other departures, which stay after the finger is lifted so that a pill can be tapped. While it is
   * selected, a tap on the S5 or beside it only ends the selection.
   */
  @Test
  public void tapOpensAndHoldSelectsInTheTimeGrid() throws Exception
  {
    mServer.enqueue(json("plan_two_rides.json"));
    mServer.enqueue(json("plan_first_ride_alternatives.json"));
    final FragmentScenario<OnlineTransitFlowTest.PanelHostFragment> scenario = FragmentScenario.launchInContainer(
        OnlineTransitFlowTest.PanelHostFragment.class, null, R.style.MwmTheme);
    final TransitConnectionsPresenter presenter = onMain(() -> mServices.getConnectionsPresenter());
    onMain(() -> {
      presenter.search(HBF, KLOSTER);
      presenter.setViewMode(ConnectionsState.ViewMode.GRID);
      return null;
    });
    waitFor(() -> onMain(() -> presenter.getState().getItems().size() == 1));
    mServer.takeRequest();
    final AtomicReference<TimeGridView> gridRef = new AtomicReference<>();
    scenario.onFragment(fragment -> gridRef.set(fragment.requireView().findViewById(R.id.transit_grid)));
    final TimeGridView grid = gridRef.get();
    waitFor(() -> onMain(() -> grid.isShown() && grid.getRideCenter(0, 1) != null));

    // A tap on the S5: the connection opens, nothing is searched.
    Finger.tapRide(grid, 0, 1);
    waitFor(() -> onMain(() -> presenter.getState().getScreen() == ConnectionsState.Screen.DETAIL));
    assertNull(onMain(() -> presenter.getState().getAlternatives()));
    assertEquals(1, mServer.getRequestCount());
    onView(withId(R.id.transit_back)).perform(click());
    waitFor(() -> onMain(() -> presenter.getState().getScreen() == ConnectionsState.Screen.LIST && grid.isShown()
                                   && grid.getRideCenter(0, 1) != null));

    // Held and let go without moving: selected with its departures and the hint card, nothing chosen.
    Finger.hold(grid, 0, 1).lift();
    assertFalse(onMain(grid::isDragging));
    waitFor(() -> onMain(() -> {
      final AlternativesItem alternatives = presenter.getState().getAlternatives();
      return alternatives != null && !alternatives.isLoading() && grid.getAlternativeCenter(1) != null;
    }));
    assertEquals(2, mServer.getRequestCount());
    assertEquals(ConnectionsState.Screen.LIST, onMain(() -> presenter.getState().getScreen()));
    assertNull(onMain(() -> presenter.getState().getItems().get(0).getChange()));
    onView(withId(R.id.transit_grid_hint)).check(matches(isDisplayed()));

    // A tap on the selected S5 ends the selection; the connection does not open.
    Finger.tapRide(grid, 0, 1);
    waitFor(() -> onMain(() -> presenter.getState().getAlternatives() == null));
    assertEquals(ConnectionsState.Screen.LIST, onMain(() -> presenter.getState().getScreen()));
    onView(withId(R.id.transit_grid_hint)).check(matches(not(isDisplayed())));

    // Again (the departures are known): a tap beside the rides ends the selection too.
    Finger.hold(grid, 0, 1).lift();
    waitFor(() -> onMain(() -> presenter.getState().getAlternatives() != null && grid.getColumnCenter(0) != null));
    Finger.tap(grid, onMain(() -> grid.getColumnCenter(0)));
    waitFor(() -> onMain(() -> presenter.getState().getAlternatives() == null));
    assertEquals(ConnectionsState.Screen.LIST, onMain(() -> presenter.getState().getScreen()));

    // Held and let go, then a tap on the S3's pill: the S3 is used.
    Finger.hold(grid, 0, 1).lift();
    waitFor(() -> onMain(() -> {
      final AlternativesItem alternatives = presenter.getState().getAlternatives();
      return alternatives != null && !alternatives.isLoading() && grid.getAlternativeCenter(1) != null;
    }));
    Finger.tap(grid, onMain(() -> grid.getAlternativeCenter(1)));
    waitFor(() -> onMain(() -> presenter.getState().getItems().get(0).getChange() != null));
    assertTrue(onMain(() -> presenter.getState().getAlternatives().getChips().get(1).isSelected()));
    assertEquals(ConnectionsState.Screen.LIST, onMain(() -> presenter.getState().getScreen()));
    assertEquals(2, mServer.getRequestCount());

    // "Details" in the hint card opens the connection.
    onView(withId(R.id.transit_grid_hint_details)).perform(click());
    waitFor(() -> onMain(() -> presenter.getState().getScreen() == ConnectionsState.Screen.DETAIL));
  }

  /**
   * Tap and hold in the list: a tap on the first ride in the bar opens the connection like anywhere on the card;
   * holding it shows its other departures in the card (and holding it again hides them); the "Departures" button
   * shows them with a tap.
   */
  @Test
  public void tapOpensAndHoldShowsDeparturesInTheList() throws Exception
  {
    mServer.enqueue(json("plan_two_rides.json"));
    mServer.enqueue(json("plan_first_ride_alternatives.json"));
    final FragmentScenario<OnlineTransitFlowTest.PanelHostFragment> scenario = FragmentScenario.launchInContainer(
        OnlineTransitFlowTest.PanelHostFragment.class, null, R.style.MwmTheme);
    final TransitConnectionsPresenter presenter = onMain(() -> mServices.getConnectionsPresenter());
    onMain(() -> {
      presenter.search(HBF, KLOSTER);
      presenter.setViewMode(ConnectionsState.ViewMode.LIST);
      return null;
    });
    waitFor(() -> onMain(() -> presenter.getState().getItems().size() == 1));
    mServer.takeRequest();
    final AtomicReference<RecyclerView> list = new AtomicReference<>();
    scenario.onFragment(fragment -> list.set(fragment.requireView().findViewById(R.id.transit_list)));
    waitFor(() -> onMain(() -> list.get().getChildCount() > 0));

    // A tap on the S5 in the bar: the connection opens, nothing is searched.
    onView(allOf(withId(R.id.bar), isDisplayed())).perform(onBarRide(Tap.SINGLE));
    waitFor(() -> onMain(() -> presenter.getState().getScreen() == ConnectionsState.Screen.DETAIL));
    assertNull(onMain(() -> presenter.getState().getAlternatives()));
    assertEquals(1, mServer.getRequestCount());
    onView(withId(R.id.transit_back)).perform(click());
    waitFor(() -> onMain(() -> presenter.getState().getScreen() == ConnectionsState.Screen.LIST));

    // Held: its departures in the card, the connection stays closed.
    onView(allOf(withId(R.id.bar), isDisplayed())).perform(onBarRide(Tap.LONG));
    waitFor(() -> onMain(() -> {
      final AlternativesItem alternatives = presenter.getState().getAlternatives();
      return alternatives != null && !alternatives.isLoading();
    }));
    assertEquals(2, mServer.getRequestCount());
    assertEquals(ConnectionsState.Screen.LIST, onMain(() -> presenter.getState().getScreen()));
    assertEquals(1, (int) onMain(() -> presenter.getState().getAlternatives().getLegIndex()));
    onView(withId(R.id.alternatives_chips)).check(matches(hasChildCount(4)));

    // Held again: hidden.
    onView(allOf(withId(R.id.bar), isDisplayed())).perform(onBarRide(Tap.LONG));
    waitFor(() -> onMain(() -> presenter.getState().getAlternatives() == null));
    assertEquals(ConnectionsState.Screen.LIST, onMain(() -> presenter.getState().getScreen()));

    // The button shows them with a tap (known already: no request). Its negative margins keep it partly outside the
    // row, too little of it for Espresso's click.
    onView(withId(R.id.alternatives_button)).check(matches(isDisplayed()));
    onMain(() -> list.get().findViewById(R.id.alternatives_button).performClick());
    waitFor(() -> onMain(() -> presenter.getState().getAlternatives() != null));
    assertEquals(ConnectionsState.Screen.LIST, onMain(() -> presenter.getState().getScreen()));
    assertEquals(2, mServer.getRequestCount());
  }

  /** Taps or holds the first ride in a connection's bar. */
  @NonNull
  private static ViewAction onBarRide(@NonNull Tap tap)
  {
    return actionWithAssertions(new GeneralClickAction(tap, view -> {
      final float[] center = ((JourneyBarView) view).getRideCenter();
      if (center == null)
        throw new AssertionError("No ride in the bar");
      final int[] location = new int[2];
      view.getLocationOnScreen(location);
      return new float[] {location[0] + center[0], location[1] + center[1]};
    }, Press.FINGER, 0, 0));
  }

  /**
   * The same connection in the time grid: holding the S5 selects and lifts it in one gesture and loads its other
   * departures, dragging snaps it to them with the hint card telling what each one means, lifting the finger uses the
   * departure. A cancelled drag and a drag back to the start change nothing; a departure that misses the U2 searches
   * the rest again.
   */
  @Test
  public void dragTheFirstRideInTheTimeGrid() throws Exception
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
    onMain(() -> {
      presenter.setViewMode(ConnectionsState.ViewMode.GRID);
      return null;
    });
    final AtomicReference<TimeGridView> gridRef = new AtomicReference<>();
    final AtomicReference<View> scrollRef = new AtomicReference<>();
    scenario.onFragment(fragment -> {
      gridRef.set(fragment.requireView().findViewById(R.id.transit_grid));
      scrollRef.set(fragment.requireView().findViewById(R.id.transit_grid_scroll));
    });
    final TimeGridView grid = gridRef.get();
    // Walk, S5, walk, U2, walk: the S5 is leg 1.
    waitFor(() -> onMain(() -> grid.isShown() && grid.getRideCenter(0, 1) != null));
    final int sheetHeight = onMain(() -> scrollRef.get().getHeight());

    // Hold the S5: it is lifted and its departures are searched (one request), nothing is chosen yet.
    final Finger finger = Finger.hold(grid, 0, 1);
    waitFor(() -> onMain(grid::isDragging));
    waitFor(() -> onMain(() -> {
      final AlternativesItem alternatives = presenter.getState().getAlternatives();
      return alternatives != null && !alternatives.isLoading() && grid.getAlternativeCenter(0) != null;
    }));
    assertEquals(2, mServer.getRequestCount());
    mServer.takeRequest();
    final List<String> lines = onMain(() -> {
      final List<String> result = new ArrayList<>();
      for (AlternativesItem.Chip chip : presenter.getState().getAlternatives().getChips())
        result.add(chip.getBadge().getText());
      return result;
    });
    // The S5 itself, the S3 that still reaches the U2, the S9 and the S7 that miss it.
    assertEquals(List.of("S5", "S3", "S9", "S7"), lines);
    final AlternativesItem.Chip s3 = onMain(() -> presenter.getState().getAlternatives().getChips().get(1));

    // Over the S3: the hint card tells about it; cancelled: nothing changes.
    finger.moveToPill(1, 0);
    assertTrue(onMain(grid::isDragging));
    onView(withId(R.id.transit_grid_hint_title)).check(matches(withText(containsString("S3"))));
    onView(withId(R.id.transit_grid_hint_title)).check(matches(withText(containsString(s3.getDeparture()))));
    finger.cancel();
    assertFalse(onMain(grid::isDragging));
    assertNull(onMain(() -> presenter.getState().getItems().get(0).getChange()));
    onView(withId(R.id.transit_grid_hint_title)).check(matches(withText(containsString("S5"))));
    // The sheet stayed as it was.
    assertEquals(sheetHeight, (int) onMain(() -> scrollRef.get().getHeight()));

    // Down to the S9 (it misses the U2) and back to the S5: nothing changes.
    final Finger again = Finger.hold(grid, 0, 1);
    waitFor(() -> onMain(grid::isDragging));
    again.moveToPill(2, 0);
    onView(withId(R.id.transit_grid_hint_text))
        .check(matches(withText(mContext.getString(R.string.transit_alt_hint_drag_missed, "U2"))));
    again.moveToPill(0, 0);
    again.lift();
    assertFalse(onMain(grid::isDragging));
    assertNull(onMain(() -> presenter.getState().getItems().get(0).getChange()));

    // Onto the S3 and let go: the S3 is used, nothing is requested.
    final Finger s3Drag = Finger.hold(grid, 0, 1);
    waitFor(() -> onMain(grid::isDragging));
    s3Drag.moveToPill(1, 0);
    s3Drag.lift();
    waitFor(() -> onMain(() -> presenter.getState().getItems().get(0).getChange() != null));
    assertFalse(onMain(() -> presenter.getState().getItems().get(0).getChange().isReplanned()));
    assertTrue(onMain(() -> presenter.getState().getAlternatives().getChips().get(1).isSelected()));
    assertEquals(2, mServer.getRequestCount());

    // The S3 (now at the S3's place) onto the S9: the rest is searched again from Alexanderplatz.
    waitFor(() -> onMain(() -> grid.getRideCenter(0, 1) != null && grid.getAlternativeCenter(2) != null));
    final Finger s9Drag = Finger.hold(grid, 0, 1);
    waitFor(() -> onMain(grid::isDragging));
    s9Drag.moveToPill(2, 1);
    s9Drag.lift();
    waitFor(() -> onMain(() -> {
      final ChangeItem change = presenter.getState().getItems().get(0).getChange();
      return change != null && change.isReplanned();
    }));
    assertEquals(3, mServer.getRequestCount());
    assertEquals(ALEX_S, mServer.takeRequest().getRequestUrl().queryParameter("fromPlace"));
    final ChangeItem change = onMain(() -> presenter.getState().getItems().get(0).getChange());
    assertEquals("U2", change.getMissedLine());
    onView(withId(R.id.transit_grid_hint_text))
        .check(matches(withText(mContext.getString(R.string.transit_alt_hint_missed, "U2", change.getArrival()))));

    // Undo: the connection as found.
    onMain(() -> {
      presenter.undoChange(0);
      return null;
    });
    assertNull(onMain(() -> presenter.getState().getItems().get(0).getChange()));
    assertEquals(sheetHeight, (int) onMain(() -> scrollRef.get().getHeight()));
  }

  /** A finger on the time grid, injected as motion events in screen coordinates. */
  private static final class Finger
  {
    private final TimeGridView mGrid;
    private final long mDownTime;
    private final float mScreenX;
    private final float mDownScreenY;
    private float mScreenY;

    private Finger(@NonNull TimeGridView grid, long downTime, float screenX, float screenY)
    {
      mGrid = grid;
      mDownTime = downTime;
      mScreenX = screenX;
      mDownScreenY = screenY;
      mScreenY = screenY;
    }

    /** A short tap on a ride. */
    static void tapRide(@NonNull TimeGridView grid, int connection, int legIndex) throws Exception
    {
      tap(grid, onMain(() -> grid.getRideCenter(connection, legIndex)));
    }

    /** A short tap at a point in the grid's coordinates. */
    static void tap(@NonNull TimeGridView grid, @NonNull float[] point) throws Exception
    {
      final int[] location = onMain(() -> {
        final int[] result = new int[2];
        grid.getLocationOnScreen(result);
        return result;
      });
      final Finger finger = new Finger(grid, SystemClock.uptimeMillis(), location[0] + point[0], location[1] + point[1]);
      finger.send(MotionEvent.ACTION_DOWN);
      finger.lift();
    }

    /** Puts the finger on a ride and holds it there long enough to lift it. */
    @NonNull
    static Finger hold(@NonNull TimeGridView grid, int connection, int legIndex) throws Exception
    {
      final float[] point = onMain(() -> {
        final float[] center = grid.getRideCenter(connection, legIndex);
        final int[] location = new int[2];
        grid.getLocationOnScreen(location);
        return new float[] {location[0] + center[0], location[1] + center[1]};
      });
      final long now = SystemClock.uptimeMillis();
      final Finger finger = new Finger(grid, now, point[0], point[1]);
      finger.send(MotionEvent.ACTION_DOWN);
      SystemClock.sleep(TimeGridView.HOLD_MS + 200);
      return finger;
    }

    /**
     * Moves the finger in small steps by the distance between two pills, i.e. from the departure {@code from} that
     * the ride was at when the finger came down to the departure {@code to}.
     */
    void moveToPill(int to, int from) throws Exception
    {
      final float distance = onMain(() -> mGrid.getAlternativeCenter(to)[1] - mGrid.getAlternativeCenter(from)[1]);
      final float target = mDownScreenY + distance;
      final int steps = 12;
      final float start = mScreenY;
      for (int i = 1; i <= steps; ++i)
      {
        mScreenY = start + (target - start) * i / steps;
        send(MotionEvent.ACTION_MOVE);
        SystemClock.sleep(16);
      }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    void lift()
    {
      send(MotionEvent.ACTION_UP);
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    void cancel()
    {
      send(MotionEvent.ACTION_CANCEL);
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void send(int action)
    {
      final MotionEvent event =
          MotionEvent.obtain(mDownTime, SystemClock.uptimeMillis(), action, mScreenX, mScreenY, 0);
      event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      try
      {
        InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
      }
      finally
      {
        event.recycle();
      }
    }
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
