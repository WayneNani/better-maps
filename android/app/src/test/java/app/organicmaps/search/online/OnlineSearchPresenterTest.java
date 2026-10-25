package app.organicmaps.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.online.OnlineSearchException;
import app.organicmaps.sdk.search.online.OnlineSearchProvider;
import app.organicmaps.sdk.search.online.OnlineSearchRequest;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import app.organicmaps.sdk.search.online.OnlineSearchService;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import app.organicmaps.sdk.util.concurrency.Debouncer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Test;

/** Flow of the online section: decision, consent, debounce, cache, duplicates, errors, outdated answers. */
public class OnlineSearchPresenterTest
{
  private static final Executor DIRECT = Runnable::run;
  private static final double[] BERLIN = {52.520008, 13.404954};
  private static final double[] MUNICH = {48.137, 11.575};
  private static final RegionInfo BERLIN_REGION = new RegionInfo("Germany_Berlin", "Berlin", true, 0);
  private static final RegionInfo BAVARIA_REGION =
      new RegionInfo("Germany_Free State of Bavaria_Upper Bavaria", "Upper Bavaria", false, 190_000_000);

  private static final OnlineSearchResult MARIENPLATZ = OnlineSearchResult.builder(48.137569, 11.57498)
                                                            .setName("Marienplatz")
                                                            .setOsm("W", 259055080)
                                                            .setTag("highway", "pedestrian")
                                                            .setPlaceType(OnlineSearchResult.PlaceType.STREET)
                                                            .setCity("München")
                                                            .build();
  private static final OnlineSearchResult MARIENPLATZ_STATION = OnlineSearchResult.builder(48.1371436, 11.5753989)
                                                                    .setName("Marienplatz")
                                                                    .setOsm("N", 2941687118L)
                                                                    .setTag("railway", "station")
                                                                    .setPlaceType(OnlineSearchResult.PlaceType.HOUSE)
                                                                    .build();
  private static final OnlineSearchResult CAFE_BERLIN = OnlineSearchResult.builder(52.5205, 13.4050)
                                                            .setName("Café Einstein")
                                                            .setOsm("N", 7)
                                                            .setPlaceType(OnlineSearchResult.PlaceType.HOUSE)
                                                            .build();

  /** Provider whose answers the test completes by hand. */
  private static final class FakeProvider implements OnlineSearchProvider
  {
    final List<OnlineSearchRequest> mRequests = new ArrayList<>();
    final List<AsyncResult<List<OnlineSearchResult>>> mPending = new ArrayList<>();

    @NonNull
    @Override
    public String getId()
    {
      return "photon";
    }

    @NonNull
    @Override
    public String getName()
    {
      return "Photon";
    }

    @NonNull
    @Override
    public String getAttributionUrl()
    {
      return "https://www.openstreetmap.org/copyright";
    }

    @NonNull
    @Override
    public AsyncResult<List<OnlineSearchResult>> search(@NonNull OnlineSearchRequest request)
    {
      mRequests.add(request);
      final AsyncResult<List<OnlineSearchResult>> result = new AsyncResult<>(DIRECT);
      mPending.add(result);
      return result;
    }

    @NonNull
    OnlineSearchRequest last()
    {
      return mRequests.get(mRequests.size() - 1);
    }

    void answer(int index, @NonNull OnlineSearchResult... results)
    {
      mPending.get(index).complete(Arrays.asList(results));
    }

    void answerLast(@NonNull OnlineSearchResult... results)
    {
      answer(mPending.size() - 1, results);
    }
  }

  /** Map regions: north of 50° Berlin (downloaded), south of it Upper Bavaria (not downloaded). */
  private static final class FakeRegions implements RegionInfo.Lookup
  {
    int mLookups;

    @Nullable
    @Override
    public RegionInfo regionAt(double lat, double lon)
    {
      ++mLookups;
      if (lat > 89)
        return null;
      return lat > 50 ? BERLIN_REGION : BAVARIA_REGION;
    }
  }

  private MapStore mStore;
  private OnlineSearchConsent mConsent;
  private FakeProvider mProvider;
  private OnlineSearchService mService;
  private ManualScheduler mScheduler;
  private FakeRegions mRegions;
  private OnlineSearchPresenter mPresenter;
  private final List<OnlineSearchState> mStates = new ArrayList<>();

  @Before
  public void setUp()
  {
    mStore = new MapStore();
    mConsent = new OnlineSearchConsent(mStore);
    mProvider = new FakeProvider();
    mService = new OnlineSearchService(DIRECT, () -> 0, mConsent::isGranted);
    mService.setProvider(mProvider);
    mScheduler = new ManualScheduler();
    mRegions = new FakeRegions();
    mPresenter = new OnlineSearchPresenter(mService, mConsent, mRegions,
                                           new Debouncer(mScheduler, OnlineSearchPresenter.DEBOUNCE_MS));
    mPresenter.setListener(mStates::add);
  }

  private OnlineSearchState.Status status()
  {
    return mPresenter.getState().getStatus();
  }

  private void type(@NonNull String query, @Nullable double[] center)
  {
    mPresenter.onQueryChanged(query, false, "de-DE", center, null);
  }

  private void debounce()
  {
    mScheduler.advance(OnlineSearchPresenter.DEBOUNCE_MS);
  }

  @NonNull
  private List<String> titles()
  {
    final List<String> titles = new ArrayList<>();
    for (OnlineSearchItem item : mPresenter.getState().getItems())
      titles.add(item.getResult().getTitle() + " " + item.getResult().getOsmRef());
    return titles;
  }

  @Test
  public void withoutDecisionTheOnlineSearchIsOffered()
  {
    type("Marienplatz", MUNICH);
    assertEquals(OnlineSearchPolicy.Decision.REGION_NOT_DOWNLOADED, mPresenter.getDecision());
    assertEquals(OnlineSearchState.Status.OFFER, status());
    assertEquals("Photon", mPresenter.getState().getProviderName());
    debounce();
    mPresenter.onOfflineSearchFinished();
    assertTrue(mProvider.mRequests.isEmpty());
    assertEquals(0, mScheduler.pending());
  }

  @Test
  public void offerAcceptedSearchesRightAway()
  {
    type("Marienplatz", MUNICH);
    mConsent.grant(1);
    mPresenter.onConsentChanged();
    // The user tapped "Search online": no debounce.
    assertEquals(1, mProvider.mRequests.size());
    assertEquals(OnlineSearchState.Status.LOADING, status());
    mProvider.answerLast(MARIENPLATZ);
    assertEquals(OnlineSearchState.Status.RESULTS, status());
  }

  @Test
  public void declinedNeverSendsOrOffers()
  {
    mConsent.decline();
    type("Marienplatz", MUNICH);
    assertEquals(OnlineSearchState.Status.HIDDEN, status());
    mPresenter.onOfflineSearchFinished();
    debounce();
    assertTrue(mProvider.mRequests.isEmpty());
    assertTrue(mStates.isEmpty());
  }

  @Test
  public void missingMapSearchesOnlineWithoutWaitingForOffline()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    assertEquals(OnlineSearchState.Status.LOADING, status());
    assertTrue("debounced", mProvider.mRequests.isEmpty());
    debounce();
    assertEquals(1, mProvider.mRequests.size());

    final OnlineSearchRequest request = mProvider.last();
    assertEquals("Marienplatz", request.getQuery());
    assertEquals("de", request.getLanguage());
    assertEquals(OnlineSearchPresenter.RESULT_LIMIT, request.getLimit());
    assertEquals(48.14, request.getBiasLat(), 0);
    assertEquals(11.58, request.getBiasLon(), 0);

    mProvider.answerLast(MARIENPLATZ, MARIENPLATZ_STATION);
    assertEquals(OnlineSearchState.Status.RESULTS, status());
    assertEquals(2, mPresenter.getState().getItems().size());
    final OnlineSearchItem item = mPresenter.getState().getItems().get(0);
    assertSame(BAVARIA_REGION, item.getRegion());
    assertTrue(item.canDownloadRegion());

    // The end of the offline search changes nothing and sends no second request.
    mPresenter.onOfflineSearchFinished();
    debounce();
    assertEquals(1, mProvider.mRequests.size());
    assertEquals(OnlineSearchState.Status.RESULTS, status());
  }

  @Test
  public void typingSendsOnlyTheLastQuery()
  {
    mConsent.grant(1);
    for (String q : new String[] {"M", "Ma", "Mar", "Mari", "Marie", "Marienplatz"})
    {
      type(q, MUNICH);
      mScheduler.advance(OnlineSearchPresenter.DEBOUNCE_MS / 3);
    }
    assertTrue(mProvider.mRequests.isEmpty());
    debounce();
    assertEquals(1, mProvider.mRequests.size());
    assertEquals("Marienplatz", mProvider.last().getQuery());
  }

  @Test
  public void shortQueriesAndCategoriesAreNotSent()
  {
    mConsent.grant(1);
    type("Ma", MUNICH);
    assertEquals(OnlineSearchState.Status.HIDDEN, status());
    mPresenter.onQueryChanged("cafe", true, "de", MUNICH, null);
    assertEquals(OnlineSearchPolicy.Decision.CATEGORY, mPresenter.getDecision());
    mPresenter.onOfflineSearchFinished();
    debounce();
    assertTrue(mProvider.mRequests.isEmpty());
    // No region lookups for them either.
    assertEquals(0, mRegions.mLookups);
  }

  @Test
  public void downloadedMapWithFewOfflineResultsAddsOnlineResults()
  {
    mConsent.grant(1);
    type("Marienplatz München", BERLIN);
    assertEquals(OnlineSearchState.Status.HIDDEN, status());
    assertEquals(OnlineSearchPolicy.Decision.WAIT_FOR_OFFLINE, mPresenter.getDecision());
    debounce();
    assertTrue(mProvider.mRequests.isEmpty());

    mPresenter.onOfflineResults(Collections.singletonList(new OfflinePlace("München", 48.137, 11.575)));
    mPresenter.onOfflineSearchFinished();
    assertEquals(OnlineSearchPolicy.Decision.FEW_OFFLINE_RESULTS, mPresenter.getDecision());
    assertEquals(OnlineSearchState.Status.LOADING, status());
    debounce();
    assertEquals(1, mProvider.mRequests.size());
    assertEquals(52.52, mProvider.last().getBiasLat(), 0);
    mProvider.answerLast(MARIENPLATZ);
    assertEquals(Collections.singletonList("Marienplatz W259055080"), titles());
  }

  @Test
  public void enoughOfflineResultsStayOffline()
  {
    mConsent.grant(1);
    type("Alexanderplatz", BERLIN);
    mPresenter.onOfflineResults(Arrays.asList(new OfflinePlace("Alexanderplatz", 52.5219, 13.4132),
                                              new OfflinePlace("Alexanderplatz", 52.5215, 13.4110),
                                              new OfflinePlace("Alexanderplatz Bhf", 52.5213, 13.4105)));
    mPresenter.onOfflineSearchFinished();
    assertEquals(OnlineSearchPolicy.Decision.ENOUGH_OFFLINE_RESULTS, mPresenter.getDecision());
    debounce();
    assertEquals(OnlineSearchState.Status.HIDDEN, status());
    assertTrue(mProvider.mRequests.isEmpty());
  }

  @Test
  public void positionInRegionWithoutMapCounts()
  {
    mConsent.grant(1);
    // The map shows Berlin, but the user is in Munich without its map.
    mPresenter.onQueryChanged("Viktualienmarkt", false, "de", BERLIN, MUNICH);
    assertEquals(OnlineSearchPolicy.Decision.REGION_NOT_DOWNLOADED, mPresenter.getDecision());
    debounce();
    // The location bias stays the map center.
    assertEquals(52.52, mProvider.last().getBiasLat(), 0);
  }

  @Test
  public void withoutCenterNoBiasIsSent()
  {
    mConsent.grant(1);
    mPresenter.onQueryChanged("Marienplatz", false, null, null, null);
    mPresenter.onOfflineSearchFinished();
    debounce();
    assertFalse(mProvider.last().hasLocationBias());
    assertNull(mProvider.last().getLanguage());
  }

  @Test
  public void offlineDuplicatesAreHidden()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    debounce();
    mProvider.answerLast(MARIENPLATZ, MARIENPLATZ_STATION);
    assertEquals(2, mPresenter.getState().getItems().size());

    // Offline results arrive later (e.g. the region has been downloaded meanwhile): the list is filtered again.
    mPresenter.onOfflineResults(Collections.singletonList(new OfflinePlace("Marienplatz", 48.1375, 11.5752)));
    assertEquals(OnlineSearchState.Status.EMPTY, status());
    assertTrue(mPresenter.getState().getItems().isEmpty());
  }

  @Test
  public void emptyAnswer()
  {
    mConsent.grant(1);
    type("xqzvbnwplkjh", MUNICH);
    debounce();
    mProvider.answerLast();
    assertEquals(OnlineSearchState.Status.EMPTY, status());
  }

  @Test
  public void outdatedAnswersAreDropped()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    debounce();
    type("Viktualienmarkt", MUNICH);
    assertEquals(OnlineSearchState.Status.LOADING, status());
    mProvider.answer(0, MARIENPLATZ);
    assertEquals(OnlineSearchState.Status.LOADING, status());
    debounce();
    mProvider.answer(1, CAFE_BERLIN);
    assertEquals(Collections.singletonList("Café Einstein N7"), titles());
    // A Berlin result lies in a downloaded region: no download offer.
    assertFalse(mPresenter.getState().getItems().get(0).canDownloadRegion());
  }

  @Test
  public void errorAndRetry()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    debounce();
    mProvider.mPending.get(0).fail(
        new OnlineSearchException(OnlineSearchException.Kind.NETWORK, 0, null, "offline", null));
    assertEquals(OnlineSearchState.Status.ERROR, status());
    assertEquals(OnlineSearchState.Error.NETWORK, mPresenter.getState().getError());

    mPresenter.retry();
    assertEquals(OnlineSearchState.Status.LOADING, status());
    assertEquals(2, mProvider.mRequests.size());
    mProvider.answerLast(MARIENPLATZ);
    assertEquals(OnlineSearchState.Status.RESULTS, status());
    // Retry only applies to errors.
    mPresenter.retry();
    assertEquals(2, mProvider.mRequests.size());
  }

  @Test
  public void repeatedQueryComesFromTheCache()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    debounce();
    mProvider.answerLast(MARIENPLATZ);
    type("Marienplatz München", MUNICH);
    type("Marienplatz", MUNICH);
    debounce();
    assertEquals(1, mProvider.mRequests.size());
    assertEquals(1, mService.getOnlineRequestCount());
    assertEquals(OnlineSearchState.Status.RESULTS, status());
  }

  @Test
  public void revokingConsentHidesResultsAndDropsPendingAnswers()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    debounce();
    mConsent.decline();
    mPresenter.onConsentChanged();
    assertEquals(OnlineSearchState.Status.HIDDEN, status());
    mProvider.answerLast(MARIENPLATZ);
    assertEquals(OnlineSearchState.Status.HIDDEN, status());
    type("Viktualienmarkt", MUNICH);
    debounce();
    assertEquals(1, mProvider.mRequests.size());
  }

  @Test
  public void revokingWhileDebouncingSendsNothing()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    mConsent.decline();
    mPresenter.onConsentChanged();
    debounce();
    assertTrue(mProvider.mRequests.isEmpty());
  }

  @Test
  public void clearCancelsThePendingRequest()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    mPresenter.clear();
    assertEquals(OnlineSearchState.Status.HIDDEN, status());
    debounce();
    assertTrue(mProvider.mRequests.isEmpty());
  }

  @Test
  public void listenerIsNotifiedOncePerChange()
  {
    mConsent.grant(1);
    type("Marienplatz", MUNICH);
    type("Marienplatz ", MUNICH);
    debounce();
    mProvider.answerLast(MARIENPLATZ);
    final List<OnlineSearchState.Status> statuses = new ArrayList<>();
    for (OnlineSearchState state : mStates)
      statuses.add(state.getStatus());
    // HIDDEN between the two queries is skipped because the decision is made right away.
    assertEquals(Arrays.asList(OnlineSearchState.Status.LOADING, OnlineSearchState.Status.RESULTS), statuses);
  }

  @Test
  public void unknownRegionWaitsForOffline()
  {
    mConsent.grant(1);
    type("Nordpol", new double[] {89.5, 0});
    assertEquals(OnlineSearchPolicy.Decision.WAIT_FOR_OFFLINE, mPresenter.getDecision());
    mPresenter.onOfflineSearchFinished();
    mPresenter.onOfflineSearchFinished();
    debounce();
    assertEquals(1, mProvider.mRequests.size());
  }
}
