package app.organicmaps.search.online;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.online.OnlineSearchProvider;
import app.organicmaps.sdk.search.online.OnlineSearchRequest;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import app.organicmaps.sdk.search.online.OnlineSearchService;
import app.organicmaps.sdk.util.concurrency.Debouncer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * State of the online section of the search list, without Android dependencies.
 * <p>
 * The search screen reports the query, the offline results and the end of the offline search; the presenter decides
 * with {@link OnlineSearchPolicy} whether online results are needed, offers the online search if the user has not
 * decided yet, and otherwise sends one debounced request per query (nothing without consent). Online results are
 * filtered against the offline results ({@link OnlineResultFilter}) whenever either changes. Results of an outdated
 * query are dropped.
 */
@MainThread
public final class OnlineSearchPresenter
{
  /** Quiet time after the last change of the query before a request is sent. */
  public static final long DEBOUNCE_MS = 600;
  public static final int RESULT_LIMIT = 10;

  public interface Listener
  {
    void onStateChanged(@NonNull OnlineSearchState state);
  }

  @NonNull
  private final OnlineSearchService mService;
  @NonNull
  private final OnlineSearchConsent mConsent;
  @NonNull
  private final RegionInfo.Lookup mRegions;
  @NonNull
  private final Debouncer mDebouncer;
  @Nullable
  private Listener mListener;

  // Current query; a new query increases the generation, which invalidates pending results.
  private int mGeneration;
  @Nullable
  private String mQuery;
  private boolean mIsCategory;
  @Nullable
  private String mLanguage;
  private boolean mHasCenter;
  private double mCenterLat;
  private double mCenterLon;
  @Nullable
  private RegionInfo mCenterRegion;

  @NonNull
  private List<OfflinePlace> mOffline = Collections.emptyList();
  private boolean mOfflineFinished;
  // A request for the current query is scheduled, running or done.
  private boolean mRequested;
  // Unfiltered online results of the current query, null until they arrived.
  @Nullable
  private List<OnlineSearchResult> mOnline;

  @NonNull
  private OnlineSearchPolicy.Decision mDecision = OnlineSearchPolicy.Decision.QUERY_TOO_SHORT;
  @NonNull
  private OnlineSearchState mState = OnlineSearchState.HIDDEN;

  public OnlineSearchPresenter(@NonNull OnlineSearchService service, @NonNull OnlineSearchConsent consent,
                               @NonNull RegionInfo.Lookup regions, @NonNull Debouncer debouncer)
  {
    mService = service;
    mConsent = consent;
    mRegions = regions;
    mDebouncer = debouncer;
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
  }

  @NonNull
  public OnlineSearchState getState()
  {
    return mState;
  }

  @NonNull
  public OnlineSearchPolicy.Decision getDecision()
  {
    return mDecision;
  }

  /**
   * A new query (the offline search starts at the same time).
   *
   * @param language locale of the query, e.g. "de-DE"; used for the names of the results.
   * @param center {lat, lon} of the map center: region check and (rounded) location bias; null if unknown.
   * @param position {lat, lon} of the user's position: region check only; null if unknown.
   */
  public void onQueryChanged(@Nullable String query, boolean isCategory, @Nullable String language,
                             @Nullable double[] center, @Nullable double[] position)
  {
    resetQuery();
    mQuery = query;
    mIsCategory = isCategory;
    mLanguage = language;
    final boolean hasCenter = center != null && center.length >= 2;
    mHasCenter = hasCenter;
    if (hasCenter)
    {
      mCenterLat = center[0];
      mCenterLon = center[1];
    }
    // Only needed for longer free-text queries; avoids region lookups while typing the first letters.
    mCenterRegion = OnlineSearchPolicy.isLongEnough(query) && !isCategory ? regionOfInterest(center, position) : null;
    evaluate();
  }

  /** The region at the map center, or at the position if the map center's region is downloaded or unknown. */
  @Nullable
  private RegionInfo regionOfInterest(@Nullable double[] center, @Nullable double[] position)
  {
    final RegionInfo centerRegion = regionAt(center);
    if (centerRegion != null && !centerRegion.isDownloaded())
      return centerRegion;
    final RegionInfo positionRegion = regionAt(position);
    if (positionRegion != null && !positionRegion.isDownloaded())
      return positionRegion;
    return centerRegion != null ? centerRegion : positionRegion;
  }

  @Nullable
  private RegionInfo regionAt(@Nullable double[] point)
  {
    if (point == null || point.length < 2)
      return null;
    return mRegions.regionAt(point[0], point[1]);
  }

  /** All offline results of the current query so far (places only, without query suggestions). */
  public void onOfflineResults(@NonNull List<OfflinePlace> places)
  {
    mOffline = new ArrayList<>(places);
    if (mOnline != null)
      publishResults();
  }

  public void onOfflineSearchFinished()
  {
    if (mOfflineFinished)
      return;
    mOfflineFinished = true;
    evaluate();
  }

  /** Call after the user agreed or declined (dialog or settings). */
  public void onConsentChanged()
  {
    if (mConsent.isGranted())
    {
      if (mState.getStatus() == OnlineSearchState.Status.OFFER)
      {
        // The user asked for it just now: no need to wait.
        mRequested = true;
        setState(status(OnlineSearchState.Status.LOADING));
        send();
      }
      return;
    }
    // Revoked: drop results and pending requests.
    final String query = mQuery;
    final boolean isCategory = mIsCategory;
    final List<OfflinePlace> offline = mOffline;
    final boolean finished = mOfflineFinished;
    resetQuery();
    mQuery = query;
    mIsCategory = isCategory;
    mOffline = offline;
    mOfflineFinished = finished;
    evaluate();
  }

  /** Repeats a failed request (button in the list). */
  public void retry()
  {
    if (mState.getStatus() != OnlineSearchState.Status.ERROR || !mConsent.isGranted())
      return;
    setState(status(OnlineSearchState.Status.LOADING));
    send();
  }

  /** The query was cleared or the search screen closed. */
  public void clear()
  {
    resetQuery();
    mQuery = null;
    mDecision = OnlineSearchPolicy.Decision.QUERY_TOO_SHORT;
    setState(OnlineSearchState.HIDDEN);
  }

  private void resetQuery()
  {
    ++mGeneration;
    mDebouncer.cancel();
    mOffline = Collections.emptyList();
    mOfflineFinished = false;
    mRequested = false;
    mOnline = null;
  }

  private void evaluate()
  {
    mDecision = OnlineSearchPolicy.decide(mQuery, mIsCategory, mCenterRegion, OfflinePlace.countAnswers(mOffline),
                                          OfflinePlace.bestIsAnswer(mOffline), mOfflineFinished);
    if (!mDecision.isOnline())
    {
      if (!mRequested)
        setState(OnlineSearchState.HIDDEN);
      return;
    }
    if (mRequested)
      return;
    switch (mConsent.getState())
    {
    case DECLINED -> setState(OnlineSearchState.HIDDEN);
    case UNDECIDED -> setState(status(OnlineSearchState.Status.OFFER));
    case GRANTED ->
    {
      mRequested = true;
      setState(status(OnlineSearchState.Status.LOADING));
      mDebouncer.submit(this::send);
    }
    }
  }

  private void send()
  {
    final String query = mQuery;
    if (query == null || !OnlineSearchPolicy.isLongEnough(query))
      return;
    final OnlineSearchRequest.Builder builder =
        OnlineSearchRequest.builder(query).setLanguage(mLanguage).setLimit(RESULT_LIMIT);
    if (mHasCenter)
      builder.setLocationBias(mCenterLat, mCenterLon);
    final int generation = mGeneration;
    mService.search(builder.build())
        .onSuccess(results -> {
          if (generation != mGeneration)
            return;
          mOnline = results;
          publishResults();
        })
        .onFailure(error -> {
          if (generation != mGeneration)
            return;
          setState(new OnlineSearchState(OnlineSearchState.Status.ERROR, Collections.emptyList(),
                                         OnlineSearchState.Error.of(error), providerName()));
        });
  }

  private void publishResults()
  {
    if (mOnline == null)
      return;
    final List<OnlineSearchResult> filtered = OnlineResultFilter.filter(mOnline, mOffline);
    final List<OnlineSearchItem> items = new ArrayList<>(filtered.size());
    for (OnlineSearchResult result : filtered)
      items.add(new OnlineSearchItem(result, mRegions.regionAt(result.getLat(), result.getLon())));
    setState(new OnlineSearchState(items.isEmpty() ? OnlineSearchState.Status.EMPTY : OnlineSearchState.Status.RESULTS,
                                   items, null, providerName()));
  }

  @NonNull
  private OnlineSearchState status(@NonNull OnlineSearchState.Status status)
  {
    return new OnlineSearchState(status, Collections.emptyList(), null, providerName());
  }

  @NonNull
  private String providerName()
  {
    final OnlineSearchProvider provider = mService.getProvider();
    return provider != null ? provider.getName() : "";
  }

  private void setState(@NonNull OnlineSearchState state)
  {
    final boolean unchanged = state.getStatus() == mState.getStatus() && state.getItems().isEmpty()
                           && mState.getItems().isEmpty() && state.getError() == mState.getError();
    mState = state;
    if (!unchanged && mListener != null)
      mListener.onStateChanged(state);
  }
}
