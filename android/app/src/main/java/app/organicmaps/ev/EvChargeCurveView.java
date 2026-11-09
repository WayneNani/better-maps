package app.organicmaps.ev;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.util.ThemeUtils;
import java.util.List;

/** Charging curve of a vehicle: power over the state of charge (0-100 %). */
public class EvChargeCurveView extends android.view.View
{
  private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path mPath = new Path();
  private final int mHeight;
  @Nullable
  private EvVehicleProfile mProfile;

  public EvChargeCurveView(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    final float density = getResources().getDisplayMetrics().density;
    mHeight = Math.round(72 * density);
    mLine.setColor(ContextCompat.getColor(context, R.color.ev_drive));
    mLine.setStyle(Paint.Style.STROKE);
    mLine.setStrokeWidth(2.5f * density);
    mAxis.setColor(ThemeUtils.getColor(context, android.R.attr.textColorSecondary));
    mAxis.setStrokeWidth(density);
    mText.setColor(ThemeUtils.getColor(context, android.R.attr.textColorSecondary));
    mText.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
  }

  public void setProfile(@Nullable EvVehicleProfile profile)
  {
    mProfile = profile;
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
    final float textHeight = mText.getTextSize();
    final float bottom = getHeight() - textHeight - 4;
    final float top = textHeight + 2;
    canvas.drawLine(0, bottom, getWidth(), bottom, mAxis);
    canvas.drawText("0 %", 0, getHeight() - 2, mText);
    final String full = "100 %";
    canvas.drawText(full, getWidth() - mText.measureText(full), getHeight() - 2, mText);
    if (mProfile == null || mProfile.chargeCurve.size() < 2)
      return;
    final double peak = Math.max(1, mProfile.getCurvePeakKw());
    canvas.drawText(Math.round(peak) + " kW", 4, top - 2, mText);
    mPath.reset();
    final List<EvVehicleProfile.CurvePoint> curve = mProfile.chargeCurve;
    for (int i = 0; i < curve.size(); ++i)
    {
      final EvVehicleProfile.CurvePoint p = curve.get(i);
      final double kw = mProfile.maxDcPowerKw > 0 ? Math.min(p.kw, mProfile.maxDcPowerKw) : p.kw;
      final float x = (float) (p.soc / 100.0 * getWidth());
      final float y = (float) (bottom - kw / peak * (bottom - top));
      if (i == 0)
        mPath.moveTo(x, y);
      else
        mPath.lineTo(x, y);
    }
    canvas.drawPath(mPath, mLine);
  }
}
