package app.organicmaps.settings;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.transit.KeyValueStore;

/**
 * Indoor maps (better-maps, services/indoor-server): the switch, server address and device token entered by the user.
 * Off by default; nothing is compiled into the APK. Values live in the app-private "indoor_maps" SharedPreferences,
 * which are not part of the backup (see backup_content.xml). The token is never logged. Only https:// is accepted,
 * except for local addresses (same rules as {@link RoutingServerSettings#checkUrl}).
 */
public final class IndoorMapsSettings
{
  private static final String TAG = IndoorMapsSettings.class.getSimpleName();

  public static final String PREFS_NAME = "indoor_maps";
  public static final String KEY_ENABLED = "indoor_maps_enabled";
  public static final String KEY_URL = "indoor_maps_url";
  public static final String KEY_TOKEN = "indoor_maps_token";

  /** Where the settings go to (the core in the app, a fake in tests). */
  public interface CoreSink
  {
    void setIndoorServer(boolean enabled, @NonNull String url, @NonNull String token);
  }

  @NonNull
  private final KeyValueStore mStore;

  public IndoorMapsSettings(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  @NonNull
  public static IndoorMapsSettings get(@NonNull Context context)
  {
    return new IndoorMapsSettings(KeyValueStore.of(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)));
  }

  /** Passes the current settings to the core. Call after the framework is initialized and after each change. */
  public static void applyToCore(@NonNull Context context)
  {
    final boolean active = get(context).apply(Framework::nativeSetIndoorServer);
    Logger.i(TAG, "Indoor maps " + (active ? "on" : "off"));
  }

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

  /** Switched on and a usable server. */
  public boolean isActive()
  {
    return isEnabled() && isConfigured();
  }

  /**
   * Passes the settings to |sink|: the server and the switch if the server is usable, else off with empty strings.
   *
   * @return whether indoor maps are active.
   */
  public boolean apply(@NonNull CoreSink sink)
  {
    if (isConfigured())
      sink.setIndoorServer(isEnabled(), getUrl(), getToken());
    else
      sink.setIndoorServer(false, "", "");
    return isActive();
  }
}
