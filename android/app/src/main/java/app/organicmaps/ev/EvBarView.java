package app.organicmaps.ev;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import java.util.ArrayList;
import java.util.List;

/** Bar of a charging plan: driving (teal) and charging (amber) over the total time, see {@link EvBarLayout}. */
public class EvBarView extends android.view.View
{
  private final Paint mDrive = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mCharge = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF mRect = new RectF();
  private final float mGap;
  private final float mRadius;
  private final int mHeight;
  @NonNull
  private List<EvBarLayout.Piece> mPieces = new ArrayList<>();

  public EvBarView(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    final float density = getResources().getDisplayMetrics().density;
    mGap = 2 * density;
    mRadius = 3 * density;
    mHeight = Math.round(16 * density);
    mDrive.setColor(ContextCompat.getColor(context, R.color.ev_drive));
    mCharge.setColor(ContextCompat.getColor(context, R.color.ev_charge));
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  public EvBarView(@NonNull Context context)
  {
    this(context, null);
  }

  public void setPlan(@NonNull EvPlanResult.Plan plan)
  {
    mPieces = EvBarLayout.layout(plan);
    invalidate();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
  {
    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), mHeight);
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas)
  {
    final float width = getWidth() - mGap * Math.max(0, mPieces.size() - 1);
    float x = 0;
    for (EvBarLayout.Piece piece : mPieces)
    {
      final float w = (float) (piece.fraction * width);
      mRect.set(x, 0, x + Math.max(w, mGap), getHeight());
      canvas.drawRoundRect(mRect, mRadius, mRadius, piece.kind == EvBarLayout.Kind.DRIVE ? mDrive : mCharge);
      x += w + mGap;
    }
  }
}
