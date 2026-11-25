package app.organicmaps.settings;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.maplayer.aerial.AerialImageryLayer;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.transit.KeyValueStore;

/**
 * Aerial imagery layer "Luftbild" (better-maps, services/imagery-proxy): the server address and device token entered by
 * the user, and the switch of the layer in the map layers menu ({@link AerialImageryLayer}). Off by default; nothing is
 * compiled into the APK. Values live in the app-private "aerial_imagery" SharedPreferences, which are not part of the
 * backup (see backup_content.xml). The token is never logged. Only https:// is accepted, except for local addresses
 * (same rules as {@link RoutingServerSettings#checkUrl}).
 */
public final class AerialImagerySettings
{
  private static final String TAG = AerialImagerySettings.class.getSimpleName();

  public static final String PREFS_NAME = AerialImageryLayer.PREFS_NAME;
  public static final String KEY_ENABLED = AerialImageryLayer.KEY_ENABLED;
  public static final String KEY_URL = "aerial_imagery_url";
  public static final String KEY_TOKEN = "aerial_imagery_token";

  /** Where the settings go to (the core in the app, a fake in tests). */
  public interface CoreSink
  {
    void setAerialImagery(boolean enabled, @NonNull String url, @NonNull String token);
  }

  @NonNull
  private final KeyValueStore mStore;

  public AerialImagerySettings(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  @NonNull
  public static AerialImagerySettings get(@NonNull Context context)
  {
    return new AerialImagerySettings(
        KeyValueStore.of(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)));
  }

  /**
   * Passes the current settings to the core and keeps doing so when the layer is switched in the layers menu. Call
   * after the framework is initialized; call {@link #applyToCore} after each change of the settings.
   */
  public static void init(@NonNull Context context)
  {
    AerialImageryLayer.setListener(AerialImagerySettings::applyToCore);
    applyToCore(context);
  }

  public static void applyToCore(@NonNull Context context)
  {
    final boolean active = get(context).apply(Framework::nativeSetAerialImagery);
    Logger.i(TAG, "Aerial imagery " + (active ? "on" : "off"));
  }

  /** The layer switch of the map layers menu. */
  public boolean isEnabled()
  {
    return mStore.getBoolean(KEY_ENABLED, false);
  }

  public void setEnabled(boolean enabled)
  {
    mStore.putBoolean(KEY_ENABLED, enabled);
  }

  @NonNull
  public String getUrl()
  {
    final String url = mStore.getString(KEY_URL, null);
    return url == null ? "" : url.trim();
  }

  public void setUrl(@Nullable String url)
  {
    mStore.putString(KEY_URL, url == null ? "" : url.trim());
  }

  @NonNull
  public String getToken()
  {
    final String token = mStore.getString(KEY_TOKEN, null);
    return token == null ? "" : token.trim();
  }

  public boolean hasToken()
  {
    return !getToken().isEmpty();
  }

  public void setToken(@Nullable String token)
  {
    if (token == null || token.trim().isEmpty())
      mStore.remove(KEY_TOKEN);
    else
      mStore.putString(KEY_TOKEN, token.trim());
  }

  /** A usable server: valid address and a token. */
  public boolean isConfigured()
  {
    return RoutingServerSettings.checkUrl(getUrl()) == null && hasToken();
  }

  /** The layer is switched on and has a usable server. */
  public boolean isActive()
  {
    return isEnabled() && isConfigured();
  }

  /**
   * Passes the settings to |sink|: the server whenever it is configured (so that switching the layer needs no new
   * check), and whether the layer is on. Nothing but empty strings if the server is not usable.
   *
   * @return whether the layer is active.
   */
  public boolean apply(@NonNull CoreSink sink)
  {
    if (isConfigured())
      sink.setAerialImagery(isEnabled(), getUrl(), getToken());
    else
      sink.setAerialImagery(false, "", "");
    return isActive();
  }

  /** Base URL without trailing slashes, for building request URLs. */
  @NonNull
  public static String baseUrl(@NonNull String url)
  {
    String u = url.trim();
    while (u.endsWith("/"))
      u = u.substring(0, u.length() - 1);
    return u;
  }
}
