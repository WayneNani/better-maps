package app.organicmaps.transit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
import app.organicmaps.transit.ItineraryItem;
import app.organicmaps.transit.ItineraryTimeline;
import app.organicmaps.transit.JourneyBarLayout;
import app.organicmaps.transit.LineBadge;
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
 */
public final class TimeGridView extends View
{
  public interface Listener
  {
    void onConnectionClick(int index);

    void onEarlierClick();

    void onLaterClick();
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

  /** Virtual view ids for accessibility; connections use their index. */
  private static final int ID_EARLIER = -10;
  private static final int ID_LATER = -11;

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
  private final RectF mRect = new RectF();
  private final Paint.FontMetrics mMetrics = new Paint.FontMetrics();
  @ColorInt
  private final int mTextColor;
  @ColorInt
  private final int mLateColor;
  @ColorInt
  private final int mOnTimeColor;
  private final GestureDetector mGestures;
  private final AccessHelper mAccess;
  private final ViewTreeObserver.OnScrollChangedListener mScrollListener = this::invalidate;

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
    mAxisWidth = dp(MIN_AXIS_DP);

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
    final float oldAxisWidth = mAxisWidth;
    final boolean sizeChanged = items.size() != mItems.size() || showEarlier != mShowEarlier || showLater != mShowLater;
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
    if (sizeChanged || oldAxisWidth != mAxisWidth)
      requestLayout();
    invalidate();
    mAccess.invalidateRoot();
  }

  /** Horizontal distance between two connections, e.g. to keep them in view when earlier ones are added. */
  public int getColumnStep()
  {
    return Math.round(dp(COLUMN_DP) + dp(GAP_DP));
  }

  /** Center of a connection's column in view coordinates, null if there is no such column. */
  @VisibleForTesting
  @Nullable
  public float[] getColumnCenter(int index)
  {
    final TimeGridLayout layout = layout();
    if (layout == null || index < 0 || index >= layout.getColumns().size())
      return null;
    final TimeGridLayout.Column column = layout.getColumns().get(index);
    return new float[] {column.getCenterX(), (column.getTop() + column.getBottom()) / 2f};
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
    }
    return mLayout;
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
    for (int i = 0; i < layout.getColumns().size() && i < mItems.size(); ++i)
      drawColumn(canvas, layout.getColumns().get(i), mItems.get(i));
    if (mShowLater)
      drawPaging(canvas, layout.getContentWidth(), layout, false);
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
      if (badge == null)
        continue;
      final int alpha = block.getSegment().isCancelled() ? CANCELLED_ALPHA : 0xFF;
      mFill.setColor(badge.getBackgroundColor());
      mFill.setAlpha(alpha);
      mRect.set(column.getLeft(), block.getTop(), column.getRight(), block.getBottom());
      canvas.drawRoundRect(mRect, dp(CORNER_DP), dp(CORNER_DP), mFill);
      mBlockText.getFontMetrics(mMetrics);
      final float textHeight = mMetrics.descent - mMetrics.ascent;
      if (block.getHeight() >= textHeight + dp(2))
      {
        for (String label : JourneyBarLayout.labelCandidates(badge.getText()))
        {
          if (!JourneyBarLayout.fitsLabel(column.getRight() - column.getLeft(), mBlockText.measureText(label),
                                          Math.round(dp(2))))
            continue;
          mBlockText.setColor(badge.getTextColor());
          mBlockText.setAlpha(alpha);
          final float centerY = (block.getTop() + block.getBottom()) / 2f;
          canvas.drawText(label, centerX, centerY - (mMetrics.ascent + mMetrics.descent) / 2, mBlockText);
          break;
        }
      }
    }

    // Departure above, arrival below, colored by delay.
    mLabel.getFontMetrics(mMetrics);
    setLabelStyle(item.getDepartureDelay(), item.isCancelled());
    canvas.drawText(item.getDeparture(), centerX, column.getTop() - dp(3) - mMetrics.descent, mLabel);
    setLabelStyle(item.getArrivalDelay(), item.isCancelled());
    canvas.drawText(item.getArrival(), centerX, column.getBottom() + dp(3) - mMetrics.ascent, mLabel);
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
    final RectF earlier = pagingRect(true);
    if (earlier != null && earlier.contains(x, y))
      return ID_EARLIER;
    final RectF later = pagingRect(false);
    if (later != null && later.contains(x, y))
      return ID_LATER;
    final int column = layout.columnAt(x);
    return column >= 0 && column < mItems.size() ? column : ExploreByTouchHelper.INVALID_ID;
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
    if (target == ID_EARLIER)
    {
      if (!mLoadingEarlier)
        mListener.onEarlierClick();
    }
    else if (target == ID_LATER)
    {
      if (!mLoadingLater)
        mListener.onLaterClick();
    }
    else
      mListener.onConnectionClick(target);
    mAccess.sendEventForVirtualView(target, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
    return true;
  }

  @Override
  public boolean onTouchEvent(@NonNull MotionEvent event)
  {
    return mGestures.onTouchEvent(event) || super.onTouchEvent(event);
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
          ids.add(i);
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
      else if (layout != null && id >= 0 && id < layout.getColumns().size() && id < mItems.size())
      {
        final TimeGridLayout.Column column = layout.getColumns().get(id);
        bounds.set(column.getLeft(), 0, column.getRight(), getHeight());
        node.setContentDescription(describe(mItems.get(id)));
      }
      if (bounds.isEmpty())
        bounds.set(0, 0, 1, 1);
      node.setBoundsInParent(bounds);
      node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
      node.setClickable(true);
    }

    @Override
    protected boolean onPerformActionForVirtualView(int id, int action, @Nullable Bundle arguments)
    {
      return action == AccessibilityNodeInfoCompat.ACTION_CLICK && perform(id);
    }
  }
}
