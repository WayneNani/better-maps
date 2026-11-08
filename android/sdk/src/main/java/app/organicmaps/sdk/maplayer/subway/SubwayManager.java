package app.organicmaps.sdk.maplayer.subway;

import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.Framework;

public class SubwayManager
{
  @NonNull
  private final OnTransitSchemeChangedListener mSchemeChangedListener;

  public SubwayManager(@NonNull Context context)
  {
    mSchemeChangedListener = new OnTransitSchemeChangedListener.Default(context);
  }

  // better-maps: decides whether a "no subway data" report is shown (only after the user enabled the layer).
  static final SubwayNoDataNotice NOTICE = new SubwayNoDataNotice();

  static public void setEnabled(boolean isEnabled)
  {
    if (isEnabled == isEnabled())
      return;

    if (isEnabled)
      NOTICE.onUserEnabled(SystemClock.elapsedRealtime());
    Framework.nativeSetTransitSchemeEnabled(isEnabled);
  }

  static public boolean isEnabled()
  {
    return Framework.nativeIsTransitSchemeEnabled();
  }

  public void initialize()
  {
    registerListener();
  }

  private void registerListener()
  {
    nativeAddListener(mSchemeChangedListener);
  }

  private static native void nativeAddListener(@NonNull OnTransitSchemeChangedListener listener);

  private static native void nativeRemoveListener(@NonNull OnTransitSchemeChangedListener listener);
}
