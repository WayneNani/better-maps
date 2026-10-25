package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitDeparture;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.provider.ProviderResult;
import app.organicmaps.sdk.transit.provider.TransitDepartureProvider;
import app.organicmaps.sdk.transit.provider.TransitProvider;
import app.organicmaps.sdk.transit.provider.TransitProviderRegistry;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logic of the departures section in the place page, without Android dependencies. Requests are only sent with the
 * user's consent; refreshing is an explicit user action (no automatic polling).
 */
public final class TransitDeparturesPresenter
{
  public static final int LIMIT = 8;

  public interface View
  {
    void render(@NonNull DeparturesState state);
  }

  public interface ConsentSource
  {
    @NonNull
    TransitConsent.State getState();
  }

  @NonNull
  private final TransitProviderRegistry mRegistry;
  @NonNull
  private final ConsentSource mConsent;
  @NonNull
  private final TransitItemFactory mItems;
  @NonNull
  private final TransitConnectionsPresenter.Clock mClock;

  @Nullable
  private View mView;
  private boolean mHasPlace;
  private double mLat;
  private double mLon;
  private int mGeneration;

  @NonNull
  private DeparturesState mState = DeparturesState.HIDDEN;

  public TransitDeparturesPresenter(@NonNull TransitProviderRegistry registry, @NonNull ConsentSource consent,
                                    @NonNull TransitItemFactory items, @NonNull TransitConnectionsPresenter.Clock clock)
  {
    mRegistry = registry;
    mConsent = consent;
    mItems = items;
    mClock = clock;
  }

  public void attach(@NonNull View view)
  {
    mView = view;
    view.render(mState);
  }

  public void detach(@NonNull View view)
  {
    if (mView == view)
      mView = null;
  }

  /** Shows departures of the stop at (or nearest to) the given place. */
  public void load(double lat, double lon)
  {
    if (mHasPlace && mLat == lat && mLon == lon && mState.mStatus != DeparturesState.Status.HIDDEN
        && mState.mStatus != DeparturesState.Status.CONSENT_REQUIRED)
      return;
    mHasPlace = true;
    mLat = lat;
    mLon = lon;
    request(false);
  }

  /** Explicit refresh by the user; ignores cached data. */
  public void refresh()
  {
    if (mHasPlace)
      request(true);
  }

  /** The consent changed (e.g. the user agreed in the dialog). */
  public void onConsentChanged()
  {
    if (mHasPlace)
      request(false);
  }

  /** Not a stop any more (e.g. the place page shows something else). */
  public void clear()
  {
    ++mGeneration;
    mHasPlace = false;
    setState(DeparturesState.HIDDEN);
  }

  @NonNull
  public DeparturesState getState()
  {
    return mState;
  }

  private void request(boolean refresh)
  {
    final int generation = ++mGeneration;
    switch (mConsent.getState())
    {
    case UNDECIDED:
      setState(new DeparturesState(DeparturesState.Status.CONSENT_REQUIRED, null, Collections.emptyList(), null, false,
                                   null, providerName(null), attributionUrl(null)));
      return;
    case DECLINED: setState(DeparturesState.HIDDEN); return;
    case GRANTED: break;
    }

    if (!mRegistry.isDeparturesAvailable())
    {
      setState(DeparturesState.HIDDEN);
      return;
    }

    final boolean keepContent = refresh && mState.mStatus == DeparturesState.Status.CONTENT;
    if (keepContent)
      setState(new DeparturesState(DeparturesState.Status.CONTENT, mState.mStopName, mState.mItems, null, true,
                                   mState.mUpdatedAt, mState.mProviderName, mState.mAttributionUrl));
    else
      setState(new DeparturesState(DeparturesState.Status.LOADING, null, Collections.emptyList(), null, false, null,
                                   providerName(null), attributionUrl(null)));

    final AsyncResult<ProviderResult<TransitDepartures>> result =
        refresh ? mRegistry.refreshDeparturesNear(mLat, mLon, LIMIT) : mRegistry.departuresNear(mLat, mLon, LIMIT);
    result.onSuccess(value -> {
      if (generation == mGeneration)
        onLoaded(value);
    });
    result.onFailure(error -> {
      if (generation == mGeneration)
        onFailed(error);
    });
  }

  private void onLoaded(@NonNull ProviderResult<TransitDepartures> result)
  {
    final TransitDepartures departures = result.getValue();
    final List<DepartureItem> items = new ArrayList<>();
    for (TransitDeparture departure : departures.getDepartures())
      items.add(mItems.departure(departure));
    final String stopName = departures.getStop().getName().isEmpty() ? null : departures.getStop().getName();
    setState(new DeparturesState(items.isEmpty() ? DeparturesState.Status.EMPTY : DeparturesState.Status.CONTENT,
                                 stopName, items, null, false, mItems.getFormatter().formatDeviceTime(mClock.now()),
                                 providerName(result.getProvider()), attributionUrl(result.getProvider())));
  }

  private void onFailed(@NonNull Throwable error)
  {
    final TransitError kind = TransitError.from(error);
    if (kind == TransitError.DISABLED)
    {
      setState(DeparturesState.HIDDEN);
      return;
    }
    // No stop near the place: nothing to show, but say so instead of hiding silently.
    setState(new DeparturesState(
        kind == TransitError.NO_RESULTS ? DeparturesState.Status.EMPTY : DeparturesState.Status.ERROR, null,
        Collections.emptyList(), kind, false, null, providerName(null), attributionUrl(null)));
  }

  @Nullable
  private TransitProvider provider(@Nullable TransitProvider used)
  {
    if (used != null)
      return used;
    for (TransitProvider provider : mRegistry.getProviders())
      if (provider instanceof TransitDepartureProvider && provider.isEnabled())
        return provider;
    return null;
  }

  @Nullable
  private String providerName(@Nullable TransitProvider used)
  {
    final TransitProvider provider = provider(used);
    return provider != null ? provider.getName() : null;
  }

  @Nullable
  private String attributionUrl(@Nullable TransitProvider used)
  {
    final TransitProvider provider = provider(used);
    return provider != null ? provider.getAttributionUrl() : null;
  }

  private void setState(@NonNull DeparturesState state)
  {
    mState = state;
    if (mView != null)
      mView.render(state);
  }
}
