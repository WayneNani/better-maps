package app.organicmaps.sdk.indoor;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

/**
 * better-maps: indoor maps (services/indoor-server). Called by the core on the main thread, see
 * {@link app.organicmaps.sdk.Framework#nativeSetIndoorListener}.
 */
@Keep
public interface IndoorListener
{
  /**
   * The indoor map in view changed: shown (with its levels, lowest first, and the shown level) or hidden.
   *
   * @param levels level numbers (may be fractional), ascending
   * @param labels the labels of |levels|
   */
  @Keep
  void onIndoorStateChanged(boolean visible, @NonNull String venueName, @NonNull String attribution,
                            @NonNull double[] levels, @NonNull String[] labels, double level);

  /**
   * A room or POI of the indoor map was tapped.
   *
   * @param kind rendering class from the server, e.g. "shop", "gate", "toilets"
   * @param tags key, value, key, value, ... (a subset of the OSM tags)
   */
  @Keep
  void onIndoorFeatureTapped(@NonNull String name, @NonNull String ref, @NonNull String kind,
                             @NonNull String levelLabel, @NonNull String venueName, @NonNull String osmId,
                             @NonNull String[] tags, double lat, double lon);
}
