package app.organicmaps.transit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.transit.ItineraryTimeline;
import app.organicmaps.transit.JourneyBarLayout;
import app.organicmaps.transit.LineBadge;
import app.organicmaps.util.ThemeUtils;
import java.util.Collections;
import java.util.List;

/**
 * Horizontal bar over the whole duration of a connection: rides in their line colors with the line name if it fits,
 * walks as dotted lines, waits empty. The geometry comes from {@link JourneyBarLayout}.
 * <p>
 * The bar takes no touches itself: taps go to the parent (e.g. to open the connection). One ride can be marked as
 * the one the parent reacts to when it is held ({@link #setRide}, {@link #isOnRide}), e.g. to show the other
 * departures of the first ride.
 */
public final class JourneyBarView extends View
{
  /** Touches this far beside the ride still count. */
  private static final float TOUCH_SLACK_DP = 8;

  private static final float HEIGHT_DP = 20;
  private static final float MIN_RIDE_DP = 6;
  private static final float GAP_DP = 2;
  private static final float CORNER_DP = 4;
  private static final float LABEL_PADDING_DP = 3;
  private static final float TEXT_SP = 11;
  private static final float DOT_DP = 3;
  private static final float DOT_SPACING_DP = 6;
  /** Cancelled rides are drawn faded. */
  private static final int CANCELLED_ALPHA = 0x66;

  private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mWalk = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF mRect = new RectF();
  private final Paint.FontMetrics mFontMetrics = new Paint.FontMetrics();
  private final float mDensity;
  @Nullable
  private ItineraryTimeline mTimeline;
  @NonNull
  private List<JourneyBarLayout.Piece> mPieces = Collections.emptyList();
  private int mLayoutWidth = -1;
  private int mRideLegIndex = -1;
  // Leg index of the highlighted (open) ride, -1 for none.
  private int mOpenLegIndex = -1;
  private final Paint mOutline = new Paint(Paint.ANTI_ALIAS_FLAG);

  public JourneyBarView(@NonNull Context context)
  {
    this(context, null);
  }

  public JourneyBarView(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    mDensity = getResources().getDisplayMetrics().density;
    mText.setTextAlign(Paint.Align.CENTER);
    mText.setTypeface(Typeface.DEFAULT_BOLD);
    mText.setTextSize(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, getResources().getDisplayMetrics()));
    mWalk.setStyle(Paint.Style.STROKE);
    mWalk.setStrokeCap(Paint.Cap.ROUND);
    mWalk.setStrokeWidth(dp(DOT_DP));
    // Zero-length dashes with round caps are dots.
    mWalk.setPathEffect(new DashPathEffect(new float[] {0, dp(DOT_SPACING_DP)}, 0));
    mWalk.setColor(ThemeUtils.getColor(context, android.R.attr.textColorSecondary));
    // The rows around it describe the connection; the ride's departures are also reachable with a button.
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    mOutline.setStyle(Paint.Style.STROKE);
    mOutline.setStrokeWidth(dp(2));
    mOutline.setColor(ContextCompat.getColor(context, R.color.base_accent));
  }

  /**
   * Marks the ride the parent reacts to when it is held.
   *
   * @param legIndex leg index of the ride ({@link ItineraryTimeline.Segment#getLegIndex()}), -1 for none.
   * @param open the ride is outlined, e.g. while its other departures are shown.
   */
  public void setRide(int legIndex, boolean open)
  {
    mRideLegIndex = legIndex;
    mOpenLegIndex = open ? legIndex : -1;
    invalidate();
  }

  /** True if a point in view coordinates is on (or a little beside) the marked ride. */
  public boolean isOnRide(float x, float y)
  {
    final JourneyBarLayout.Piece piece = ridePiece();
    if (piece == null || y < 0 || y > getHeight())
      return false;
    final float[] bounds = bounds(piece);
    return x >= bounds[0] - dp(TOUCH_SLACK_DP) && x <= bounds[1] + dp(TOUCH_SLACK_DP);
  }

  /** Center of the marked ride in view coordinates, null if there is none (or the bar is not laid out yet). */
  @VisibleForTesting
  @Nullable
  public float[] getRideCenter()
  {
    final JourneyBarLayout.Piece piece = ridePiece();
    if (piece == null)
      return null;
    final float[] bounds = bounds(piece);
    return new float[] {(bounds[0] + bounds[1]) / 2, getHeight() / 2f};
  }

  @Nullable
  private JourneyBarLayout.Piece ridePiece()
  {
    if (mRideLegIndex < 0)
      return null;
    updateLayout();
    for (JourneyBarLayout.Piece piece : mPieces)
      if (piece.getKind() == ItineraryTimeline.Kind.RIDE && piece.getSegment().getLegIndex() == mRideLegIndex)
        return piece;
    return null;
  }

  /** Left and right of a piece in view coordinates. */
  @NonNull
  private float[] bounds(@NonNull JourneyBarLayout.Piece piece)
  {
    final int width = getWidth() - getPaddingLeft() - getPaddingRight();
    final float left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getPaddingLeft() + width - piece.getRight()
                                                                    : getPaddingLeft() + piece.getLeft();
    return new float[] {left, left + piece.getWidth()};
  }

  public void setTimeline(@Nullable ItineraryTimeline timeline)
  {
    mTimeline = timeline;
    mLayoutWidth = -1;
    invalidate();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
  {
    final int height = Math.round(dp(HEIGHT_DP)) + getPaddingTop() + getPaddingBottom();
    setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                         resolveSize(height, heightMeasureSpec));
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas)
  {
    super.onDraw(canvas);
    if (mTimeline == null)
      return;
    updateLayout();
    final int width = getWidth() - getPaddingLeft() - getPaddingRight();
    final float top = getPaddingTop();
    final float bottom = getHeight() - getPaddingBottom();
    final float centerY = (top + bottom) / 2;
    final float x0 = getPaddingLeft();
    final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    mText.getFontMetrics(mFontMetrics);
    final float baseline = centerY - (mFontMetrics.ascent + mFontMetrics.descent) / 2;
    for (JourneyBarLayout.Piece piece : mPieces)
    {
      // Time runs from right to left in right-to-left layouts.
      final float left = rtl ? x0 + width - piece.getRight() : x0 + piece.getLeft();
      final float right = left + piece.getWidth();
      if (piece.getKind() == ItineraryTimeline.Kind.WALK)
      {
        final float inset = Math.min(dp(DOT_DP) / 2, piece.getWidth() / 2f);
        canvas.drawLine(left + inset, centerY, right - inset, centerY, mWalk);
        continue;
      }
      final LineBadge badge = piece.getBadge();
      if (badge == null)
        continue;
      final int alpha = piece.getSegment().isCancelled() ? CANCELLED_ALPHA : 0xFF;
      mFill.setColor(badge.getBackgroundColor());
      mFill.setAlpha(alpha);
      mRect.set(left, top, right, bottom);
      canvas.drawRoundRect(mRect, dp(CORNER_DP), dp(CORNER_DP), mFill);
      if (mOpenLegIndex >= 0 && piece.getSegment().getLegIndex() == mOpenLegIndex)
      {
        mRect.inset(-dp(2), -dp(2));
        canvas.drawRoundRect(mRect, dp(CORNER_DP + 2), dp(CORNER_DP + 2), mOutline);
        mRect.inset(dp(2), dp(2));
      }
      for (String label : JourneyBarLayout.labelCandidates(badge.getText()))
      {
        if (JourneyBarLayout.fitsLabel(piece.getWidth(), mText.measureText(label), Math.round(dp(LABEL_PADDING_DP))))
        {
          mText.setColor(badge.getTextColor());
          mText.setAlpha(alpha);
          canvas.drawText(label, (left + right) / 2, baseline, mText);
          break;
        }
      }
    }
  }

  /** The pieces for the current width; a touch can come before the first draw after new data. */
  private void updateLayout()
  {
    final int width = getWidth() - getPaddingLeft() - getPaddingRight();
    if (mTimeline == null || width <= 0)
    {
      mPieces = Collections.emptyList();
      mLayoutWidth = -1;
      return;
    }
    if (width != mLayoutWidth)
    {
      mLayoutWidth = width;
      mPieces = JourneyBarLayout.layout(mTimeline, width, Math.round(dp(MIN_RIDE_DP)), Math.round(dp(GAP_DP)));
    }
  }

  private float dp(float value)
  {
    return value * mDensity;
  }
}
