package app.organicmaps.ev;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Result of the charging planner, see ev::ToJson(PlanResult) in libs/ev_planning/plan_json.cpp. */
public class EvPlanResult
{
  public static final String STATUS_OK = "ok";
  public static final String STATUS_UNREACHABLE = "unreachable";
  public static final String STATUS_NO_ROUTE = "no_route";
  public static final String STATUS_INVALID_PROFILE = "invalid_profile";
  /** Not from the core: the JSON could not be read. */
  public static final String STATUS_ERROR = "error";

  public static class Socket
  {
    public String type = "";
    public int count;
    public double powerKw;
  }

  public static class Amenity
  {
    public static final String PLAYGROUND = "playground";
    public static final String TOILETS = "toilets";
    public static final String FOOD = "food";
    public static final String SHOPPING = "shopping";

    public String type = "";
    /** E.g. "restaurant", "supermarket", "services". */
    public String kind = "";
    public String name = "";
    public double distanceM;
    @Nullable
    public Boolean openAtArrival;
    public double lat;
    public double lon;
  }

  public static class Longer
  {
    public double departureSocPct;
    public double extraStopTimeSec;
    public double extraTotalTimeSec;
    public double arrivalSocPct;
  }

  public static class Stop
  {
    public String key = "";
    public String name = "";
    public String title = "";
    public String provider = "";
    public String operatorName = "";
    public String brand = "";
    public String network = "";
    public double lat;
    public double lon;
    public double distanceAlongRouteM;
    public double distanceFromRouteM;
    public double detourSec;
    public double powerKw;
    public boolean powerKnown = true;
    public boolean dc = true;
    public List<Socket> sockets = new ArrayList<>();
    public int capacity;
    public String openingHours = "";
    public String price = "";
    public String availability = "unknown";
    public double arrivalSocPct;
    public double departureSocPct;
    public double chargingTimeSec;
    public double stopTimeSec;
    public double averagePowerKw;
    public double arrivalTimeSec;
    public boolean preferredProvider;
    public List<String> matchedAmenities = new ArrayList<>();
    public List<Amenity> amenities = new ArrayList<>();
    @Nullable
    public Longer longer;

    @NonNull
    Stop normalize()
    {
      if (sockets == null)
        sockets = new ArrayList<>();
      if (matchedAmenities == null)
        matchedAmenities = new ArrayList<>();
      if (amenities == null)
        amenities = new ArrayList<>();
      if (title == null || title.isEmpty())
        title = provider == null || provider.isEmpty() ? name : provider;
      return this;
    }

    /** Takes the values of an evaluation with the real route; the station data stay. */
    void takeEvaluation(@NonNull Stop evaluated)
    {
      arrivalSocPct = evaluated.arrivalSocPct;
      departureSocPct = evaluated.departureSocPct;
      chargingTimeSec = evaluated.chargingTimeSec;
      stopTimeSec = evaluated.stopTimeSec;
      averagePowerKw = evaluated.averagePowerKw;
      arrivalTimeSec = evaluated.arrivalTimeSec;
      distanceAlongRouteM = evaluated.distanceAlongRouteM;
      longer = evaluated.longer;
    }
  }

  public static class Plan
  {
    public List<Stop> stops = new ArrayList<>();
    public double distanceM;
    public double driveTimeSec;
    public double stopTimeSec;
    public double totalTimeSec;
    public double arrivalSocPct;
    public double minSocPct;
    public boolean meetsMinimums = true;
    public String priority = "fastest";
    /** For alternatives: "priority" or "other_station". */
    public String reason = "";

    @NonNull
    Plan normalize()
    {
      if (stops == null)
        stops = new ArrayList<>();
      for (Stop s : stops)
        s.normalize();
      return this;
    }

    /**
     * Takes the values of an evaluation with the real route (same stops in the same order, possibly fewer if some
     * are not on the route).
     */
    public void takeEvaluation(@NonNull Plan evaluated)
    {
      if (evaluated.stops.size() == stops.size())
        for (int i = 0; i < stops.size(); ++i)
          stops.get(i).takeEvaluation(evaluated.stops.get(i));
      distanceM = evaluated.distanceM;
      driveTimeSec = evaluated.driveTimeSec;
      stopTimeSec = evaluated.stopTimeSec;
      totalTimeSec = evaluated.totalTimeSec;
      arrivalSocPct = evaluated.arrivalSocPct;
      minSocPct = evaluated.minSocPct;
      meetsMinimums = evaluated.meetsMinimums;
    }

    public boolean hasSameStations(@NonNull Plan other)
    {
      if (stops.size() != other.stops.size())
        return false;
      for (int i = 0; i < stops.size(); ++i)
        if (!stops.get(i).key.equals(other.stops.get(i).key))
          return false;
      return true;
    }
  }

  public static class Provider
  {
    public String key = "";
    public String name = "";
    public int stations;
  }

  public String status = STATUS_ERROR;
  public Plan plan = new Plan();
  public List<Plan> alternatives = new ArrayList<>();
  public double reachableUntilM;
  public double gapEndM;
  public int candidates;
  public int usable;
  public List<Provider> providers = new ArrayList<>();

  public boolean isOk()
  {
    return STATUS_OK.equals(status);
  }

  @NonNull
  EvPlanResult normalize()
  {
    if (plan == null)
      plan = new Plan();
    plan.normalize();
    if (alternatives == null)
      alternatives = new ArrayList<>();
    for (Plan a : alternatives)
      a.normalize();
    if (providers == null)
      providers = new ArrayList<>();
    return this;
  }
}
