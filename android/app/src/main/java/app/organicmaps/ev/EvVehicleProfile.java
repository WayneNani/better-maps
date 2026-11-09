package app.organicmaps.ev;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Vehicle profile of the charging planner, as JSON in the core (libs/ev_planning/vehicle_profile.hpp). Field names are
 * the JSON keys.
 */
public class EvVehicleProfile
{
  public static final String CUSTOM_ID = "custom";

  /** OSM socket types the user can choose, see the mockup: CCS, Type 2, CHAdeMO. */
  public static final String SOCKET_CCS = "type2_combo";
  public static final String SOCKET_TYPE2 = "type2";
  public static final String SOCKET_CHADEMO = "chademo";

  public static class CurvePoint
  {
    public double soc;
    public double kw;

    public CurvePoint() {}

    public CurvePoint(double soc, double kw)
    {
      this.soc = soc;
      this.kw = kw;
    }
  }

  public String id = CUSTOM_ID;
  public String name = "";
  public double usableCapacityKwh;
  public double consumptionCountry;
  public double consumptionMotorway;
  public double auxiliaryPowerKw = 0.5;
  public double massKg = 1800;
  public double drivetrainEfficiency = 0.9;
  public double recuperationEfficiency = 0.6;
  public double consumptionFactor = 1.0;
  public List<String> sockets = new ArrayList<>();
  public double maxDcPowerKw;
  public double maxAcPowerKw;
  public List<CurvePoint> chargeCurve = new ArrayList<>();
  public String source = "";

  @NonNull
  public String toJson()
  {
    return EvJson.GSON.toJson(this);
  }

  @Nullable
  public static EvVehicleProfile fromJson(@Nullable String json)
  {
    if (json == null || json.isEmpty())
      return null;
    try
    {
      final EvVehicleProfile profile = EvJson.GSON.fromJson(json, EvVehicleProfile.class);
      if (profile == null)
        return null;
      if (profile.sockets == null)
        profile.sockets = new ArrayList<>();
      if (profile.chargeCurve == null)
        profile.chargeCurve = new ArrayList<>();
      return profile;
    }
    catch (RuntimeException e)
    {
      return null;
    }
  }

  @NonNull
  public static List<EvVehicleProfile> listFromJson(@Nullable String json)
  {
    final List<EvVehicleProfile> result = new ArrayList<>();
    if (json == null)
      return result;
    try
    {
      final EvVehicleProfile[] profiles = EvJson.GSON.fromJson(json, EvVehicleProfile[].class);
      if (profiles != null)
        for (EvVehicleProfile p : profiles)
          if (p != null)
            result.add(p);
    }
    catch (RuntimeException ignored)
    {
      // An empty list.
    }
    return result;
  }

  @NonNull
  public EvVehicleProfile copy()
  {
    return Objects.requireNonNull(fromJson(toJson()));
  }

  public boolean hasSocket(@NonNull String socket)
  {
    return sockets.contains(socket);
  }

  public void setSocket(@NonNull String socket, boolean has)
  {
    sockets.remove(socket);
    if (has)
      sockets.add(socket);
  }

  public boolean isCustom()
  {
    return CUSTOM_ID.equals(id);
  }

  /** The values the planner needs, like VehicleProfile::IsValid() in the core. */
  public boolean isValid()
  {
    return usableCapacityKwh > 0 && consumptionCountry > 0 && consumptionMotorway > consumptionCountry
 && consumptionFactor > 0 && !sockets.isEmpty() && maxDcPowerKw >= 0 && maxAcPowerKw >= 0
 && (maxDcPowerKw == 0 || chargeCurve.size() >= 2);
  }

  /** Highest power of the charge curve, e.g. for the chart. */
  public double getCurvePeakKw()
  {
    double peak = 0;
    for (CurvePoint p : chargeCurve)
      peak = Math.max(peak, p.kw);
    return Math.min(peak, maxDcPowerKw > 0 ? maxDcPowerKw : peak);
  }
}
