package app.organicmaps.sdk.crowdtraffic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Takes the bundles that are due from the native client and sends them (docs/CROWD_TRAFFIC_PRIVACY.md, 7.8
 * and 13.5).
 * <p>
 * The native client decides when a bundle is due (30–240 s after the end of its window). The scheduler polls
 * it every few seconds while a navigation runs and afterwards until nothing is pending. Bundles are kept in
 * memory only: no report data is written to disk (WorkManager would persist them in its database). If the
 * process dies, unsent bundles are lost, which is acceptable for anonymous statistics.
 * <p>
 * Temporary failures are retried with a random delay until the deadline of the bundle (end of the server's
 * grace period), then the bundle is dropped. All work runs on the given single-threaded executor.
 */
public final class CrowdTrafficScheduler
{
  /** Access to the native client. */
  public interface NativeQueue
  {
    @NonNull
    CrowdTrafficRequest[] takeDue(long nowMs);

    boolean hasPendingWork();
  }

  public interface Transport
  {
    @NonNull
    CrowdTrafficSender.Result send(@NonNull byte[] request);
  }

  static final long TICK_MS = 5_000;
  static final long RETRY_MIN_MS = 10_000;
  static final long RETRY_MAX_MS = 40_000;

  private static final class Pending
  {
    @NonNull
    final CrowdTrafficRequest request;
    long notBeforeMs;

    Pending(@NonNull CrowdTrafficRequest request, long notBeforeMs)
    {
      this.request = request;
      this.notBeforeMs = notBeforeMs;
    }
  }

  /** Counters for logging and tests (no report data). */
  public static final class Stats
  {
    public int sent;
    public int accepted;
    public int rejected;
    public int failed;
    public int retried;
    public int expired;
    public int discarded;
  }

  @NonNull
  private final ScheduledExecutorService mExecutor;
  @NonNull
  private final NativeQueue mQueue;
  @NonNull
  private final CrowdTrafficSender.Clock mClock;
  @NonNull
  private final Random mRandom;
  @NonNull
  private final CrowdTrafficSender.Log mLog;

  // Accessed on the executor thread only.
  @Nullable
  private Transport mTransport;
  private boolean mNavigating;
  @Nullable
  private ScheduledFuture<?> mTick;
  private final List<Pending> mPending = new ArrayList<>();
  private final Stats mStats = new Stats();

  public CrowdTrafficScheduler(@NonNull ScheduledExecutorService executor, @NonNull NativeQueue queue,
                               @NonNull CrowdTrafficSender.Clock clock, @NonNull Random random,
                               @NonNull CrowdTrafficSender.Log log)
  {
    mExecutor = executor;
    mQueue = queue;
    mClock = clock;
    mRandom = random;
    mLog = log;
  }

  /** Sets the transport (opt-in and relay configured) or removes it (opt-out): unsent bundles are dropped. */
  public void setTransport(@Nullable Transport transport)
  {
    mExecutor.execute(() -> {
      mTransport = transport;
      if (transport == null)
      {
        mStats.discarded += mPending.size();
        mPending.clear();
        stopTicking();
      }
      else if (mNavigating || mQueue.hasPendingWork())
      {
        startTicking();
      }
    });
  }

  public void onNavigationStarted()
  {
    mExecutor.execute(() -> {
      mNavigating = true;
      if (mTransport != null)
        startTicking();
    });
  }

  /** Bundles of the trip are still sent at their time; ticking stops when nothing is left. */
  public void onNavigationStopped()
  {
    mExecutor.execute(() -> mNavigating = false);
  }

  /** Runs one tick now (tests). */
  void tickNow()
  {
    mExecutor.execute(this::tick);
  }

  @NonNull
  Stats getStats()
  {
    return mStats;
  }

  boolean isTicking()
  {
    return mTick != null;
  }

  private void startTicking()
  {
    if (mTick == null)
      mTick = mExecutor.scheduleWithFixedDelay(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
  }

  private void stopTicking()
  {
    if (mTick != null)
    {
      mTick.cancel(false);
      mTick = null;
    }
  }

  private void tick()
  {
    final Transport transport = mTransport;
    if (transport == null)
    {
      stopTicking();
      return;
    }
    final long now = mClock.nowMs();
    for (CrowdTrafficRequest r : mQueue.takeDue(now))
      mPending.add(new Pending(r, now));

    for (Iterator<Pending> it = mPending.iterator(); it.hasNext();)
    {
      final Pending p = it.next();
      if (p.notBeforeMs > now)
        continue;
      if (now >= p.request.deadlineMs)
      {
        ++mStats.expired;
        it.remove();
        continue;
      }
      ++mStats.sent;
      final CrowdTrafficSender.Result result = transport.send(p.request.request);
      switch (result)
      {
      case ACCEPTED -> ++mStats.accepted;
      case REJECTED -> ++mStats.rejected;
      case FAILED -> ++mStats.failed;
      case RETRY -> {
        final long retryAt =
            mClock.nowMs() + RETRY_MIN_MS + (long) (mRandom.nextDouble() * (RETRY_MAX_MS - RETRY_MIN_MS));
        if (retryAt < p.request.deadlineMs)
        {
          ++mStats.retried;
          p.notBeforeMs = retryAt;
          continue;
        }
        ++mStats.expired;
      }
      }
      mLog.log("bundle with " + p.request.reports + " reports: " + result);
      it.remove();
    }

    if (!mNavigating && mPending.isEmpty() && !mQueue.hasPendingWork())
      stopTicking();
  }
}
