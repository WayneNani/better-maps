package app.organicmaps.ev;

import androidx.annotation.NonNull;

/** JSON as the core writes it (libs/ev_planning/plan_json.cpp, vehicle_profile.cpp). */
final class EvTestData
{
  private EvTestData() {}

  static final String ELROQ_JSON =
      "{\"id\":\"skoda_elroq_85\",\"name\":\"Škoda Elroq 85\",\"usableCapacityKwh\":77,\"consumptionCountry\":12.9,"
      + "\"consumptionMotorway\":23.5,\"auxiliaryPowerKw\":0.5,\"massKg\":2215,\"drivetrainEfficiency\":0.9,"
      + "\"recuperationEfficiency\":0.6,\"consumptionFactor\":1,\"sockets\":[\"type2_combo\",\"type2\"],"
      + "\"maxDcPowerKw\":135,\"maxAcPowerKw\":11,\"chargeCurve\":[{\"soc\":0,\"kw\":113},{\"soc\":10,\"kw\":135},"
      + "{\"soc\":50,\"kw\":129},{\"soc\":80,\"kw\":73},{\"soc\":100,\"kw\":12}],\"source\":\"Škoda press kit\"}";

  @NonNull
  static String stop(String key, String title, double km, double arrivalTimeSec, double stopTimeSec, double arrival,
                     double departure)
  {
    return "{\"key\":\"" + key + "\",\"name\":\"\",\"title\":\"" + title + "\",\"provider\":\"EnBW\","
  + "\"operatorName\":\"EnBW mobility+\",\"brand\":\"\",\"network\":\"\",\"lat\":49.9,\"lon\":10.1,"
  + "\"distanceAlongRouteM\":" + km * 1000 + ",\"distanceFromRouteM\":120,\"detourSec\":60,\"powerKw\":300,"
  + "\"powerKnown\":true,\"dc\":true,\"sockets\":[{\"type\":\"type2_combo\",\"count\":4,\"powerKw\":300},"
  + "{\"type\":\"type2_combo\",\"count\":4,\"powerKw\":150},{\"type\":\"type2\",\"count\":2,\"powerKw\":22}],"
  + "\"capacity\":8,\"openingHours\":\"24/7\",\"price\":\"\",\"availability\":\"unknown\","
  + "\"arrivalSocPct\":" + arrival + ",\"departureSocPct\":" + departure + ",\"chargingTimeSec\":"
  + (stopTimeSec - 180) + ",\"stopTimeSec\":" + stopTimeSec + ",\"averagePowerKw\":120,\"arrivalTimeSec\":"
  + arrivalTimeSec + ",\"preferredProvider\":false,\"matchedAmenities\":[\"playground\"],"
  + "\"amenities\":[{\"type\":\"toilets\",\"kind\":\"services\",\"name\":\"Rasthof\",\"distanceM\":60,"
  + "\"lat\":49.9,\"lon\":10.1},{\"type\":\"playground\",\"kind\":\"playground\",\"name\":\"\","
  + "\"distanceM\":90,\"lat\":49.9,\"lon\":10.1},{\"type\":\"food\",\"kind\":\"restaurant\",\"name\":\"Rasthof\","
  + "\"distanceM\":120,\"openAtArrival\":true,\"lat\":49.9,\"lon\":10.1},{\"type\":\"shopping\","
  + "\"kind\":\"supermarket\",\"name\":\"\",\"distanceM\":450,\"lat\":49.9,\"lon\":10.1}],"
  + "\"longer\":{\"departureSocPct\":" + (departure + 10) + ",\"extraStopTimeSec\":540,"
  + "\"extraTotalTimeSec\":540,\"arrivalSocPct\":34}}";
  }

  /** Bonn - Nuremberg like the mockup: one stop at EnBW Aurach, an alternative with two stops. */
  static final String RESULT_JSON =
      "{\"status\":\"ok\",\"plan\":{\"stops\":[" + stop("Germany:1", "EnBW Aurach", 278, 10080, 1620, 16, 70)
      + "],\"distanceM\":412000,\"driveTimeSec\":15660,\"stopTimeSec\":1620,\"totalTimeSec\":17280,"
      + "\"arrivalSocPct\":24,\"minSocPct\":16,\"meetsMinimums\":true,\"priority\":\"fastest\",\"reason\":\"\"},"
      + "\"alternatives\":[{\"stops\":[" + stop("Germany:2", "Ionity Montabaur", 90, 3600, 900, 55, 75) + ","
      + stop("Germany:1", "EnBW Aurach", 278, 11400, 840, 30, 55)
      + "],\"distanceM\":412000,\"driveTimeSec\":15780,\"stopTimeSec\":1740,\"totalTimeSec\":17520,"
      + "\"arrivalSocPct\":41,\"minSocPct\":30,\"meetsMinimums\":true,\"priority\":\"fewest_stops\","
      + "\"reason\":\"priority\"}],\"reachableUntilM\":0,\"gapEndM\":0,\"candidates\":147,\"usable\":120,"
      + "\"providers\":[{\"key\":\"enbw\",\"name\":\"EnBW\",\"stations\":20},{\"key\":\"ionity\",\"name\":\"Ionity\","
      + "\"stations\":13},{\"key\":\"tesla\",\"name\":\"Tesla\",\"stations\":15}]}";

  static final String UNREACHABLE_JSON =
      "{\"status\":\"unreachable\",\"plan\":{\"stops\":[]},\"alternatives\":[],\"reachableUntilM\":120000,"
      + "\"gapEndM\":380000,\"candidates\":3,\"usable\":1,\"providers\":[{\"key\":\"enbw\",\"name\":\"EnBW\","
      + "\"stations\":3}]}";

  /** The evaluation with the real route: arrives with 18 % instead of 16 %. */
  static final String EVALUATED_JSON =
      "{\"stops\":[" + stop("Germany:1", "EnBW Aurach", 280, 10200, 1500, 18, 70) + "],\"distanceM\":415000,"
      + "\"driveTimeSec\":15800,\"stopTimeSec\":1500,\"totalTimeSec\":17300,\"arrivalSocPct\":23,\"minSocPct\":18,"
      + "\"meetsMinimums\":true,\"priority\":\"fastest\",\"reason\":\"\"}";
}
