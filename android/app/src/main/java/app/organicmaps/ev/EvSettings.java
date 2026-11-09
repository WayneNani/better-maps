package app.organicmaps.ev;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Router;
import app.organicmaps.transit.KeyValueStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings of the charging planner: whether it is on, the vehicle profile and the last options of the route planner.
 * The planner is off by default and needs a vehicle profile.
 */
public class EvSettings
{
  public static final String PREFS_NAME = "ev_planning";

  static final String KEY_ENABLED = "enabled";
  static final String KEY_PROFILE = "profile";
  static final String KEY_CAR_SOC = "car_soc";
  static final String KEY_START_SOC = "start_soc";
  static final String KEY_MIN_ARRIVAL_SOC = "min_arrival_soc";
  static final String KEY_MIN_STOP_SOC = "min_stop_soc";
  static final String KEY_PRIORITY = "priority";
  static final String KEY_PREFERRED_PROVIDERS = "preferred_providers";
  static final String KEY_EXCLUDED_PROVIDERS = "excluded_providers";
  static final String KEY_MIN_POWER = "min_power_kw";
  static final String KEY_AMENITIES = "amenities";

  /** Choices of the minimum power in the route planner, kW. */
  public static final int[] MIN_POWER_CHOICES = {50, 150, 300};

  @NonNull
  private final KeyValueStore mStore;

  public EvSettings(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  @NonNull
  public static EvSettings from(@NonNull Context context)
  {
    return new EvSettings(
        KeyValueStore.of(context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)));
  }

  public boolean isEnabled()
  {
    return mStore.getBoolean(KEY_ENABLED, false);
  }

  public void setEnabled(boolean enabled)
  {
    mStore.putBoolean(KEY_ENABLED, enabled);
  }

  /** @return the vehicle profile, null if the user has none yet. */
  @Nullable
  public EvVehicleProfile getProfile()
  {
    return EvVehicleProfile.fromJson(mStore.getString(KEY_PROFILE, null));
  }

  public void setProfile(@Nullable EvVehicleProfile profile)
  {
    if (profile == null)
      mStore.remove(KEY_PROFILE);
    else
      mStore.putString(KEY_PROFILE, profile.toJson());
  }

  /** Planning in the route planner: on, a valid profile and the car router. */
  public boolean isAvailable(@Nullable Router router)
  {
    final EvVehicleProfile profile = getProfile();
    return router == Router.Vehicle && isEnabled() && profile != null && profile.isValid();
  }

  /** Take the state of charge from Android Auto, if the car provides it. */
  public boolean isCarSocEnabled()
  {
    return mStore.getBoolean(KEY_CAR_SOC, true);
  }

  public void setCarSocEnabled(boolean enabled)
  {
    mStore.putBoolean(KEY_CAR_SOC, enabled);
  }

  /** The options of the last planning, with the defaults of the mockup for the first one. */
  @NonNull
  public EvPlanOptions getOptions()
  {
    final EvPlanOptions o = new EvPlanOptions();
    o.startSoc = mStore.getInt(KEY_START_SOC, 80);
    o.minArrivalSoc = mStore.getInt(KEY_MIN_ARRIVAL_SOC, 20);
    o.minStopSoc = mStore.getInt(KEY_MIN_STOP_SOC, 10);
    final String priority = mStore.getString(KEY_PRIORITY, EvPlanOptions.PRIORITY_FASTEST);
    o.priority = priority == null ? EvPlanOptions.PRIORITY_FASTEST : priority;
    o.preferredProviders = getList(KEY_PREFERRED_PROVIDERS);
    o.excludedProviders = getList(KEY_EXCLUDED_PROVIDERS);
    o.minPowerKw = mStore.getInt(KEY_MIN_POWER, 50);
    o.preferredAmenities = getList(KEY_AMENITIES);
    return o;
  }

  /** Stores the options for the next planning (not the excluded stations, which are for one route). */
  public void setOptions(@NonNull EvPlanOptions o)
  {
    mStore.putInt(KEY_START_SOC, (int) Math.round(o.startSoc));
    mStore.putInt(KEY_MIN_ARRIVAL_SOC, (int) Math.round(o.minArrivalSoc));
    mStore.putInt(KEY_MIN_STOP_SOC, (int) Math.round(o.minStopSoc));
    mStore.putString(KEY_PRIORITY, o.priority);
    putList(KEY_PREFERRED_PROVIDERS, o.preferredProviders);
    putList(KEY_EXCLUDED_PROVIDERS, o.excludedProviders);
    mStore.putInt(KEY_MIN_POWER, (int) Math.round(o.minPowerKw));
    putList(KEY_AMENITIES, o.preferredAmenities);
  }

  @NonNull
  private List<String> getList(@NonNull String key)
  {
    final String value = mStore.getString(key, "");
    if (value == null || value.isEmpty())
      return new ArrayList<>();
    return new ArrayList<>(Arrays.asList(value.split(",")));
  }

  private void putList(@NonNull String key, @NonNull List<String> values)
  {
    mStore.putString(key, String.join(",", values));
  }
}
