package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.concurrency.Debouncer;
import java.util.ArrayList;
import java.util.List;

/** Debouncer scheduler whose actions run when the test advances the time. */
class ManualScheduler implements Debouncer.Scheduler
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
  private final List<Task> mTasks = new ArrayList<>();

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

  int pending()
  {
    int count = 0;
    for (Task task : mTasks)
      if (!task.mCancelled)
        ++count;
    return count;
  }
}
