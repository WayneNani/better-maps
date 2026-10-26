package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits the horizontal bar of a connection in the list into pieces: the bar spans the whole connection, every ride
 * gets a width proportional to its duration, walks are dotted lines, waits stay empty. Every connection is scaled
 * to the full width, so all bars are equally wide.
 * <p>
 * Very short rides get a minimum width, so that they stay visible; neighbours are pushed aside, and rides are kept
 * apart by a small gap so that two lines without a wait in between can be told apart.
 */
public final class JourneyBarLayout
{
  public static final class Piece
  {
    @NonNull
    private final ItineraryTimeline.Segment mSegment;
    private final int mLeft;
    private final int mRight;

    Piece(@NonNull ItineraryTimeline.Segment segment, int left, int right)
    {
      mSegment = segment;
      mLeft = left;
      mRight = right;
    }

    @NonNull
    public ItineraryTimeline.Kind getKind()
    {
      return mSegment.getKind();
    }

    @NonNull
    public ItineraryTimeline.Segment getSegment()
    {
      return mSegment;
    }

    /** Line of a ride, null for walks. */
    @Nullable
    public LineBadge getBadge()
    {
      return mSegment.getBadge();
    }

    public int getLeft()
    {
      return mLeft;
    }

    public int getRight()
    {
      return mRight;
    }

    public int getWidth()
    {
      return mRight - mLeft;
    }

    @NonNull
    @Override
    public String toString()
    {
      return getKind() + " " + mLeft + ".." + mRight;
    }
  }

  private JourneyBarLayout() {}

  /**
   * @param width width of the bar in pixels.
   * @param minRideWidth minimum width of a ride in pixels.
   * @param rideGap free pixels between two rides.
   * @return rides and walks from left to right; empty if the connection has no duration.
   */
  @NonNull
  public static List<Piece> layout(@NonNull ItineraryTimeline timeline, int width, int minRideWidth, int rideGap)
  {
    final long total = timeline.getDuration();
    if (total <= 0 || width <= 0)
      return Collections.emptyList();
    // Rides first: they get their minimum width and must not overlap.
    final List<ItineraryTimeline.Segment> rides = new ArrayList<>();
    for (ItineraryTimeline.Segment segment : timeline.getSegments())
      if (segment.getKind() == ItineraryTimeline.Kind.RIDE)
        rides.add(segment);
    final int n = rides.size();
    // With very many rides, the minimum width must leave room for all of them.
    final int minWidth = n == 0 ? 1 : Math.max(1, Math.min(minRideWidth, (width - (n - 1) * rideGap) / n));
    final int[] left = new int[n];
    final int[] right = new int[n];
    for (int i = 0; i < n; ++i)
    {
      int l = x(rides.get(i).getStart(), timeline, width);
      int r = x(rides.get(i).getEnd(), timeline, width);
      if (r - l < minWidth)
      {
        l -= (minWidth - (r - l)) / 2;
        r = l + minWidth;
      }
      left[i] = l;
      right[i] = r;
    }
    // Keep rides apart: shorten the previous ride if it stays wide enough, otherwise push this one to the right.
    // Then push back from the end of the bar.
    if (n > 0 && left[0] < 0)
    {
      right[0] -= left[0];
      left[0] = 0;
    }
    for (int i = 1; i < n; ++i)
    {
      int overlap = right[i - 1] + rideGap - left[i];
      if (overlap <= 0)
        continue;
      final int shrink = Math.max(0, Math.min(overlap, right[i - 1] - left[i - 1] - minWidth));
      right[i - 1] -= shrink;
      overlap -= shrink;
      left[i] += overlap;
      right[i] += overlap;
    }
    int max = width;
    for (int i = n - 1; i >= 0; --i)
    {
      if (right[i] > max)
      {
        final int w = right[i] - left[i];
        right[i] = max;
        left[i] = max - w;
      }
      max = left[i] - rideGap;
    }
    // Safety net for rounding: everything inside the bar and in order.
    int min = 0;
    for (int i = 0; i < n; ++i)
    {
      left[i] = Math.min(Math.max(left[i], min), width);
      right[i] = Math.min(Math.max(right[i], left[i]), width);
      min = right[i] + rideGap;
    }

    final List<Piece> pieces = new ArrayList<>();
    int ride = 0;
    for (ItineraryTimeline.Segment segment : timeline.getSegments())
    {
      if (segment.getKind() == ItineraryTimeline.Kind.RIDE)
      {
        if (right[ride] > left[ride])
          pieces.add(new Piece(segment, left[ride], right[ride]));
        ++ride;
        continue;
      }
      // Walks fill the time between the neighbouring rides only.
      int l = x(segment.getStart(), timeline, width);
      int r = x(segment.getEnd(), timeline, width);
      if (ride > 0)
        l = Math.max(l, right[ride - 1]);
      if (ride < n)
        r = Math.min(r, left[ride]);
      if (r > l)
        pieces.add(new Piece(segment, l, r));
    }
    return Collections.unmodifiableList(pieces);
  }

  /** True if a text of this width fits into a piece with the given padding on both sides. */
  public static boolean fitsLabel(int pieceWidth, float textWidth, int padding)
  {
    return textWidth + 2 * padding <= pieceWidth;
  }

  private static int x(long time, @NonNull ItineraryTimeline timeline, int width)
  {
    final double fraction = (double) (time - timeline.getStart()) / timeline.getDuration();
    return (int) Math.max(0, Math.min(width, Math.round(fraction * width)));
  }
}
