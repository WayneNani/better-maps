package app.organicmaps.sdk.util.concurrency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AsyncResultTest
{
  private static final Executor DIRECT = Runnable::run;

  @Test
  public void successBeforeAndAfterRegistration()
  {
    final List<String> received = new ArrayList<>();
    final AsyncResult<String> result = new AsyncResult<>(DIRECT);
    result.onSuccess(received::add);
    assertTrue(result.complete("a"));
    result.onSuccess(v -> received.add(v + "2"));
    assertEquals(List.of("a", "a2"), received);
    assertTrue(result.isDone());
  }

  @Test
  public void completesOnlyOnce()
  {
    final List<Object> received = new ArrayList<>();
    final AsyncResult<String> result = new AsyncResult<>(DIRECT);
    result.onSuccess(received::add).onFailure(received::add);
    assertTrue(result.complete("first"));
    assertFalse(result.complete("second"));
    assertFalse(result.fail(new RuntimeException()));
    assertEquals(List.of("first"), received);
  }

  @Test
  public void failureIsDeliveredOnlyToFailureHandlers()
  {
    final RuntimeException error = new RuntimeException("x");
    final List<Object> received = new ArrayList<>();
    final AsyncResult<String> result = AsyncResult.failed(DIRECT, error);
    result.onSuccess(received::add);
    result.onFailure(received::add);
    assertEquals(List.of(error), received);
  }

  @Test
  public void nullValueIsDelivered()
  {
    final AtomicInteger calls = new AtomicInteger();
    AsyncResult.completed(DIRECT, (String) null).onSuccess(v -> {
      assertNull(v);
      calls.incrementAndGet();
    });
    assertEquals(1, calls.get());
  }

  @Test
  public void handlersRunOnExecutor()
  {
    final List<Runnable> queue = new ArrayList<>();
    final List<String> received = new ArrayList<>();
    final AsyncResult<String> result = new AsyncResult<>(queue::add);
    result.onSuccess(received::add);
    result.complete("v");
    assertTrue(received.isEmpty());
    assertEquals(1, queue.size());
    queue.get(0).run();
    assertEquals(List.of("v"), received);
  }

  @Test
  public void thenApplyMapsValueAndFailures()
  {
    final List<Object> received = new ArrayList<>();
    AsyncResult.completed(DIRECT, 2).thenApply(v -> v * 21).onSuccess(received::add);
    final RuntimeException error = new RuntimeException("e");
    AsyncResult.<Integer>failed(DIRECT, error).thenApply(v -> v * 2).onFailure(received::add);
    final IllegalStateException mapperError = new IllegalStateException();
    AsyncResult.completed(DIRECT, 1).thenApply(v -> { throw mapperError; }).onFailure(received::add);
    assertEquals(List.of(42, error, mapperError), received);
  }

  @Test
  public void thenComposeChainsResults()
  {
    final List<Object> received = new ArrayList<>();
    final AsyncResult<String> inner = new AsyncResult<>(DIRECT);
    AsyncResult.completed(DIRECT, "a").thenCompose(v -> inner).onSuccess(received::add);
    assertTrue(received.isEmpty());
    inner.complete("b");
    assertEquals(List.of("b"), received);

    final RuntimeException error = new RuntimeException();
    AsyncResult.completed(DIRECT, "a")
        .thenCompose(v -> AsyncResult.<String>failed(DIRECT, error))
        .onFailure(received::add);
    assertSame(error, received.get(1));
  }

  @Test
  public void handlerAndUserHandlersDoNotOverrideEachOther()
  {
    // thenApply registers its own handlers; handlers of the caller must still be called.
    final List<Object> received = new ArrayList<>();
    final AsyncResult<Integer> source = new AsyncResult<>(DIRECT);
    source.onSuccess(received::add);
    source.thenApply(v -> v + 1).onSuccess(received::add);
    source.complete(1);
    assertEquals(List.of(1, 2), received);
  }

  @Test
  public void concurrentCompletionAndRegistrationDeliversExactlyOnce() throws Exception
  {
    final ExecutorService pool = Executors.newFixedThreadPool(4);
    try
    {
      for (int i = 0; i < 2000; ++i)
      {
        final AsyncResult<Integer> result = new AsyncResult<>(DIRECT);
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(3);
        pool.execute(() -> {
          await(start);
          result.onSuccess(v -> calls.incrementAndGet());
          done.countDown();
        });
        pool.execute(() -> {
          await(start);
          result.complete(1);
          done.countDown();
        });
        pool.execute(() -> {
          await(start);
          result.complete(2);
          done.countDown();
        });
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("iteration " + i, 1, calls.get());
      }
    }
    finally
    {
      pool.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch)
  {
    try
    {
      latch.await();
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
    }
  }
}
