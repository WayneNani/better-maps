package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Entry point for online search requests:
 * <ul>
 * <li>Gate: without permission ({@link OnlineGate}, i.e. the user's consent) no provider is called at all and
 * requests fail with {@link OnlineSearchException.Kind#DISABLED}.</li>
 * <li>Cache: successful results are kept for {@link #CACHE_TTL_MS}; equal requests in this time and requests while
 * an equal one is still running are answered without another online request. Failures are not cached.</li>
 * </ul>
 * Debouncing of typed input is the caller's job. Thread-safe; results are delivered on the given executor.
 */
public class OnlineSearchService
{
  /** Places change rarely; 10 minutes saves requests when the user edits the query back and forth. */
  public static final long CACHE_TTL_MS = 10 * 60_000;
  @VisibleForTesting
  static final int MAX_CACHE_ENTRIES = 64;

  public interface Clock
  {
    long nowMillis();
  }

  /** Decides whether online requests are allowed at all (user consent). */
  public interface OnlineGate
  {
    boolean isOnlineAllowed();
  }

  private static final class CacheEntry
  {
    @NonNull
    final List<OnlineSearchResult> mResults;
    final long mExpiresAtMs;

    CacheEntry(@NonNull List<OnlineSearchResult> results, long expiresAtMs)
    {
      mResults = results;
      mExpiresAtMs = expiresAtMs;
    }
  }

  private static final class Key
  {
    @NonNull
    final String mProviderId;
    @NonNull
    final OnlineSearchRequest mRequest;

    Key(@NonNull String providerId, @NonNull OnlineSearchRequest request)
    {
      mProviderId = providerId;
      mRequest = request;
    }

    @Override
    public boolean equals(Object o)
    {
      if (!(o instanceof Key))
        return false;
      final Key that = (Key) o;
      return mProviderId.equals(that.mProviderId) && mRequest.equals(that.mRequest);
    }

    @Override
    public int hashCode()
    {
      return 31 * mProviderId.hashCode() + mRequest.hashCode();
    }
  }

  @NonNull
  private final Executor mExecutor;
  @NonNull
  private final Clock mClock;
  @NonNull
  private final OnlineGate mGate;

  private final Object mLock = new Object();
  @Nullable
  private OnlineSearchProvider mProvider;
  // Least recently used entries are dropped first.
  private final LinkedHashMap<Key, CacheEntry> mCache = new LinkedHashMap<>(16, 0.75f, true);
  private final Map<Key, AsyncResult<List<OnlineSearchResult>>> mInFlight = new HashMap<>();
  private int mOnlineRequestCount;

  public OnlineSearchService(@NonNull Executor executor, @NonNull Clock clock, @NonNull OnlineGate gate)
  {
    mExecutor = executor;
    mClock = clock;
    mGate = gate;
  }

  /** Sets the provider (e.g. after the server URL changed); forgets cached results. */
  public void setProvider(@Nullable OnlineSearchProvider provider)
  {
    synchronized (mLock)
    {
      mProvider = provider;
      mCache.clear();
    }
  }

  @Nullable
  public OnlineSearchProvider getProvider()
  {
    synchronized (mLock)
    {
      return mProvider;
    }
  }

  /** True if online requests are allowed and a provider is set. */
  public boolean isAvailable()
  {
    return mGate.isOnlineAllowed() && getProvider() != null;
  }

  public void clearCache()
  {
    synchronized (mLock)
    {
      mCache.clear();
    }
  }

  /** Number of requests actually passed to the provider (not answered from the cache). */
  public int getOnlineRequestCount()
  {
    synchronized (mLock)
    {
      return mOnlineRequestCount;
    }
  }

  @NonNull
  public AsyncResult<List<OnlineSearchResult>> search(@NonNull OnlineSearchRequest request)
  {
    if (!mGate.isOnlineAllowed())
      return AsyncResult.failed(mExecutor, OnlineSearchException.disabled("Online search is disabled"));

    final OnlineSearchProvider provider;
    final Key key;
    final AsyncResult<List<OnlineSearchResult>> result;
    synchronized (mLock)
    {
      provider = mProvider;
      if (provider == null)
        return AsyncResult.failed(mExecutor, OnlineSearchException.disabled("No online search provider"));
      key = new Key(provider.getId(), request);
      final CacheEntry cached = mCache.get(key);
      if (cached != null)
      {
        if (cached.mExpiresAtMs > mClock.nowMillis())
          return AsyncResult.completed(mExecutor, cached.mResults);
        mCache.remove(key);
      }
      final AsyncResult<List<OnlineSearchResult>> running = mInFlight.get(key);
      if (running != null)
        return running;
      result = new AsyncResult<>(mExecutor);
      mInFlight.put(key, result);
      ++mOnlineRequestCount;
    }

    final AsyncResult<List<OnlineSearchResult>> providerResult;
    try
    {
      providerResult = provider.search(request);
    }
    catch (RuntimeException e)
    {
      onFailure(key, result, e);
      return result;
    }
    providerResult
        .onSuccess(results -> {
          final List<OnlineSearchResult> immutable = Collections.unmodifiableList(results);
          synchronized (mLock)
          {
            mInFlight.remove(key);
            // A provider change in the meantime cleared the cache; don't add results of the old one.
            if (mProvider == provider)
            {
              mCache.put(key, new CacheEntry(immutable, mClock.nowMillis() + CACHE_TTL_MS));
              while (mCache.size() > MAX_CACHE_ENTRIES)
                mCache.remove(mCache.keySet().iterator().next());
            }
          }
          result.complete(immutable);
        })
        .onFailure(error -> onFailure(key, result, error));
    return result;
  }

  private void onFailure(@NonNull Key key, @NonNull AsyncResult<List<OnlineSearchResult>> result,
                         @NonNull Throwable error)
  {
    synchronized (mLock)
    {
      mInFlight.remove(key);
    }
    result.fail(error);
  }
}
