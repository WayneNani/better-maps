package app.organicmaps.transit.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/** RecyclerView that wraps its content up to a maximum height and scrolls beyond it. */
public class MaxHeightRecyclerView extends RecyclerView
{
  private int mMaxHeight;

  public MaxHeightRecyclerView(@NonNull Context context)
  {
    super(context);
  }

  public MaxHeightRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
  }

  public MaxHeightRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle)
  {
    super(context, attrs, defStyle);
  }

  /** @param maxHeight in pixels, 0 for no limit. */
  public void setMaxHeight(int maxHeight)
  {
    if (mMaxHeight == maxHeight)
      return;
    mMaxHeight = maxHeight;
    requestLayout();
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec)
  {
    if (mMaxHeight > 0)
    {
      final int mode = View.MeasureSpec.getMode(heightSpec);
      final int size = View.MeasureSpec.getSize(heightSpec);
      final int limit = mode == View.MeasureSpec.UNSPECIFIED ? mMaxHeight : Math.min(size, mMaxHeight);
      heightSpec = View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST);
    }
    super.onMeasure(widthSpec, heightSpec);
  }
}
