package app.organicmaps.sdk.transit.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TestModels;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.TransitServiceException;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

public class TransitProviderRegistryTest
{
  private static final TransitPlanRequest REQUEST = new TransitPlanRequest(
      TransitLocation.fromCoordinates(52.525, 13.3694), TransitLocation.fromCoordinates(52.5219, 13.4132), null, false,
      null, TransitRoutingOptions.defaults());

  private long mNow = 1_000_000;
  private boolean mOnline = true;
  private TransitProviderRegistry mRegistry;
  private FakeProvider mPrimary;
  private FakeProvider mSecondary;

  @Before
  public void setUp()
  {
    mRegistry = new TransitProviderRegistry(Runnable::run, () -> mNow, () -> mOnline);
    mPrimary = new FakeProvider("primary");
    mSecondary = new FakeProvider("secondary");
  }

  private static <T> Outcome<T> observe(@NonNull AsyncResult<T> result)
  {
    final Outcome<T> outcome = new Outcome<>();
    result.onSuccess(outcome.mValue::set).onFailure(outcome.mError::set);
    return outcome;
  }

  private static final class Outcome<T>
  {
    final AtomicReference<T> mValue = new AtomicReference<>();
    final AtomicReference<Throwable> mError = new AtomicReference<>();
  }

  private static TransitServiceException error(@NonNull TransitServiceException.Kind kind, int code)
  {
    return new TransitServiceException(kind, code, null, kind + " " + code, null);
  }

  @Test
  public void higherPriorityIsAskedFirst()
  {
    mRegistry.register(mSecondary, 0);
    mRegistry.register(mPrimary, 10);
    assertEquals("primary", mRegistry.getProviders().get(0).getId());

    final Outcome<ProviderResult<TransitPlan>> outcome = observe(mRegistry.plan(REQUEST));
    assertEquals(1, mPrimary.mPlanRequests.size());
    assertTrue(mSecondary.mPlanRequests.isEmpty());

    final TransitPlan plan = TestModels.plan("next");
    mPrimary.lastPlan().complete(plan);
    assertSame(plan, outcome.mValue.get().getValue());
    assertSame(mPrimary, outcome.mValue.get().getProvider());
  }

  @Test
  public void equalPriorityKeepsRegistrationOrder()
  {
    mRegistry.register(mPrimary, 0);
    mRegistry.register(mSecondary, 0);
    assertEquals("primary", mRegistry.getProviders().get(0).getId());
    assertEquals("secondary", mRegistry.getProviders().get(1).getId());
  }

  @Test
  public void registeringTheSameIdReplacesTheProvider()
  {
    mRegistry.register(mPrimary, 0);
    final FakeProvider replacement = new FakeProvider("primary");
    mRegistry.register(replacement, 0);
    assertEquals(1, mRegistry.getProviders().size());
    assertSame(replacement, mRegistry.getProviders().get(0));
  }

  @Test
  public void fallsBackOnRetryableError()
  {
    mRegistry.register(mPrimary, 10);
    mRegistry.register(mSecondary, 0);
    final Outcome<ProviderResult<TransitPlan>> outcome = observe(mRegistry.plan(REQUEST));

    mPrimary.lastPlan().fail(error(TransitServiceException.Kind.NETWORK, 0));
    assertEquals(1, mSecondary.mPlanRequests.size());
    assertEquals(REQUEST, mSecondary.mPlanRequests.get(0));
    assertNull(outcome.mValue.get());

    final TransitPlan plan = TestModels.plan("x");
    mSecondary.lastPlan().complete(plan);
    assertSame(mSecondary, outcome.mValue.get().getProvider());
    assertNull(outcome.mError.get());
    assertEquals(2, mRegistry.getOnlineRequestCount());
  }

  @Test
  public void fallsBackOnServerErrorAndRateLimit()
  {
    final FakeProvider third = new FakeProvider("third");
    mRegistry.register(mPrimary, 2);
    mRegistry.register(mSecondary, 1);
    mRegistry.register(third, 0);
    observe(mRegistry.plan(REQUEST));
    mPrimary.lastPlan().fail(error(TransitServiceException.Kind.SERVER_ERROR, 503));
    mSecondary.lastPlan().fail(error(TransitServiceException.Kind.CLIENT_ERROR, 429));
    assertEquals(1, third.mPlanRequests.size());
  }

  @Test
  public void doesNotFallBackOnClientError()
  {
    mRegistry.register(mPrimary, 10);
    mRegistry.register(mSecondary, 0);
    final Outcome<ProviderResult<TransitPlan>> outcome = observe(mRegistry.plan(REQUEST));
    final TransitServiceException clientError = error(TransitServiceException.Kind.CLIENT_ERROR, 400);
    mPrimary.lastPlan().fail(clientError);
    assertTrue(mSecondary.mPlanRequests.isEmpty());
    assertSame(clientError, outcome.mError.get());
  }

  @Test
  public void doesNotFallBackOnOtherExceptions()
  {
    mRegistry.register(mPrimary, 10);
    mRegistry.register(mSecondary, 0);
    final Outcome<ProviderResult<TransitPlan>> outcome = observe(mRegistry.plan(REQUEST));
    mPrimary.lastPlan().fail(new IllegalStateException("bug"));
    assertTrue(mSecondary.mPlanRequests.isEmpty());
    assertTrue(outcome.mError.get() instanceof IllegalStateException);
  }

  @Test
  public void lastErrorIsReportedWhenAllProvidersFail()
  {
    mRegistry.register(mPrimary, 10);
    mRegistry.register(mSecondary, 0);
    final Outcome<ProviderResult<TransitPlan>> outcome = observe(mRegistry.plan(REQUEST));
    mPrimary.lastPlan().fail(error(TransitServiceException.Kind.NETWORK, 0));
    final TransitServiceException last = error(TransitServiceException.Kind.SERVER_ERROR, 502);
    mSecondary.lastPlan().fail(last);
    assertSame(last, outcome.mError.get());
    // No automatic retries.
    assertEquals(1, mPrimary.mPlanRequests.size());
    assertEquals(1, mSecondary.mPlanRequests.size());
  }

  @Test
  public void throwingProviderIsTreatedAsFailure()
  {
    mPrimary.mThrow = new IllegalArgumentException("broken");
    mRegistry.register(mPrimary, 0);
    final Outcome<ProviderResult<TransitPlan>> outcome = observe(mRegistry.plan(REQUEST));
    assertTrue(outcome.mError.get() instanceof IllegalArgumentException);
    // The failed request is not kept as running.
    mPrimary.mThrow = null;
    observe(mRegistry.plan(REQUEST));
    assertEquals(1, mPrimary.mPlanRequests.size());
  }

  @Test
  public void disabledProvidersAreSkipped()
  {
    mPrimary.mEnabled = false;
    mRegistry.register(mPrimary, 10);
    mRegistry.register(mSecondary, 0);
    observe(mRegistry.plan(REQUEST));
    assertTrue(mPrimary.mPlanRequests.isEmpty());
    assertEquals(1, mSecondary.mPlanRequests.size());
  }

  @Test
  public void withoutPermissionNothingIsRequested()
  {
    mOnline = false;
    mRegistry.register(mPrimary, 0);
    assertFalse(mRegistry.isRoutingAvailable());
    assertFalse(mRegistry.isDeparturesAvailable());

    final Outcome<ProviderResult<TransitPlan>> plan = observe(mRegistry.plan(REQUEST));
    final Outcome<ProviderResult<TransitDepartures>> departures = observe(mRegistry.departuresNear(52.5, 13.4, 5));

    assertTrue(mPrimary.mPlanRequests.isEmpty());
    assertTrue(mPrimary.mDepartureResults.isEmpty());
    assertEquals(TransitServiceException.Kind.DISABLED, ((TransitServiceException) plan.mError.get()).getKind());
    assertEquals(TransitServiceException.Kind.DISABLED, ((TransitServiceException) departures.mError.get()).getKind());
    assertEquals(0, mRegistry.getOnlineRequestCount());
  }

  @Test
  public void withoutProvidersRequestsFail()
  {
    assertFalse(mRegistry.isRoutingAvailable());
    final Outcome<ProviderResult<TransitPlan>> plan = observe(mRegistry.plan(REQUEST));
    assertEquals(TransitServiceException.Kind.DISABLED, ((TransitServiceException) plan.mError.get()).getKind());
    assertFalse(((TransitServiceException) plan.mError.get()).isRetryable());
  }

  @Test
  public void equalRequestWithinTtlIsAnsweredFromCache()
  {
    mRegistry.register(mPrimary, 0);
    observe(mRegistry.plan(REQUEST));
    final TransitPlan plan = TestModels.plan("c");
    mPrimary.lastPlan().complete(plan);

    mNow += TransitProviderRegistry.CACHE_TTL_MS - 1;
    final TransitPlanRequest equal = new TransitPlanRequest(TransitLocation.fromCoordinates(52.525, 13.3694),
                                                            TransitLocation.fromCoordinates(52.5219, 13.4132), null,
                                                            false, null, TransitRoutingOptions.builder().build());
    final Outcome<ProviderResult<TransitPlan>> outcome = observe(mRegistry.plan(equal));
    assertSame(plan, outcome.mValue.get().getValue());
    assertEquals(1, mPrimary.mPlanRequests.size());
    assertEquals(1, mRegistry.getOnlineRequestCount());
  }

  @Test
  public void cacheExpiresAfterTtl()
  {
    mRegistry.register(mPrimary, 0);
    observe(mRegistry.plan(REQUEST));
    mPrimary.lastPlan().complete(TestModels.plan("c"));
    mNow += TransitProviderRegistry.CACHE_TTL_MS;
    observe(mRegistry.plan(REQUEST));
    assertEquals(2, mPrimary.mPlanRequests.size());
  }

  @Test
  public void differentRequestsAreNotMixedUp()
  {
    mRegistry.register(mPrimary, 0);
    observe(mRegistry.plan(REQUEST));
    mPrimary.lastPlan().complete(TestModels.plan("c"));

    observe(mRegistry.plan(REQUEST.withPageCursor("next")));
    observe(mRegistry.plan(new TransitPlanRequest(REQUEST.getFrom(), REQUEST.getTo(), Instant.ofEpochSecond(100), false,
                                                  null, REQUEST.getOptions())));
    observe(mRegistry.plan(new TransitPlanRequest(REQUEST.getFrom(), REQUEST.getTo(), Instant.ofEpochSecond(100), true,
                                                  null, REQUEST.getOptions())));
    observe(mRegistry.plan(new TransitPlanRequest(REQUEST.getFrom(), REQUEST.getTo(), null, false, null,
                                                  TransitRoutingOptions.builder().setMaxTransfers(1).build())));
    assertEquals(5, mPrimary.mPlanRequests.size());
  }

  @Test
  public void failuresAreNotCached()
  {
    mRegistry.register(mPrimary, 0);
    observe(mRegistry.plan(REQUEST));
    mPrimary.lastPlan().fail(error(TransitServiceException.Kind.NETWORK, 0));
    observe(mRegistry.plan(REQUEST));
    assertEquals(2, mPrimary.mPlanRequests.size());
  }

  @Test
  public void runningRequestIsShared()
  {
    mRegistry.register(mPrimary, 0);
    final Outcome<ProviderResult<TransitPlan>> first = observe(mRegistry.plan(REQUEST));
    final Outcome<ProviderResult<TransitPlan>> second = observe(mRegistry.plan(REQUEST));
    assertEquals(1, mPrimary.mPlanRequests.size());
    final TransitPlan plan = TestModels.plan("c");
    mPrimary.lastPlan().complete(plan);
    assertSame(plan, first.mValue.get().getValue());
    assertSame(plan, second.mValue.get().getValue());
  }

  @Test
  public void clearCacheForcesNewRequest()
  {
    mRegistry.register(mPrimary, 0);
    observe(mRegistry.plan(REQUEST));
    mPrimary.lastPlan().complete(TestModels.plan("c"));
    mRegistry.clearCache();
    observe(mRegistry.plan(REQUEST));
    assertEquals(2, mPrimary.mPlanRequests.size());
  }

  @Test
  public void cacheIsBounded()
  {
    mRegistry.register(mPrimary, 0);
    for (int i = 0; i <= TransitProviderRegistry.MAX_CACHE_ENTRIES; ++i)
    {
      observe(mRegistry.plan(REQUEST.withPageCursor("p" + i)));
      mPrimary.lastPlan().complete(TestModels.plan("c" + i));
    }
    // The oldest entry was dropped, the newest is still cached.
    observe(mRegistry.plan(REQUEST.withPageCursor("p0")));
    assertEquals(TransitProviderRegistry.MAX_CACHE_ENTRIES + 2, mPrimary.mPlanRequests.size());
    observe(mRegistry.plan(REQUEST.withPageCursor("p" + TransitProviderRegistry.MAX_CACHE_ENTRIES)));
    assertEquals(TransitProviderRegistry.MAX_CACHE_ENTRIES + 2, mPrimary.mPlanRequests.size());
  }

  @Test
  public void pinnedProviderIsUsedWithoutFallback()
  {
    mRegistry.register(mPrimary, 10);
    mRegistry.register(mSecondary, 0);
    final Outcome<ProviderResult<TransitPlan>> outcome =
        observe(mRegistry.plan(REQUEST.withPageCursor("cursor-of-secondary"), "secondary"));
    assertTrue(mPrimary.mPlanRequests.isEmpty());
    assertEquals(1, mSecondary.mPlanRequests.size());
    mSecondary.lastPlan().fail(error(TransitServiceException.Kind.NETWORK, 0));
    assertNotNull(outcome.mError.get());
    assertTrue(mPrimary.mPlanRequests.isEmpty());
  }

  @Test
  public void unknownPinnedProviderFails()
  {
    mRegistry.register(mPrimary, 0);
    final Outcome<ProviderResult<TransitPlan>> outcome = observe(mRegistry.plan(REQUEST, "gone"));
    assertEquals(TransitServiceException.Kind.DISABLED, ((TransitServiceException) outcome.mError.get()).getKind());
  }

  @Test
  public void unregisterRemovesProvider()
  {
    mRegistry.register(mPrimary, 0);
    mRegistry.unregister("primary");
    assertTrue(mRegistry.getProviders().isEmpty());
    assertFalse(mRegistry.isRoutingAvailable());
  }

  @Test
  public void departuresFallBackAndAreCached()
  {
    mRegistry.register(mPrimary, 10);
    mRegistry.register(mSecondary, 0);
    assertTrue(mRegistry.isDeparturesAvailable());
    final Outcome<ProviderResult<TransitDepartures>> outcome = observe(mRegistry.departuresNear(52.52, 13.41, 5));
    mPrimary.mDepartureResults.get(0).fail(error(TransitServiceException.Kind.NETWORK, 0));
    final TransitDepartures departures = TestModels.departures("Alexanderplatz");
    mSecondary.mDepartureResults.get(0).complete(departures);
    assertSame(departures, outcome.mValue.get().getValue());
    assertSame(mSecondary, outcome.mValue.get().getProvider());

    // Nearly the same point within the TTL: cached.
    observe(mRegistry.departuresNear(52.520001, 13.410001, 5));
    assertEquals(1, mSecondary.mDepartureResults.size());
    // Another limit: new request.
    observe(mRegistry.departuresNear(52.52, 13.41, 10));
    assertEquals(2, mPrimary.mDepartureResults.size());
  }

  @Test
  public void refreshDeparturesBypassesCache()
  {
    mRegistry.register(mPrimary, 0);
    observe(mRegistry.departuresNear(52.52, 13.41, 5));
    mPrimary.mDepartureResults.get(0).complete(TestModels.departures("A"));
    observe(mRegistry.refreshDeparturesNear(52.52, 13.41, 5));
    assertEquals(2, mPrimary.mDepartureResults.size());
  }

  @Test
  public void routingOnlyProviderIsNotUsedForDepartures()
  {
    final TransitRoutingProvider routingOnly = new TransitRoutingProvider() {
      @NonNull
      @Override
      public AsyncResult<TransitPlan> plan(@NonNull TransitPlanRequest request)
      {
        return AsyncResult.completed(Runnable::run, TestModels.plan("r"));
      }

      @NonNull
      @Override
      public String getId()
      {
        return "routing";
      }

      @NonNull
      @Override
      public String getName()
      {
        return "Routing";
      }

      @NonNull
      @Override
      public String getAttributionUrl()
      {
        return "https://example.org";
      }
    };
    mRegistry.register(routingOnly, 0);
    assertTrue(mRegistry.isRoutingAvailable());
    assertFalse(mRegistry.isDeparturesAvailable());
  }
}
