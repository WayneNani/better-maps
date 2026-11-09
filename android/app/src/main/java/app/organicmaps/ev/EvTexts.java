package app.organicmaps.ev;

import androidx.annotation.NonNull;

/** Localized texts of the charging planner, so that the presentation can be tested without Android. */
public interface EvTexts
{
  /** E.g. "4:21 h driving · 37 min charging · arrival 15:28 with 24 %". */
  @NonNull
  String summary(@NonNull String driving, @NonNull String charging, @NonNull String arrivalTime, int arrivalSoc);

  /** E.g. "412 km · 1 charging stop" (plural). */
  @NonNull
  String distanceAndStops(@NonNull String distance, int stops);

  /** E.g. "412 km · no charging stop needed". */
  @NonNull
  String noStopNeeded(@NonNull String distance);

  /** E.g. "in 278 km". */
  @NonNull
  String inDistance(@NonNull String distance);

  /** E.g. "8 charging points" (plural). */
  @NonNull
  String chargingPoints(int count);

  /** E.g. "up to 300 kW". */
  @NonNull
  String upToPower(int kw);

  /** E.g. "arrive 16 % → 70 %". */
  @NonNull
  String arriveAndCharge(int arrivalSoc, int departureSoc);

  /** E.g. "Charge 16 % → 70 %". */
  @NonNull
  String charge(int arrivalSoc, int departureSoc);

  /** E.g. "Charge longer → 80 % (+9 min), arrival with 34 %". */
  @NonNull
  String chargeLonger(int departureSoc, @NonNull String extraTime, int arrivalSoc);

  /** E.g. "Charge longer → 73 % (+5 min), later stops 3 min shorter, arrival with 23 %". */
  @NonNull
  String chargeLongerLaterShorter(int departureSoc, @NonNull String extraTime, @NonNull String savedTime,
                                  int arrivalSoc);

  /** Name of an OSM socket type, e.g. "CCS" for type2_combo. */
  @NonNull
  String socketName(@NonNull String type);

  /** Name of an amenity type ("playground", "toilets", "food", "shopping") with the kind, e.g. "Restaurant". */
  @NonNull
  String amenityName(@NonNull String type, @NonNull String kind);

  /** E.g. "open on arrival". */
  @NonNull
  String openOnArrival(boolean open);

  /** "Alternative: Ionity Montabaur + EnBW Aurach". */
  @NonNull
  String alternativeTitle(@NonNull String stations);

  /** E.g. "2 shorter stops" / "1 longer stop" / "1 stop" (plural). */
  @NonNull
  String alternativeStops(int stops, int mainStops);

  /** E.g. "arrival 4 min later" / "4 min earlier" / "same arrival". */
  @NonNull
  String alternativeArrival(int deltaMinutes);

  /** E.g. "with 41 % on arrival". */
  @NonNull
  String alternativeSoc(int arrivalSoc);

  /** The destination can't be reached: no suitable station between two distances along the route. */
  @NonNull
  String unreachable(@NonNull String from, @NonNull String to);

  @NonNull
  String noRoute();

  @NonNull
  String error();

  /** "Charging station · EnBW · 24/7". */
  @NonNull
  String stationHeader(@NonNull String provider, @NonNull String openingHours);

  /** "Occupancy: no real-time data yet" or the status. */
  @NonNull
  String availability(@NonNull String availability);

  /** A distance, e.g. "278 km" or "90 m". */
  @NonNull
  String distance(double meters);
}
