package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
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

  @Nullable
  private View mView;
  @Nullable
  private Listener mListener;

  @Nullable
  private TransitLocation mFrom;
  @Nullable
  private TransitLocation mTo;
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

  public TransitConnectionsPresenter(@NonNull TransitProviderRegistry registry, @NonNull OptionsSource options,
                                     @NonNull TransitItemFactory items, @NonNull Debouncer debouncer,
                                     @NonNull Clock clock, @NonNull RouteDisplay routeDisplay)
  {
    mRegistry = registry;
    mOptions = options;
    mItems = items;
    mDebouncer = debouncer;
    mClock = clock;
    mRouteDisplay = routeDisplay;
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
    mFrom = from;
    mTo = to;
    startQuery(true);
  }

  /**
   * @param time departure time, or arrival time if {@code arriveBy}; null for now.
   */
  public void setTime(@Nullable Instant time, boolean arriveBy)
  {
    if (Objects.equals(mTime, time) && mArriveBy == arriveBy)
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
    if (hasQuery())
      startQuery(true);
  }

  /** Explicit retry after an error. */
  public void retry()
  {
    if (!hasQuery())
      return;
    if (mError != null || mRequest == null)
    {
      startQuery(false);
      return;
    }
    if (mPagingError != null)
    {
      mPagingError = null;
      render();
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
          render();
        });
  }

  /** Opens the detail view of a connection and shows it on the map. */
  public void select(int index)
  {
    final List<TransitItinerary> all = getAllItineraries();
    if (index < 0 || index >= all.size())
      return;
    mSelected = index;
    mExpandedLegs.clear();
    mRouteDisplay.show(all.get(index));
    render();
  }

  /** Back from the detail view to the list. @return false if the list was shown already. */
  public boolean back()
  {
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
    if (mSelected < 0)
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
    final boolean hadSelection = mSelected >= 0;
    mFrom = null;
    mTo = null;
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
    if (!hasQuery())
      return new ConnectionsState(ConnectionsState.Screen.NONE, timeLabel, mArriveBy, false, false, false, null, null,
                                  Collections.emptyList(), false, false, null, Collections.emptyList(), providerName(),
                                  attributionUrl());

    final List<TransitItinerary> all = getAllItineraries();
    final List<ItineraryItem> items = new ArrayList<>(all.size());
    for (TransitItinerary itinerary : all)
      items.add(mItems.itinerary(itinerary));

    final TransitItinerary selected = getSelectedItinerary();
    final boolean detail = selected != null;
    return new ConnectionsState(
        detail ? ConnectionsState.Screen.DETAIL : ConnectionsState.Screen.LIST, timeLabel, mArriveBy, mLoading,
        mLoadingEarlier, mLoadingLater, mError, mPagingError, items,
        !mLoading && mError == null && mPreviousCursor != null, !mLoading && mError == null && mNextCursor != null,
        detail ? items.get(mSelected) : null, detail ? mItems.legs(selected, mExpandedLegs) : Collections.emptyList(),
        providerName(), attributionUrl());
  }

  private void startQuery(boolean debounce)
  {
    final int generation = ++mGeneration;
    final boolean hadSelection = mSelected >= 0;
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
