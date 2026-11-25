package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;

/**
 * Snapping of a ride that is dragged up or down in the time grid: it jumps to the nearest of its other departures.
 * Pure computation in pixels without Android; the view feeds it the positions of the departures (the centers of the
 * ghost pills, top to bottom) and the finger.
 * <p>
 * The finger moves a point that starts at the departure the connection uses ("original"): moving the finger by 30
 * pixels moves the point by 30 pixels, and the departure nearest to the point is the target. To switch to another
 * departure the point has to be nearer to it than to the current target by more than the hysteresis, so that the
 * target does not flicker between two departures when the finger rests in the middle.
 * <p>
 * The targets can arrive after the drag started (the departures are still loading) and change during the drag (the
 * grid is laid out again): the current target stays under the finger, the drag goes on from there.
 */
public final class RideDragSnap
{
  private final float mHysteresis;
  @Nullable
  private float[] mTargets;
  private int mOriginal = -1;
  private int mIndex = -1;
  // The point is at mAnchorPoint while the finger is at mAnchorFinger.
  private float mAnchorFinger;
  private float mAnchorPoint;

  /** @param hysteresis pixels, see the class description. */
  public RideDragSnap(float hysteresis)
  {
    mHysteresis = Math.max(0, hysteresis);
  }

  /**
   * Sets or updates the departures.
   *
   * @param targets y of each departure, top to bottom; empty if there are none (the drag does nothing).
   * @param original index of the departure the connection uses, i.e. where the ride is; out of range for unknown, then
   *                 the first call starts at {@code originalY}.
   * @param originalY y of the ride if {@code original} is unknown.
   * @param fingerY where the finger is now.
   */
  public void setTargets(@NonNull float[] targets, int original, float originalY, float fingerY)
  {
    if (targets.length == 0)
    {
      mTargets = null;
      mOriginal = -1;
      mIndex = -1;
      return;
    }
    if (mTargets != null && Arrays.equals(mTargets, targets) && original == mOriginal)
      return;
    final float[] old = mTargets;
    mOriginal = original >= 0 && original < targets.length ? original : nearest(targets, originalY);
    if (old == null || mIndex < 0)
    {
      // The ride is where it was: the finger is at the original departure now.
      mIndex = mOriginal;
      mAnchorFinger = fingerY;
      mAnchorPoint = targets[mIndex];
    }
    else
    {
      // Keep the current target and the finger's offset from it.
      final float offset = point(fingerY) - old[Math.min(mIndex, old.length - 1)];
      mIndex = Math.min(mIndex, targets.length - 1);
      mAnchorFinger = fingerY;
      mAnchorPoint = targets[mIndex] + offset;
    }
    mTargets = targets.clone();
  }

  /** True once there are departures to snap to. */
  public boolean hasTargets()
  {
    return mTargets != null;
  }

  /**
   * The finger moved.
   *
   * @return true if the target changed (e.g. for a haptic tick).
   */
  public boolean move(float fingerY)
  {
    final float[] targets = mTargets;
    if (targets == null)
      return false;
    final float point = point(fingerY);
    final int best = nearest(targets, point);
    if (best == mIndex)
      return false;
    if (Math.abs(point - targets[mIndex]) - Math.abs(point - targets[best]) <= mHysteresis)
      return false;
    mIndex = best;
    return true;
  }

  /** The departure the ride snaps to now, -1 without departures. */
  public int getIndex()
  {
    return mIndex;
  }

  /** The departure the connection uses, -1 without departures. */
  public int getOriginal()
  {
    return mOriginal;
  }

  /** The departure to use when the finger is lifted now, -1 to change nothing (back at the original, or none). */
  public int result()
  {
    return mTargets != null && mIndex != mOriginal ? mIndex : -1;
  }

  private float point(float fingerY)
  {
    return mAnchorPoint + fingerY - mAnchorFinger;
  }

  /** Index of the target nearest to y (the earlier one on a tie), -1 if there is none. */
  public static int nearest(@NonNull float[] targets, float y)
  {
    int best = -1;
    float distance = Float.POSITIVE_INFINITY;
    for (int i = 0; i < targets.length; ++i)
    {
      final float d = Math.abs(targets[i] - y);
      if (d < distance)
      {
        distance = d;
        best = i;
      }
    }
    return best;
  }

  /** Index of the departure time nearest to {@code epochSecond}, -1 if there is none. */
  public static int nearestTime(@NonNull long[] departures, long epochSecond)
  {
    int best = -1;
    long distance = Long.MAX_VALUE;
    for (int i = 0; i < departures.length; ++i)
    {
      final long d = Math.abs(departures[i] - epochSecond);
      if (d < distance)
      {
        distance = d;
        best = i;
      }
    }
    return best;
  }
}
