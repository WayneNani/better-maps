package app.organicmaps.sdk.crowdtraffic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

public class CrowdTrafficSchedulerTest
{
  private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor();
  private long mNow = 1_000_000L;
  private final List<CrowdTrafficRequest> mDue = new ArrayList<>();
  private boolean mPendingWork;
  private final List<byte[]> mSent = new ArrayList<>();
  private final Deque<CrowdTrafficSender.Result> mResults = new ArrayDeque<>();

  private final CrowdTrafficScheduler.NativeQueue mQueue = new CrowdTrafficScheduler.NativeQueue() {
    @NonNull
    @Override
    public CrowdTrafficRequest[] takeDue(long nowMs)
    {
      final CrowdTrafficRequest[] res = mDue.toArray(new CrowdTrafficRequest[0]);
      mDue.clear();
      return res;
    }

    @Override
    public boolean hasPendingWork()
    {
      return mPendingWork;
    }
  };

  private final CrowdTrafficScheduler.Transport mTransport = request -> {
    mSent.add(request);
    return mResults.isEmpty() ? CrowdTrafficSender.Result.ACCEPTED : mResults.poll();
  };

  private final CrowdTrafficScheduler mScheduler =
      new CrowdTrafficScheduler(mExecutor, mQueue, () -> mNow, new Random(1), message -> {});

  @After
  public void tearDown()
  {
    mExecutor.shutdownNow();
  }

  /** Waits until everything submitted so far has run. */
  private void sync() throws Exception
  {
    mExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
  }

  private void tick() throws Exception
  {
    mScheduler.tickNow();
    sync();
  }

  private static CrowdTrafficRequest request(int id, long deadline)
  {
    return new CrowdTrafficRequest(new byte[] {(byte) id}, deadline, 3);
  }

  @Test
  public void dueBundlesAreSentOnce() throws Exception
  {
    mScheduler.setTransport(mTransport);
    sync();
    mDue.add(request(1, mNow + 60_000));
    mDue.add(request(2, mNow + 60_000));
    tick();
    tick();
    assertEquals(2, mSent.size());
    assertEquals(2, mScheduler.getStats().accepted);
  }

  @Test
  public void temporaryFailuresAreRetriedUntilTheDeadline() throws Exception
  {
    mScheduler.setTransport(mTransport);
    sync();
    mResults.add(CrowdTrafficSender.Result.RETRY);
    mResults.add(CrowdTrafficSender.Result.RETRY);
    mDue.add(request(1, mNow + 300_000));
    tick();
    assertEquals(1, mSent.size());
    // Not before the random retry delay (10–40 s).
    mNow += CrowdTrafficScheduler.RETRY_MIN_MS - 1;
    tick();
    assertEquals(1, mSent.size());
    mNow += CrowdTrafficScheduler.RETRY_MAX_MS;
    tick();
    assertEquals(2, mSent.size());
    mNow += CrowdTrafficScheduler.RETRY_MAX_MS;
    tick();
    assertEquals(3, mSent.size());
    assertEquals(1, mScheduler.getStats().accepted);
    assertEquals(2, mScheduler.getStats().retried);

    // A bundle whose deadline passed is dropped without sending.
    mResults.add(CrowdTrafficSender.Result.RETRY);
    mDue.add(request(2, mNow + 15_000));
    tick();
    mNow += CrowdTrafficScheduler.RETRY_MAX_MS;
    tick();
    assertEquals(4, mSent.size());
    assertEquals(1, mScheduler.getStats().expired);
  }

  @Test
  public void rejectedAndFailedBundlesAreDropped() throws Exception
  {
    mScheduler.setTransport(mTransport);
    sync();
    mResults.add(CrowdTrafficSender.Result.REJECTED);
    mResults.add(CrowdTrafficSender.Result.FAILED);
    mDue.add(request(1, mNow + 60_000));
    mDue.add(request(2, mNow + 60_000));
    tick();
    mNow += 60_000;
    tick();
    assertEquals(2, mSent.size());
    assertEquals(1, mScheduler.getStats().rejected);
    assertEquals(1, mScheduler.getStats().failed);
  }

  @Test
  public void expiredBundlesAreNotSent() throws Exception
  {
    mScheduler.setTransport(mTransport);
    sync();
    mDue.add(request(1, mNow - 1));
    tick();
    assertEquals(0, mSent.size());
    assertEquals(1, mScheduler.getStats().expired);
  }

  @Test
  public void optOutDropsQueuedBundles() throws Exception
  {
    mScheduler.setTransport(mTransport);
    sync();
    mResults.add(CrowdTrafficSender.Result.RETRY);
    mDue.add(request(1, mNow + 300_000));
    tick();
    mScheduler.setTransport(null);
    sync();
    assertEquals(1, mScheduler.getStats().discarded);
    mNow += 100_000;
    tick();
    assertEquals(1, mSent.size());
    assertFalse(mScheduler.isTicking());
  }

  @Test
  public void ticksDuringNavigationAndWhileWorkIsPending() throws Exception
  {
    // Without opt-in, navigation does not start the scheduler.
    mScheduler.onNavigationStarted();
    sync();
    assertFalse(mScheduler.isTicking());

    mScheduler.setTransport(mTransport);
    sync();
    assertTrue(mScheduler.isTicking());
    // After the navigation, the bundles of the trip are still sent at their time.
    mScheduler.onNavigationStopped();
    mPendingWork = true;
    tick();
    assertTrue(mScheduler.isTicking());
    mPendingWork = false;
    tick();
    assertFalse(mScheduler.isTicking());

    // Opt-in while bundles are pending starts ticking as well.
    mScheduler.setTransport(null);
    sync();
    mPendingWork = true;
    mScheduler.setTransport(mTransport);
    sync();
    assertTrue(mScheduler.isTicking());
  }
}
