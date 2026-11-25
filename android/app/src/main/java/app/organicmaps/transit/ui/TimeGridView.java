package app.organicmaps.transit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import app.organicmaps.R;
import app.organicmaps.transit.AlternativesItem;
import app.organicmaps.transit.GridLane;
import app.organicmaps.transit.ItineraryItem;
import app.organicmaps.transit.ItineraryTimeline;
import app.organicmaps.transit.JourneyBarLayout;
import app.organicmaps.transit.LineBadge;
import app.organicmaps.transit.RideDragSnap;
import app.organicmaps.transit.TimeGridLayout;
import app.organicmaps.transit.TransitFormatter;
import app.organicmaps.util.ThemeUtils;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The time grid ("Zeitraster"): a vertical time axis on the left and one column per connection with its rides as
 * blocks in line colors, walks as dotted lines, the departure above and the arrival below each column, and a red
 * line for the current time. The geometry comes from {@link TimeGridLayout}.
 * <p>
 * The view lives in a {@link android.widget.HorizontalScrollView}; the axis stays at the left edge while the
 * columns scroll. "Earlier" and "Later" are narrow columns at the start and the end.
 * <p>
 * A tap anywhere in a column, on a ride or beside it, opens the connection.
 * <p>
 * Holding a ride ({@link #HOLD_MS}, with a haptic long press) selects it and lifts it in one gesture. Selected, its
 * other departures are shown: the other columns fade, the column gets a background and a lane beside it with small
 * "ghost" pills at the departure times (dashed if they miss the next ride). The lane is drawn over the faded neighbour
 * (on the left if it fits into the view there, else on the right, see {@link GridLane}): nothing is laid out again and
 * the time axis stays as it is, so that the held ride stays exactly under the finger. Departures outside the time axis
 * are at its edge. Lifted, the ride can be dragged up and down right away and snaps to its other departures
 * ({@link RideDragSnap}) with a haptic tick each, the target pill highlighted. Lifting the finger uses that departure
 * as a tap on its pill would; lifted without moving, back at the start, or cancelled, nothing changes and the ride
 * stays selected, so that its pills can be tapped. While the departures are loading the ride is lifted in place. The
 * scrolling parents do not get the gesture while a ride is lifted.
 * <p>
 * While a ride is selected, a tap on a pill uses that departure, "Earlier" and "Later" page as usual, and any other
 * tap (on the selected ride, another ride or beside the rides) only ends the selection; it never opens a connection,
 * so that nothing opens by accident while the departures are showing. Holding a ride works as without a selection.
 * <p>
 * Screen readers: a ride's click opens the connection (or ends the selection), its long click shows or hides its
 * other departures.
 */
public final class TimeGridView extends View
{
  public interface Listener
  {
    void onConnectionClick(int index);

    void onEarlierClick();

    void onLaterClick();

    /** A ride of a connection was held (or long-clicked by a screen reader): show (or hide) its other departures. */
    default void onRideLongClick(int index, int legIndex) {}

    /** An other departure of the selected ride was tapped. */
    default void onAlternativeClick(int chipIndex) {}

    /** A tap while a ride is selected, other than on a pill or "Earlier" / "Later": end the selection. */
    default void onDismissAlternatives() {}

    /**
     * A lifted ride snapped to another departure (or back to its own): show what it would mean. -1 when the drag ends
     * (then {@link #onAlternativeClick} follows if the ride was dropped at another departure).
     */
    default void onAlternativePreview(int chipIndex) {}
  }

  /** A held ride, lifted to be dragged to its other departures. */
  private static final class Drag
  {
    final int mConnection;
    final int mLegIndex;
    final RideDragSnap mSnap;
    float mFingerY;
    // The pills the snap knows, to update it when they move.
    @Nullable
    List<RectF> mPills;
    // The departures of this ride were shown during the drag.
    boolean mShown;

    Drag(int connection, int legIndex, @NonNull RideDragSnap snap, float fingerY)
    {
      mConnection = connection;
      mLegIndex = legIndex;
      mSnap = snap;
      mFingerY = fingerY;
    }
  }

  private static final float MIN_AXIS_DP = 40;
  private static final float COLUMN_DP = 56;
  private static final float GAP_DP = 8;
  private static final float PAGING_DP = 40;
  private static final float LABEL_SP = 12;
  private static final float BLOCK_TEXT_SP = 11;
  private static final float MIN_TICK_SPACING_DP = 28;
  private static final float MAX_DP_PER_MINUTE = 10;
  private static final float MIN_BLOCK_DP = 4;
  private static final float CORNER_DP = 4;
  private static final float DOT_DP = 3;
  private static final float DOT_SPACING_DP = 6;
  private static final int CANCELLED_ALPHA = 0x66;
  /** Columns other than the one with a selected ride. */
  private static final int FADED_ALPHA = 0x5A;
  private static final float LANE_DP = 46;
  private static final float LABEL_OVERHANG_DP = 20;
  private static final float PILL_DP = 17;
  /** Touch targets of the pills are this high where they fit. */
  private static final float PILL_TOUCH_DP = 44;
  private static final float PILL_TEXT_SP = 10;
  private static final float STRIPE_DP = 4;
  /** Taps this close to a ride count, so that small rides are easy to hit. */
  private static final float TOUCH_DP = 22;
  /** Holding a ride this long lifts it for dragging. */
  @VisibleForTesting
  public static final long HOLD_MS = 300;
  /** See {@link RideDragSnap}: a little more than the middle between two departures switches. */
  private static final float SNAP_HYSTERESIS_DP = 3;
  /** A lifted ride is this much wider on each side. */
  private static final float LIFT_DP = 3;

  /** Virtual view ids for accessibility; connections use their index. */
  private static final int ID_EARLIER = -10;
  private static final int ID_LATER = -11;
  /** Tap outside the selected ride while it is selected. */
  private static final int ID_DISMISS = -12;
  /** Pills: ID_PILL - chip index. */
  private static final int ID_PILL = -1000;
  /** Rides: ID_RIDE + column * RIDE_STRIDE + leg index. */
  private static final int ID_RIDE = 1_000_000;
  private static final int RIDE_STRIDE = 1000;

  private final float mDensity;
  private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mBlockText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mAxisText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mGridLine = new Paint();
  private final Paint mWalk = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mNowLine = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mPagingFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mPagingText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mSelection = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mPill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mPillStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mPillDashed = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mPillText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mSlot = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mTarget = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mLane = new Paint();
  private final RectF mRect = new RectF();
  private final Paint.FontMetrics mMetrics = new Paint.FontMetrics();
  @ColorInt
  private final int mTextColor;
  @ColorInt
  private final int mLateColor;
  @ColorInt
  private final int mOnTimeColor;
  @ColorInt
  private final int mSecondaryColor;
  @ColorInt
  private final int mAccentColor;
  @ColorInt
  private final int mChipColor;
  @ColorInt
  private final int mChipMissedColor;
  private final GestureDetector mGestures;
  private final AccessHelper mAccess;
  private final ViewTreeObserver.OnScrollChangedListener mScrollListener = this::invalidate;
  private final int mTouchSlop;
  private final Runnable mHold = this::startDrag;

  @NonNull
  private List<ItineraryItem> mItems = Collections.emptyList();
  @Nullable
  private TransitFormatter mFormatter;
  @NonNull
  private Instant mNow = Instant.EPOCH;
  private boolean mShowEarlier;
  private boolean mShowLater;
  private boolean mLoadingEarlier;
  private boolean mLoadingLater;
  @Nullable
  private Listener mListener;
  @Nullable
  private TimeGridLayout mLayout;
  // Wide enough for the longest time label, e.g. "10:58 PM".
  private float mAxisWidth;
  // Other departures of the selected ride, null without a selection.
  @Nullable
  private AlternativesItem mAlternatives;
  // Pills of the other departures in view coordinates, in the order of the chips; computed with the layout.
  @Nullable
  private List<RectF> mPills;
  // Touch areas of the pills (top and bottom of each, see GridLane.touchAreas).
  @Nullable
  private float[] mPillTouch;
  // Wide enough for the longest departure time in a pill.
  private int mLaneWidth;
  // The ride the side of the lane was chosen for (see rideId), 0 for none: the lane stays on that side while the ride
  // is selected.
  private int mLaneFor;
  private boolean mLaneOnRight;
  // A ride under the finger that is lifted when held long enough; -1 if none.
  private int mHoldConnection = -1;
  private int mHoldLeg = -1;
  private float mDownX;
  private float mDownY;
  private float mLastY;
  @Nullable
  private Drag mDrag;

  public TimeGridView(@NonNull Context context)
  {
    this(context, null);
  }

  public TimeGridView(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    mDensity = getResources().getDisplayMetrics().density;
    mTextColor = ThemeUtils.getColor(context, android.R.attr.textColorPrimary);
    final int secondary = ThemeUtils.getColor(context, android.R.attr.textColorSecondary);
    mLateColor = TransitViews.lateColor(context);
    mOnTimeColor = TransitViews.onTimeColor(context);

    mBlockText.setTypeface(Typeface.DEFAULT_BOLD);
    mBlockText.setTextAlign(Paint.Align.CENTER);
    mBlockText.setTextSize(sp(BLOCK_TEXT_SP));
    mLabel.setTypeface(Typeface.DEFAULT_BOLD);
    mLabel.setTextAlign(Paint.Align.CENTER);
    mLabel.setTextSize(sp(LABEL_SP));
    mAxisText.setTextAlign(Paint.Align.RIGHT);
    mAxisText.setTextSize(sp(LABEL_SP));
    mAxisText.setColor(secondary);
    mGridLine.setColor(ContextCompat.getColor(context, R.color.divider));
    mGridLine.setStrokeWidth(Math.max(1, dp(1)));
    mWalk.setStyle(Paint.Style.STROKE);
    mWalk.setStrokeCap(Paint.Cap.ROUND);
    mWalk.setStrokeWidth(dp(DOT_DP));
    mWalk.setPathEffect(new DashPathEffect(new float[] {0, dp(DOT_SPACING_DP)}, 0));
    mWalk.setColor(secondary);
    mNowLine.setColor(mLateColor);
    mNowLine.setStrokeWidth(dp(2));
    mPagingFrame.setStyle(Paint.Style.STROKE);
    mPagingFrame.setStrokeWidth(dp(1));
    mPagingFrame.setColor(ContextCompat.getColor(context, R.color.divider));
    mPagingText.setTextAlign(Paint.Align.CENTER);
    mPagingText.setTextSize(sp(LABEL_SP));
    mPagingText.setColor(ContextCompat.getColor(context, R.color.base_accent));
    mSecondaryColor = secondary;
    mAccentColor = ContextCompat.getColor(context, R.color.base_accent);
    mChipColor = ContextCompat.getColor(context, R.color.transit_alt_chip);
    mChipMissedColor = ContextCompat.getColor(context, R.color.transit_alt_chip_missed);
    mSelection.setColor(ContextCompat.getColor(context, R.color.transit_alt_area));
    mOutline.setStyle(Paint.Style.STROKE);
    mOutline.setStrokeWidth(dp(2));
    mOutline.setColor(mAccentColor);
    mPillStroke.setStyle(Paint.Style.STROKE);
    mPillStroke.setStrokeWidth(dp(1));
    mPillDashed.setStyle(Paint.Style.STROKE);
    mPillDashed.setStrokeWidth(dp(1));
    mPillDashed.setColor(ContextCompat.getColor(context, R.color.transit_alt_chip_stroke));
    mPillDashed.setPathEffect(new DashPathEffect(new float[] {dp(3), dp(2)}, 0));
    mPillText.setTypeface(Typeface.DEFAULT_BOLD);
    mPillText.setTextAlign(Paint.Align.LEFT);
    mPillText.setTextSize(sp(PILL_TEXT_SP));
    mShadow.setColor(Color.argb(0x50, 0, 0, 0));
    mSlot.setStyle(Paint.Style.STROKE);
    mSlot.setStrokeWidth(dp(1.5f));
    mSlot.setColor(mAccentColor);
    mSlot.setPathEffect(new DashPathEffect(new float[] {dp(4), dp(3)}, 0));
    mTarget.setStyle(Paint.Style.STROKE);
    mTarget.setStrokeWidth(dp(2));
    mTarget.setColor(mAccentColor);
    mLane.setColor(backgroundColor(context));
    mAxisWidth = dp(MIN_AXIS_DP);
    mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

    mGestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onDown(@NonNull MotionEvent e)
      {
        return true;
      }

      @Override
      public boolean onSingleTapUp(@NonNull MotionEvent e)
      {
        return onTap(e.getX(), e.getY());
      }
    });
    mAccess = new AccessHelper(this);
    ViewCompat.setAccessibilityDelegate(this, mAccess);
    setFocusable(true);
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
  }

  /**
   * @param formatter for the axis labels (device time zone).
   */
  public void setData(@NonNull List<ItineraryItem> items, @NonNull Instant now, @NonNull TransitFormatter formatter,
                      boolean showEarlier, boolean loadingEarlier, boolean showLater, boolean loadingLater)
  {
    setData(items, now, formatter, showEarlier, loadingEarlier, showLater, loadingLater, null);
  }

  /**
   * @param alternatives other departures of the selected ride, null without a selection.
   */
  public void setData(@NonNull List<ItineraryItem> items, @NonNull Instant now, @NonNull TransitFormatter formatter,
                      boolean showEarlier, boolean loadingEarlier, boolean showLater, boolean loadingLater,
                      @Nullable AlternativesItem alternatives)
  {
    final float oldAxisWidth = mAxisWidth;
    // The lane of a selected ride is drawn over the grid: a selection does not change the size.
    final boolean sizeChanged = items.size() != mItems.size() || showEarlier != mShowEarlier || showLater != mShowLater;
    mAlternatives = alternatives != null && alternatives.getConnection() < items.size() ? alternatives : null;
    float text = 0;
    if (mAlternatives != null)
      for (AlternativesItem.Chip chip : mAlternatives.getChips())
        text = Math.max(text, mPillText.measureText(chip.getDeparture()));
    mLaneWidth = Math.round(Math.max(dp(LANE_DP), text + dp(STRIPE_DP) + dp(12)));
    if (mAlternatives == null || mLaneFor != rideId(mAlternatives.getConnection(), mAlternatives.getLegIndex()))
      mLaneFor = 0;
    mItems = new ArrayList<>(items);
    mNow = now;
    mFormatter = formatter;
    final Instant wide = now.atZone(formatter.getDeviceZone()).withHour(22).withMinute(58).toInstant();
    mAxisWidth = Math.max(dp(MIN_AXIS_DP), mAxisText.measureText(formatter.formatDeviceTime(wide)) + dp(12));
    mShowEarlier = showEarlier;
    mShowLater = showLater;
    mLoadingEarlier = loadingEarlier;
    mLoadingLater = loadingLater;
    mLayout = null;
    mPills = null;
    if (sizeChanged || oldAxisWidth != mAxisWidth)
      requestLayout();
    final Drag drag = mDrag;
    if (drag != null && drag.mShown && !isDraggedRideShown(drag))
      endDrag(); // E.g. a new search: the departures of the lifted ride are gone.
    else if (drag != null)
      updateDrag(drag); // E.g. the departures arrived.
    invalidate();
    mAccess.invalidateRoot();
  }

  /** True while a ride is lifted for dragging. */
  @VisibleForTesting
  public boolean isDragging()
  {
    return mDrag != null;
  }

  /** Horizontal distance between two connections, e.g. to keep them in view when earlier ones are added. */
  public int getColumnStep()
  {
    return Math.round(dp(COLUMN_DP) + dp(GAP_DP));
  }

  /**
   * A point of a connection's column in view coordinates that opens it (its departure time above the rides), null if
   * there is no such column.
   */
  @VisibleForTesting
  @Nullable
  public float[] getColumnCenter(int index)
  {
    final TimeGridLayout layout = layout();
    if (layout == null || index < 0 || index >= layout.getColumns().size())
      return null;
    final TimeGridLayout.Column column = layout.getColumns().get(index);
    return new float[] {column.getCenterX(), column.getTop() - labelHeight() / 2f};
  }

  /** Center of a ride in view coordinates, null if there is no such ride. */
  @VisibleForTesting
  @Nullable
  public float[] getRideCenter(int index, int legIndex)
  {
    final TimeGridLayout layout = layout();
    if (layout == null || index < 0 || index >= layout.getColumns().size())
      return null;
    final TimeGridLayout.Column column = layout.getColumns().get(index);
    for (TimeGridLayout.Block block : column.getBlocks())
      if (block.getKind() == ItineraryTimeline.Kind.RIDE && block.getSegment().getLegIndex() == legIndex)
        return new float[] {column.getCenterX(), (block.getTop() + block.getBottom()) / 2f};
    return null;
  }

  /** Bounds of a ride in view coordinates, null if there is no such ride. */
  @VisibleForTesting
  @Nullable
  public RectF getRideBounds(int index, int legIndex)
  {
    final TimeGridLayout layout = layout();
    if (layout == null || index < 0 || index >= layout.getColumns().size())
      return null;
    final TimeGridLayout.Column column = layout.getColumns().get(index);
    for (TimeGridLayout.Block block : column.getBlocks())
      if (block.getKind() == ItineraryTimeline.Kind.RIDE && block.getSegment().getLegIndex() == legIndex)
        return new RectF(column.getLeft(), block.getTop(), column.getRight(), block.getBottom());
    return null;
  }

  /** Center of the pill of an other departure in view coordinates, null if there is none. */
  @VisibleForTesting
  @Nullable
  public float[] getAlternativeCenter(int chipIndex)
  {
    final List<RectF> pills = pills();
    if (pills == null || chipIndex < 0 || chipIndex >= pills.size())
      return null;
    return new float[] {pills.get(chipIndex).centerX(), pills.get(chipIndex).centerY()};
  }

  @Override
  protected void onAttachedToWindow()
  {
    super.onAttachedToWindow();
    getViewTreeObserver().addOnScrollChangedListener(mScrollListener);
  }

  @Override
  protected void onDetachedFromWindow()
  {
    cancelHold();
    endDrag();
    getViewTreeObserver().removeOnScrollChangedListener(mScrollListener);
    super.onDetachedFromWindow();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
  {
    final int content = Math.round(leadingWidth() + mItems.size() * (dp(COLUMN_DP) + dp(GAP_DP)) + dp(GAP_DP)
                                   + (mShowLater ? dp(PAGING_DP) + dp(GAP_DP) : 0));
    final int width = Math.max(content, MeasureSpec.getSize(widthMeasureSpec));
    setMeasuredDimension(width, getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec));
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh)
  {
    super.onSizeChanged(w, h, oldw, oldh);
    mLayout = null;
    mPills = null;
  }

  private float leadingWidth()
  {
    return mAxisWidth + (mShowEarlier ? dp(GAP_DP) + dp(PAGING_DP) : 0);
  }

  @Nullable
  private TimeGridLayout layout()
  {
    if (mLayout == null && getHeight() > 0)
    {
      final TimeGridLayout.Params params = new TimeGridLayout.Params();
      params.height = getHeight();
      params.top = Math.round(labelHeight() + dp(6));
      params.bottom = params.top;
      params.axisWidth = Math.round(leadingWidth());
      params.columnWidth = Math.round(dp(COLUMN_DP));
      params.columnGap = Math.round(dp(GAP_DP));
      params.minTickSpacing = Math.round(dp(MIN_TICK_SPACING_DP));
      params.maxPixelsPerMinute = dp(MAX_DP_PER_MINUTE);
      params.minBlockHeight = Math.round(dp(MIN_BLOCK_DP));
      final List<ItineraryTimeline> timelines = new ArrayList<>(mItems.size());
      for (ItineraryItem item : mItems)
        timelines.add(item.getTimeline());
      final ZoneId zone = mFormatter != null ? mFormatter.getDeviceZone() : ZoneId.systemDefault();
      mLayout = TimeGridLayout.compute(timelines, mNow.getEpochSecond(), zone, params);
      mPills = null;
    }
    return mLayout;
  }

  /**
   * The lane of the selected ride in view coordinates: as wide as the lane, as high as the time range; null without a
   * selection. Its side is chosen once for the selected ride, from what is in view then.
   */
  @Nullable
  private RectF laneRect()
  {
    final TimeGridLayout layout = layout();
    final AlternativesItem alternatives = mAlternatives;
    if (layout == null || alternatives == null || alternatives.getConnection() >= layout.getColumns().size())
      return null;
    final TimeGridLayout.Column column = layout.getColumns().get(alternatives.getConnection());
    final int ride = rideId(alternatives.getConnection(), alternatives.getLegIndex());
    if (mLaneFor != ride)
    {
      final int scroll = scrollOffset();
      final int visibleRight = Math.min(getWidth(), scroll + viewportWidth());
      mLaneOnRight = GridLane.onRight(column.getLeft(), column.getRight(), mLaneWidth, Math.round(scroll + mAxisWidth),
                                      visibleRight);
      mLaneFor = ride;
    }
    final int left = GridLane.left(column.getLeft(), column.getRight(), mLaneWidth, mLaneOnRight);
    return new RectF(left, layout.y(layout.getRangeStart()), left + mLaneWidth, layout.y(layout.getRangeEnd()));
  }

  /**
   * What the lane hides: the whole place of the neighbouring column (or "Earlier" / "Later") on its side, so that no
   * faded sliver of it is left beside the lane. As high as the view.
   */
  @NonNull
  private RectF laneCover(@NonNull RectF lane)
  {
    final float width = Math.max(lane.width(), dp(COLUMN_DP) + dp(GAP_DP));
    return mLaneOnRight ? new RectF(lane.left, 0, lane.left + width, getHeight())
                        : new RectF(lane.right - width, 0, lane.right, getHeight());
  }

  /** Virtual view id of a ride, see {@link #ID_RIDE}. */
  private static int rideId(int connection, int legIndex)
  {
    return ID_RIDE + connection * RIDE_STRIDE + legIndex;
  }

  private int viewportWidth()
  {
    return getParent() instanceof View parent ? parent.getWidth() : getWidth();
  }

  /**
   * True if a ride is mostly in the lower half of the grid, e.g. to show something in the upper half while it is
   * selected. A selection does not move it.
   */
  public boolean isRideLow(int index, int legIndex)
  {
    final float[] center = getRideCenter(index, legIndex);
    return center != null && center[1] > getHeight() / 2f;
  }

  /**
   * Scroll position of the parent that shows the selected column with its lane completely, or -1 if it is in view
   * already (or there is no selection).
   *
   * @param scrollX current scroll position of the parent.
   * @param viewportWidth width of the parent.
   */
  public int scrollForSelection(int scrollX, int viewportWidth)
  {
    final TimeGridLayout layout = layout();
    if (layout == null || mAlternatives == null)
      return -1;
    final TimeGridLayout.Column column = layout.getColumns().get(mAlternatives.getConnection());
    final RectF lane = laneRect();
    final int margin = Math.round(dp(GAP_DP));
    final int left = Math.round(lane != null ? Math.min(lane.left, column.getLeft()) : column.getLeft()) - margin;
    // The time labels are wider than the column.
    final int right =
        Math.round(Math.max(column.getRight() + dp(LABEL_OVERHANG_DP), lane != null ? lane.right + margin : 0));
    if (right > scrollX + viewportWidth)
      return Math.max(0, Math.min(right - viewportWidth, left - Math.round(mAxisWidth)));
    if (left < scrollX + mAxisWidth)
      return Math.max(0, left - Math.round(mAxisWidth));
    return -1;
  }

  /**
   * Pills of the other departures at their times in the lane, moved apart where they would overlap and into the time
   * range where they are outside it.
   */
  @Nullable
  private List<RectF> pills()
  {
    final TimeGridLayout layout = layout();
    final RectF lane = laneRect();
    if (layout == null || lane == null || mAlternatives == null)
      return null;
    if (mPills == null)
    {
      final List<AlternativesItem.Chip> chips = mAlternatives.getChips();
      final float[] centers = new float[chips.size()];
      for (int i = 0; i < centers.length; ++i)
        centers[i] = layout.y(chips.get(i).getDepartureEpoch());
      final float height = dp(PILL_DP);
      final float[] tops = GridLane.pillTops(centers, height, dp(1), lane.top, lane.bottom);
      final List<RectF> pills = new ArrayList<>(tops.length);
      for (float top : tops)
        pills.add(new RectF(lane.left + dp(4), top, lane.right - dp(4), top + height));
      mPillTouch = GridLane.touchAreas(tops, height, dp(PILL_TOUCH_DP));
      mPills = pills;
    }
    return mPills;
  }

  /** Touch area of a pill in view coordinates (as wide as the lane), null if there is none. */
  @Nullable
  private RectF pillTouchRect(int index)
  {
    final List<RectF> pills = pills();
    final RectF lane = laneRect();
    final float[] touch = mPillTouch;
    if (pills == null || lane == null || touch == null || index < 0 || 2 * index + 1 >= touch.length)
      return null;
    return new RectF(lane.left, touch[2 * index], lane.right, touch[2 * index + 1]);
  }

  private float labelHeight()
  {
    mLabel.getFontMetrics(mMetrics);
    return mMetrics.descent - mMetrics.ascent;
  }

  private int scrollOffset()
  {
    final ViewParent parent = getParent();
    return parent instanceof View ? ((View) parent).getScrollX() : 0;
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas)
  {
    super.onDraw(canvas);
    // The layout may be new (e.g. the departures arrived): the lifted ride snaps to the new pills.
    if (mDrag != null)
      updateDrag(mDrag);
    final TimeGridLayout layout = layout();
    if (layout == null)
      return;
    final int scroll = scrollOffset();
    final float axisRight = scroll + mAxisWidth;
    final int viewportRight = scroll + (getParent() instanceof View ? ((View) getParent()).getWidth() : getWidth());

    for (TimeGridLayout.Tick tick : layout.getTicks())
      canvas.drawLine(axisRight, tick.getY(), getWidth(), tick.getY(), mGridLine);

    // Columns scroll under the axis.
    canvas.save();
    canvas.clipRect(axisRight, 0, Math.max(axisRight, viewportRight), getHeight());
    // Below the columns, so that departure times stay readable.
    final Integer nowY = layout.getNowY();
    if (nowY != null)
      canvas.drawLine(axisRight, nowY, getWidth(), nowY, mNowLine);
    if (mShowEarlier)
      drawPaging(canvas, mAxisWidth + dp(GAP_DP), layout, true);
    final int selected = mAlternatives != null ? mAlternatives.getConnection() : -1;
    for (int i = 0; i < layout.getColumns().size() && i < mItems.size(); ++i)
    {
      final TimeGridLayout.Column column = layout.getColumns().get(i);
      if (i == selected)
        continue;
      if (selected >= 0)
      {
        // Wide enough for the time labels above and below.
        mRect.set(column.getLeft() - dp(GAP_DP), 0, column.getRight() + dp(GAP_DP), getHeight());
        canvas.saveLayerAlpha(mRect, FADED_ALPHA);
      }
      drawColumn(canvas, column, mItems.get(i));
      if (selected >= 0)
        canvas.restore();
    }
    if (mShowLater)
      drawPaging(canvas, layout.getContentWidth(), layout, false);
    // Last: its lane is drawn over a neighbour.
    if (selected >= 0 && selected < layout.getColumns().size() && selected < mItems.size())
      drawSelectedColumn(canvas, layout, layout.getColumns().get(selected), mItems.get(selected));
    canvas.restore();

    // The axis stays in place.
    mAxisText.getFontMetrics(mMetrics);
    final float half = (mMetrics.ascent + mMetrics.descent) / 2;
    for (TimeGridLayout.Tick tick : layout.getTicks())
    {
      final String text = mFormatter != null ? mFormatter.formatDeviceTime(tick.getInstant()) : "";
      canvas.drawText(text, axisRight - dp(6), tick.getY() - half, mAxisText);
    }
    if (nowY != null)
      canvas.drawCircle(axisRight, nowY, dp(3), mNowLine);
  }

  private void drawColumn(@NonNull Canvas canvas, @NonNull TimeGridLayout.Column column, @NonNull ItineraryItem item)
  {
    drawColumn(canvas, column, item, -1);
  }

  /** @param skipLeg leg index of a ride not to draw (it is lifted), -1 for none. */
  private void drawColumn(@NonNull Canvas canvas, @NonNull TimeGridLayout.Column column, @NonNull ItineraryItem item,
                          int skipLeg)
  {
    final float centerX = column.getCenterX();
    for (TimeGridLayout.Block block : column.getBlocks())
    {
      if (block.getKind() == ItineraryTimeline.Kind.WALK)
      {
        final float inset = Math.min(dp(DOT_DP) / 2, block.getHeight() / 2f);
        canvas.drawLine(centerX, block.getTop() + inset, centerX, block.getBottom() - inset, mWalk);
        continue;
      }
      final LineBadge badge = block.getBadge();
      if (badge == null || (skipLeg >= 0 && block.getSegment().getLegIndex() == skipLeg))
        continue;
      mRect.set(column.getLeft(), block.getTop(), column.getRight(), block.getBottom());
      drawRide(canvas, mRect, badge, block.getSegment().isCancelled() ? CANCELLED_ALPHA : 0xFF);
    }

    // Departure above, arrival below, colored by delay.
    mLabel.getFontMetrics(mMetrics);
    setLabelStyle(item.getDepartureDelay(), item.isCancelled());
    canvas.drawText(item.getDeparture(), centerX, column.getTop() - dp(3) - mMetrics.descent, mLabel);
    setLabelStyle(item.getArrivalDelay(), item.isCancelled());
    canvas.drawText(item.getArrival(), centerX, column.getBottom() + dp(3) - mMetrics.ascent, mLabel);
  }

  /** A ride block with its line name if it fits. */
  private void drawRide(@NonNull Canvas canvas, @NonNull RectF rect, @NonNull LineBadge badge, int alpha)
  {
    mFill.setColor(badge.getBackgroundColor());
    mFill.setAlpha(alpha);
    canvas.drawRoundRect(rect, dp(CORNER_DP), dp(CORNER_DP), mFill);
    mBlockText.getFontMetrics(mMetrics);
    final float textHeight = mMetrics.descent - mMetrics.ascent;
    if (rect.height() < textHeight + dp(2))
      return;
    for (String label : JourneyBarLayout.labelCandidates(badge.getText()))
    {
      if (!JourneyBarLayout.fitsLabel(Math.round(rect.width()), mBlockText.measureText(label), Math.round(dp(2))))
        continue;
      mBlockText.setColor(badge.getTextColor());
      mBlockText.setAlpha(alpha);
      canvas.drawText(label, rect.centerX(), rect.centerY() - (mMetrics.ascent + mMetrics.descent) / 2, mBlockText);
      break;
    }
  }

  /**
   * The column with the selected ride: a background, the ride outlined, the other departures in the lane. A lifted
   * ride is drawn above everything at the departure it snaps to, its place as a dashed outline.
   */
  private void drawSelectedColumn(@NonNull Canvas canvas, @NonNull TimeGridLayout layout,
                                  @NonNull TimeGridLayout.Column column, @NonNull ItineraryItem item)
  {
    final AlternativesItem alternatives = mAlternatives;
    final RectF lane = laneRect();
    if (alternatives == null || lane == null)
      return;
    // The lane hides the neighbour below it (with its time labels); the grid lines and "now" go on through it.
    final RectF cover = laneCover(lane);
    canvas.drawRect(cover, mLane);
    for (TimeGridLayout.Tick tick : layout.getTicks())
      canvas.drawLine(cover.left, tick.getY(), cover.right, tick.getY(), mGridLine);
    final Integer nowY = layout.getNowY();
    if (nowY != null)
      canvas.drawLine(cover.left, nowY, cover.right, nowY, mNowLine);
    mRect.set(Math.min(cover.left, column.getLeft()) - dp(2), lane.top,
              Math.max(cover.right, column.getRight()) + dp(2), lane.bottom);
    canvas.drawRoundRect(mRect, dp(8), dp(8), mSelection);
    final Drag drag = mDrag != null && isDraggedRideShown(mDrag) ? mDrag : null;
    drawColumn(canvas, column, item, drag != null ? drag.mLegIndex : -1);
    TimeGridLayout.Block selectedBlock = null;
    for (TimeGridLayout.Block block : column.getBlocks())
      if (block.getKind() == ItineraryTimeline.Kind.RIDE
          && block.getSegment().getLegIndex() == alternatives.getLegIndex())
      {
        selectedBlock = block;
        mRect.set(column.getLeft() - dp(2), block.getTop() - dp(2), column.getRight() + dp(2), block.getBottom() + dp(2));
        canvas.drawRoundRect(mRect, dp(CORNER_DP + 2), dp(CORNER_DP + 2), drag != null ? mSlot : mOutline);
      }
    final List<RectF> pills = pills();
    if (pills == null)
    {
      if (drag != null && selectedBlock != null)
        drawLifted(canvas, column, selectedBlock.getBadge(), selectedBlock.getTop(), selectedBlock.getBottom());
      return;
    }
    mPillText.getFontMetrics(mMetrics);
    final float corner = dp(3);
    for (int i = 0; i < pills.size(); ++i)
    {
      final AlternativesItem.Chip chip = alternatives.getChips().get(i);
      final RectF pill = pills.get(i);
      final boolean missed = chip.missesNext();
      mPill.setColor(missed ? mChipMissedColor : mChipColor);
      canvas.drawRoundRect(pill, corner, corner, mPill);
      if (chip.isSelected())
      {
        mPill.setColor(Color.argb(0x40, Color.red(mAccentColor), Color.green(mAccentColor), Color.blue(mAccentColor)));
        canvas.drawRoundRect(pill, corner, corner, mPill);
      }
      mPill.setColor(chip.getBadge().getBackgroundColor());
      mRect.set(pill.left, pill.top, pill.left + dp(STRIPE_DP), pill.bottom);
      canvas.drawRoundRect(mRect, corner, corner, mPill);
      if (missed && !chip.isSelected())
        canvas.drawRoundRect(pill, corner, corner, mPillDashed);
      else
      {
        mPillStroke.setColor(chip.isSelected() ? mAccentColor : mPillDashed.getColor());
        canvas.drawRoundRect(pill, corner, corner, mPillStroke);
      }
      mPillText.setColor(missed ? mSecondaryColor : mTextColor);
      final float baseline = pill.centerY() - (mMetrics.ascent + mMetrics.descent) / 2;
      canvas.drawText(chip.getDeparture(), pill.left + dp(STRIPE_DP) + dp(2), baseline, mPillText);
    }
    if (drag == null || selectedBlock == null)
      return;
    final int target = drag.mSnap.getIndex();
    if (target < 0 || target >= pills.size() || target == drag.mSnap.getOriginal())
    {
      drawLifted(canvas, column, selectedBlock.getBadge(), selectedBlock.getTop(), selectedBlock.getBottom());
      return;
    }
    // The pill it snaps to, and the ride at that departure.
    final RectF pill = pills.get(target);
    mPill.setColor(Color.argb(0x55, Color.red(mAccentColor), Color.green(mAccentColor), Color.blue(mAccentColor)));
    canvas.drawRoundRect(pill, dp(3), dp(3), mPill);
    mRect.set(pill.left - dp(1), pill.top - dp(1), pill.right + dp(1), pill.bottom + dp(1));
    canvas.drawRoundRect(mRect, dp(4), dp(4), mTarget);
    final AlternativesItem.Chip chip = alternatives.getChips().get(target);
    final float departure = layout.y(chip.getDepartureEpoch());
    final float arrival = chip.getArrivalEpoch() > chip.getDepartureEpoch() ? layout.y(chip.getArrivalEpoch())
                                                                           : departure + selectedBlock.getHeight();
    final float height = Math.max(arrival - departure, dp(MIN_BLOCK_DP));
    // Outside the time range (like its pill): at its edge.
    final float top = GridLane.liftedTop(departure, height, lane.top, lane.bottom);
    drawLifted(canvas, column, chip.getBadge(), top, top + height);
  }

  /** A lifted ride (in the line of the departure it is at): a little wider, with a shadow and an outline. */
  private void drawLifted(@NonNull Canvas canvas, @NonNull TimeGridLayout.Column column, @Nullable LineBadge badge,
                          float top, float bottom)
  {
    if (badge == null)
      return;
    final float grow = dp(LIFT_DP);
    mRect.set(column.getLeft() - grow, top + dp(1), column.getRight() + grow, bottom + dp(4));
    canvas.drawRoundRect(mRect, dp(CORNER_DP + 2), dp(CORNER_DP + 2), mShadow);
    mRect.set(column.getLeft() - grow, top - dp(1), column.getRight() + grow, bottom + dp(1));
    drawRide(canvas, mRect, badge, 0xFF);
    mRect.inset(-dp(2), -dp(2));
    canvas.drawRoundRect(mRect, dp(CORNER_DP + 2), dp(CORNER_DP + 2), mOutline);
  }

  private void setLabelStyle(@NonNull TransitFormatter.Delay delay, boolean cancelled)
  {
    switch (delay.getStatus())
    {
    case LATE: mLabel.setColor(mLateColor); break;
    case ON_TIME:
    case EARLY: mLabel.setColor(mOnTimeColor); break;
    default: mLabel.setColor(mTextColor);
    }
    mLabel.setStrikeThruText(cancelled);
  }

  private void drawPaging(@NonNull Canvas canvas, float left, @NonNull TimeGridLayout layout, boolean earlier)
  {
    final float top = layout.y(layout.getRangeStart());
    final float bottom = layout.y(layout.getRangeEnd());
    mRect.set(left + dp(1), top, left + dp(PAGING_DP) - dp(1), bottom);
    canvas.drawRoundRect(mRect, dp(CORNER_DP), dp(CORNER_DP), mPagingFrame);
    final boolean loading = earlier ? mLoadingEarlier : mLoadingLater;
    final String text =
        loading ? "…" : getContext().getString(earlier ? R.string.transit_earlier : R.string.transit_later);
    canvas.save();
    canvas.rotate(-90, mRect.centerX(), mRect.centerY());
    mPagingText.getFontMetrics(mMetrics);
    canvas.drawText(text, mRect.centerX(), mRect.centerY() - (mMetrics.ascent + mMetrics.descent) / 2, mPagingText);
    canvas.restore();
  }

  @Nullable
  private RectF pagingRect(boolean earlier)
  {
    final TimeGridLayout layout = layout();
    if (layout == null || (earlier ? !mShowEarlier : !mShowLater))
      return null;
    final float left = earlier ? mAxisWidth + dp(GAP_DP) : layout.getContentWidth();
    return new RectF(left, 0, left + dp(PAGING_DP), getHeight());
  }

  /** The connection index, ID_EARLIER, ID_LATER, or ExploreByTouchHelper.INVALID_ID at a point. */
  private int targetAt(float x, float y)
  {
    final TimeGridLayout layout = layout();
    if (layout == null || x < scrollOffset() + mAxisWidth)
      return ExploreByTouchHelper.INVALID_ID;
    // The lane is over a neighbour.
    final RectF lane = laneRect();
    final RectF cover = lane != null ? laneCover(lane) : null;
    if (cover != null && x >= cover.left && x < cover.right)
    {
      final int pill = pillAt(x, y);
      return pill >= 0 ? ID_PILL - pill : ID_DISMISS;
    }
    final RectF earlier = pagingRect(true);
    if (earlier != null && earlier.contains(x, y))
      return ID_EARLIER;
    final RectF later = pagingRect(false);
    if (later != null && later.contains(x, y))
      return ID_LATER;
    final int column = layout.columnAt(x);
    if (column < 0 || column >= mItems.size())
      return mAlternatives != null ? ID_DISMISS : ExploreByTouchHelper.INVALID_ID;
    final int ride = rideAt(layout.getColumns().get(column), y);
    if (ride >= 0)
      return rideId(column, ride);
    return column;
  }

  /**
   * What a tap (or a screen reader's click) on a target does: rides and columns open their connection, or end the
   * selection while a ride is selected.
   */
  private int tapAction(int target)
  {
    if (target >= ID_RIDE)
      target = (target - ID_RIDE) / RIDE_STRIDE;
    if (target >= 0 && mAlternatives != null)
      return ID_DISMISS;
    return target;
  }

  /** Index of the pill whose touch area is at a point, -1 if none. */
  private int pillAt(float x, float y)
  {
    final List<RectF> pills = pills();
    if (pills == null)
      return -1;
    for (int i = 0; i < pills.size(); ++i)
    {
      final RectF touch = pillTouchRect(i);
      if (touch != null && touch.contains(x, y))
        return i;
    }
    return -1;
  }

  /** Leg index of the ride at y in a column (small rides are easier to hit), -1 if none. */
  private int rideAt(@NonNull TimeGridLayout.Column column, float y)
  {
    for (TimeGridLayout.Block block : column.getBlocks())
    {
      if (block.getKind() != ItineraryTimeline.Kind.RIDE || block.getSegment().getLegIndex() < 0)
        continue;
      final float slack = Math.max(0, (dp(2 * TOUCH_DP) - block.getHeight()) / 2);
      if (y >= block.getTop() - slack && y <= block.getBottom() + slack)
        return block.getSegment().getLegIndex();
    }
    return -1;
  }

  private boolean onTap(float x, float y)
  {
    return perform(targetAt(x, y));
  }

  private boolean perform(int target)
  {
    if (mListener == null || target == ExploreByTouchHelper.INVALID_ID)
      return false;
    playSoundEffect(android.view.SoundEffectConstants.CLICK);
    final int action = tapAction(target);
    if (action == ID_EARLIER)
    {
      if (!mLoadingEarlier)
        mListener.onEarlierClick();
    }
    else if (action == ID_LATER)
    {
      if (!mLoadingLater)
        mListener.onLaterClick();
    }
    else if (action == ID_DISMISS)
      mListener.onDismissAlternatives();
    else if (action <= ID_PILL)
      mListener.onAlternativeClick(ID_PILL - action);
    else
      mListener.onConnectionClick(action);
    mAccess.sendEventForVirtualView(target, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
    return true;
  }

  @Override
  public boolean onTouchEvent(@NonNull MotionEvent event)
  {
    if (mDrag != null)
    {
      onDragEvent(mDrag, event);
      return true;
    }
    switch (event.getActionMasked())
    {
    case MotionEvent.ACTION_DOWN:
      cancelHold();
      mDownX = event.getX();
      mDownY = event.getY();
      mLastY = mDownY;
      final int target = mListener != null ? targetAt(mDownX, mDownY) : ExploreByTouchHelper.INVALID_ID;
      if (target >= ID_RIDE)
      {
        mHoldConnection = (target - ID_RIDE) / RIDE_STRIDE;
        mHoldLeg = (target - ID_RIDE) % RIDE_STRIDE;
        postDelayed(mHold, HOLD_MS);
      }
      break;
    case MotionEvent.ACTION_MOVE:
      mLastY = event.getY();
      // A scroll (of the grid or the sheet), not a hold.
      if (Math.abs(event.getX() - mDownX) > mTouchSlop || Math.abs(event.getY() - mDownY) > mTouchSlop)
        cancelHold();
      break;
    case MotionEvent.ACTION_POINTER_DOWN:
    case MotionEvent.ACTION_UP:
    case MotionEvent.ACTION_CANCEL: cancelHold(); break;
    default: break;
    }
    return mGestures.onTouchEvent(event) || super.onTouchEvent(event);
  }

  private void cancelHold()
  {
    removeCallbacks(mHold);
    mHoldConnection = -1;
    mHoldLeg = -1;
  }

  /** The ride under the finger was held long enough: select it (show its departures) if it is not, and lift it. */
  private void startDrag()
  {
    final Listener listener = mListener;
    if (listener == null || mHoldConnection < 0)
      return;
    final Drag drag = new Drag(mHoldConnection, mHoldLeg, new RideDragSnap(dp(SNAP_HYSTERESIS_DP)), mLastY);
    cancelHold();
    mDrag = drag;
    // The rest of the gesture is no tap, and the scrolling parents must not take it.
    final long now = SystemClock.uptimeMillis();
    final MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
    mGestures.onTouchEvent(cancel);
    cancel.recycle();
    final ViewParent parent = getParent();
    if (parent != null)
      parent.requestDisallowInterceptTouchEvent(true);
    TransitSheetLayout.keepGesture(this);
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    if (!isDraggedRideShown(drag))
      listener.onRideLongClick(drag.mConnection, drag.mLegIndex);
    updateDrag(drag);
    invalidate();
  }

  private void onDragEvent(@NonNull Drag drag, @NonNull MotionEvent event)
  {
    switch (event.getActionMasked())
    {
    case MotionEvent.ACTION_MOVE:
      drag.mFingerY = event.getY();
      updateDrag(drag);
      if (drag.mSnap.move(drag.mFingerY))
      {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (mListener != null)
          mListener.onAlternativePreview(drag.mSnap.getIndex());
        invalidate();
      }
      break;
    case MotionEvent.ACTION_UP:
      final int chosen = isDraggedRideShown(drag) ? drag.mSnap.result() : -1;
      endDrag();
      if (chosen >= 0 && mListener != null)
      {
        playSoundEffect(android.view.SoundEffectConstants.CLICK);
        mListener.onAlternativeClick(chosen);
      }
      break;
    case MotionEvent.ACTION_CANCEL: endDrag(); break;
    default: break;
    }
  }

  /** Lets the lifted ride snap to the pills once its departures are there, and as they move. */
  private void updateDrag(@NonNull Drag drag)
  {
    final AlternativesItem alternatives = mAlternatives;
    if (alternatives == null || !isDraggedRideShown(drag))
      return;
    drag.mShown = true;
    final List<RectF> pills = pills();
    if (pills == null || pills == drag.mPills)
      return;
    drag.mPills = pills;
    final boolean wasSnapping = drag.mSnap.hasTargets();
    final List<AlternativesItem.Chip> chips = alternatives.getChips();
    final boolean usable = !alternatives.isLoading() && alternatives.getError() == null && chips.size() == pills.size();
    final float[] centers = new float[usable ? pills.size() : 0];
    int original = -1;
    for (int i = 0; i < centers.length; ++i)
    {
      centers[i] = pills.get(i).centerY();
      if (chips.get(i).isSelected())
        original = i;
    }
    // Unknown which departure the connection uses: the one at the ride's departure.
    final float top = rideTop(drag.mConnection, drag.mLegIndex);
    drag.mSnap.setTargets(centers, original, Float.isNaN(top) ? drag.mFingerY : top, drag.mFingerY);
    if (!wasSnapping && drag.mSnap.hasTargets() && mListener != null)
      mListener.onAlternativePreview(drag.mSnap.getIndex());
  }

  private void endDrag()
  {
    if (mDrag == null)
      return;
    mDrag = null;
    if (mListener != null)
      mListener.onAlternativePreview(-1);
    invalidate();
  }

  /** Index of the departure a shown ride uses, -1 if its departures are not shown (or not known yet). */
  private int selectedChip(int connection, int legIndex)
  {
    final AlternativesItem alternatives = mAlternatives;
    if (alternatives == null || alternatives.getConnection() != connection || alternatives.getLegIndex() != legIndex
        || alternatives.isLoading())
      return -1;
    for (int i = 0; i < alternatives.getChips().size(); ++i)
      if (alternatives.getChips().get(i).isSelected())
        return i;
    return -1;
  }

  /** Top of a ride in view coordinates, NaN if there is no such ride. */
  private float rideTop(int connection, int legIndex)
  {
    final TimeGridLayout layout = layout();
    if (layout == null || connection < 0 || connection >= layout.getColumns().size())
      return Float.NaN;
    for (TimeGridLayout.Block block : layout.getColumns().get(connection).getBlocks())
      if (block.getKind() == ItineraryTimeline.Kind.RIDE && block.getSegment().getLegIndex() == legIndex)
        return block.getTop();
    return Float.NaN;
  }

  private boolean isDraggedRideShown(@NonNull Drag drag)
  {
    return mAlternatives != null && mAlternatives.getConnection() == drag.mConnection
        && mAlternatives.getLegIndex() == drag.mLegIndex;
  }

  @Override
  protected boolean dispatchHoverEvent(@NonNull MotionEvent event)
  {
    return mAccess.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
  }

  @Override
  public boolean dispatchKeyEvent(@NonNull KeyEvent event)
  {
    return mAccess.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
  }

  @Override
  protected void onFocusChanged(boolean gainFocus, int direction, @Nullable Rect previouslyFocusedRect)
  {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    mAccess.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
  }

  @NonNull
  private String describe(@NonNull ItineraryItem item)
  {
    final List<String> lines = new ArrayList<>();
    for (LineBadge badge : item.getBadges())
      lines.add(badge.getText());
    final String transport = item.isWalkOnly() ? getContext().getString(R.string.transit_walk, item.getDuration())
                                               : String.join(", ", lines);
    return getContext().getString(R.string.transit_grid_column, item.getDeparture(), item.getArrival(),
                                  item.getDuration(), transport);
  }

  /** The sheet's background: under the lane. */
  @ColorInt
  private static int backgroundColor(@NonNull Context context)
  {
    final TypedValue value = new TypedValue();
    if (context.getTheme().resolveAttribute(R.attr.appBackground, value, true)
        && value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT)
      return value.data;
    return ThemeUtils.getColor(context, android.R.attr.colorBackground);
  }

  private float dp(float value)
  {
    return value * mDensity;
  }

  private float sp(float value)
  {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
  }

  /** Columns and paging as virtual views for screen readers. */
  private final class AccessHelper extends ExploreByTouchHelper
  {
    AccessHelper(@NonNull View host)
    {
      super(host);
    }

    @Override
    protected int getVirtualViewAt(float x, float y)
    {
      return targetAt(x, y);
    }

    @Override
    protected void getVisibleVirtualViews(@NonNull List<Integer> ids)
    {
      if (mShowEarlier)
        ids.add(ID_EARLIER);
      final TimeGridLayout layout = layout();
      if (layout != null)
        for (int i = 0; i < Math.min(layout.getColumns().size(), mItems.size()); ++i)
        {
          ids.add(i);
          for (TimeGridLayout.Block block : layout.getColumns().get(i).getBlocks())
            if (block.getKind() == ItineraryTimeline.Kind.RIDE && block.getSegment().getLegIndex() >= 0)
              ids.add(rideId(i, block.getSegment().getLegIndex()));
          if (mAlternatives != null && mAlternatives.getConnection() == i)
            for (int c = 0; c < mAlternatives.getChips().size(); ++c)
              ids.add(ID_PILL - c);
        }
      if (mShowLater)
        ids.add(ID_LATER);
    }

    // ExploreByTouchHelper needs the bounds in the parent.
    @SuppressWarnings("deprecation")
    @Override
    protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node)
    {
      final Rect bounds = new Rect();
      final TimeGridLayout layout = layout();
      if (id == ID_EARLIER || id == ID_LATER)
      {
        final RectF rect = pagingRect(id == ID_EARLIER);
        if (rect != null)
          rect.roundOut(bounds);
        node.setContentDescription(
            getContext().getString(id == ID_EARLIER ? R.string.transit_earlier : R.string.transit_later));
      }
      else if (id <= ID_PILL && mAlternatives != null)
      {
        final int chip = ID_PILL - id;
        final RectF touch = pillTouchRect(chip);
        if (touch != null && chip < mAlternatives.getChips().size())
        {
          touch.roundOut(bounds);
          node.setContentDescription(
              AlternativesViews.description(getContext(), mAlternatives.getChips().get(chip), mAlternatives.getNextLine()));
          node.setSelected(mAlternatives.getChips().get(chip).isSelected());
        }
      }
      else if (id >= ID_RIDE && layout != null)
      {
        final int index = (id - ID_RIDE) / RIDE_STRIDE;
        final int leg = (id - ID_RIDE) % RIDE_STRIDE;
        if (index < layout.getColumns().size())
        {
          final TimeGridLayout.Column column = layout.getColumns().get(index);
          for (TimeGridLayout.Block block : column.getBlocks())
            if (block.getKind() == ItineraryTimeline.Kind.RIDE && block.getSegment().getLegIndex() == leg)
            {
              bounds.set(column.getLeft(), block.getTop(), column.getRight(), block.getBottom());
              final LineBadge badge = block.getBadge();
              final String time = mFormatter != null
                                    ? mFormatter.formatDeviceTime(Instant.ofEpochSecond(block.getSegment().getStart()))
                                    : "";
              node.setContentDescription((badge != null ? badge.getText() : "") + " " + time);
            }
          // Holding the ride: its other departures.
          final boolean shown =
              mAlternatives != null && mAlternatives.getConnection() == index && mAlternatives.getLegIndex() == leg;
          node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
              AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
              getContext().getString(shown ? R.string.transit_alt_hide : R.string.transit_alt_show)));
          node.setLongClickable(true);
          // What dragging the selected ride does: the previous or next departure.
          final int selected = selectedChip(index, leg);
          if (selected > 0)
            node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                R.id.transit_action_earlier_departure, getContext().getString(R.string.transit_alt_earlier)));
          if (selected >= 0 && mAlternatives != null && selected + 1 < mAlternatives.getChips().size())
            node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                R.id.transit_action_later_departure, getContext().getString(R.string.transit_alt_later)));
        }
      }
      else if (layout != null && id >= 0 && id < layout.getColumns().size() && id < mItems.size())
      {
        final TimeGridLayout.Column column = layout.getColumns().get(id);
        bounds.set(column.getLeft(), 0, column.getRight(), getHeight());
        node.setContentDescription(describe(mItems.get(id)));
      }
      if (bounds.isEmpty())
        bounds.set(0, 0, 1, 1);
      node.setBoundsInParent(bounds);
      // Rides and columns open their connection, or end the selection.
      final int tap = tapAction(id);
      final String label = tap == ID_DISMISS ? getContext().getString(R.string.transit_alt_done)
                         : tap >= 0          ? getContext().getString(R.string.transit_alt_details)
                                             : null;
      node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_CLICK,
                                                                               label));
      node.setClickable(true);
    }

    @Override
    protected boolean onPerformActionForVirtualView(int id, int action, @Nullable Bundle arguments)
    {
      if ((action == R.id.transit_action_earlier_departure || action == R.id.transit_action_later_departure)
          && id >= ID_RIDE && mListener != null)
      {
        final int selected = selectedChip((id - ID_RIDE) / RIDE_STRIDE, (id - ID_RIDE) % RIDE_STRIDE);
        final int chip = selected + (action == R.id.transit_action_earlier_departure ? -1 : 1);
        if (selected < 0 || mAlternatives == null || chip < 0 || chip >= mAlternatives.getChips().size())
          return false;
        mListener.onAlternativeClick(chip);
        return true;
      }
      if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK && id >= ID_RIDE && mListener != null)
      {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        mListener.onRideLongClick((id - ID_RIDE) / RIDE_STRIDE, (id - ID_RIDE) % RIDE_STRIDE);
        sendEventForVirtualView(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
        return true;
      }
      return action == AccessibilityNodeInfoCompat.ACTION_CLICK && perform(id);
    }
  }
}
