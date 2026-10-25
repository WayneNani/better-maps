package app.organicmaps.sdk.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Consent gate, cache and request sharing of {@link OnlineSearchService}. */
public class OnlineSearchServiceTest
{
  private static final Executor DIRECT = Runnable::run;

  /** Provider whose answers the test completes by hand. */
  static final class FakeProvider implements OnlineSearchProvider
  {
    final String mId;
    final List<OnlineSearchRequest> mRequests = new ArrayList<>();
    final List<AsyncResult<List<OnlineSearchResult>>> mPending = new ArrayList<>();
    boolean mThrow;

    FakeProvider(String id)
    {
      mId = id;
    }

    @NonNull
    @Override
    public String getId()
    {
      return mId;
    }

    @NonNull
    @Override
    public String getName()
    {
      return "Fake " + mId;
    }

    @NonNull
    @Override
    public String getAttributionUrl()
    {
      return "https://example.org";
    }

    @NonNull
    @Override
    public AsyncResult<List<OnlineSearchResult>> search(@NonNull OnlineSearchRequest request)
    {
      if (mThrow)
        throw new IllegalStateException("broken provider");
      mRequests.add(request);
      final AsyncResult<List<OnlineSearchResult>> result = new AsyncResult<>(DIRECT);
      mPending.add(result);
      return result;
    }

    void completeLast(@NonNull List<OnlineSearchResult> results)
    {
      mPending.get(mPending.size() - 1).complete(results);
    }

    void failLast(@NonNull Throwable error)
    {
      mPending.get(mPending.size() - 1).fail(error);
    }
  }

  private long mNow = 1_000;
  private boolean mAllowed = true;
  private FakeProvider mProvider;
  private OnlineSearchService mService;

  @Before
  public void setUp()
  {
    mProvider = new FakeProvider("fake");
    mService = new OnlineSearchService(DIRECT, () -> mNow, () -> mAllowed);
    mService.setProvider(mProvider);
  }

  @NonNull
  private static OnlineSearchRequest request(@NonNull String query)
  {
    return OnlineSearchRequest.builder(query).setLanguage("de").setLocationBias(52.52, 13.40).build();
  }

  @NonNull
  private static List<OnlineSearchResult> results(@NonNull String name)
  {
    return Collections.singletonList(OnlineSearchResult.builder(48.1, 11.5).setName(name).build());
  }

  @NonNull
  private static <T> AtomicReference<Object> capture(@NonNull AsyncResult<T> result)
  {
    final AtomicReference<Object> value = new AtomicReference<>();
    result.onSuccess(value::set).onFailure(value::set);
    return value;
  }

  @Test
  public void withoutConsentNothingIsSent()
  {
    mAllowed = false;
    assertFalse(mService.isAvailable());
    final AtomicReference<Object> value = capture(mService.search(request("Marienplatz")));
    assertTrue(value.get() instanceof OnlineSearchException);
    assertEquals(OnlineSearchException.Kind.DISABLED, ((OnlineSearchException) value.get()).getKind());
    assertTrue(mProvider.mRequests.isEmpty());
    assertEquals(0, mService.getOnlineRequestCount());
  }

  @Test
  public void withoutProviderNothingIsSent()
  {
    mService.setProvider(null);
    assertFalse(mService.isAvailable());
    assertNull(mService.getProvider());
    final AtomicReference<Object> value = capture(mService.search(request("Marienplatz")));
    assertEquals(OnlineSearchException.Kind.DISABLED, ((OnlineSearchException) value.get()).getKind());
  }

  @Test
  public void equalRequestsAreAnsweredFromTheCache()
  {
    assertTrue(mService.isAvailable());
    final AtomicReference<Object> first = capture(mService.search(request("Marienplatz")));
    mProvider.completeLast(results("Marienplatz"));
    assertNotNull(first.get());

    mNow += OnlineSearchService.CACHE_TTL_MS - 1;
    final AtomicReference<Object> second = capture(mService.search(request("Marienplatz")));
    assertSame(first.get(), second.get());
    assertEquals(1, mProvider.mRequests.size());
    assertEquals(1, mService.getOnlineRequestCount());

    // A nearby map center gives the same rounded request.
    final AtomicReference<Object> nearby = capture(mService.search(
        OnlineSearchRequest.builder("Marienplatz").setLanguage("de").setLocationBias(52.5249, 13.4049).build()));
    assertSame(first.get(), nearby.get());
    assertEquals(1, mProvider.mRequests.size());
  }

  @Test
  public void cacheExpires()
  {
    capture(mService.search(request("Marienplatz")));
    mProvider.completeLast(results("Marienplatz"));
    mNow += OnlineSearchService.CACHE_TTL_MS;
    capture(mService.search(request("Marienplatz")));
    assertEquals(2, mProvider.mRequests.size());
  }

  @Test
  public void runningRequestIsShared()
  {
    final AtomicReference<Object> a = capture(mService.search(request("Marienplatz")));
    final AtomicReference<Object> b = capture(mService.search(request("Marienplatz")));
    assertEquals(1, mProvider.mRequests.size());
    mProvider.completeLast(results("Marienplatz"));
    assertSame(a.get(), b.get());
  }

  @Test
  public void differentRequestsAreSentSeparately()
  {
    capture(mService.search(request("Marienplatz")));
    capture(mService.search(request("Marienplatz München")));
    capture(mService.search(OnlineSearchRequest.builder("Marienplatz").setLanguage("en").build()));
    assertEquals(3, mProvider.mRequests.size());
    assertEquals(3, mService.getOnlineRequestCount());
  }

  @Test
  public void failuresAreNotCached()
  {
    final AtomicReference<Object> first = capture(mService.search(request("Marienplatz")));
    final OnlineSearchException error =
        new OnlineSearchException(OnlineSearchException.Kind.NETWORK, 0, null, "offline", null);
    mProvider.failLast(error);
    assertSame(error, first.get());

    final AtomicReference<Object> second = capture(mService.search(request("Marienplatz")));
    assertEquals(2, mProvider.mRequests.size());
    mProvider.completeLast(results("Marienplatz"));
    assertTrue(second.get() instanceof List);
  }

  @Test
  public void throwingProviderFailsTheResult()
  {
    mProvider.mThrow = true;
    final AtomicReference<Object> value = capture(mService.search(request("Marienplatz")));
    assertTrue(value.get() instanceof IllegalStateException);
    // The request is not stuck as "in flight".
    mProvider.mThrow = false;
    capture(mService.search(request("Marienplatz")));
    assertEquals(1, mProvider.mRequests.size());
  }

  @Test
  public void newProviderClearsTheCache()
  {
    capture(mService.search(request("Marienplatz")));
    mProvider.completeLast(results("Marienplatz"));
    final FakeProvider other = new FakeProvider("other");
    mService.setProvider(other);
    assertSame(other, mService.getProvider());
    capture(mService.search(request("Marienplatz")));
    assertEquals(1, other.mRequests.size());
  }

  @Test
  public void resultOfAReplacedProviderIsNotCached()
  {
    final AtomicReference<Object> old = capture(mService.search(request("Marienplatz")));
    final FakeProvider other = new FakeProvider("other");
    mService.setProvider(other);
    mProvider.completeLast(results("old"));
    assertTrue(old.get() instanceof List);
    capture(mService.search(request("Marienplatz")));
    assertEquals(1, other.mRequests.size());
  }

  @Test
  public void clearCache()
  {
    capture(mService.search(request("Marienplatz")));
    mProvider.completeLast(results("Marienplatz"));
    mService.clearCache();
    capture(mService.search(request("Marienplatz")));
    assertEquals(2, mProvider.mRequests.size());
  }

  @Test
  public void cacheIsLimited()
  {
    for (int i = 0; i <= OnlineSearchService.MAX_CACHE_ENTRIES; ++i)
    {
      capture(mService.search(request("query " + i)));
      mProvider.completeLast(results("r" + i));
    }
    // The oldest entry was dropped, the newest is still there.
    capture(mService.search(request("query " + OnlineSearchService.MAX_CACHE_ENTRIES)));
    assertEquals(OnlineSearchService.MAX_CACHE_ENTRIES + 1, mProvider.mRequests.size());
    capture(mService.search(request("query 0")));
    assertEquals(OnlineSearchService.MAX_CACHE_ENTRIES + 2, mProvider.mRequests.size());
  }

  @Test
  public void cachedListIsImmutable()
  {
    final AtomicReference<Object> value = capture(mService.search(request("Marienplatz")));
    mProvider.completeLast(new ArrayList<>(results("Marienplatz")));
    @SuppressWarnings("unchecked")
    final List<OnlineSearchResult> list = (List<OnlineSearchResult>) value.get();
    try
    {
      list.clear();
      throw new AssertionError("list must be immutable");
    }
    catch (UnsupportedOperationException expected)
    {
      // ok
    }
  }
}
