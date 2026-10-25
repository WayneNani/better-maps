package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.online.PhotonConfig;
import app.organicmaps.transit.KeyValueStore;
import java.util.Locale;

/** Settings of the online search (Photon). Keys are shared with {@code prefs_online_search.xml}. */
public final class OnlineSearchSettings
{
  /** Name of the SharedPreferences file. */
  public static final String PREFS_NAME = "online_search";

  /** Switch of the online search; it also stores the consent, see {@link OnlineSearchConsent}. */
  public static final String KEY_ONLINE = "online_search_enabled";
  public static final String KEY_SERVER_URL = "online_search_server_url";

  @NonNull
  private final KeyValueStore mStore;

  public OnlineSearchSettings(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  /** Photon server; the default is the public instance. Invalid values fall back to the default. */
  @NonNull
  public String getServerUrl()
  {
    final String url = mStore.getString(KEY_SERVER_URL, null);
    return isValidServerUrl(url) ? normalizeServerUrl(url) : PhotonConfig.DEFAULT_URL;
  }

  public boolean isDefaultServer()
  {
    return getServerUrl().equals(PhotonConfig.DEFAULT_URL);
  }

  public static boolean isValidServerUrl(@Nullable String url)
  {
    if (url == null)
      return false;
    final String trimmed = url.trim().toLowerCase(Locale.ROOT);
    if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://"))
      return false;
    final String host = trimmed.substring(trimmed.indexOf("://") + 3);
    return !host.isEmpty() && !host.startsWith("/") && !host.contains(" ");
  }

  /** Removes white space, trailing slashes and a trailing "/api" (the endpoint is appended by the provider). */
  @NonNull
  static String normalizeServerUrl(@NonNull String url)
  {
    String result = url.trim();
    while (result.endsWith("/"))
      result = result.substring(0, result.length() - 1);
    if (result.endsWith("/api"))
      result = result.substring(0, result.length() - 4);
    while (result.endsWith("/"))
      result = result.substring(0, result.length() - 1);
    return result;
  }
}
