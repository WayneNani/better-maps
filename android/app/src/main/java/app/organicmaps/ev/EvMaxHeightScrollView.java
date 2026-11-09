package app.organicmaps.ev;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/** A scroll view that is at most a part of the screen high, so that the map with the route stays visible above. */
public class EvMaxHeightScrollView extends NestedScrollView
{
  private static final float MAX_SCREEN_FRACTION = 0.6f;

  public EvMaxHeightScrollView(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
  {
    final int max = (int) (getResources().getDisplayMetrics().heightPixels * MAX_SCREEN_FRACTION);
    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
  }
}
