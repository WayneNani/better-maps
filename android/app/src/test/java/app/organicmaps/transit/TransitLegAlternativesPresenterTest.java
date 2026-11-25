package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.plan;
import static app.organicmaps.transit.LegFixtures.at;
import static app.organicmaps.transit.LegFixtures.connection;
import static app.organicmaps.transit.LegFixtures.firstAlternative;
import static app.organicmaps.transit.LegFixtures.rest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.provider.TransitProviderRegistry;
import app.organicmaps.sdk.util.concurrency.Debouncer;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Alternative departures of single rides in the connection list, the time grid and the detail view. */
public class TransitLegAlternativesPresenterTest
{
  private static final TransitLocation FROM = TransitLocation.fromCoordinates(50.7068, 7.1406);
  private static final TransitLocation TO = TransitLocation.fromCoordinates(50.9430, 6.9589);
  private static final Instant NOW = at("08:30");

  private final ManualScheduler mScheduler = new ManualScheduler();
  private final FakeTransitProvider mProvider = new FakeTransitProvider();
  private final List<ConnectionsState> mRendered = new ArrayList<>();
  private final List<String> mDisplay = new ArrayList<>();
  private SavedConnectionsStore mSaved;
  private TransitConnectionsPresenter mPresenter;

  @Rule
  public final TemporaryFolder mFolder = new TemporaryFolder();

  @Before
  public void setUp()
  {
    mSaved = new SavedConnectionsStore(new File(mFolder.getRoot(), "saved.json"), Runnable::run, () -> NOW);
    final TransitProviderRegistry registry = new TransitProviderRegistry(Runnable::run, () -> 0L, () -> true);
    registry.register(mProvider, TransitProviderRegistry.PRIORITY_DEFAULT);
    mPresenter = new TransitConnectionsPresenter(
        registry, TransitRoutingOptions::defaults, new TransitItemFactory(Fixtures.formatter()),
        new Debouncer(mScheduler, TransitConnectionsPresenter.DEBOUNCE_MS), () -> NOW,
        new TransitConnectionsPresenter.RouteDisplay() {
          @Override
          public void show(@NonNull TransitItinerary itinerary)
          {
            mDisplay.add("show " + itinerary.getId());
          }

          @Override
          public void clear()
          {
            mDisplay.add("clear");
          }
        }, () -> ConnectionsState.ViewMode.LIST, mSaved);
    mPresenter.attach(mRendered::add);
  }

  private ConnectionsState state()
  {
    return mRendered.get(mRendered.size() - 1);
  }

  /** Searches and finds a walk-only connection and the Bonn connection (index 1). */
  private void search()
  {
    mPresenter.search(FROM, TO);
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("other", 0), connection()));
  }

  private void completeAlternatives()
  {
    mProvider.lastPlan().complete(plan(null, null, firstAlternative("16", "09:09"), firstAlternative("16", "09:22"),
                                       firstAlternative("63", "09:39")));
  }

  private int chip(@NonNull String departure)
  {
    final List<AlternativesItem.Chip> chips = state().getAlternatives().getChips();
    for (int i = 0; i < chips.size(); ++i)
      if (chips.get(i).getDeparture().equals(departure))
        return i;
    throw new AssertionError("No chip " + departure);
  }

  @Test
  public void nothingIsRequestedWithoutATap()
  {
    search();
    mPresenter.select(1);
    assertEquals(1, mProvider.mPlanRequests.size());
    assertNull(state().getAlternatives());
    assertTrue(state().canChooseAlternatives());
  }

  @Test
  public void detailShowsChipsUnderTheRide()
  {
    search();
    mPresenter.select(1);
    mPresenter.toggleAlternatives(1);

    AlternativesItem alternatives = state().getAlternatives();
    assertNotNull(alternatives);
    assertTrue(alternatives.isLoading());
    assertEquals(1, alternatives.getConnection());
    assertEquals(1, alternatives.getLegIndex());
    assertEquals(LegAlternatives.Position.FIRST, alternatives.getPosition());
    assertEquals("ICE 1022", alternatives.getNextLine());
    assertEquals("7 min", alternatives.getTransfer());
    assertEquals(2, mProvider.mPlanRequests.size());
    assertEquals(FROM, mProvider.lastRequest().getFrom());

    completeAlternatives();
    alternatives = state().getAlternatives();
    assertFalse(alternatives.isLoading());
    assertFalse(alternatives.isEmpty());
    assertEquals(4, alternatives.getChips().size());
    assertEquals("09:29", alternatives.getSelected().getDeparture());
    assertTrue(alternatives.getSelected().isCurrent());
    assertEquals(3, alternatives.getConnectingCount());
    assertEquals(LegAlternatives.Status.TIGHT, alternatives.getSelected().getStatus());
    assertEquals(1, alternatives.getSelected().getBufferMinutes());

    // Tapping the ride again hides them.
    mPresenter.toggleAlternatives(1);
    assertNull(state().getAlternatives());
  }

  @Test
  public void choosingAChipChangesHeaderMapAndSaving()
  {
    search();
    mPresenter.select(1);
    mPresenter.toggleAlternatives(1);
    completeAlternatives();
    final String before = state().getSelected().getDeparture();
    mDisplay.clear();

    mPresenter.chooseAlternative(chip("09:22"));

    final ItineraryItem selected = state().getSelected();
    assertNotEquals(before, selected.getDeparture());
    assertEquals("09:09", selected.getDeparture());
    assertNotNull(selected.getChange());
    assertFalse(selected.getChange().isReplanned());
    assertEquals(LegAlternatives.Position.FIRST, selected.getChange().getPosition());
    assertEquals(1, selected.getChange().getRideNumber());
    assertEquals(0, selected.getChange().getDelayMinutes());
    assertEquals("09:22", state().getAlternatives().getSelected().getDeparture());
    // The legs show the new tram and walk.
    assertEquals("09:09", state().getLegs().get(0).getDeparture());
    assertEquals("16", state().getLegs().get(1).getBadge().getText());
    assertEquals(1, mDisplay.size());
    assertTrue(mDisplay.get(0).startsWith("show found|1:"));
    // The list shows the changed connection too.
    assertEquals("09:09", state().getItems().get(1).getDeparture());

    // Saving saves the changed connection.
    mPresenter.toggleSaved();
    assertEquals(1, mSaved.getAll().size());
    assertEquals(at("09:09"), mSaved.getAll().get(0).getItinerary().getStartTime());
    assertTrue(state().isSelectedSaved());

    // Undo: the found connection again, also on the map.
    mPresenter.undoChange();
    assertNull(state().getSelected().getChange());
    assertEquals(before, state().getSelected().getDeparture());
    assertEquals("show found", mDisplay.get(mDisplay.size() - 1));
    assertFalse(state().isSelectedSaved());
  }

  @Test
  public void missedConnectionIsReplanned()
  {
    search();
    mPresenter.select(1);
    mPresenter.toggleAlternatives(1);
    completeAlternatives();
    mPresenter.chooseAlternative(chip("09:39"));
    assertTrue(state().getAlternatives().isReplanning());
    assertTrue(state().getAlternatives().getChips().get(chip("09:39")).isPending());
    assertEquals(TO, mProvider.lastRequest().getTo());

    mProvider.lastPlan().complete(plan(null, null, rest("10:07")));
    final ChangeItem change = state().getSelected().getChange();
    assertTrue(change.isReplanned());
    assertEquals("ICE 1022", change.getMissedLine());
    assertEquals("10:32", change.getArrival());
    assertEquals(24, change.getDelayMinutes());
    assertEquals(2, change.getRestLegIndex());
    assertFalse(state().getAlternatives().isReplanning());
    assertEquals("10:32", state().getSelected().getArrival());
  }

  @Test
  public void restErrorIsShownInTheChipRow()
  {
    search();
    mPresenter.select(1);
    mPresenter.toggleAlternatives(1);
    completeAlternatives();
    mPresenter.chooseAlternative(chip("09:39"));
    mProvider.lastPlan().complete(plan(null, null));
    assertEquals(TransitError.NO_RESULTS, state().getAlternatives().getRestError());
    assertNull(state().getSelected().getChange());
  }

  @Test
  public void listOpensTheFirstRideOfACard()
  {
    search();
    mPresenter.toggleAlternatives(1, 1);
    final AlternativesItem alternatives = state().getAlternatives();
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertEquals(1, alternatives.getConnection());
    completeAlternatives();
    mPresenter.chooseAlternative(chip("09:22"));
    assertEquals("09:09", state().getItems().get(1).getDeparture());
    assertNotNull(state().getItems().get(1).getChange());
    assertNull(state().getItems().get(0).getChange());
    // The map shows nothing in the list.
    assertFalse(mDisplay.contains("show found"));

    // Opening the connection shows the changed one; the chips of the list are closed.
    mPresenter.select(1);
    assertNull(state().getAlternatives());
    assertEquals("09:09", state().getSelected().getDeparture());

    // Back in the list the change stays; undo from the list.
    mPresenter.back();
    mPresenter.undoChange(1);
    assertNull(state().getItems().get(1).getChange());
  }

  @Test
  public void detailOfAnotherConnectionHidesTheChips()
  {
    search();
    mPresenter.toggleAlternatives(1, 1);
    mPresenter.select(0);
    assertNull(state().getAlternatives());
  }

  @Test
  public void invalidIndicesAreIgnored()
  {
    search();
    mPresenter.toggleAlternatives(5, 1);
    mPresenter.toggleAlternatives(-1, 1);
    // No detail view.
    mPresenter.toggleAlternatives(1);
    mPresenter.undoChange(7);
    mPresenter.undoChange();
    assertNull(state().getAlternatives());
    assertEquals(1, mProvider.mPlanRequests.size());
  }

  @Test
  public void closeAndBackForgetTheOpenRide()
  {
    search();
    mPresenter.select(1);
    mPresenter.toggleAlternatives(1);
    mPresenter.closeAlternatives();
    assertNull(state().getAlternatives());
    mPresenter.toggleAlternatives(1);
    mPresenter.back();
    assertNull(state().getAlternatives());
    // A late answer changes nothing.
    completeAlternatives();
    assertNull(state().getAlternatives());
  }

  @Test
  public void newSearchForgetsChanges()
  {
    search();
    mPresenter.toggleAlternatives(1, 1);
    completeAlternatives();
    mPresenter.chooseAlternative(chip("09:22"));
    search();
    assertNull(state().getAlternatives());
    assertNull(state().getItems().get(1).getChange());
  }

  @Test
  public void savedConnectionsAreNotChanged()
  {
    search();
    mPresenter.select(1);
    mPresenter.toggleSaved();
    mPresenter.showSaved(mSaved.getAll().get(0));
    final int requests = mProvider.mPlanRequests.size();
    mPresenter.toggleAlternatives(1);
    assertFalse(state().canChooseAlternatives());
    assertNull(state().getAlternatives());
    assertEquals(requests, mProvider.mPlanRequests.size());
  }
}
