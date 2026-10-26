package app.organicmaps.transit;

import androidx.annotation.NonNull;

/** Localized text fragments for {@link TransitFormatter}; implemented with Android resources in the app. */
public interface TransitTexts
{
  /** E.g. "12 min". */
  @NonNull
  String minutes(long minutes);

  /** E.g. "1 h 5 min"; minutes may be 0 ("2 h"). */
  @NonNull
  String hoursMinutes(long hours, long minutes);

  /** E.g. "Direct", "1 transfer", "2 transfers". */
  @NonNull
  String transfers(int transfers);

  /** E.g. "Depart now". */
  @NonNull
  String departNow();

  /** E.g. "Depart 14:05". */
  @NonNull
  String departAt(@NonNull String time);

  /** E.g. "Arrive by 14:05". */
  @NonNull
  String arriveBy(@NonNull String time);

  /** E.g. "Platform 3". */
  @NonNull
  String track(@NonNull String track);

  /** E.g. "Walk 5 min (350 m)"; distance may be empty. */
  @NonNull
  String walk(@NonNull String duration, @NonNull String distance);

  /** E.g. "350 m" or "1.2 km". */
  @NonNull
  String distance(double meters);

  /** Name of the start point of a query (MOTIS calls it "START"). */
  @NonNull
  String start();

  /** Name of the destination of a query (MOTIS calls it "END"). */
  @NonNull
  String destination();

  /** E.g. "2 stops"; used for collapsed intermediate stops. */
  @NonNull
  String stops(int count);

  /** E.g. "Searching connections…". */
  @NonNull
  String searching();

  /** E.g. "No connections found." */
  @NonNull
  String noConnections();

  /** E.g. "5 connections". */
  @NonNull
  String connections(int count);

  /** E.g. "My position", for a search from or to the own position. */
  @NonNull
  String myPosition();

  /** E.g. "in 25 min" for a departure. */
  @NonNull
  String inTime(@NonNull String duration);

  /** E.g. "now" for a departure within the current minute. */
  @NonNull
  String departingNow();

  /** E.g. "on the way", for a connection that has departed and not arrived yet. */
  @NonNull
  String onTheWay();
}
