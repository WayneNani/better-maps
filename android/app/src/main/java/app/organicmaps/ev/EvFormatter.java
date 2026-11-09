package app.organicmaps.ev;

import androidx.annotation.NonNull;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Texts of the charging plan: times, states of charge, stops, alternatives. Without Android, see {@link EvTexts}. */
public class EvFormatter
{
  @NonNull
  private final EvTexts mTexts;
  @NonNull
  private final ZoneId mZone;

  public EvFormatter(@NonNull EvTexts texts, @NonNull ZoneId zone)
  {
    mTexts = texts;
    mZone = zone;
  }

  @NonNull
  public EvTexts getTexts()
  {
    return mTexts;
  }

  /** "4:58 h" from one hour on, otherwise "27 min". */
  @NonNull
  public static String duration(double seconds)
  {
    final long minutes = Math.max(0, Math.round(seconds / 60.0));
    if (minutes < 60)
      return minutes + " min";
    return String.format(Locale.ROOT, "%d:%02d h", minutes / 60, minutes % 60);
  }

  /** "90 m", "1.2 km", "278 km" (metric: the planner is for Germany first). */
  @NonNull
  public static String distance(double meters, @NonNull Locale locale)
  {
    if (meters < 1000)
      return Math.round(meters / 10.0) * 10 + " m";
    if (meters < 10000)
      return String.format(locale, "%.1f km", meters / 1000.0);
    return Math.round(meters / 1000.0) + " km";
  }

  public static int soc(double percent)
  {
    return (int) Math.round(Math.max(0, Math.min(100, percent)));
  }

  /** "15:28" for a departure at |nowEpochSec| plus |seconds|. */
  @NonNull
  public String clockTime(long nowEpochSec, double seconds)
  {
    return DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        .format(Instant.ofEpochSecond(nowEpochSec + Math.round(seconds)).atZone(mZone));
  }

  /** The total time incl. charging, e.g. "4:58 h". */
  @NonNull
  public String total(@NonNull EvPlanResult.Plan plan)
  {
    return duration(plan.totalTimeSec);
  }

  /** "412 km · 1 charging stop". */
  @NonNull
  public String distanceAndStops(@NonNull EvPlanResult.Plan plan)
  {
    final String distance = mTexts.distance(plan.distanceM);
    return plan.stops.isEmpty() ? mTexts.noStopNeeded(distance) : mTexts.distanceAndStops(distance, plan.stops.size());
  }

  /** "4:21 h driving · 37 min charging · arrival 15:28 with 24 %". */
  @NonNull
  public String summary(@NonNull EvPlanResult.Plan plan, long nowEpochSec)
  {
    return mTexts.summary(duration(plan.driveTimeSec), duration(plan.stopTimeSec),
                          clockTime(nowEpochSec, plan.totalTimeSec), soc(plan.arrivalSocPct));
  }

  /** "CCS up to 300 kW · 8 charging points · EnBW". */
  @NonNull
  public String stopDetails(@NonNull EvPlanResult.Stop stop)
  {
    final List<String> parts = new ArrayList<>();
    parts.add(mTexts.socketName(stop.dc ? EvVehicleProfile.SOCKET_CCS : EvVehicleProfile.SOCKET_TYPE2) + " "
              + mTexts.upToPower((int) Math.round(stop.powerKw)));
    if (stop.capacity > 0)
      parts.add(mTexts.chargingPoints(stop.capacity));
    if (!stop.provider.isEmpty() && !stop.provider.equals(stop.title))
      parts.add(stop.provider);
    return String.join(" · ", parts);
  }

  /** "arrive 16 % → 70 %". */
  @NonNull
  public String arriveAndCharge(@NonNull EvPlanResult.Stop stop)
  {
    return mTexts.arriveAndCharge(soc(stop.arrivalSocPct), soc(stop.departureSocPct));
  }

  /** "Charge 16 % → 70 %". */
  @NonNull
  public String charge(@NonNull EvPlanResult.Stop stop)
  {
    return mTexts.charge(soc(stop.arrivalSocPct), soc(stop.departureSocPct));
  }

  /** Duration of the stop with the overhead, "27 min". */
  @NonNull
  public String stopDuration(@NonNull EvPlanResult.Stop stop)
  {
    return duration(stop.stopTimeSec);
  }

  /**
   * "Charge longer → 80 % (+9 min), arrival with 34 %", empty if not possible. With following stops, the extra charge
   * makes them shorter: "Charge longer → 73 % (+5 min), later stops 3 min shorter, arrival with 23 %".
   */
  @NonNull
  public String longer(@NonNull EvPlanResult.Stop stop)
  {
    if (stop.longer == null)
      return "";
    final String extra = "+" + duration(stop.longer.extraStopTimeSec);
    if (Math.round(stop.longer.laterStopsSavedSec / 60.0) > 0)
      return mTexts.chargeLongerLaterShorter(soc(stop.longer.departureSocPct), extra,
                                             duration(stop.longer.laterStopsSavedSec), soc(stop.longer.arrivalSocPct));
    return mTexts.chargeLonger(soc(stop.longer.departureSocPct), extra, soc(stop.longer.arrivalSocPct));
  }

  /** "Playground 90 m". */
  @NonNull
  public String amenity(@NonNull EvPlanResult.Amenity amenity)
  {
    return mTexts.amenityName(amenity.type, amenity.kind) + " " + mTexts.distance(amenity.distanceM);
  }

  /** "Restaurant · open on arrival" (without the distance). */
  @NonNull
  public String amenityWithOpening(@NonNull EvPlanResult.Amenity amenity)
  {
    final String name = mTexts.amenityName(amenity.type, amenity.kind);
    if (amenity.openAtArrival == null)
      return name;
    return name + " · " + mTexts.openOnArrival(amenity.openAtArrival);
  }

  /** The nearest amenity of each type, for the short list of a stop. */
  @NonNull
  public static List<EvPlanResult.Amenity> nearestByType(@NonNull EvPlanResult.Stop stop)
  {
    final List<EvPlanResult.Amenity> result = new ArrayList<>();
    for (String type : new String[] {EvPlanResult.Amenity.PLAYGROUND, EvPlanResult.Amenity.TOILETS,
                                     EvPlanResult.Amenity.FOOD, EvPlanResult.Amenity.SHOPPING})
    {
      EvPlanResult.Amenity nearest = null;
      for (EvPlanResult.Amenity a : stop.amenities)
        if (type.equals(a.type) && (nearest == null || a.distanceM < nearest.distanceM))
          nearest = a;
      if (nearest != null)
        result.add(nearest);
    }
    return result;
  }

  /** "Alternative: Ionity Montabaur + EnBW Aurach". */
  @NonNull
  public String alternativeTitle(@NonNull EvPlanResult.Plan alternative)
  {
    final List<String> names = new ArrayList<>();
    for (EvPlanResult.Stop stop : alternative.stops)
      names.add(stop.title);
    return mTexts.alternativeTitle(String.join(" + ", names));
  }

  /**
   * "2 shorter stops, arrival 4 min later, with 41 % on arrival".
   * <p>
   * The main plan is evaluated with the real route, the alternatives are not (only the one that was the main plan
   * before). So both are compared on the same basis: the numbers of the planner, unless both were evaluated. The
   * arrival with the alternative gets the correction of the main plan by its evaluation. An alternative is called
   * earlier only if its numbers include the real detours to its stations.
   */
  @NonNull
  public String alternativeDescription(@NonNull EvPlanResult.Plan alternative, @NonNull EvPlanResult.Plan main)
  {
    final boolean bothEvaluated = alternative.evaluated && main.evaluated;
    final double alternativeTime = bothEvaluated ? alternative.totalTimeSec : alternative.plannedTotalTimeSec;
    final double mainTime = bothEvaluated ? main.totalTimeSec : main.plannedTotalTimeSec;
    int deltaMinutes = (int) Math.round((alternativeTime - mainTime) / 60.0);
    if (deltaMinutes < 0 && !bothEvaluated && !alternative.hasCheckedDetours())
      deltaMinutes = 0;
    return mTexts.alternativeStops(alternative.stops.size(), main.stops.size()) + ", "
  + mTexts.alternativeArrival(deltaMinutes) + ", "
  + mTexts.alternativeSoc(soc(alternativeArrivalSoc(alternative, main)));
  }

  /** The arrival with |alternative|, with the correction of |main| by its evaluation if it is not evaluated itself. */
  static double alternativeArrivalSoc(@NonNull EvPlanResult.Plan alternative, @NonNull EvPlanResult.Plan main)
  {
    if (alternative.evaluated || !main.evaluated)
      return alternative.arrivalSocPct;
    return alternative.plannedArrivalSocPct + main.arrivalSocPct - main.plannedArrivalSocPct;
  }

  /** The error of a result that is not OK. */
  @NonNull
  public String error(@NonNull EvPlanResult result)
  {
    if (EvPlanResult.STATUS_UNREACHABLE.equals(result.status))
      return mTexts.unreachable(mTexts.distance(result.reachableUntilM), mTexts.distance(result.gapEndM));
    if (EvPlanResult.STATUS_NO_ROUTE.equals(result.status))
      return mTexts.noRoute();
    return mTexts.error();
  }

  /** "Charging station · EnBW · 24/7". */
  @NonNull
  public String stationHeader(@NonNull EvPlanResult.Stop stop)
  {
    return mTexts.stationHeader(stop.provider, stop.openingHours);
  }

  /** Rows of the sockets: "CCS" and "4 × 300 kW". Sockets of other vehicles too. */
  @NonNull
  public List<String[]> socketRows(@NonNull EvPlanResult.Stop stop)
  {
    final List<String[]> rows = new ArrayList<>();
    for (EvPlanResult.Socket socket : stop.sockets)
    {
      final String power = socket.powerKw > 0 ? Math.round(socket.powerKw) + " kW" : "? kW";
      rows.add(new String[] {mTexts.socketName(socket.type), socket.count > 0 ? socket.count + " × " + power : power});
    }
    return rows;
  }
}
