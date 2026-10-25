package app.organicmaps.sdk.util.concurrency;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Function;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Result of an asynchronous operation that completes exactly once, either with a value or with an error.
 * <p>
 * Handlers are always invoked on the executor passed to the constructor (e.g. the main thread executor),
 * regardless of the thread that completes the result. Handlers registered after completion are invoked
 * as well. Completing a result more than once is ignored. This class is thread-safe.
 */
public class AsyncResult<T>
{
  public interface SuccessHandler<T>
  {
    void handle(T value);
  }

  public interface FailureHandler
  {
    void handle(@NonNull Throwable error);
  }

  @NonNull
  private final Executor mExecutor;
  private final Object mLock = new Object();

  private final List<SuccessHandler<T>> mSuccessHandlers = new ArrayList<>();
  private final List<FailureHandler> mFailureHandlers = new ArrayList<>();

  private boolean mCompleted = false;
  @Nullable
  private T mValue;
  @Nullable
  private Throwable mError;

  public AsyncResult(@NonNull Executor executor)
  {
    mExecutor = executor;
  }

  /** Creates an already completed result. */
  @NonNull
  public static <T> AsyncResult<T> completed(@NonNull Executor executor, T value)
  {
    final AsyncResult<T> result = new AsyncResult<>(executor);
    result.complete(value);
    return result;
  }

  /** Creates an already failed result. */
  @NonNull
  public static <T> AsyncResult<T> failed(@NonNull Executor executor, @NonNull Throwable error)
  {
    final AsyncResult<T> result = new AsyncResult<>(executor);
    result.fail(error);
    return result;
  }

  /** @return false if the result has already been completed or failed before. */
  public boolean complete(T value)
  {
    final List<SuccessHandler<T>> handlers;
    synchronized (mLock)
    {
      if (mCompleted)
        return false;
      mCompleted = true;
      mValue = value;
      handlers = new ArrayList<>(mSuccessHandlers);
      mSuccessHandlers.clear();
      mFailureHandlers.clear();
    }
    for (SuccessHandler<T> handler : handlers)
      mExecutor.execute(() -> handler.handle(value));
    return true;
  }

  /** @return false if the result has already been completed or failed before. */
  public boolean fail(@NonNull Throwable error)
  {
    final List<FailureHandler> handlers;
    synchronized (mLock)
    {
      if (mCompleted)
        return false;
      mCompleted = true;
      mError = error;
      handlers = new ArrayList<>(mFailureHandlers);
      mSuccessHandlers.clear();
      mFailureHandlers.clear();
    }
    for (FailureHandler handler : handlers)
      mExecutor.execute(() -> handler.handle(error));
    return true;
  }

  public boolean isDone()
  {
    synchronized (mLock)
    {
      return mCompleted;
    }
  }

  @NonNull
  public AsyncResult<T> onSuccess(@NonNull SuccessHandler<T> handler)
  {
    final T value;
    synchronized (mLock)
    {
      if (!mCompleted)
      {
        mSuccessHandlers.add(handler);
        return this;
      }
      if (mError != null)
        return this;
      value = mValue;
    }
    mExecutor.execute(() -> handler.handle(value));
    return this;
  }

  @NonNull
  public AsyncResult<T> onFailure(@NonNull FailureHandler handler)
  {
    final Throwable error;
    synchronized (mLock)
    {
      if (!mCompleted)
      {
        mFailureHandlers.add(handler);
        return this;
      }
      if (mError == null)
        return this;
      error = mError;
    }
    mExecutor.execute(() -> handler.handle(error));
    return this;
  }

  /** Maps the value. An exception thrown by the mapper fails the returned result. */
  @NonNull
  public <R> AsyncResult<R> thenApply(@NonNull Function<T, R> mapper)
  {
    final AsyncResult<R> next = new AsyncResult<>(mExecutor);
    onSuccess(value -> {
      try
      {
        next.complete(mapper.apply(value));
      }
      catch (Throwable t)
      {
        next.fail(t);
      }
    });
    onFailure(next::fail);
    return next;
  }

  /** Chains another asynchronous operation. An exception thrown by the mapper fails the returned result. */
  @NonNull
  public <R> AsyncResult<R> thenCompose(@NonNull Function<T, AsyncResult<R>> mapper)
  {
    final AsyncResult<R> next = new AsyncResult<>(mExecutor);
    onSuccess(value -> {
      try
      {
        mapper.apply(value).onSuccess(next::complete).onFailure(next::fail);
      }
      catch (Throwable t)
      {
        next.fail(t);
      }
    });
    onFailure(next::fail);
    return next;
  }
}
