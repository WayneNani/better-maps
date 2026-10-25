package app.organicmaps.sdk.util.concurrency;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Runs only the last of several actions submitted in quick succession, after a quiet period. Used to avoid
 * online requests while the user is still changing input (e.g. time or settings).
 * <p>
 * Not thread-safe: submit and cancel on one thread (usually the main thread).
 */
public final class Debouncer
{
  /** Schedules delayed actions; replaceable in tests. */
  public interface Scheduler
  {
    @NonNull
    Cancellable schedule(@NonNull Runnable action, long delayMs);
  }

  public interface Cancellable
  {
    void cancel();
  }

  @NonNull
  private final Scheduler mScheduler;
  private final long mDelayMs;
  @Nullable
  private Cancellable mPending;
  @Nullable
  private Object mToken;

  public Debouncer(@NonNull Scheduler scheduler, long delayMs)
  {
    if (delayMs < 0)
      throw new IllegalArgumentException("delayMs must be >= 0: " + delayMs);
    mScheduler = scheduler;
    mDelayMs = delayMs;
  }

  /** Debouncer on the main thread. */
  @NonNull
  public static Debouncer onMainThread(long delayMs)
  {
    final Handler handler = new Handler(Looper.getMainLooper());
    return new Debouncer((action, delay) -> {
      handler.postDelayed(action, delay);
      return () -> handler.removeCallbacks(action);
    }, delayMs);
  }

  /** Replaces a pending action; {@code action} runs after the delay unless replaced or cancelled before. */
  public void submit(@NonNull Runnable action)
  {
    cancel();
    final Object token = new Object();
    mToken = token;
    final Cancellable pending = mScheduler.schedule(() -> {
      if (mToken != token)
        return;
      mToken = null;
      mPending = null;
      action.run();
    }, mDelayMs);
    // A scheduler may run the action synchronously (delay 0).
    if (mToken == token)
      mPending = pending;
  }

  /** Drops a pending action. */
  public void cancel()
  {
    mToken = null;
    if (mPending != null)
    {
      mPending.cancel();
      mPending = null;
    }
  }

  public boolean isPending()
  {
    return mToken != null;
  }
}
