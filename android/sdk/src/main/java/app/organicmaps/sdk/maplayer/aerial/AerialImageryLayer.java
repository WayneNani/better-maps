package app.organicmaps.sdk.maplayer.aerial;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * better-maps: the switch of the aerial imagery layer ("Luftbild") in the map layers menu. The state lives in the
 * app-private "aerial_imagery" SharedPreferences, next to the server address and token of the app's settings, which
 * listens for changes and passes everything to the core.
 */
public final class AerialImageryLayer
{
  /** Name of the SharedPreferences file (shared with the app's settings of the layer). */
  public static final String PREFS_NAME = "aerial_imagery";
  public static final String KEY_ENABLED = "aerial_imagery_enabled";

  public interface Listener
  {
    void onAerialImageryChanged(@NonNull Context context);
  }

  @Nullable
  private static Listener sListener;

  private AerialImageryLayer() {}

  public static void setListener(@Nullable Listener listener)
  {
    sListener = listener;
  }

  public static boolean isEnabled(@NonNull Context context)
  {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
  }

  public static void setEnabled(@NonNull Context context, boolean enabled)
  {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
    final Listener listener = sListener;
    if (listener != null)
      listener.onAerialImageryChanged(context.getApplicationContext());
  }
}
