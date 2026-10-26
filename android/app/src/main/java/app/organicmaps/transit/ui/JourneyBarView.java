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
import app.organicmaps.transit.ItineraryTimeline;
import app.organicmaps.transit.JourneyBarLayout;
import app.organicmaps.transit.LineBadge;
import app.organicmaps.util.ThemeUtils;
import java.util.Collections;
import java.util.List;

/**
 * Horizontal bar over the whole duration of a connection: rides in their line colors with the line name if it fits,
 * walks as dotted lines, waits empty. The geometry comes from {@link JourneyBarLayout}.
 */
public final class JourneyBarView extends View
{
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
    // The rows around it describe the connection.
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
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
    final int width = getWidth() - getPaddingLeft() - getPaddingRight();
    if (width != mLayoutWidth)
    {
      mLayoutWidth = width;
      mPieces = JourneyBarLayout.layout(mTimeline, width, Math.round(dp(MIN_RIDE_DP)), Math.round(dp(GAP_DP)));
    }
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
      if (JourneyBarLayout.fitsLabel(piece.getWidth(), mText.measureText(badge.getText()),
                                     Math.round(dp(LABEL_PADDING_DP))))
      {
        mText.setColor(badge.getTextColor());
        mText.setAlpha(alpha);
        canvas.drawText(badge.getText(), (left + right) / 2, baseline, mText);
      }
    }
  }

  private float dp(float value)
  {
    return value * mDensity;
  }
}
