package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitPlace;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.provider.ProviderResult;
import app.organicmaps.sdk.transit.provider.TransitPlanRequest;
import app.organicmaps.sdk.transit.provider.TransitProvider;
import app.organicmaps.sdk.transit.provider.TransitProviderRegistry;
import app.organicmaps.sdk.transit.provider.TransitRoutingProvider;
import app.organicmaps.sdk.util.concurrency.Debouncer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Logic of the connection list and detail view of the online public transport planner, without Android
 * dependencies. All methods must be called on one thread (the main thread); provider results are expected on it.
 * <p>
 * New queries are debounced, so that quick changes of start, destination or time cause one request only. Paging
 * ("earlier"/"later") and "try again" are explicit user actions and run immediately. Results of outdated queries are
 * ignored. Nothing is retried automatically.
 * <p>
 * The connections are shown as a list or as a time grid. Each search starts in the default view from the settings;
 * the user can switch for the running search, and returns to that view from the detail view.
 * <p>
 * The detail view can also show a saved connection ({@link #showSaved}) without any query; the connection in the
 * detail view can be saved or removed from the saved connections ({@link #toggleSaved}).
 */
public final class TransitConnectionsPresenter
{
  /** Delay before a new query is sent. */
  public static final long DEBOUNCE_MS = 400;

  public interface View
  {
    void render(@NonNull ConnectionsState state);
  }

  /** Draws the selected connection on the map. */
  public interface RouteDisplay
  {
    void show(@NonNull TransitItinerary itinerary);

    void clear();
  }

  /** Notified when the first page of a query has finished (successfully or not). */
  public interface Listener
  {
    void onQueryFinished(boolean success);
  }

  public interface OptionsSource
  {
    @NonNull
    TransitRoutingOptions getOptions();
  }

  public interface Clock
  {
    @NonNull
    Instant now();
  }

  public interface DefaultViewSource
  {
    @NonNull
    ConnectionsState.ViewMode getDefaultViewMode();
  }

  @NonNull
  private final TransitProviderRegistry mRegistry;
  @NonNull
  private final OptionsSource mOptions;
  @NonNull
  private final TransitItemFactory mItems;
  @NonNull
  private final Debouncer mDebouncer;
  @NonNull
  private final Clock mClock;
  @NonNull
  private final RouteDisplay mRouteDisplay;
  @NonNull
  private final DefaultViewSource mDefaultView;
  @NonNull
  private final SavedConnectionsStore mSavedStore;

  @Nullable
  private View mView;
  @Nullable
  private Listener mListener;

  @Nullable
  private TransitLocation mFrom;
  @Nullable
  private TransitLocation mTo;
  @NonNull
  private String mFromName = "";
  @NonNull
  private String mToName = "";
  @Nullable
  private Instant mTime;
  private boolean mArriveBy;
  @Nullable
  private TransitPlanRequest mRequest;

  // Incremented for every new query; results of older queries are dropped.
  private int mGeneration;
  private boolean mLoading;
  private boolean mLoadingEarlier;
  private boolean mLoadingLater;
  @Nullable
  private TransitError mError;
  @Nullable
  private TransitError mPagingError;
  // Which page failed, for retry().
  private boolean mFailedPageEarlier;

  @NonNull
  private final List<TransitItinerary> mItineraries = new ArrayList<>();
  @NonNull
  private final List<TransitItinerary> mDirect = new ArrayList<>();
  @Nullable
  private String mPreviousCursor;
  @Nullable
  private String mNextCursor;
  @Nullable
  private TransitProvider mProvider;

  // Index into getAllItineraries() of the connection in the detail view, -1 for the list.
  private int mSelected = -1;
  @NonNull
  private final Set<Integer> mExpandedLegs = new HashSet<>();
  // The saved connection in the detail view, null for searched connections.
  @Nullable
  private SavedConnection mSaved;
  // View chosen by the user for the running search; null for the default from the settings.
  @Nullable
  private ConnectionsState.ViewMode mViewMode;

  public TransitConnectionsPresenter(@NonNull TransitProviderRegistry registry, @NonNull OptionsSource options,
                                     @NonNull TransitItemFactory items, @NonNull Debouncer debouncer,
                                     @NonNull Clock clock, @NonNull RouteDisplay routeDisplay,
                                     @NonNull DefaultViewSource defaultView, @NonNull SavedConnectionsStore savedStore)
  {
    mRegistry = registry;
    mOptions = options;
    mItems = items;
    mDebouncer = debouncer;
    mClock = clock;
    mRouteDisplay = routeDisplay;
    mDefaultView = defaultView;
    mSavedStore = savedStore;
    // E.g. a connection was removed in the list of saved connections.
    mSavedStore.addListener(this::render);
  }

  public void attach(@NonNull View view)
  {
    mView = view;
    render();
  }

  public void detach(@NonNull View view)
  {
    if (mView == view)
      mView = null;
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
  }

  /** Starts a new query between two places (debounced). The selected time is kept. */
  public void search(@NonNull TransitLocation from, @NonNull TransitLocation to)
  {
    search(from, to, "", "");
  }

  /**
   * @param fromName name of the start for saved connections, e.g. "Hauptbahnhof"; empty if unknown (e.g. the own
   *     position), then the first stop is used.
   */
  public void search(@NonNull TransitLocation from, @NonNull TransitLocation to, @NonNull String fromName,
                     @NonNull String toName)
  {
    // Another start or destination is a new search: back to the default view.
    if (!from.equals(mFrom) || !to.equals(mTo))
      mViewMode = null;
    mFrom = from;
    mTo = to;
    mFromName = fromName;
    mToName = toName;
    startQuery(true);
  }

  @NonNull
  public ConnectionsState.ViewMode getViewMode()
  {
    return mViewMode != null ? mViewMode : mDefaultView.getDefaultViewMode();
  }

  /** Switches between list and time grid for the running search. */
  public void setViewMode(@NonNull ConnectionsState.ViewMode mode)
  {
    if (getViewMode() == mode && mViewMode != null)
      return;
    mViewMode = mode;
    render();
  }

  /**
   * Shows a saved connection in the detail view and on the map, without a query. {@link #back()} closes it,
   * {@link #searchAgain()} searches between the same places now.
   */
  public void showSaved(@NonNull SavedConnection saved)
  {
    mDebouncer.cancel();
    ++mGeneration;
    resetResults();
    mRequest = null;
    mLoading = false;
    mError = null;
    mFrom = saved.getFrom();
    mTo = saved.getTo();
    mFromName = saved.getFromName();
    mToName = saved.getToName();
    mTime = null;
    mArriveBy = false;
    mViewMode = null;
    mSaved = saved;
    mRouteDisplay.show(saved.getItinerary());
    render();
    if (mListener != null)
      mListener.onQueryFinished(true);
  }

  /** True if the detail view shows a saved connection. */
  public boolean isShowingSaved()
  {
    return mSaved != null;
  }

  /** Searches connections between the places of the shown saved connection, departing now. */
  public void searchAgain()
  {
    if (mSaved == null)
      return;
    mTime = null;
    mArriveBy = false;
    startQuery(false);
  }

  /** Saves the connection in the detail view, or removes it if it is saved already. */
  public void toggleSaved()
  {
    final TransitItinerary itinerary = getSelectedItinerary();
    if (itinerary == null)
      return;
    final String key = TransitItemFactory.key(itinerary);
    if (mSavedStore.contains(key))
    {
      mSavedStore.remove(key);
      return;
    }
    final TransitLocation from = coordinates(mFrom, firstPlace(itinerary));
    final TransitLocation to = coordinates(mTo, lastPlace(itinerary));
    if (from == null || to == null)
      return;
    mSavedStore.save(itinerary, placeName(mFromName, itinerary, true), placeName(mToName, itinerary, false), from, to);
  }

  /**
   * @param time departure time, or arrival time if {@code arriveBy}; null for now.
   */
  public void setTime(@Nullable Instant time, boolean arriveBy)
  {
    if (Objects.equals(mTime, time) && mArriveBy == arriveBy && mSaved == null)
      return;
    mTime = time;
    mArriveBy = arriveBy;
    if (hasQuery())
      startQuery(true);
    else
      render();
  }

  @Nullable
  public Instant getTime()
  {
    return mTime;
  }

  public boolean isArriveBy()
  {
    return mArriveBy;
  }

  /** Settings changed: query again if a query is shown. */
  public void onOptionsChanged()
  {
    if (hasQuery() && mSaved == null)
      startQuery(true);
  }

  /** Explicit retry after an error: repeats the failed query or page. */
  public void retry()
  {
    if (!hasQuery() || mSaved != null)
      return;
    if (mError != null || mRequest == null)
    {
      startQuery(false);
      return;
    }
    if (mPagingError != null)
    {
      mPagingError = null;
      if (mFailedPageEarlier)
        loadEarlier();
      else
        loadLater();
    }
  }

  public void loadEarlier()
  {
    if (mRequest == null || mPreviousCursor == null || mLoading || mLoadingEarlier)
      return;
    mLoadingEarlier = true;
    mPagingError = null;
    render();
    final int generation = mGeneration;
    mRegistry.plan(mRequest.withPageCursor(mPreviousCursor), providerId())
        .onSuccess(result -> {
          if (generation != mGeneration)
            return;
          mLoadingEarlier = false;
          final TransitPlan plan = result.getValue();
          mPreviousCursor = plan.getPreviousPageCursor();
          final List<TransitItinerary> earlier = newItineraries(plan.getItineraries());
          mItineraries.addAll(0, earlier);
          // The user may have opened a connection meanwhile.
          if (mSelected >= 0)
            mSelected += earlier.size();
          render();
        })
        .onFailure(error -> {
          if (generation != mGeneration)
            return;
          mLoadingEarlier = false;
          mPagingError = TransitError.from(error);
          mFailedPageEarlier = true;
          render();
        });
  }

  public void loadLater()
  {
    if (mRequest == null || mNextCursor == null || mLoading || mLoadingLater)
      return;
    mLoadingLater = true;
    mPagingError = null;
    render();
    final int generation = mGeneration;
    mRegistry.plan(mRequest.withPageCursor(mNextCursor), providerId())
        .onSuccess(result -> {
          if (generation != mGeneration)
            return;
          mLoadingLater = false;
          final TransitPlan plan = result.getValue();
          mNextCursor = plan.getNextPageCursor();
          mItineraries.addAll(newItineraries(plan.getItineraries()));
          render();
        })
        .onFailure(error -> {
          if (generation != mGeneration)
            return;
          mLoadingLater = false;
          mPagingError = TransitError.from(error);
          mFailedPageEarlier = false;
          render();
        });
  }

  /** Opens the detail view of a connection and shows it on the map. */
  public void select(int index)
  {
    final List<TransitItinerary> all = getAllItineraries();
    if (mSaved != null || index < 0 || index >= all.size())
      return;
    mSelected = index;
    mExpandedLegs.clear();
    mRouteDisplay.show(all.get(index));
    render();
  }

  /**
   * Back from the detail view to the list or time grid. A saved connection is closed completely (there is no list).
   *
   * @return false if the list was shown already.
   */
  public boolean back()
  {
    if (mSaved != null)
    {
      clear();
      return true;
    }
    if (mSelected < 0)
      return false;
    mSelected = -1;
    mExpandedLegs.clear();
    mRouteDisplay.clear();
    render();
    return true;
  }

  /** Shows or hides the intermediate stops of a leg in the detail view. */
  public void toggleStops(int legIndex)
  {
    if (getSelectedItinerary() == null)
      return;
    if (!mExpandedLegs.remove(legIndex))
      mExpandedLegs.add(legIndex);
    render();
  }

  /** Forgets everything, e.g. when route planning is closed. */
  public void clear()
  {
    mDebouncer.cancel();
    ++mGeneration;
    final boolean hadSelection = mSelected >= 0 || mSaved != null;
    mSaved = null;
    mViewMode = null;
    mFrom = null;
    mTo = null;
    mFromName = "";
    mToName = "";
    mRequest = null;
    resetResults();
    mLoading = false;
    mError = null;
    if (hadSelection)
      mRouteDisplay.clear();
    render();
  }

  /** The selected connection, null in the list. */
  @Nullable
  public TransitItinerary getSelectedItinerary()
  {
    if (mSaved != null)
      return mSaved.getItinerary();
    final List<TransitItinerary> all = getAllItineraries();
    return mSelected >= 0 && mSelected < all.size() ? all.get(mSelected) : null;
  }

  public boolean hasQuery()
  {
    return mFrom != null && mTo != null;
  }

  @NonNull
  public ConnectionsState getState()
  {
    final Instant now = mClock.now();
    final String timeLabel = mItems.getFormatter().formatQueryTime(mTime, mArriveBy, now);
    final ConnectionsState.ViewMode viewMode = getViewMode();
    if (!hasQuery())
      return new ConnectionsState(ConnectionsState.Screen.NONE, timeLabel, mArriveBy, false, false, false, null, null,
                                  Collections.emptyList(), false, false, null, Collections.emptyList(), providerName(),
                                  attributionUrl(), viewMode, false, false, false, null, now);

    final List<TransitItinerary> all = getAllItineraries();
    final List<ItineraryItem> items = new ArrayList<>(all.size());
    for (TransitItinerary itinerary : all)
      items.add(mItems.itinerary(itinerary));

    final TransitItinerary selected = getSelectedItinerary();
    final boolean detail = selected != null;
    ItineraryItem selectedItem = null;
    if (mSaved != null)
      selectedItem = mItems.itinerary(mSaved.getItinerary());
    else if (detail)
      selectedItem = items.get(mSelected);
    return new ConnectionsState(
        detail ? ConnectionsState.Screen.DETAIL : ConnectionsState.Screen.LIST, timeLabel, mArriveBy, mLoading,
        mLoadingEarlier, mLoadingLater, mError, mPagingError, items,
        !mLoading && mError == null && mPreviousCursor != null, !mLoading && mError == null && mNextCursor != null,
        selectedItem, detail ? mItems.legs(selected, mExpandedLegs) : Collections.emptyList(), providerName(),
        attributionUrl(), viewMode, mSaved != null, detail && mSavedStore.contains(TransitItemFactory.key(selected)),
        mSaved != null && mSaved.isPast(now),
        detail ? mItems.getFormatter().formatDate(selected.getStartTime(), zoneOf(selected)) : null, now);
  }

  private void startQuery(boolean debounce)
  {
    final int generation = ++mGeneration;
    final boolean hadSelection = mSelected >= 0 || mSaved != null;
    mSaved = null;
    resetResults();
    mLoading = true;
    mError = null;
    if (hadSelection)
      mRouteDisplay.clear();
    render();
    if (debounce)
      mDebouncer.submit(() -> sendQuery(generation));
    else
    {
      mDebouncer.cancel();
      sendQuery(generation);
    }
  }

  private void sendQuery(int generation)
  {
    if (generation != mGeneration || mFrom == null || mTo == null)
      return;
    final TransitPlanRequest request =
        new TransitPlanRequest(mFrom, mTo, mTime, mArriveBy, null, mOptions.getOptions());
    mRequest = request;
    mRegistry.plan(request)
        .onSuccess(result -> onFirstPage(generation, result))
        .onFailure(error -> onFirstPageFailed(generation, error));
  }

  private void onFirstPage(int generation, @NonNull ProviderResult<TransitPlan> result)
  {
    if (generation != mGeneration)
      return;
    final TransitPlan plan = result.getValue();
    mLoading = false;
    mProvider = result.getProvider();
    mItineraries.addAll(plan.getItineraries());
    mDirect.addAll(plan.getDirect());
    mPreviousCursor = plan.getPreviousPageCursor();
    mNextCursor = plan.getNextPageCursor();
    render();
    if (mListener != null)
      mListener.onQueryFinished(true);
  }

  private void onFirstPageFailed(int generation, @NonNull Throwable error)
  {
    if (generation != mGeneration)
      return;
    mLoading = false;
    mError = TransitError.from(error);
    render();
    if (mListener != null)
      mListener.onQueryFinished(false);
  }

  private void resetResults()
  {
    mItineraries.clear();
    mDirect.clear();
    mPreviousCursor = null;
    mNextCursor = null;
    mLoadingEarlier = false;
    mLoadingLater = false;
    mPagingError = null;
    mSelected = -1;
    mExpandedLegs.clear();
  }

  /** Transit connections in time order, followed by alternatives without transit (e.g. walking). */
  @NonNull
  private List<TransitItinerary> getAllItineraries()
  {
    if (mDirect.isEmpty())
      return mItineraries;
    final List<TransitItinerary> all = new ArrayList<>(mItineraries);
    all.addAll(mDirect);
    return all;
  }

  /** Itineraries of a further page that are not shown yet (pages may overlap). */
  @NonNull
  private List<TransitItinerary> newItineraries(@NonNull List<TransitItinerary> page)
  {
    final Set<String> known = new HashSet<>();
    for (TransitItinerary itinerary : mItineraries)
      known.add(TransitItemFactory.key(itinerary));
    final List<TransitItinerary> result = new ArrayList<>();
    for (TransitItinerary itinerary : page)
      if (known.add(TransitItemFactory.key(itinerary)))
        result.add(itinerary);
    return result;
  }

  @Nullable
  private static String zoneOf(@NonNull TransitItinerary itinerary)
  {
    return itinerary.getLegs().isEmpty() ? null : itinerary.getLegs().get(0).getFrom().getTimeZone();
  }

  @Nullable
  private static TransitPlace firstPlace(@NonNull TransitItinerary itinerary)
  {
    return itinerary.getLegs().isEmpty() ? null : itinerary.getLegs().get(0).getFrom();
  }

  @Nullable
  private static TransitPlace lastPlace(@NonNull TransitItinerary itinerary)
  {
    final List<TransitLeg> legs = itinerary.getLegs();
    return legs.isEmpty() ? null : legs.get(legs.size() - 1).getTo();
  }

  /** The query location if it has coordinates, otherwise the coordinates of the place in the result. */
  @Nullable
  private static TransitLocation coordinates(@Nullable TransitLocation query, @Nullable TransitPlace place)
  {
    if (query != null && !query.isStop())
      return query;
    if (place != null && isValidCoordinate(place.getLat(), place.getLon()))
      return TransitLocation.fromCoordinates(place.getLat(), place.getLon());
    return null;
  }

  private static boolean isValidCoordinate(double lat, double lon)
  {
    return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
  }

  /** The given name, or the first (last) stop with public transport, or "Start" ("Destination"). */
  @NonNull
  private String placeName(@NonNull String name, @NonNull TransitItinerary itinerary, boolean start)
  {
    if (!name.trim().isEmpty())
      return name.trim();
    final TransitLeg leg = start ? itinerary.getFirstTransitLeg() : itinerary.getLastTransitLeg();
    if (leg != null)
      return mItems.getFormatter().placeName(start ? leg.getFrom() : leg.getTo());
    return start ? mItems.getFormatter().getTexts().start() : mItems.getFormatter().getTexts().destination();
  }

  @Nullable
  private String providerId()
  {
    return mProvider != null ? mProvider.getId() : null;
  }

  @Nullable
  private TransitProvider currentProvider()
  {
    if (mProvider != null)
      return mProvider;
    for (TransitProvider provider : mRegistry.getProviders())
      if (provider instanceof TransitRoutingProvider && provider.isEnabled())
        return provider;
    return null;
  }

  @Nullable
  private String providerName()
  {
    final TransitProvider provider = currentProvider();
    return provider != null ? provider.getName() : null;
  }

  @Nullable
  private String attributionUrl()
  {
    final TransitProvider provider = currentProvider();
    return provider != null ? provider.getAttributionUrl() : null;
  }

  private void render()
  {
    if (mView != null)
      mView.render(getState());
  }
}
