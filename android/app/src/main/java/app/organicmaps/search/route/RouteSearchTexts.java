package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.Distance;

/** Texts of the search along the route, so that their composition can be tested without Android. */
public interface RouteSearchTexts
{
  /** "12 km". */
  @NonNull
  String distance(@NonNull Distance distance);

  /** "in 12 km": where one leaves the route for the place. */
  @NonNull
  String inDistance(@NonNull String distance);

  /** "Detour ~2 min". */
  @NonNull
  String detourMinutes(int minutes);

  /** The place is next to the route, no detour worth mentioning. */
  @NonNull
  String onTheRoute();

  /** "Open on arrival" or "Closed on arrival". */
  @NonNull
  String openOnArrival(boolean open);

  /** A corridor width: "1 km", "300 m". */
  @NonNull
  String width(int meters);
}
