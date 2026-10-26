package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.plan;
import static app.organicmaps.sdk.transit.TransitTestData.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitServiceException;
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

public class TransitSheetTest
{
  private static final int HALF = 400;
  private static final int MAX = 1000;
  private static final float FLING = 1000;
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.525, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);
  private static final Instant NOW = t("2026-10-25T16:00:00Z");

  @Rule
  public final TemporaryFolder mFolder = new TemporaryFolder();

  private final ManualScheduler mScheduler = new ManualScheduler();
  private final FakeTransitProvider mProvider = new FakeTransitProvider();
  private final List<ConnectionsState> mRendered = new ArrayList<>();
  private TransitConnectionsPresenter mPresenter;

  @Before
  public void setUp()
  {
    final TransitProviderRegistry registry = new TransitProviderRegistry(Runnable::run, () -> 0L, () -> true);
    registry.register(mProvider, TransitProviderRegistry.PRIORITY_DEFAULT);
    final SavedConnectionsStore saved =
        new SavedConnectionsStore(new File(mFolder.getRoot(), "saved.json"), Runnable::run, () -> NOW);
    mPresenter =
        new TransitConnectionsPresenter(registry, app.organicmaps.sdk.transit.TransitRoutingOptions::defaults,
                                        new TransitItemFactory(Fixtures.formatter()),
                                        new Debouncer(mScheduler, TransitConnectionsPresenter.DEBOUNCE_MS),
                                        () -> NOW, new FakeRouteDisplay(), () -> ConnectionsState.ViewMode.LIST, saved);
    mPresenter.attach(mRendered::add);
  }

  private ConnectionsState state()
  {
    return mRendered.get(mRendered.size() - 1);
  }

  @Test
  public void slowDragsEndInTheNearestState()
  {
    assertEquals(TransitSheet.State.COLLAPSED, TransitSheet.settle(0, HALF, MAX, 0, FLING));
    assertEquals(TransitSheet.State.COLLAPSED, TransitSheet.settle(HALF / 2 - 1, HALF, MAX, 0, FLING));
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(HALF / 2, HALF, MAX, 0, FLING));
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(HALF, HALF, MAX, 0, FLING));
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(699, HALF, MAX, 0, FLING));
    assertEquals(TransitSheet.State.EXPANDED, TransitSheet.settle(700, HALF, MAX, 0, FLING));
    assertEquals(TransitSheet.State.EXPANDED, TransitSheet.settle(MAX, HALF, MAX, 0, FLING));
  }

  @Test
  public void flingsMoveToTheNextState()
  {
    // Downwards (positive): from above half to half, from half or below to collapsed.
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(900, HALF, MAX, 2 * FLING, FLING));
    assertEquals(TransitSheet.State.COLLAPSED, TransitSheet.settle(HALF, HALF, MAX, 2 * FLING, FLING));
    assertEquals(TransitSheet.State.COLLAPSED, TransitSheet.settle(350, HALF, MAX, 2 * FLING, FLING));
    // Upwards.
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(50, HALF, MAX, -2 * FLING, FLING));
    assertEquals(TransitSheet.State.EXPANDED, TransitSheet.settle(HALF, HALF, MAX, -2 * FLING, FLING));
    assertEquals(TransitSheet.State.EXPANDED, TransitSheet.settle(450, HALF, MAX, -2 * FLING, FLING));
    // Slower than a fling: the nearest state.
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(450, HALF, MAX, -FLING, FLING));
  }

  @Test
  public void shortContentHasTwoStates()
  {
    // E.g. two connections: the half sheet shows everything, there is no expanded state.
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(300, 300, 300, -2 * FLING, FLING));
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(300, 300, 300, 0, FLING));
    assertEquals(TransitSheet.State.COLLAPSED, TransitSheet.settle(300, 300, 300, 2 * FLING, FLING));
    assertEquals(TransitSheet.State.COLLAPSED, TransitSheet.settle(100, 300, 300, 0, FLING));
    // A half height above the maximum (e.g. landscape) is the maximum.
    assertEquals(TransitSheet.State.HALF, TransitSheet.settle(250, 400, 300, 0, FLING));
  }

  @Test
  public void bodyHeights()
  {
    assertEquals(0, TransitSheet.bodyHeight(TransitSheet.State.COLLAPSED, HALF, MAX));
    assertEquals(HALF, TransitSheet.bodyHeight(TransitSheet.State.HALF, HALF, MAX));
    assertEquals(MAX, TransitSheet.bodyHeight(TransitSheet.State.EXPANDED, HALF, MAX));
    assertEquals(300, TransitSheet.bodyHeight(TransitSheet.State.HALF, HALF, 300));
  }

  @Test
  public void summaryOfTheList()
  {
    assertNull(TransitSheet.summary(state(), new FakeTexts()));

    mPresenter.search(HBF, ALEX);
    assertEquals("Searching · Depart now", TransitSheet.summary(state(), new FakeTexts()));

    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 29), Fixtures.s5("b", 39), Fixtures.s5("c", 49)));
    assertEquals("3 connections · Depart now", TransitSheet.summary(state(), new FakeTexts()));

    mPresenter.setTime(t("2026-10-25T17:00:00Z"), false);
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 29)));
    assertEquals("1 connection · Depart 18:00", TransitSheet.summary(state(), new FakeTexts()));
  }

  @Test
  public void summaryWithoutConnectionsOrWithError()
  {
    mPresenter.search(HBF, ALEX);
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    mProvider.lastPlan().complete(plan(null, null));
    assertEquals("No connections", TransitSheet.summary(state(), new FakeTexts()));

    mPresenter.setTime(t("2026-10-25T17:00:00Z"), false);
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    mProvider.lastPlan().fail(new TransitServiceException(TransitServiceException.Kind.NETWORK, 0, null, "x", null));
    // The view shows the error text.
    assertNull(TransitSheet.summary(state(), new FakeTexts()));
  }

  @Test
  public void summaryOfAConnection()
  {
    mPresenter.search(HBF, ALEX);
    mScheduler.advance(TransitConnectionsPresenter.DEBOUNCE_MS);
    mProvider.lastPlan().complete(plan(null, null, Fixtures.s5("a", 29)));
    mPresenter.select(0);
    // Times of the S5 example (planned 17:29, walk 2 min first), with its duration and transfers.
    assertEquals(TransitSheet.title(state().getSelected()), TransitSheet.summary(state(), new FakeTexts()));
    assertEquals(state().getSelected().getDeparture() + " – " + state().getSelected().getArrival() + " · "
                     + state().getSelected().getDuration() + " · direct",
                 TransitSheet.summary(state(), new FakeTexts()));
  }

  private static final class FakeRouteDisplay implements TransitConnectionsPresenter.RouteDisplay
  {
    @Override
    public void show(@androidx.annotation.NonNull app.organicmaps.sdk.transit.TransitItinerary itinerary)
    {}

    @Override
    public void clear()
    {}
  }
}
