package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.plan;
import static app.organicmaps.sdk.transit.TransitTestData.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.TransitServiceException;
import app.organicmaps.sdk.transit.TransitTestData;
import app.organicmaps.sdk.transit.provider.TransitProviderRegistry;
import app.organicmaps.sdk.util.concurrency.Debouncer;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TransitConnectionsPresenterTest
{
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.525, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);
  private static final Instant NOW = t("2026-10-25T16:00:00Z");

  private final ManualScheduler mScheduler = new ManualScheduler();
  private final FakeTransitProvider mProvider = new FakeTransitProvider();
  private final List<ConnectionsState> mRendered = new ArrayList<>();
  private final List<String> mDisplay = new ArrayList<>();
  private final List<Boolean> mFinished = new ArrayList<>();
  private boolean mOnline = true;
  private TransitRoutingOptions mOptions = TransitRoutingOptions.defaults();
  private ConnectionsState.ViewMode mDefaultView = ConnectionsState.ViewMode.LIST;
  private TransitProviderRegistry mRegistry;
  private SavedConnectionsStore mSaved;
  private TransitConnectionsPresenter mPresenter;

  @Rule
  public final TemporaryFolder mFolder = new TemporaryFolder();

  @Before
  public void setUp()
  {
    mSaved = new SavedConnectionsStore(new File(mFolder.getRoot(), "saved.json"), Runnable::run, () -> NOW);
    mRegistry = new TransitProviderRegistry(Runnable::run, () -> 0L, () -> mOnline);
    mRegistry.register(mProvider, TransitProviderRegistry.PRIORITY_DEFAULT);
    mPresenter = new TransitConnectionsPresenter(mRegistry,
                                                 ()
                                                     -> mOptions,
                                                 new TransitItemFactory(Fixtures.formatter()),
                                                 new Debouncer(mScheduler, TransitConnectionsPresenter.DEBOUNCE_MS),
                                                 () -> NOW, new TransitConnectionsPresenter.RouteDisplay() {
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
                                                 }, () -> mDefaultView, mSaved);
    mPresenter.attach(mRendered::add);
    mPresenter.setListener(mFinished::add);
  }

  private ConnectionsState state()
  {
    return mRendered.get(mRendered.size() - 1);
  }

  private void searchAndWait()
  {
    mPresenter.search(HBF, ALEX);
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
  }

  @Test
  public void initiallyNothingIsShown()
  {
    assertEquals(ConnectionsState.Screen.NONE, state().getScreen());
    assertEquals("Depart now", state().getTimeLabel());
    // The attribution is known before the first result.
    assertEquals("Fake transit", state().getProviderName());
    assertEquals("https://example.org/sources", state().getAttributionUrl());
  }

  @Test
  public void searchIsDebounced()
  {
    mPresenter.search(HBF, ALEX);
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertTrue(state().isLoading());
    assertTrue(mProvider.mPlanRequests.isEmpty());

    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS - 1);
    assertTrue(mProvider.mPlanRequests.isEmpty());
    mScheduler.advance(1);
    assertEquals(1, mProvider.mPlanRequests.size());
    assertEquals(HBF, mProvider.lastRequest().getFrom());
    assertEquals(ALEX, mProvider.lastRequest().getTo());
    assertNull(mProvider.lastRequest().getTime());
    assertFalse(mProvider.lastRequest().isArriveBy());
    assertNull(mProvider.lastRequest().getPageCursor());
    assertSame(mOptions, mProvider.lastRequest().getOptions());
  }

  @Test
  public void quickChangesSendOneRequest()
  {
    mPresenter.search(HBF, ALEX);
    mScheduler.advance(100);
    mPresenter.search(ALEX, HBF);
    mScheduler.advance(100);
    mPresenter.setTime(t("2026-10-25T17:00:00Z"), false);
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    assertEquals(1, mProvider.mPlanRequests.size());
    assertEquals(ALEX, mProvider.lastRequest().getFrom());
    assertEquals(t("2026-10-25T17:00:00Z"), mProvider.lastRequest().getTime());
  }

  @Test
  public void showsConnections()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan("prev", "next", Fixtures.s5("a", 29), Fixtures.busAndSubway("b")));

    final ConnectionsState state = state();
    assertFalse(state.isLoading());
    assertNull(state.getError());
    assertFalse(state.isEmpty());
    assertEquals(2, state.getItems().size());
    assertEquals("a", state.getItems().get(0).getKey());
    assertEquals("17:29", state.getItems().get(0).getDeparture());
    assertTrue(state.canLoadEarlier());
    assertTrue(state.canLoadLater());
    assertEquals(Collections.singletonList(true), mFinished);
  }

  @Test
  public void emptyResult()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, "next"));
    assertTrue(state().isEmpty());
    assertFalse(state().canLoadEarlier());
    // Later connections may exist.
    assertTrue(state().canLoadLater());
  }

  @Test
  public void directAlternativesAreListedAfterTransit()
  {
    final TransitItinerary walk =
        TransitTestData.itinerary("walk", TransitTestData.leg(app.organicmaps.sdk.transit.TransitMode.WALK)
                                              .from(TransitTestData.place("START").build())
                                              .to(TransitTestData.place("END").build())
                                              .times(t("2026-10-25T16:00:00Z"), t("2026-10-25T16:40:00Z"))
                                              .build());
    searchAndWait();
    mProvider.lastPlan().complete(TransitTestData.planWithDirect(Collections.singletonList(Fixtures.s5("a", 0)),
                                                                 Collections.singletonList(walk)));
    assertEquals(2, state().getItems().size());
    assertFalse(state().getItems().get(0).isWalkOnly());
    assertTrue(state().getItems().get(1).isWalkOnly());

    mPresenter.select(1);
    assertEquals(Collections.singletonList("show walk"), mDisplay);
  }

  @Test
  public void staleResultsAreIgnored()
  {
    searchAndWait();
    mPresenter.search(ALEX, HBF);
    // The answer to the first query arrives after the second query started.
    mProvider.mPlanResults.get(0).complete(plan(null, null, Fixtures.s5("old", 0)));
    assertTrue(state().isLoading());
    assertTrue(state().getItems().isEmpty());
    assertTrue(mFinished.isEmpty());

    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("new", 0)));
    assertEquals("new", state().getItems().get(0).getKey());
  }

  @Test
  public void errorAndExplicitRetry()
  {
    searchAndWait();
    mProvider.lastPlan().fail(new TransitServiceException(TransitServiceException.Kind.NETWORK, 0, null, "x", null));
    assertEquals(TransitError.NETWORK, state().getError());
    assertFalse(state().isLoading());
    assertFalse(state().canLoadLater());
    assertEquals(Collections.singletonList(false), mFinished);
    // No automatic retry.
    mScheduler.advance(60_000);
    assertEquals(1, mProvider.mPlanRequests.size());

    mPresenter.retry();
    // Retry is a user action and is sent at once.
    assertEquals(2, mProvider.mPlanRequests.size());
    assertTrue(state().isLoading());
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    assertNull(state().getError());
    assertEquals(1, state().getItems().size());
  }

  @Test
  public void retryWithoutQueryDoesNothing()
  {
    mPresenter.retry();
    assertTrue(mProvider.mPlanRequests.isEmpty());
  }

  @Test
  public void withoutConsentNothingIsSent()
  {
    mOnline = false;
    searchAndWait();
    assertTrue(mProvider.mPlanRequests.isEmpty());
    assertEquals(TransitError.DISABLED, state().getError());
  }

  @Test
  public void timeSelection()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));

    mPresenter.setTime(t("2026-10-25T17:00:00Z"), true);
    assertEquals("Arrive by 18:00", state().getTimeLabel());
    assertTrue(state().isArriveBy());
    assertTrue(state().isLoading());
    assertTrue(state().getItems().isEmpty());
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    assertEquals(2, mProvider.mPlanRequests.size());
    assertTrue(mProvider.lastRequest().isArriveBy());
    assertEquals(t("2026-10-25T17:00:00Z"), mPresenter.getTime());

    // Setting the same time again does not query again.
    mPresenter.setTime(t("2026-10-25T17:00:00Z"), true);
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    assertEquals(2, mProvider.mPlanRequests.size());
  }

  @Test
  public void timeBeforeQueryOnlyUpdatesLabel()
  {
    mPresenter.setTime(t("2026-10-25T17:30:00Z"), false);
    assertEquals("Depart 18:30", state().getTimeLabel());
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    assertTrue(mProvider.mPlanRequests.isEmpty());
    // The time is used by the next query.
    searchAndWait();
    assertEquals(t("2026-10-25T17:30:00Z"), mProvider.lastRequest().getTime());
  }

  @Test
  public void optionsChangeQueriesAgain()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    mOptions = TransitRoutingOptions.builder().setMaxTransfers(1).build();
    mPresenter.onOptionsChanged();
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    assertEquals(2, mProvider.mPlanRequests.size());
    assertEquals(Integer.valueOf(1), mProvider.lastRequest().getOptions().getMaxTransfers());
  }

  @Test
  public void loadLaterAppendsWithoutDuplicates()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan("p1", "n1", Fixtures.s5("a", 0), Fixtures.s5("b", 10)));

    mPresenter.loadLater();
    assertTrue(state().isLoadingLater());
    assertEquals("n1", mProvider.lastRequest().getPageCursor());
    // Paging keeps the query.
    assertEquals(HBF, mProvider.lastRequest().getFrom());

    // A second tap while loading is ignored.
    mPresenter.loadLater();
    assertEquals(2, mProvider.mPlanRequests.size());

    mProvider.lastPlan().complete(plan("p2", "n2", Fixtures.s5("b", 10), Fixtures.s5("c", 20)));
    final ConnectionsState state = state();
    assertFalse(state.isLoadingLater());
    assertEquals(3, state.getItems().size());
    assertEquals("c", state.getItems().get(2).getKey());

    mPresenter.loadLater();
    assertEquals("n2", mProvider.lastRequest().getPageCursor());
  }

  @Test
  public void loadEarlierPrepends()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan("p1", "n1", Fixtures.s5("b", 10)));
    mPresenter.loadEarlier();
    assertTrue(state().isLoadingEarlier());
    assertEquals("p1", mProvider.lastRequest().getPageCursor());
    mProvider.lastPlan().complete(plan(null, "ignored", Fixtures.s5("a", 0)));
    assertEquals("a", state().getItems().get(0).getKey());
    assertEquals("b", state().getItems().get(1).getKey());
    assertFalse(state().canLoadEarlier());
    assertTrue(state().canLoadLater());
  }

  @Test
  public void selectionIsKeptWhenEarlierConnectionsArrive()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan("p1", null, Fixtures.s5("b", 10)));
    mPresenter.loadEarlier();
    mPresenter.select(0);
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    assertEquals("b", state().getSelected().getKey());
  }

  @Test
  public void pagingErrorKeepsList()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, "n1", Fixtures.s5("a", 0)));
    mPresenter.loadLater();
    mProvider.lastPlan().fail(
        new TransitServiceException(TransitServiceException.Kind.SERVER_ERROR, 503, null, "busy", null));
    assertEquals(TransitError.SERVER, state().getPagingError());
    assertNull(state().getError());
    assertEquals(1, state().getItems().size());
    assertTrue(state().canLoadLater());

    // "Try again" repeats the failed page.
    mPresenter.retry();
    assertNull(state().getPagingError());
    assertTrue(state().isLoadingLater());
    assertEquals(3, mProvider.mPlanRequests.size());
    assertEquals("n1", mProvider.lastRequest().getPageCursor());
  }

  @Test
  public void retryRepeatsFailedEarlierPage()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan("p1", "n1", Fixtures.s5("b", 10)));
    mPresenter.loadEarlier();
    mProvider.lastPlan().fail(new TransitServiceException(TransitServiceException.Kind.NETWORK, 0, null, "x", null));
    mPresenter.retry();
    assertTrue(state().isLoadingEarlier());
    assertEquals("p1", mProvider.lastRequest().getPageCursor());
  }

  @Test
  public void pagingWithoutCursorDoesNothing()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    mPresenter.loadEarlier();
    mPresenter.loadLater();
    assertEquals(1, mProvider.mPlanRequests.size());
  }

  @Test
  public void pagingResultOfOldQueryIsIgnored()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, "n1", Fixtures.s5("a", 0)));
    mPresenter.loadLater();
    final int pagingIndex = mProvider.mPlanResults.size() - 1;
    mPresenter.search(ALEX, HBF);
    mProvider.mPlanResults.get(pagingIndex).complete(plan(null, null, Fixtures.s5("late", 0)));
    assertTrue(state().getItems().isEmpty());
    assertFalse(state().isLoadingLater());
  }

  @Test
  public void detailViewAndMap()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0), Fixtures.busAndSubway("b")));

    mPresenter.select(1);
    ConnectionsState state = state();
    assertEquals(ConnectionsState.Screen.DETAIL, state.getScreen());
    assertEquals("b", state.getSelected().getKey());
    assertEquals(2, state.getLegs().size());
    assertEquals("b", mPresenter.getSelectedItinerary().getId());
    assertEquals(Collections.singletonList("show b"), mDisplay);

    assertTrue(mPresenter.back());
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertEquals(List.of("show b", "clear"), mDisplay);
    assertFalse(mPresenter.back());
    assertNull(mPresenter.getSelectedItinerary());
  }

  @Test
  public void invalidSelectionIsIgnored()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    mPresenter.select(5);
    mPresenter.select(-1);
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertTrue(mDisplay.isEmpty());
  }

  @Test
  public void toggleIntermediateStops()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    mPresenter.toggleStops(1);
    // Not in the detail view: ignored.
    mPresenter.select(0);
    assertFalse(state().getLegs().get(1).isExpanded());
    mPresenter.toggleStops(1);
    assertTrue(state().getLegs().get(1).isExpanded());
    mPresenter.toggleStops(1);
    assertFalse(state().getLegs().get(1).isExpanded());

    // A new selection starts collapsed.
    mPresenter.toggleStops(1);
    mPresenter.back();
    mPresenter.select(0);
    assertFalse(state().getLegs().get(1).isExpanded());
  }

  @Test
  public void newQueryClosesDetailAndClearsMap()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    mPresenter.select(0);
    mPresenter.search(ALEX, HBF);
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertEquals(List.of("show a", "clear"), mDisplay);
  }

  @Test
  public void clearForgetsQuery()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    mPresenter.select(0);
    mPresenter.clear();
    assertEquals(ConnectionsState.Screen.NONE, state().getScreen());
    assertFalse(mPresenter.hasQuery());
    assertEquals(List.of("show a", "clear"), mDisplay);

    // A pending debounced query is dropped as well.
    mPresenter.search(HBF, ALEX);
    mPresenter.clear();
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    assertEquals(1, mProvider.mPlanRequests.size());
  }

  @Test
  public void cachedQueryNeedsNoSecondRequest()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 0)));
    // The same query again (e.g. route rebuilt after rotating the device).
    searchAndWait();
    assertEquals(1, mProvider.mPlanRequests.size());
    assertEquals(1, state().getItems().size());
  }

  @Test
  public void detachedViewIsNotRendered()
  {
    final List<ConnectionsState> other = new ArrayList<>();
    final TransitConnectionsPresenter.View view = other::add;
    mPresenter.attach(view);
    assertEquals(1, other.size());
    mPresenter.detach(view);
    searchAndWait();
    assertEquals(1, other.size());
  }

  // View switching.

  @Test
  public void startsInDefaultView()
  {
    assertEquals(ConnectionsState.ViewMode.LIST, state().getViewMode());
    mDefaultView = ConnectionsState.ViewMode.GRID;
    searchAndWait();
    assertEquals(ConnectionsState.ViewMode.GRID, state().getViewMode());
    assertEquals(ConnectionsState.ViewMode.GRID, mPresenter.getViewMode());
  }

  @Test
  public void switchingViewNeedsNoRequest()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 29), Fixtures.busAndSubway("b")));
    mPresenter.setViewMode(ConnectionsState.ViewMode.GRID);
    assertEquals(ConnectionsState.ViewMode.GRID, state().getViewMode());
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertEquals(2, state().getItems().size());
    mPresenter.setViewMode(ConnectionsState.ViewMode.LIST);
    assertEquals(ConnectionsState.ViewMode.LIST, state().getViewMode());
    assertEquals(1, mProvider.mPlanRequests.size());
  }

  @Test
  public void settingTheSameViewTwiceRendersOnce()
  {
    mPresenter.setViewMode(ConnectionsState.ViewMode.GRID);
    final int rendered = mRendered.size();
    mPresenter.setViewMode(ConnectionsState.ViewMode.GRID);
    assertEquals(rendered, mRendered.size());
  }

  @Test
  public void backFromDetailReturnsToLastUsedView()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 29), Fixtures.busAndSubway("b")));
    mPresenter.setViewMode(ConnectionsState.ViewMode.GRID);
    mPresenter.select(1);
    assertEquals(ConnectionsState.Screen.DETAIL, state().getScreen());
    assertEquals("b", state().getSelected().getKey());
    assertTrue(mPresenter.back());
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertEquals(ConnectionsState.ViewMode.GRID, state().getViewMode());

    mPresenter.setViewMode(ConnectionsState.ViewMode.LIST);
    mPresenter.select(0);
    assertTrue(mPresenter.back());
    assertEquals(ConnectionsState.ViewMode.LIST, state().getViewMode());
  }

  @Test
  public void viewIsKeptForTheRunningSearch()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, "next", Fixtures.s5("a", 29)));
    mPresenter.setViewMode(ConnectionsState.ViewMode.GRID);

    mPresenter.loadLater();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("b", 39)));
    assertEquals(ConnectionsState.ViewMode.GRID, state().getViewMode());

    mPresenter.setTime(t("2026-10-25T17:00:00Z"), false);
    assertEquals(ConnectionsState.ViewMode.GRID, state().getViewMode());
    mPresenter.onOptionsChanged();
    assertEquals(ConnectionsState.ViewMode.GRID, state().getViewMode());
    // The same places again (e.g. the route is rebuilt).
    mPresenter.search(HBF, ALEX);
    assertEquals(ConnectionsState.ViewMode.GRID, state().getViewMode());
  }

  @Test
  public void newPlacesStartInDefaultView()
  {
    searchAndWait();
    mPresenter.setViewMode(ConnectionsState.ViewMode.GRID);
    mPresenter.search(ALEX, HBF);
    assertEquals(ConnectionsState.ViewMode.LIST, state().getViewMode());

    mPresenter.setViewMode(ConnectionsState.ViewMode.GRID);
    mPresenter.clear();
    assertEquals(ConnectionsState.ViewMode.LIST, state().getViewMode());
  }

  @Test
  public void changedDefaultAppliesUnlessSwitched()
  {
    searchAndWait();
    mDefaultView = ConnectionsState.ViewMode.GRID;
    mPresenter.onOptionsChanged();
    assertEquals(ConnectionsState.ViewMode.GRID, state().getViewMode());
    mPresenter.setViewMode(ConnectionsState.ViewMode.LIST);
    mDefaultView = ConnectionsState.ViewMode.GRID;
    mPresenter.onOptionsChanged();
    assertEquals(ConnectionsState.ViewMode.LIST, state().getViewMode());
  }

  @Test
  public void stateCarriesTheClock()
  {
    assertEquals(NOW, state().getNow());
  }

  // Saved connections.

  private void searchWithNamesAndSelect(@NonNull TransitItinerary itinerary)
  {
    mPresenter.search(HBF, ALEX, "Hauptbahnhof", "Alexanderplatz");
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    mProvider.lastPlan().complete(plan(null, null, itinerary));
    mPresenter.select(0);
  }

  @Test
  public void saveAndRemoveTheSelectedConnection()
  {
    searchWithNamesAndSelect(Fixtures.s5("a", 29));
    assertFalse(state().isSelectedSaved());
    assertFalse(state().isSavedView());

    mPresenter.toggleSaved();
    assertTrue(state().isSelectedSaved());
    final SavedConnection saved = mSaved.get("a");
    assertEquals("Hauptbahnhof", saved.getFromName());
    assertEquals("Alexanderplatz", saved.getToName());
    assertEquals(HBF, saved.getFrom());
    assertEquals(ALEX, saved.getTo());
    assertEquals(NOW, saved.getSavedAt());

    mPresenter.toggleSaved();
    assertFalse(state().isSelectedSaved());
    assertFalse(mSaved.contains("a"));
    // Nothing went online.
    assertEquals(1, mProvider.mPlanRequests.size());
  }

  @Test
  public void savingWithoutNamesUsesTheStops()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 29)));
    mPresenter.select(0);
    mPresenter.toggleSaved();
    assertEquals("S+U Berlin Hauptbahnhof", mSaved.get("a").getFromName());
    assertEquals("S+U Alexanderplatz", mSaved.get("a").getToName());
  }

  @Test
  public void savingInTheListDoesNothing()
  {
    searchAndWait();
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 29)));
    mPresenter.toggleSaved();
    assertEquals(0, mSaved.size());
  }

  @Test
  public void removalElsewhereIsRendered()
  {
    searchWithNamesAndSelect(Fixtures.s5("a", 29));
    mPresenter.toggleSaved();
    assertTrue(state().isSelectedSaved());
    // E.g. deleted in the list of saved connections.
    mSaved.remove("a");
    assertFalse(state().isSelectedSaved());
  }

  @NonNull
  private SavedConnection saved(@NonNull TransitItinerary itinerary)
  {
    return mSaved.save(itinerary, "Hauptbahnhof", "Alexanderplatz", HBF, ALEX);
  }

  @Test
  public void showSavedWithoutRequest()
  {
    mPresenter.showSaved(saved(Fixtures.s5("a", 29)));
    final ConnectionsState state = state();
    assertEquals(ConnectionsState.Screen.DETAIL, state.getScreen());
    assertTrue(state.isSavedView());
    assertTrue(state.isSelectedSaved());
    assertFalse(state.isPast());
    assertFalse(state.isLoading());
    assertTrue(state.getItems().isEmpty());
    // No "no connections found" for a saved connection.
    assertFalse(state.isEmpty());
    assertEquals("a", state.getSelected().getKey());
    assertEquals("17:29", state.getSelected().getDeparture());
    assertEquals(3, state.getLegs().size());
    assertEquals("So., 25.10.2026", state.getSelectedDate());
    assertTrue(mPresenter.isShowingSaved());
    assertEquals(List.of("show a"), mDisplay);
    // The route planner learns that the route is ready.
    assertEquals(List.of(true), mFinished);
    assertTrue(mProvider.mPlanRequests.isEmpty());
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    assertTrue(mProvider.mPlanRequests.isEmpty());
  }

  @Test
  public void pastSavedConnection()
  {
    // Arrives at 16:00 - 2 h + 17 min.
    mPresenter.showSaved(saved(Fixtures.s5("old", -120)));
    assertTrue(state().isPast());
  }

  @Test
  public void showSavedReplacesARunningSearch()
  {
    searchAndWait();
    mPresenter.showSaved(saved(Fixtures.s5("a", 29)));
    // The late result of the search is ignored.
    mProvider.lastPlan().complete(plan(null, null, Fixtures.busAndSubway("b")));
    assertTrue(state().isSavedView());
    assertEquals("a", state().getSelected().getKey());
    assertTrue(state().getItems().isEmpty());
  }

  @Test
  public void backFromSavedClosesIt()
  {
    mPresenter.showSaved(saved(Fixtures.s5("a", 29)));
    assertTrue(mPresenter.back());
    assertEquals(ConnectionsState.Screen.NONE, state().getScreen());
    assertFalse(mPresenter.hasQuery());
    assertFalse(mPresenter.isShowingSaved());
    assertEquals(List.of("show a", "clear"), mDisplay);
    // Still saved.
    assertTrue(mSaved.contains("a"));
  }

  @Test
  public void searchAgainFromSaved()
  {
    mPresenter.showSaved(saved(Fixtures.s5("old", -120)));
    mPresenter.searchAgain();
    // An explicit action: sent at once, departing now, between the saved places.
    assertEquals(1, mProvider.mPlanRequests.size());
    assertEquals(HBF, mProvider.lastRequest().getFrom());
    assertEquals(ALEX, mProvider.lastRequest().getTo());
    assertNull(mProvider.lastRequest().getTime());
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertFalse(state().isSavedView());
    assertEquals(List.of("show old", "clear"), mDisplay);

    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 29)));
    assertEquals(1, state().getItems().size());
    mPresenter.select(0);
    mPresenter.toggleSaved();
    // The names of the saved connection are kept for the new search.
    assertEquals("Hauptbahnhof", mSaved.get("a").getFromName());
  }

  @Test
  public void savedViewIgnoresListActions()
  {
    mPresenter.showSaved(saved(Fixtures.s5("a", 29)));
    mPresenter.select(0);
    mPresenter.loadLater();
    mPresenter.loadEarlier();
    mPresenter.retry();
    mPresenter.onOptionsChanged();
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    assertTrue(mProvider.mPlanRequests.isEmpty());
    assertTrue(state().isSavedView());
    // Stops can be expanded as in any detail view.
    mPresenter.toggleStops(1);
    assertTrue(state().getLegs().get(1).isExpanded());
  }

  @Test
  public void unsaveInSavedViewKeepsShowingIt()
  {
    mPresenter.showSaved(saved(Fixtures.s5("a", 29)));
    mPresenter.toggleSaved();
    assertFalse(mSaved.contains("a"));
    assertTrue(state().isSavedView());
    assertFalse(state().isSelectedSaved());
    mPresenter.toggleSaved();
    assertTrue(mSaved.contains("a"));
    assertEquals("Hauptbahnhof", mSaved.get("a").getFromName());
  }

  @Test
  public void newSearchLeavesSavedView()
  {
    mPresenter.showSaved(saved(Fixtures.s5("a", 29)));
    mPresenter.search(ALEX, HBF);
    assertFalse(state().isSavedView());
    assertEquals(ConnectionsState.Screen.LIST, state().getScreen());
    assertEquals(List.of("show a", "clear"), mDisplay);
  }
}
