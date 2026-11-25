package app.organicmaps.transit;

import androidx.annotation.NonNull;

/**
 * Geometry of the lane with the other departures of a selected ride in the time grid ({@link TimeGridLayout}).
 * <p>
 * The lane is drawn over the grid next to the selected column, over the neighbouring column (faded while a ride is
 * selected) or the "Earlier" / "Later" column: nothing is laid out again when it appears or disappears, so the selected
 * column and its ride stay exactly where they are, under the finger that holds the ride. For the same reason the time
 * axis is not widened for departures outside it: their pills stay at the edge of the range.
 * <p>
 * Pure computation in pixels without Android.
 */
public final class GridLane
{
  private GridLane() {}

  /**
   * Which side of the selected column the lane goes: left if it is completely in view there, else right if it is
   * completely in view there, else the side where more of it is in view (left on a tie).
   *
   * @param columnLeft left edge of the selected column.
   * @param columnRight right edge of the selected column.
   * @param laneWidth width of the lane.
   * @param visibleLeft left edge of what is in view (e.g. the right edge of the axis that stays in place).
   * @param visibleRight right edge of what is in view.
   * @return true for the right side.
   */
  public static boolean onRight(int columnLeft, int columnRight, int laneWidth, int visibleLeft, int visibleRight)
  {
    final int leftInView = Math.min(columnLeft, visibleRight) - Math.max(columnLeft - laneWidth, visibleLeft);
    if (leftInView >= laneWidth)
      return false;
    final int rightInView = Math.min(columnRight + laneWidth, visibleRight) - Math.max(columnRight, visibleLeft);
    return rightInView > leftInView;
  }

  /** Left edge of the lane, see {@link #onRight}. */
  public static int left(int columnLeft, int columnRight, int laneWidth, boolean onRight)
  {
    return onRight ? columnRight : columnLeft - laneWidth;
  }

  /**
   * Tops of the pills of the departures: centered at their times where possible, inside the range, and at least
   * {@code spacing} apart, keeping their order. If they do not all fit into the range, the last ones go below it.
   *
   * @param centers y of each departure, top to bottom.
   * @param height height of a pill.
   * @param spacing space between two pills.
   * @param minTop top of the range (e.g. the y of the first time on the axis).
   * @param maxBottom bottom of the range.
   */
  @NonNull
  public static float[] pillTops(@NonNull float[] centers, float height, float spacing, float minTop, float maxBottom)
  {
    final int n = centers.length;
    final float[] tops = new float[n];
    final float step = height + spacing;
    // Down: inside the range at the top, below the previous pill.
    for (int i = 0; i < n; ++i)
    {
      float top = Math.max(centers[i] - height / 2, minTop);
      if (i > 0)
        top = Math.max(top, tops[i - 1] + step);
      tops[i] = top;
    }
    // Up: inside the range at the bottom, above the next pill.
    for (int i = n - 1; i >= 0; --i)
    {
      float top = Math.min(tops[i], maxBottom - height);
      if (i < n - 1)
        top = Math.min(top, tops[i + 1] - step);
      tops[i] = top;
    }
    // Too many for the range: the first ones stay at the top, the last ones go below.
    for (int i = 0; i < n; ++i)
      tops[i] = Math.max(tops[i], i > 0 ? tops[i - 1] + step : minTop);
    return tops;
  }

  /**
   * Vertical touch areas of the pills: at least {@code minHeight} high around a pill where it fits, otherwise up to
   * the middle between two pills, so that each point belongs to one pill at most.
   *
   * @param tops tops of the pills, top to bottom (see {@link #pillTops}).
   * @return top and bottom of each area, {@code [2 * i]} and {@code [2 * i + 1]}.
   */
  @NonNull
  public static float[] touchAreas(@NonNull float[] tops, float height, float minHeight)
  {
    final int n = tops.length;
    final float[] areas = new float[2 * n];
    final float extra = Math.max(0, (minHeight - height) / 2);
    for (int i = 0; i < n; ++i)
    {
      float top = tops[i] - extra;
      float bottom = tops[i] + height + extra;
      if (i > 0)
        top = Math.max(top, (tops[i - 1] + height + tops[i]) / 2);
      if (i < n - 1)
        bottom = Math.min(bottom, (tops[i] + height + tops[i + 1]) / 2);
      areas[2 * i] = top;
      areas[2 * i + 1] = bottom;
    }
    return areas;
  }

  /**
   * Where the lifted ride is drawn at a departure: at its time, moved into the range if it is outside (like its
   * pill), keeping its height.
   *
   * @return the top.
   */
  public static float liftedTop(float top, float height, float minTop, float maxBottom)
  {
    return Math.max(minTop, Math.min(top, maxBottom - height));
  }
}
