package app.organicmaps.sdk.crowdtraffic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Settings of the anonymous congestion reports: the opt-in (off by default) and the relay URL (expert
 * setting, empty by default, which disables the reports). Reports are collected and sent only if both are
 * set.
 * <p>
 * The values live in the native settings (settings.ini), where the native client reads them at start-up;
 * the keys must match libs/map/crowd_traffic_recorder.hpp.
 */
public final class CrowdTrafficSettings
{
  public static final String KEY_ENABLED = "CrowdTrafficEnabled";
  public static final String KEY_RELAY_URL = "CrowdTrafficRelayUrl";

  /** Storage of the settings (native settings in the app, a map in tests). */
  public interface Store
  {
    boolean getBoolean(@NonNull String key, boolean def);

    void setBoolean(@NonNull String key, boolean value);

    @NonNull
    String getString(@NonNull String key, @NonNull String def);

    void setString(@NonNull String key, @NonNull String value);
  }

  /** Called whenever the effective state may have changed. */
  public interface Listener
  {
    void onChanged(boolean active, @Nullable RelayEndpoints endpoints);
  }

  @NonNull
  private final Store mStore;
  @NonNull
  private final Listener mListener;

  public CrowdTrafficSettings(@NonNull Store store, @NonNull Listener listener)
  {
    mStore = store;
    mListener = listener;
  }

  public boolean isEnabled()
  {
    return mStore.getBoolean(KEY_ENABLED, false);
  }

  public void setEnabled(boolean enabled)
  {
    if (enabled == isEnabled())
      return;
    mStore.setBoolean(KEY_ENABLED, enabled);
    notifyListener();
  }

  @NonNull
  public String getRelayUrl()
  {
    return mStore.getString(KEY_RELAY_URL, "");
  }

  /** Whether a value may be stored as relay URL: empty (disables the reports) or a valid http(s) URL. */
  public static boolean isAcceptableRelayUrl(@Nullable String url)
  {
    return url == null || url.trim().isEmpty() || RelayEndpoints.parse(url) != null;
  }

  /** Returns false (and keeps the old value) if the URL is not acceptable. */
  public boolean setRelayUrl(@Nullable String url)
  {
    if (!isAcceptableRelayUrl(url))
      return false;
    final String value = url == null ? "" : url.trim();
    if (value.equals(getRelayUrl()))
      return true;
    mStore.setString(KEY_RELAY_URL, value);
    notifyListener();
    return true;
  }

  @Nullable
  public RelayEndpoints getEndpoints()
  {
    return RelayEndpoints.parse(getRelayUrl());
  }

  /** Opt-in given and relay configured. */
  public boolean isActive()
  {
    return isEnabled() && getEndpoints() != null;
  }

  public void notifyListener()
  {
    final RelayEndpoints endpoints = getEndpoints();
    mListener.onChanged(isEnabled() && endpoints != null, endpoints);
  }
}
