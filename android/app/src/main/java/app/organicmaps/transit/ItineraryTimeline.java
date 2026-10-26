package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rides and walks of a connection on a time axis (expected times, i.e. with delays), for the bar in the connection
 * list and the columns of the time grid. Times between the segments are waits (transfers). Times are epoch seconds.
 */
public final class ItineraryTimeline
{
  public enum Kind
  {
    /** A ride with a public transport vehicle. */
    RIDE,
    /** Walking (or another way on the street, e.g. cycling). */
    WALK
  }

  public static final class Segment
  {
    @NonNull
    private final Kind mKind;
    private final long mStart;
    private final long mEnd;
    @Nullable
    private final LineBadge mBadge;
    private final boolean mCancelled;

    Segment(@NonNull Kind kind, long start, long end, @Nullable LineBadge badge, boolean cancelled)
    {
      mKind = kind;
      mStart = start;
      mEnd = end;
      mBadge = badge;
      mCancelled = cancelled;
    }

    @NonNull
    public Kind getKind()
    {
      return mKind;
    }

    public long getStart()
    {
      return mStart;
    }

    public long getEnd()
    {
      return mEnd;
    }

    public long getDuration()
    {
      return mEnd - mStart;
    }

    /** Line of a ride, null for walks. */
    @Nullable
    public LineBadge getBadge()
    {
      return mBadge;
    }

    public boolean isCancelled()
    {
      return mCancelled;
    }
  }

  private final long mStart;
  private final long mEnd;
  @NonNull
  private final List<Segment> mSegments;

  ItineraryTimeline(long start, long end, @NonNull List<Segment> segments)
  {
    mStart = start;
    mEnd = end;
    mSegments = Collections.unmodifiableList(segments);
  }

  @NonNull
  public static ItineraryTimeline of(@NonNull TransitItinerary itinerary)
  {
    long start = itinerary.getStartTime().getEpochSecond();
    long end = itinerary.getEndTime().getEpochSecond();
    final List<Segment> segments = new ArrayList<>();
    for (TransitLeg leg : itinerary.getLegs())
    {
      final long legStart = leg.getStartTime().getEpochSecond();
      final long legEnd = leg.getEndTime().getEpochSecond();
      if (legEnd <= legStart)
        continue;
      start = Math.min(start, legStart);
      end = Math.max(end, legEnd);
      if (leg.isTransit())
        segments.add(new Segment(Kind.RIDE, legStart, legEnd, TransitItemFactory.badge(leg), leg.isCancelled()));
      else
        segments.add(new Segment(Kind.WALK, legStart, legEnd, null, false));
    }
    return new ItineraryTimeline(start, Math.max(start, end), segments);
  }

  /** Departure at the start (including a walk to the first stop). */
  public long getStart()
  {
    return mStart;
  }

  /** Arrival at the destination. */
  public long getEnd()
  {
    return mEnd;
  }

  public long getDuration()
  {
    return mEnd - mStart;
  }

  /** Rides and walks in travel order. */
  @NonNull
  public List<Segment> getSegments()
  {
    return mSegments;
  }
}
