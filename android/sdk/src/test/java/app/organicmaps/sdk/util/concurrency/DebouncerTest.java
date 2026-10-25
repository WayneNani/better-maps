package app.organicmaps.sdk.util.concurrency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DebouncerTest
{
  /** Manual clock: actions run when the test advances the time. */
  private static final class FakeScheduler implements Debouncer.Scheduler
  {
    private static final class Task
    {
      final Runnable mAction;
      final long mDueMs;
      boolean mCancelled;

      Task(Runnable action, long dueMs)
      {
        mAction = action;
        mDueMs = dueMs;
      }
    }

    long mNow;
    final List<Task> mTasks = new ArrayList<>();

    @NonNull
    @Override
    public Debouncer.Cancellable schedule(@NonNull Runnable action, long delayMs)
    {
      final Task task = new Task(action, mNow + delayMs);
      mTasks.add(task);
      return () -> task.mCancelled = true;
    }

    void advance(long ms)
    {
      mNow += ms;
      for (Task task : new ArrayList<>(mTasks))
      {
        if (!task.mCancelled && task.mDueMs <= mNow)
        {
          mTasks.remove(task);
          task.mAction.run();
        }
      }
    }
  }

  @Test
  public void runsOnlyLastActionAfterQuietPeriod()
  {
    final FakeScheduler scheduler = new FakeScheduler();
    final Debouncer debouncer = new Debouncer(scheduler, 500);
    final List<String> runs = new ArrayList<>();

    debouncer.submit(() -> runs.add("a"));
    scheduler.advance(300);
    debouncer.submit(() -> runs.add("b"));
    scheduler.advance(300);
    assertTrue(runs.isEmpty());
    assertTrue(debouncer.isPending());
    debouncer.submit(() -> runs.add("c"));
    scheduler.advance(499);
    assertTrue(runs.isEmpty());
    scheduler.advance(1);
    assertEquals(List.of("c"), runs);
    assertFalse(debouncer.isPending());

    scheduler.advance(10_000);
    assertEquals(1, runs.size());
  }

  @Test
  public void cancelDropsPendingAction()
  {
    final FakeScheduler scheduler = new FakeScheduler();
    final Debouncer debouncer = new Debouncer(scheduler, 100);
    final List<String> runs = new ArrayList<>();
    debouncer.submit(() -> runs.add("a"));
    debouncer.cancel();
    assertFalse(debouncer.isPending());
    scheduler.advance(1000);
    assertTrue(runs.isEmpty());
    // Cancelling twice is harmless.
    debouncer.cancel();
  }

  @Test
  public void synchronousSchedulerRunsImmediately()
  {
    final List<String> runs = new ArrayList<>();
    final Debouncer debouncer = new Debouncer((action, delayMs) -> {
      action.run();
      return () -> {};
    }, 0);
    debouncer.submit(() -> runs.add("a"));
    assertEquals(List.of("a"), runs);
    assertFalse(debouncer.isPending());
  }

  @Test
  public void negativeDelayIsRejected()
  {
    assertThrows(IllegalArgumentException.class, () -> new Debouncer((a, d) -> () -> {}, -1));
  }
}
