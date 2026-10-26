package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitMode;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Settings of the online public transport feature and their mapping to MOTIS routing options. Keys are shared
 * with {@code prefs_transit.xml}.
 */
public final class TransitSettings
{
  /** Name of the SharedPreferences file. */
  public static final String PREFS_NAME = "transit";

  public static final String KEY_ONLINE = "transit_online";
  public static final String KEY_SERVER_URL = "transit_server_url";
  public static final String KEY_TRANSFER_TIME = "transit_transfer_time";
  public static final String KEY_WALKING_SPEED = "transit_walking_speed";
  public static final String KEY_WHEELCHAIR = "transit_wheelchair";
  public static final String KEY_BIKE = "transit_bike";
  public static final String KEY_MAX_WALK = "transit_max_walk";
  public static final String KEY_EXCLUDED_MODES = "transit_excluded_modes";
  public static final String KEY_DEFAULT_VIEW = "transit_default_view";
  public static final String KEY_SHOW_ON_MAP = "transit_show_on_map";
  /** Keys of saved connections the user hid on the map, separated by line breaks. */
  static final String KEY_HIDDEN_ON_MAP = "transit_hidden_on_map";

  /** Extra buffer for transfers (MOTIS additionalTransferTime). */
  public enum TransferTime
  {
    NORMAL(null),
    MORE(5),
    MUCH_MORE(10);

    @Nullable
    private final Integer mAdditionalMinutes;

    TransferTime(@Nullable Integer additionalMinutes)
    {
      mAdditionalMinutes = additionalMinutes;
    }

    @Nullable
    public Integer getAdditionalMinutes()
    {
      return mAdditionalMinutes;
    }
  }

  /** Walking speed (MOTIS pedestrianSpeed in m/s); NORMAL uses the server default (about 1.2 m/s). */
  public enum WalkingSpeed
  {
    SLOW(0.9),
    NORMAL(null),
    FAST(1.6);

    @Nullable
    private final Double mMetersPerSecond;

    WalkingSpeed(@Nullable Double metersPerSecond)
    {
      mMetersPerSecond = metersPerSecond;
    }

    @Nullable
    public Double getMetersPerSecond()
    {
      return mMetersPerSecond;
    }
  }

  /** Groups of transit modes the user can exclude. Modes outside these groups are always allowed. */
  public enum ModeGroup
  {
    BUS(TransitMode.BUS, TransitMode.COACH),
    TRAM(TransitMode.TRAM),
    SUBWAY(TransitMode.SUBWAY),
    SUBURBAN(TransitMode.SUBURBAN),
    REGIONAL(TransitMode.REGIONAL_RAIL, TransitMode.REGIONAL_FAST_RAIL),
    LONG_DISTANCE(TransitMode.HIGHSPEED_RAIL, TransitMode.LONG_DISTANCE, TransitMode.NIGHT_RAIL),
    FERRY(TransitMode.FERRY);

    @NonNull
    private final Set<TransitMode> mModes;

    ModeGroup(@NonNull TransitMode first, @NonNull TransitMode... rest)
    {
      mModes = Collections.unmodifiableSet(EnumSet.of(first, rest));
    }

    @NonNull
    public Set<TransitMode> getModes()
    {
      return mModes;
    }
  }

  /** Modes that are always allowed because the user cannot exclude them. */
  static final Set<TransitMode> ALWAYS_ALLOWED = Collections.unmodifiableSet(
      EnumSet.of(TransitMode.AIRPLANE, TransitMode.FUNICULAR, TransitMode.AERIAL_LIFT, TransitMode.OTHER));

  public static final int DEFAULT_MAX_WALK_MINUTES = 15;
  /** Values offered for the maximum walk to/from the first/last stop. */
  public static final int[] MAX_WALK_MINUTES = {5, 10, 15, 20, 30};

  @NonNull
  private final KeyValueStore mStore;

  public TransitSettings(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  /** MOTIS server; the default is Transitous. Invalid values fall back to the default. */
  @NonNull
  public String getServerUrl()
  {
    final String url = mStore.getString(KEY_SERVER_URL, null);
    return isValidServerUrl(url) ? normalizeServerUrl(url) : TransitServiceConfig.TRANSITOUS_URL;
  }

  public boolean isDefaultServer()
  {
    return getServerUrl().equals(TransitServiceConfig.TRANSITOUS_URL);
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

  @NonNull
  static String normalizeServerUrl(@NonNull String url)
  {
    String result = url.trim();
    while (result.endsWith("/"))
      result = result.substring(0, result.length() - 1);
    return result;
  }

  @NonNull
  public TransferTime getTransferTime()
  {
    return parseEnum(TransferTime.class, mStore.getString(KEY_TRANSFER_TIME, null), TransferTime.NORMAL);
  }

  @NonNull
  public WalkingSpeed getWalkingSpeed()
  {
    return parseEnum(WalkingSpeed.class, mStore.getString(KEY_WALKING_SPEED, null), WalkingSpeed.NORMAL);
  }

  public boolean isWheelchair()
  {
    return mStore.getBoolean(KEY_WHEELCHAIR, false);
  }

  public boolean isBikeTransport()
  {
    return mStore.getBoolean(KEY_BIKE, false);
  }

  /** Maximum walking time to the first and from the last stop, in minutes. */
  public int getMaxWalkMinutes()
  {
    final String value = mStore.getString(KEY_MAX_WALK, null);
    if (value == null)
      return DEFAULT_MAX_WALK_MINUTES;
    try
    {
      final int minutes = Integer.parseInt(value.trim());
      return minutes > 0 && minutes <= 120 ? minutes : DEFAULT_MAX_WALK_MINUTES;
    }
    catch (NumberFormatException e)
    {
      return DEFAULT_MAX_WALK_MINUTES;
    }
  }

  /** How the connections are shown when the route planner opens: list (default) or time grid. */
  @NonNull
  public ConnectionsState.ViewMode getDefaultViewMode()
  {
    return parseEnum(ConnectionsState.ViewMode.class, mStore.getString(KEY_DEFAULT_VIEW, null),
                     ConnectionsState.ViewMode.LIST);
  }

  @NonNull
  public Set<ModeGroup> getExcludedModeGroups()
  {
    final Set<String> values = mStore.getStringSet(KEY_EXCLUDED_MODES, null);
    final Set<ModeGroup> result = EnumSet.noneOf(ModeGroup.class);
    if (values == null)
      return result;
    for (String value : values)
    {
      final ModeGroup group = parseEnum(ModeGroup.class, value, null);
      if (group != null)
        result.add(group);
    }
    return result;
  }

  /** True if excluding these groups still leaves something to travel with. */
  public static boolean isValidExclusion(@NonNull Set<String> excluded)
  {
    final Set<ModeGroup> groups = EnumSet.noneOf(ModeGroup.class);
    for (String value : excluded)
    {
      final ModeGroup group = parseEnum(ModeGroup.class, value, null);
      if (group != null)
        groups.add(group);
    }
    return groups.size() < ModeGroup.values().length;
  }

  /** Allowed transit modes: empty if nothing is excluded (the server then uses all modes). */
  @NonNull
  public Set<TransitMode> getAllowedTransitModes()
  {
    final Set<ModeGroup> excluded = getExcludedModeGroups();
    if (excluded.isEmpty() || excluded.size() == ModeGroup.values().length)
      return EnumSet.noneOf(TransitMode.class);
    final Set<TransitMode> allowed = EnumSet.copyOf(ALWAYS_ALLOWED);
    for (ModeGroup group : ModeGroup.values())
      if (!excluded.contains(group))
        allowed.addAll(group.getModes());
    return allowed;
  }

  /** True if the next saved connection is shown on the map (default). */
  public boolean isShownOnMap()
  {
    return mStore.getBoolean(KEY_SHOW_ON_MAP, true);
  }

  /** Saved connections the user hid on the map. */
  @NonNull
  public Set<String> getHiddenOnMap()
  {
    final String value = mStore.getString(KEY_HIDDEN_ON_MAP, null);
    final Set<String> result = new HashSet<>();
    if (value == null)
      return result;
    for (String key : value.split("\n"))
      if (!key.isEmpty())
        result.add(key);
    return result;
  }

  /**
   * Hides a saved connection on the map. Keys of connections that are no longer saved are dropped.
   *
   * @param saved keys of all saved connections.
   */
  public void hideOnMap(@NonNull String key, @NonNull Set<String> saved)
  {
    final Set<String> hidden = getHiddenOnMap();
    hidden.retainAll(saved);
    hidden.add(key);
    mStore.putString(KEY_HIDDEN_ON_MAP, String.join("\n", new TreeSet<>(hidden)));
  }

  /** Routing options for a query according to these settings. */
  @NonNull
  public TransitRoutingOptions toRoutingOptions()
  {
    final int maxWalkSeconds = getMaxWalkMinutes() * 60;
    return TransitRoutingOptions.builder()
        .setAdditionalTransferTimeMinutes(getTransferTime().getAdditionalMinutes())
        .setPedestrianSpeed(getWalkingSpeed().getMetersPerSecond())
        .setPedestrianProfile(isWheelchair() ? TransitRoutingOptions.PedestrianProfile.WHEELCHAIR : null)
        .setRequireBikeTransport(isBikeTransport() ? Boolean.TRUE : null)
        .setMaxPreTransitTimeSeconds(maxWalkSeconds)
        .setMaxPostTransitTimeSeconds(maxWalkSeconds)
        .setTransitModes(getAllowedTransitModes())
        .build();
  }

  /** Values of the "excluded modes" preference. */
  @NonNull
  public static Set<String> toPreferenceValues(@NonNull Set<ModeGroup> groups)
  {
    final Set<String> result = new HashSet<>();
    for (ModeGroup group : groups)
      result.add(group.name());
    return result;
  }

  @Nullable
  private static <E extends Enum<E>> E parseEnum(@NonNull Class<E> type, @Nullable String value, @Nullable E fallback)
  {
    if (value == null)
      return fallback;
    try
    {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }
    catch (IllegalArgumentException e)
    {
      return fallback;
    }
  }
}
