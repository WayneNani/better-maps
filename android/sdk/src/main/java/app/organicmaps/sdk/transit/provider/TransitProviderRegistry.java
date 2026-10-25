package app.organicmaps.sdk.transit.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Function;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.transit.TransitServiceException;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Combines several {@link TransitProvider}s ordered by priority.
 * <ul>
 * <li>Fallback: if a provider fails with a retryable error ({@link TransitServiceException#isRetryable()}, e.g. no
 * connection or HTTP 5xx), the next provider is asked. Other errors (e.g. an invalid request) are final. A failed
 * request is never repeated automatically with the same provider.</li>
 * <li>Cache: successful results are kept for {@link #CACHE_TTL_MS}; equal requests in this time and requests
 * while an equal one is still running are answered without another online request.</li>
 * <li>Gate: without permission ({@link OnlineGate}) no provider is called at all and requests fail with
 * {@link TransitServiceException.Kind#DISABLED}.</li>
 * </ul>
 * Thread-safe; results are delivered on the given executor.
 */
public class TransitProviderRegistry
{
  /** How long a result is reused for an equal request. */
  public static final long CACHE_TTL_MS = 60_000;
  @VisibleForTesting
  static final int MAX_CACHE_ENTRIES = 32;

  /** Priority of the built-in Transitous provider. Higher priorities are asked first. */
  public static final int PRIORITY_DEFAULT = 0;

  public interface Clock
  {
    long nowMillis();
  }

  /** Decides whether online requests are allowed at all (user consent and settings). */
  public interface OnlineGate
  {
    boolean isOnlineAllowed();
  }

  private static final class Registration
  {
    @NonNull
    final TransitProvider mProvider;
    final int mPriority;
    final int mOrder;

    Registration(@NonNull TransitProvider provider, int priority, int order)
    {
      mProvider = provider;
      mPriority = priority;
      mOrder = order;
    }
  }

  private static final class CacheEntry
  {
    @NonNull
    final ProviderResult<?> mResult;
    final long mExpiresAtMs;

    CacheEntry(@NonNull ProviderResult<?> result, long expiresAtMs)
    {
      mResult = result;
      mExpiresAtMs = expiresAtMs;
    }
  }

  @NonNull
  private final Executor mExecutor;
  @NonNull
  private final Clock mClock;
  @NonNull
  private final OnlineGate mGate;

  private final Object mLock = new Object();
  private final List<Registration> mRegistrations = new ArrayList<>();
  private int mNextOrder;
  // Least recently used entries are dropped first.
  private final LinkedHashMap<Object, CacheEntry> mCache = new LinkedHashMap<>(16, 0.75f, true);
  private final Map<Object, AsyncResult<?>> mInFlight = new HashMap<>();
  private int mOnlineRequestCount;

  public TransitProviderRegistry(@NonNull Executor executor, @NonNull Clock clock, @NonNull OnlineGate gate)
  {
    mExecutor = executor;
    mClock = clock;
    mGate = gate;
  }

  /** Adds a provider; providers with a higher priority are asked first, equal ones in registration order. */
  public void register(@NonNull TransitProvider provider, int priority)
  {
    synchronized (mLock)
    {
      unregisterLocked(provider.getId());
      mRegistrations.add(new Registration(provider, priority, mNextOrder++));
      Collections.sort(mRegistrations, (a, b) -> {
        if (a.mPriority != b.mPriority)
          return Integer.compare(b.mPriority, a.mPriority);
        return Integer.compare(a.mOrder, b.mOrder);
      });
      mCache.clear();
    }
  }

  public void unregister(@NonNull String providerId)
  {
    synchronized (mLock)
    {
      unregisterLocked(providerId);
      mCache.clear();
    }
  }

  /** All registered providers, highest priority first. */
  @NonNull
  public List<TransitProvider> getProviders()
  {
    synchronized (mLock)
    {
      final List<TransitProvider> result = new ArrayList<>(mRegistrations.size());
      for (Registration r : mRegistrations)
        result.add(r.mProvider);
      return result;
    }
  }

  /** True if online requests are allowed and at least one enabled routing provider exists. */
  public boolean isRoutingAvailable()
  {
    return mGate.isOnlineAllowed() && !candidates(TransitRoutingProvider.class, null).isEmpty();
  }

  public boolean isDeparturesAvailable()
  {
    return mGate.isOnlineAllowed() && !candidates(TransitDepartureProvider.class, null).isEmpty();
  }

  /** Forgets cached results, e.g. after the user changed a setting that is not part of the request. */
  public void clearCache()
  {
    synchronized (mLock)
    {
      mCache.clear();
    }
  }

  /** Number of requests actually passed to providers (not answered from the cache). */
  public int getOnlineRequestCount()
  {
    synchronized (mLock)
    {
      return mOnlineRequestCount;
    }
  }

  /** Computes connections with the best available provider. */
  @NonNull
  public AsyncResult<ProviderResult<TransitPlan>> plan(@NonNull TransitPlanRequest request)
  {
    return plan(request, null);
  }

  /**
   * @param providerId if not null, only this provider is asked (no fallback). Use it for paging, because page
   *        cursors are only valid for the provider that returned them.
   */
  @NonNull
  public AsyncResult<ProviderResult<TransitPlan>> plan(@NonNull TransitPlanRequest request, @Nullable String providerId)
  {
    final List<TransitRoutingProvider> providers = candidates(TransitRoutingProvider.class, providerId);
    return request(new Key("plan", providerId, request), providers, p -> p.plan(request));
  }

  /** Next departures at the stop nearest to the given point. */
  @NonNull
  public AsyncResult<ProviderResult<TransitDepartures>> departuresNear(double lat, double lon, int limit)
  {
    final List<TransitDepartureProvider> providers = candidates(TransitDepartureProvider.class, null);
    // ~1 m precision is enough to recognize the same place.
    final String place = String.format(Locale.ROOT, "%.5f,%.5f,%d", lat, lon, limit);
    return request(new Key("departures", null, place), providers, p -> p.departuresNear(lat, lon, limit));
  }

  /**
   * Like {@link #departuresNear}, but ignores a cached result (explicit refresh by the user). A request for the
   * same place that is still running is reused.
   */
  @NonNull
  public AsyncResult<ProviderResult<TransitDepartures>> refreshDeparturesNear(double lat, double lon, int limit)
  {
    synchronized (mLock)
    {
      mCache.remove(new Key("departures", null, String.format(Locale.ROOT, "%.5f,%.5f,%d", lat, lon, limit)));
    }
    return departuresNear(lat, lon, limit);
  }

  @NonNull
  private <P extends TransitProvider> List<P> candidates(@NonNull Class<P> type, @Nullable String providerId)
  {
    final List<P> result = new ArrayList<>();
    synchronized (mLock)
    {
      for (Registration r : mRegistrations)
      {
        final TransitProvider provider = r.mProvider;
        if (!type.isInstance(provider) || !provider.isEnabled())
          continue;
        if (providerId != null && !providerId.equals(provider.getId()))
          continue;
        result.add(type.cast(provider));
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  @NonNull
  private <P extends TransitProvider, T> AsyncResult<ProviderResult<T>> request(
      @NonNull Key key, @NonNull List<P> providers, @NonNull Function<P, AsyncResult<T>> call)
  {
    if (!mGate.isOnlineAllowed())
      return AsyncResult.failed(mExecutor, TransitServiceException.disabled("Online public transport is disabled"));
    if (providers.isEmpty())
      return AsyncResult.failed(mExecutor, TransitServiceException.disabled("No public transport provider for " + key));

    final AsyncResult<ProviderResult<T>> result;
    synchronized (mLock)
    {
      final CacheEntry cached = mCache.get(key);
      if (cached != null)
      {
        if (cached.mExpiresAtMs > mClock.nowMillis())
          return AsyncResult.completed(mExecutor, (ProviderResult<T>) cached.mResult);
        mCache.remove(key);
      }
      final AsyncResult<?> running = mInFlight.get(key);
      if (running != null)
        return (AsyncResult<ProviderResult<T>>) running;
      result = new AsyncResult<>(mExecutor);
      mInFlight.put(key, result);
    }
    attempt(key, providers, 0, call, result);
    return result;
  }

  private <P extends TransitProvider, T> void attempt(@NonNull Key key, @NonNull List<P> providers, int index,
                                                      @NonNull Function<P, AsyncResult<T>> call,
                                                      @NonNull AsyncResult<ProviderResult<T>> result)
  {
    final P provider = providers.get(index);
    final AsyncResult<T> providerResult;
    synchronized (mLock)
    {
      ++mOnlineRequestCount;
    }
    try
    {
      providerResult = call.apply(provider);
    }
    catch (RuntimeException e)
    {
      onAttemptFailed(key, providers, index, call, result, e);
      return;
    }
    providerResult
        .onSuccess(value -> {
          final ProviderResult<T> wrapped = new ProviderResult<>(value, provider);
          synchronized (mLock)
          {
            mInFlight.remove(key);
            mCache.put(key, new CacheEntry(wrapped, mClock.nowMillis() + CACHE_TTL_MS));
            while (mCache.size() > MAX_CACHE_ENTRIES)
              mCache.remove(mCache.keySet().iterator().next());
          }
          result.complete(wrapped);
        })
        .onFailure(error -> onAttemptFailed(key, providers, index, call, result, error));
  }

  private <P extends TransitProvider, T> void onAttemptFailed(@NonNull Key key, @NonNull List<P> providers, int index,
                                                              @NonNull Function<P, AsyncResult<T>> call,
                                                              @NonNull AsyncResult<ProviderResult<T>> result,
                                                              @NonNull Throwable error)
  {
    final boolean retryable =
        error instanceof TransitServiceException && ((TransitServiceException) error).isRetryable();
    if (retryable && index + 1 < providers.size())
    {
      attempt(key, providers, index + 1, call, result);
      return;
    }
    synchronized (mLock)
    {
      mInFlight.remove(key);
    }
    result.fail(error);
  }

  private void unregisterLocked(@NonNull String providerId)
  {
    for (int i = mRegistrations.size() - 1; i >= 0; --i)
      if (mRegistrations.get(i).mProvider.getId().equals(providerId))
        mRegistrations.remove(i);
  }

  private static final class Key
  {
    @NonNull
    private final String mType;
    @Nullable
    private final String mProviderId;
    @NonNull
    private final Object mRequest;

    Key(@NonNull String type, @Nullable String providerId, @NonNull Object request)
    {
      mType = type;
      mProviderId = providerId;
      mRequest = request;
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o)
        return true;
      if (!(o instanceof Key))
        return false;
      final Key that = (Key) o;
      return mType.equals(that.mType) && Objects.equals(mProviderId, that.mProviderId)
   && mRequest.equals(that.mRequest);
    }

    @Override
    public int hashCode()
    {
      return Objects.hash(mType, mProviderId, mRequest);
    }

    @NonNull
    @Override
    public String toString()
    {
      return mType + " " + mRequest;
    }
  }
}
