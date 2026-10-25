package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.departure;
import static app.organicmaps.sdk.transit.TransitTestData.departures;
import static app.organicmaps.sdk.transit.TransitTestData.place;
import static app.organicmaps.sdk.transit.TransitTestData.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.TransitMode;
import app.organicmaps.sdk.transit.TransitServiceException;
import app.organicmaps.sdk.transit.provider.TransitProviderRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class TransitDeparturesPresenterTest
{
  private final FakeTransitProvider mProvider = new FakeTransitProvider();
  private final List<DeparturesState> mRendered = new ArrayList<>();
  private TransitConsent.State mConsent = TransitConsent.State.GRANTED;
  private TransitDeparturesPresenter mPresenter;

  @Before
  public void setUp()
  {
    final TransitProviderRegistry registry =
        new TransitProviderRegistry(Runnable::run, () -> 0L, () -> mConsent == TransitConsent.State.GRANTED);
    registry.register(mProvider, TransitProviderRegistry.PRIORITY_DEFAULT);
    mPresenter = new TransitDeparturesPresenter(
        registry, () -> mConsent, new TransitItemFactory(Fixtures.formatter()), () -> t("2026-10-25T16:00:00Z"));
    mPresenter.attach(mRendered::add);
  }

  private DeparturesState state()
  {
    return mRendered.get(mRendered.size() - 1);
  }

  private static TransitDepartures alexanderplatz()
  {
    return departures(
        "S+U Alexanderplatz",
        departure(TransitMode.SUBURBAN, "S7", "S Potsdam Hauptbahnhof",
                  place("S+U Alexanderplatz")
                      .departure(t("2026-10-25T16:03:00Z"), t("2026-10-25T16:02:00Z"))
                      .track("2", "2")
                      .build(),
                  true, false, "816da6"),
        departure(TransitMode.TRAM, "M4", "S Hackescher Markt",
                  place("S+U Alexanderplatz").departure(t("2026-10-25T16:04:00Z"), t("2026-10-25T16:04:00Z")).build(),
                  false, true, null));
  }

  @Test
  public void hiddenUntilLoaded()
  {
    assertEquals(DeparturesState.Status.HIDDEN, state().getStatus());
  }

  @Test
  public void asksForConsentFirst()
  {
    mConsent = TransitConsent.State.UNDECIDED;
    mPresenter.load(52.52, 13.41);
    assertEquals(DeparturesState.Status.CONSENT_REQUIRED, state().getStatus());
    assertTrue(mProvider.mDepartureRequests.isEmpty());
    assertEquals("Fake transit", state().getProviderName());

    mConsent = TransitConsent.State.GRANTED;
    mPresenter.onConsentChanged();
    assertEquals(DeparturesState.Status.LOADING, state().getStatus());
    assertEquals(1, mProvider.mDepartureRequests.size());
  }

  @Test
  public void declinedHidesSection()
  {
    mConsent = TransitConsent.State.DECLINED;
    mPresenter.load(52.52, 13.41);
    assertEquals(DeparturesState.Status.HIDDEN, state().getStatus());
    assertTrue(mProvider.mDepartureRequests.isEmpty());
  }

  @Test
  public void showsDepartures()
  {
    mPresenter.load(52.52, 13.41);
    assertEquals(DeparturesState.Status.LOADING, state().getStatus());
    assertEquals(52.52, mProvider.mDepartureRequests.get(0)[0], 1e-9);
    assertEquals(TransitDeparturesPresenter.LIMIT, (int) mProvider.mDepartureRequests.get(0)[2]);

    mProvider.mDepartureResults.get(0).complete(alexanderplatz());
    final DeparturesState state = state();
    assertEquals(DeparturesState.Status.CONTENT, state.getStatus());
    assertEquals("S+U Alexanderplatz", state.getStopName());
    assertEquals("17:00", state.getUpdatedAt());
    assertEquals(2, state.getItems().size());
    assertEquals("S7", state.getItems().get(0).getBadge().getText());
    assertEquals("17:03", state.getItems().get(0).getTime());
    assertEquals("+1", state.getItems().get(0).getDelay().getText());
    assertEquals("Pl. 2", state.getItems().get(0).getTrack());
    assertTrue(state.getItems().get(1).isCancelled());
    assertEquals("https://example.org/sources", state.getAttributionUrl());
  }

  @Test
  public void samePlaceIsNotRequestedTwice()
  {
    mPresenter.load(52.52, 13.41);
    mProvider.mDepartureResults.get(0).complete(alexanderplatz());
    mPresenter.load(52.52, 13.41);
    assertEquals(1, mProvider.mDepartureRequests.size());
    assertEquals(DeparturesState.Status.CONTENT, state().getStatus());
  }

  @Test
  public void refreshKeepsContentAndBypassesCache()
  {
    mPresenter.load(52.52, 13.41);
    mProvider.mDepartureResults.get(0).complete(alexanderplatz());
    mPresenter.refresh();
    assertEquals(DeparturesState.Status.CONTENT, state().getStatus());
    assertTrue(state().isRefreshing());
    assertEquals(2, state().getItems().size());
    assertEquals(2, mProvider.mDepartureRequests.size());
    mProvider.mDepartureResults.get(1).complete(departures("S+U Alexanderplatz"));
    assertEquals(DeparturesState.Status.EMPTY, state().getStatus());
    assertFalse(state().isRefreshing());
  }

  @Test
  public void refreshWithoutPlaceDoesNothing()
  {
    mPresenter.refresh();
    assertTrue(mProvider.mDepartureRequests.isEmpty());
  }

  @Test
  public void errors()
  {
    mPresenter.load(52.52, 13.41);
    mProvider.mDepartureResults.get(0).fail(
        new TransitServiceException(TransitServiceException.Kind.NETWORK, 0, null, "offline", null));
    assertEquals(DeparturesState.Status.ERROR, state().getStatus());
    assertEquals(TransitError.NETWORK, state().getError());

    // "Try again" is the refresh action.
    mPresenter.refresh();
    assertEquals(DeparturesState.Status.LOADING, state().getStatus());
    mProvider.mDepartureResults.get(1).fail(TransitServiceException.noResults("no stop"));
    assertEquals(DeparturesState.Status.EMPTY, state().getStatus());
    assertEquals(TransitError.NO_RESULTS, state().getError());
  }

  @Test
  public void staleResultIsIgnored()
  {
    mPresenter.load(52.52, 13.41);
    mPresenter.load(52.50, 13.30);
    mProvider.mDepartureResults.get(0).complete(alexanderplatz());
    assertEquals(DeparturesState.Status.LOADING, state().getStatus());
    mProvider.mDepartureResults.get(1).complete(departures("Other"));
    assertEquals(DeparturesState.Status.EMPTY, state().getStatus());
    assertEquals("Other", state().getStopName());
  }

  @Test
  public void clearHidesAndDropsRunningRequest()
  {
    mPresenter.load(52.52, 13.41);
    mPresenter.clear();
    assertEquals(DeparturesState.Status.HIDDEN, state().getStatus());
    mProvider.mDepartureResults.get(0).complete(alexanderplatz());
    assertEquals(DeparturesState.Status.HIDDEN, state().getStatus());
    assertNull(mPresenter.getState().getStopName());
  }
}
