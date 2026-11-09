package app.organicmaps.ev;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/** Options of the charging planner, see ev::PlanOptionsFromJson() in libs/ev_planning/plan_json.hpp. */
public class EvPlanOptions
{
  public static final String PRIORITY_FASTEST = "fastest";
  public static final String PRIORITY_FEWEST_STOPS = "fewest_stops";
  public static final String PRIORITY_LONG_BREAKS = "long_breaks";

  public double startSoc = 80;
  public double minArrivalSoc = 20;
  public double minStopSoc = 10;
  public double maxChargeSoc = 100;
  public String priority = PRIORITY_FASTEST;
  public List<String> preferredProviders = new ArrayList<>();
  public List<String> excludedProviders = new ArrayList<>();
  public double minPowerKw = 50;
  public List<String> preferredAmenities = new ArrayList<>();
  public List<String> excludedStations = new ArrayList<>();
  /** Seconds since the epoch, 0 for now. */
  public long departureTime;

  @NonNull
  public String toJson()
  {
    return EvJson.GSON.toJson(this);
  }

  @NonNull
  public EvPlanOptions copy()
  {
    return EvJson.GSON.fromJson(toJson(), EvPlanOptions.class);
  }
}
