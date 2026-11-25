package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.plan;
import static app.organicmaps.transit.LegFixtures.at;
import static app.organicmaps.transit.LegFixtures.connection;
import static app.organicmaps.transit.LegFixtures.firstAlternative;
import static app.organicmaps.transit.LegFixtures.rest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitMode;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.TransitServiceException;
import app.organicmaps.sdk.transit.TransitTestData;
import app.organicmaps.sdk.transit.provider.TransitProviderRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class LegChoicesTest
{
  private static final TransitLocation ORIGIN = TransitLocation.fromCoordinates(50.7068, 7.1406);
  private static final TransitLocation DESTINATION = TransitLocation.fromCoordinates(50.9430, 6.9589);

  private final FakeTransitProvider mProvider = new FakeTransitProvider();
  private final List<String> mChanged = new ArrayList<>();
  private boolean mOnline = true;
  private TransitProviderRegistry mRegistry;
  private LegChoices mChoices;
  private TransitItinerary mConnection;

  @Before
  public void setUp()
  {
    mRegistry = new TransitProviderRegistry(Runnable::run, () -> 0L, () -> mOnline);
    mRegistry.register(mProvider, TransitProviderRegistry.PRIORITY_DEFAULT);
    mChoices = new LegChoices(mRegistry, TransitRoutingOptions::defaults, () -> at("08:00"), new LegChoices.Host() {
      @Nullable
      @Override
      public String providerId()
      {
        return "fake";
      }

      @Nullable
      @Override
      public TransitLocation origin()
      {
        return ORIGIN;
      }

      @Nullable
      @Override
      public TransitLocation destination()
      {
        return DESTINATION;
      }

      @Override
      public void onChanged(@NonNull String key)
      {
        mChanged.add(key);
      }
    });
    mConnection = connection();
  }

  private void openFirstRideWithResults()
  {
    mChoices.toggle(mConnection, 1);
    mProvider.lastPlan().complete(plan(null, null, firstAlternative("16", "09:09"), firstAlternative("16", "09:22"),
                                       firstAlternative("63", "09:39"), firstAlternative("16", "09:42")));
  }

  private int index(@NonNull String departure)
  {
    final List<LegAlternatives.Option> options = mChoices.getSession().getOptions();
    for (int i = 0; i < options.size(); ++i)
      if (options.get(i).getDeparture().equals(at(departure)))
        return i;
    throw new AssertionError("No option at " + departure);
  }

  @Test
  public void nothingIsRequestedBeforeARideIsOpened()
  {
    assertTrue(mProvider.mPlanRequests.isEmpty());
    assertNull(mChoices.getSession());
    assertSame(mConnection, mChoices.current(mConnection));
    assertNull(mChoices.getChange(mConnection));
    assertEquals(-1, mChoices.openLegIndex(mConnection));
    assertNull(mChoices.selectedOption());
  }

  @Test
  public void openingARideSendsOneDirectRequest()
  {
    mChoices.toggle(mConnection, 1);
    assertEquals(1, mProvider.mPlanRequests.size());
    assertEquals(Integer.valueOf(0), mProvider.lastRequest().getOptions().getMaxTransfers());
    assertEquals(ORIGIN, mProvider.lastRequest().getFrom());
    final LegChoices.Session session = mChoices.getSession();
    assertTrue(session.isLoading());
    assertEquals(TransitItemFactory.key(mConnection), session.getKey());
    assertEquals(1, mChoices.openLegIndex(mConnection));
    assertEquals(1, mChanged.size());

    mProvider.lastPlan().complete(plan(null, null, firstAlternative("16", "09:22")));
    assertFalse(session.isLoading());
    assertEquals(2, session.getOptions().size());
    assertTrue(mChoices.selectedOption().isCurrent());
    assertEquals(2, mChanged.size());
  }

  @Test
  public void walksCannotBeOpened()
  {
    mChoices.toggle(mConnection, 0);
    assertNull(mChoices.getSession());
    assertTrue(mProvider.mPlanRequests.isEmpty());
  }

  @Test
  public void togglingClosesAndTheCacheAnswersAgain()
  {
    openFirstRideWithResults();
    mChoices.toggle(mConnection, 1);
    assertNull(mChoices.getSession());

    mChoices.toggle(mConnection, 1);
    assertEquals(1, mProvider.mPlanRequests.size());
    assertEquals(1, mChoices.getRequestCount());
    assertFalse(mChoices.getSession().isLoading());
    assertEquals(5, mChoices.getSession().getOptions().size());
  }

  @Test
  public void anotherRideIsRequestedSeparately()
  {
    openFirstRideWithResults();
    mChoices.toggle(mConnection, 3);
    assertEquals(2, mProvider.mPlanRequests.size());
    assertEquals(TransitLocation.fromStopId("bhf"), mProvider.lastRequest().getFrom());
    assertEquals(3, mChoices.openLegIndex(mConnection));
  }

  @Test
  public void resultsAfterClosingAreIgnored()
  {
    mChoices.toggle(mConnection, 1);
    mChoices.close(true);
    final int notifications = mChanged.size();
    mProvider.lastPlan().complete(plan(null, null, firstAlternative("16", "09:22")));
    assertNull(mChoices.getSession());
    assertEquals(notifications, mChanged.size());
    // Not cached here: opening again asks again (the registry's cache answers).
    mChoices.toggle(mConnection, 1);
    assertEquals(2, mChoices.getRequestCount());
    assertEquals(2, mChoices.getSession().getOptions().size());
  }

  @Test
  public void resultsForAnotherRideAreIgnored()
  {
    mChoices.toggle(mConnection, 1);
    mChoices.toggle(mConnection, 3);
    mProvider.mPlanResults.get(0).complete(plan(null, null, firstAlternative("16", "09:22")));
    assertTrue(mChoices.getSession().isLoading());
    assertEquals(3, mChoices.getSession().getRide().getLegIndex());
  }

  @Test
  public void errorsAreShownAndNotCached()
  {
    mChoices.toggle(mConnection, 1);
    mProvider.lastPlan().fail(
        new TransitServiceException(TransitServiceException.Kind.NETWORK, 0, null, "offline", null));
    assertFalse(mChoices.getSession().isLoading());
    assertEquals(TransitError.NETWORK, mChoices.getSession().getError());
    assertTrue(mChoices.getSession().getOptions().isEmpty());

    mChoices.toggle(mConnection, 1);
    mChoices.toggle(mConnection, 1);
    assertEquals(2, mProvider.mPlanRequests.size());
  }

  @Test
  public void offlineIsAnErrorWithoutRequest()
  {
    mOnline = false;
    mChoices.toggle(mConnection, 1);
    assertTrue(mProvider.mPlanRequests.isEmpty());
    assertEquals(TransitError.DISABLED, mChoices.getSession().getError());
  }

  @Test
  public void missingPlacesAreNoResults()
  {
    final TransitItinerary nowhere = TransitTestData.itinerary(
        "n", TransitTestData.leg(TransitMode.BUS)
                 .from(TransitTestData.place("A").at(Double.NaN, Double.NaN).build())
                 .to(TransitTestData.place("B").at(Double.NaN, Double.NaN).build())
                 .times(at("09:00"), at("09:10"))
                 .build());
    final LegChoices choices =
        new LegChoices(mRegistry, TransitRoutingOptions::defaults, () -> at("08:00"), new LegChoices.Host() {
          @Nullable
          @Override
          public String providerId()
          {
            return null;
          }

          @Nullable
          @Override
          public TransitLocation origin()
          {
            return null;
          }

          @Nullable
          @Override
          public TransitLocation destination()
          {
            return null;
          }

          @Override
          public void onChanged(@NonNull String key)
          {}
        });
    choices.toggle(nowhere, 0);
    assertEquals(TransitError.NO_RESULTS, choices.getSession().getError());
    assertTrue(mProvider.mPlanRequests.isEmpty());
  }

  @Test
  public void choosingAConnectingDepartureChangesTheConnection()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:22"));

    final TransitItinerary changed = mChoices.current(mConnection);
    assertNotSame(mConnection, changed);
    assertEquals(at("09:09"), changed.getStartTime());
    assertEquals(mConnection.getEndTime(), changed.getEndTime());
    final LegChoices.Change change = mChoices.getChange(mConnection);
    assertSame(mConnection, change.getOriginal());
    assertFalse(change.isReplanned());
    assertEquals(-1, change.getRestLegIndex());
    assertNull(change.getMissedLeg());
    assertEquals(1, change.getLegIndex());
    // The ride stays open with the chosen departure selected.
    assertEquals(1, mChoices.openLegIndex(mConnection));
    assertEquals(at("09:22"), mChoices.selectedOption().getDeparture());
    assertEquals(1, mProvider.mPlanRequests.size());
  }

  @Test
  public void undoRestoresTheFoundConnection()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:22"));
    mChoices.undo(mConnection);
    assertSame(mConnection, mChoices.current(mConnection));
    assertNull(mChoices.getChange(mConnection));
    // Still open, the found ride is selected again.
    assertNotNull(mChoices.getSession());
    assertTrue(mChoices.selectedOption().isCurrent());
    // Nothing to undo.
    final int notifications = mChanged.size();
    mChoices.undo(mConnection);
    assertEquals(notifications, mChanged.size());
  }

  @Test
  public void choosingTheFoundDepartureUndoes()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:22"));
    mChoices.choose(index("09:29"));
    assertSame(mConnection, mChoices.current(mConnection));
    assertNull(mChoices.getChange(mConnection));
  }

  @Test
  public void choosingAnotherDepartureOfTheChangedRideReplacesTheChange()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:22"));
    mChoices.choose(index("09:09"));
    final LegChoices.Change change = mChoices.getChange(mConnection);
    assertSame(mConnection, change.getRide().getItinerary());
    assertEquals(at("08:56"), mChoices.current(mConnection).getStartTime());
    mChoices.undo(mConnection);
    assertSame(mConnection, mChoices.current(mConnection));
  }

  @Test
  public void reopeningTheChangedRideShowsItsAlternatives()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:22"));
    mChoices.close(true);
    assertEquals(-1, mChoices.openLegIndex(mConnection));

    mChoices.toggle(mConnection, 1);
    assertEquals(1, mProvider.mPlanRequests.size());
    assertSame(mConnection, mChoices.getSession().getRide().getItinerary());
    assertEquals(at("09:22"), mChoices.selectedOption().getDeparture());
  }

  @Test
  public void changesBuildOnEachOther()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:22"));
    final TransitItinerary first = mChoices.current(mConnection);

    // The ICE of the changed connection.
    mChoices.toggle(mConnection, 3);
    assertSame(first, mChoices.getSession().getRide().getItinerary());
    mProvider.lastPlan().complete(plan(
        null, null,
        TransitTestData.itinerary("re", LegFixtures.ride(TransitMode.REGIONAL_RAIL, "RE 5", "re", LegFixtures.bonnTrain(),
                                                         LegFixtures.koeln(), "09:52", "10:15"))));
    mChoices.choose(index("09:52"));
    final TransitItinerary both = mChoices.current(mConnection);
    assertEquals(at("09:09"), both.getStartTime());
    assertEquals(at("10:17"), both.getEndTime());
    assertSame(mConnection, mChoices.getChange(mConnection).getOriginal());

    // The ICE again: back to the first change only.
    mChoices.choose(index("09:47"));
    assertSame(first, mChoices.current(mConnection));
    assertEquals(3, mChoices.openLegIndex(mConnection));

    // Undo goes back to the found connection and closes the ride of the changed one.
    mChoices.undo(mConnection);
    assertSame(mConnection, mChoices.current(mConnection));
    assertNull(mChoices.getSession());
  }

  @Test
  public void missedConnectionSearchesTheRest()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:39"));

    // Nothing changes until the rest is found.
    assertSame(mConnection, mChoices.current(mConnection));
    final LegChoices.Session session = mChoices.getSession();
    assertNotNull(session.getPendingKey());
    assertEquals(2, mProvider.mPlanRequests.size());
    assertEquals(TransitLocation.fromStopId("bhf-tram"), mProvider.lastRequest().getFrom());
    assertEquals(DESTINATION, mProvider.lastRequest().getTo());
    assertEquals(at("09:49"), mProvider.lastRequest().getTime());
    // The user's options, transfers allowed.
    assertNull(mProvider.lastRequest().getOptions().getMaxTransfers());

    mProvider.lastPlan().complete(plan(null, null, rest("10:17"), rest("10:07")));
    assertNull(session.getPendingKey());
    final TransitItinerary changed = mChoices.current(mConnection);
    assertEquals(at("10:32"), changed.getEndTime());
    final LegChoices.Change change = mChoices.getChange(mConnection);
    assertTrue(change.isReplanned());
    assertEquals(2, change.getRestLegIndex());
    assertEquals("ICE 1022", change.getMissedLeg().getLineName());
    assertEquals(at("09:39"), mChoices.selectedOption().getDeparture());

    mChoices.undo(mConnection);
    assertSame(mConnection, mChoices.current(mConnection));
  }

  @Test
  public void missedConnectionWithoutRestKeepsTheConnection()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:39"));
    mProvider.lastPlan().complete(plan(null, null));
    assertSame(mConnection, mChoices.current(mConnection));
    assertEquals(TransitError.NO_RESULTS, mChoices.getSession().getRestError());
    assertNull(mChoices.getSession().getPendingKey());
  }

  @Test
  public void failedRestKeepsTheConnection()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:39"));
    mProvider.lastPlan().fail(new TransitServiceException(TransitServiceException.Kind.SERVER_ERROR, 503, null,
                                                          "busy", null));
    assertSame(mConnection, mChoices.current(mConnection));
    assertEquals(TransitError.SERVER, mChoices.getSession().getRestError());
  }

  @Test
  public void anotherChoiceCancelsTheSearchForTheRest()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:39"));
    final int restRequest = mProvider.mPlanResults.size() - 1;
    mChoices.choose(index("09:22"));
    assertNull(mChoices.getSession().getPendingKey());
    mProvider.mPlanResults.get(restRequest).complete(plan(null, null, rest("10:07")));
    // The connecting departure stays.
    assertEquals(mConnection.getEndTime(), mChoices.current(mConnection).getEndTime());
    assertFalse(mChoices.getChange(mConnection).isReplanned());
  }

  @Test
  public void undoCancelsTheSearchForTheRest()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:22"));
    mChoices.choose(index("09:39"));
    mChoices.undo(mConnection);
    mProvider.lastPlan().complete(plan(null, null, rest("10:07")));
    assertSame(mConnection, mChoices.current(mConnection));
  }

  @Test
  public void invalidChoicesAreIgnored()
  {
    mChoices.choose(0);
    openFirstRideWithResults();
    mChoices.choose(-1);
    mChoices.choose(99);
    assertSame(mConnection, mChoices.current(mConnection));
  }

  @Test
  public void resetForgetsEverything()
  {
    openFirstRideWithResults();
    mChoices.choose(index("09:22"));
    mChoices.reset();
    assertNull(mChoices.getSession());
    assertSame(mConnection, mChoices.current(mConnection));
    mChoices.toggle(mConnection, 1);
    assertEquals(2, mChoices.getRequestCount());
  }

  @Test
  public void cacheIsLimited()
  {
    for (int i = 0; i < LegChoices.MAX_CACHE_ENTRIES + 1; ++i)
    {
      final TransitItinerary other = TransitTestData.itinerary(
          "c" + i, LegFixtures.ride(TransitMode.BUS, "1", "t" + i, LegFixtures.olofPalme(), LegFixtures.bonnTram(),
                                    "09:00", "09:10"));
      mChoices.toggle(other, 0);
      mProvider.lastPlan().complete(plan(null, null));
    }
    final TransitItinerary first = TransitTestData.itinerary(
        "c0", LegFixtures.ride(TransitMode.BUS, "1", "t0", LegFixtures.olofPalme(), LegFixtures.bonnTram(), "09:00",
                               "09:10"));
    final int requests = mProvider.mPlanRequests.size();
    mChoices.toggle(first, 0);
    // The registry's own cache answers, but LegChoices asked again.
    assertEquals(LegChoices.MAX_CACHE_ENTRIES + 2, mChoices.getRequestCount());
    assertEquals(requests, mProvider.mPlanRequests.size());
  }
}
