package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Geometry of the time grid ("Zeitraster"): a common vertical time axis, one column per connection, rides as
 * blocks at their real (expected) times. The time range follows the shown connections. Pure computation without
 * Android, in pixels; the view only draws the result.
 */
public final class TimeGridLayout
{
  /** Tick intervals in minutes, from fine to coarse. */
  static final int[] TICK_MINUTES = {5, 10, 15, 20, 30, 60, 120, 180, 240, 360, 720};
  /** "Now" is added to the range if the first connection leaves at most this much later. */
  static final long NOW_MARGIN_SECONDS = 15 * 60;
  /** The range starts and ends on multiples of this (local time). */
  static final long RANGE_ROUNDING_SECONDS = 5 * 60;

  /** Sizes in pixels. */
  public static final class Params
  {
    /** Height of the whole grid. */
    public int height;
    /** Space above the earliest departure (for the departure times). */
    public int top;
    /** Space below the latest arrival (for the arrival times). */
    public int bottom;
    /** Width of the axis labels on the left. */
    public int axisWidth;
    public int columnWidth;
    /** Space between two columns and before the first one. */
    public int columnGap;
    /** Minimum distance between two axis ticks. */
    public int minTickSpacing;
    /** Largest scale, so that short connections are not stretched over the whole height. */
    public float maxPixelsPerMinute;
    /** Minimum height of a ride block, so that very short rides stay visible. */
    public int minBlockHeight;
  }

  public static final class Tick
  {
    private final long mTime;
    private final int mY;

    Tick(long time, int y)
    {
      mTime = time;
      mY = y;
    }

    /** Epoch seconds. */
    public long getTime()
    {
      return mTime;
    }

    @NonNull
    public Instant getInstant()
    {
      return Instant.ofEpochSecond(mTime);
    }

    public int getY()
    {
      return mY;
    }
  }

  public static final class Block
  {
    @NonNull
    private final ItineraryTimeline.Segment mSegment;
    private final int mTop;
    private final int mBottom;

    Block(@NonNull ItineraryTimeline.Segment segment, int top, int bottom)
    {
      mSegment = segment;
      mTop = top;
      mBottom = bottom;
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

    @Nullable
    public LineBadge getBadge()
    {
      return mSegment.getBadge();
    }

    public int getTop()
    {
      return mTop;
    }

    public int getBottom()
    {
      return mBottom;
    }

    public int getHeight()
    {
      return mBottom - mTop;
    }
  }

  public static final class Column
  {
    private final int mLeft;
    private final int mRight;
    private final int mTop;
    private final int mBottom;
    @NonNull
    private final List<Block> mBlocks;

    Column(int left, int right, int top, int bottom, @NonNull List<Block> blocks)
    {
      mLeft = left;
      mRight = right;
      mTop = top;
      mBottom = bottom;
      mBlocks = Collections.unmodifiableList(blocks);
    }

    public int getLeft()
    {
      return mLeft;
    }

    public int getRight()
    {
      return mRight;
    }

    public int getCenterX()
    {
      return (mLeft + mRight) / 2;
    }

    /** y of the departure. */
    public int getTop()
    {
      return mTop;
    }

    /** y of the arrival. */
    public int getBottom()
    {
      return mBottom;
    }

    @NonNull
    public List<Block> getBlocks()
    {
      return mBlocks;
    }
  }

  private final long mRangeStart;
  private final long mRangeEnd;
  private final int mTop;
  private final double mPixelsPerSecond;
  private final int mTickMinutes;
  @NonNull
  private final List<Tick> mTicks;
  @NonNull
  private final List<Column> mColumns;
  @Nullable
  private final Integer mNowY;
  private final int mContentWidth;
  private final int mColumnGap;

  private TimeGridLayout(long rangeStart, long rangeEnd, int top, double pixelsPerSecond, int tickMinutes,
                         @NonNull List<Tick> ticks, @NonNull List<Column> columns, @Nullable Integer nowY,
                         int contentWidth, int columnGap)
  {
    mRangeStart = rangeStart;
    mRangeEnd = rangeEnd;
    mTop = top;
    mPixelsPerSecond = pixelsPerSecond;
    mTickMinutes = tickMinutes;
    mTicks = Collections.unmodifiableList(ticks);
    mColumns = Collections.unmodifiableList(columns);
    mNowY = nowY;
    mContentWidth = contentWidth;
    mColumnGap = columnGap;
  }

  /**
   * @param timelines the connections, one column each, in the order shown.
   * @param now epoch seconds.
   * @param zone time zone of the axis labels (the device zone); ticks are on full local minutes.
   */
  @NonNull
  public static TimeGridLayout compute(@NonNull List<ItineraryTimeline> timelines, long now, @NonNull ZoneId zone,
                                       @NonNull Params params)
  {
    long start = Long.MAX_VALUE;
    long end = Long.MIN_VALUE;
    for (ItineraryTimeline timeline : timelines)
    {
      start = Math.min(start, timeline.getStart());
      end = Math.max(end, timeline.getEnd());
    }
    if (timelines.isEmpty())
    {
      start = now;
      end = now + 3600;
    }
    if (now < start && start - now <= NOW_MARGIN_SECONDS)
      start = now;
    start = floorLocal(start, RANGE_ROUNDING_SECONDS, zone);
    end = Math.max(start + RANGE_ROUNDING_SECONDS, ceilLocal(end, RANGE_ROUNDING_SECONDS, zone));

    final int available = Math.max(1, params.height - params.top - params.bottom);
    double pixelsPerSecond = (double) available / (end - start);
    final double max = params.maxPixelsPerMinute / 60.0;
    if (max > 0 && pixelsPerSecond > max)
    {
      // Short connections: keep the scale and show more time below.
      pixelsPerSecond = max;
      end = start + (long) Math.floor(available / pixelsPerSecond);
    }

    int tickMinutes = TICK_MINUTES[TICK_MINUTES.length - 1];
    for (int minutes : TICK_MINUTES)
    {
      if (minutes * 60 * pixelsPerSecond >= params.minTickSpacing)
      {
        tickMinutes = minutes;
        break;
      }
    }
    final List<Tick> ticks = new ArrayList<>();
    final long step = tickMinutes * 60L;
    for (long t = ceilLocal(start, step, zone); t <= end; t += step)
      ticks.add(new Tick(t, y(t, start, params.top, pixelsPerSecond)));

    final List<Column> columns = new ArrayList<>(timelines.size());
    for (int i = 0; i < timelines.size(); ++i)
    {
      final ItineraryTimeline timeline = timelines.get(i);
      final int left = params.axisWidth + params.columnGap + i * (params.columnWidth + params.columnGap);
      final int bottomLimit = y(end, start, params.top, pixelsPerSecond);
      final List<Block> blocks = new ArrayList<>();
      for (ItineraryTimeline.Segment segment : timeline.getSegments())
      {
        int top = y(segment.getStart(), start, params.top, pixelsPerSecond);
        int bottom = y(segment.getEnd(), start, params.top, pixelsPerSecond);
        if (segment.getKind() == ItineraryTimeline.Kind.RIDE && bottom - top < params.minBlockHeight)
        {
          bottom = top + params.minBlockHeight;
          if (bottom > bottomLimit)
          {
            bottom = bottomLimit;
            top = Math.min(top, bottom - params.minBlockHeight);
          }
        }
        if (bottom > top)
          blocks.add(new Block(segment, top, bottom));
      }
      columns.add(new Column(left, left + params.columnWidth,
                             y(timeline.getStart(), start, params.top, pixelsPerSecond),
                             y(timeline.getEnd(), start, params.top, pixelsPerSecond), blocks));
    }

    final Integer nowY = now >= start && now <= end ? y(now, start, params.top, pixelsPerSecond) : null;
    final int contentWidth =
        params.axisWidth + params.columnGap + timelines.size() * (params.columnWidth + params.columnGap);
    return new TimeGridLayout(start, end, params.top, pixelsPerSecond, tickMinutes, ticks, columns, nowY, contentWidth,
                              params.columnGap);
  }

  /** Start of the axis, epoch seconds. */
  public long getRangeStart()
  {
    return mRangeStart;
  }

  /** End of the axis, epoch seconds. */
  public long getRangeEnd()
  {
    return mRangeEnd;
  }

  public double getPixelsPerMinute()
  {
    return mPixelsPerSecond * 60;
  }

  public int getTickMinutes()
  {
    return mTickMinutes;
  }

  @NonNull
  public List<Tick> getTicks()
  {
    return mTicks;
  }

  @NonNull
  public List<Column> getColumns()
  {
    return mColumns;
  }

  /** y of the current time, null if it is outside the range. */
  @Nullable
  public Integer getNowY()
  {
    return mNowY;
  }

  /** Width of axis and all columns. */
  public int getContentWidth()
  {
    return mContentWidth;
  }

  /** y of a time (epoch seconds). */
  public int y(long time)
  {
    return y(time, mRangeStart, mTop, mPixelsPerSecond);
  }

  /** Index of the column at x (including half of the gaps around it), -1 if none. */
  public int columnAt(float x)
  {
    for (int i = 0; i < mColumns.size(); ++i)
    {
      final Column column = mColumns.get(i);
      if (x >= column.getLeft() - mColumnGap / 2f && x < column.getRight() + mColumnGap / 2f)
        return i;
    }
    return -1;
  }

  private static int y(long time, long start, int top, double pixelsPerSecond)
  {
    return top + (int) Math.round((time - start) * pixelsPerSecond);
  }

  private static long offset(long time, @NonNull ZoneId zone)
  {
    return zone.getRules().getOffset(Instant.ofEpochSecond(time)).getTotalSeconds();
  }

  static long floorLocal(long time, long step, @NonNull ZoneId zone)
  {
    final long local = time + offset(time, zone);
    return local - Math.floorMod(local, step) - offset(time, zone);
  }

  static long ceilLocal(long time, long step, @NonNull ZoneId zone)
  {
    final long floor = floorLocal(time, step, zone);
    return floor == time ? time : floor + step;
  }
}
