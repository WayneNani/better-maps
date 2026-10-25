package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Means of transport of a leg or departure. Names match the MOTIS API "Mode" values. */
public enum TransitMode
{
  // Street modes.
  WALK(false),
  BIKE(false),
  RENTAL(false),
  CAR(false),
  CAR_PARKING(false),
  CAR_DROPOFF(false),
  ODM(false),
  RIDE_SHARING(false),
  FLEX(false),

  // Transit modes. TRANSIT is the umbrella value for all of them in requests.
  TRANSIT(true),
  TRAM(true),
  SUBWAY(true),
  FERRY(true),
  AIRPLANE(true),
  BUS(true),
  COACH(true),
  RAIL(true),
  HIGHSPEED_RAIL(true),
  LONG_DISTANCE(true),
  NIGHT_RAIL(true),
  REGIONAL_FAST_RAIL(true),
  REGIONAL_RAIL(true),
  SUBURBAN(true),
  FUNICULAR(true),
  AERIAL_LIFT(true),
  CABLE_CAR(true),
  OTHER(true),

  /** A value this client does not know yet (e.g. from a newer server). */
  UNKNOWN(false);

  private final boolean mTransit;

  TransitMode(boolean transit)
  {
    mTransit = transit;
  }

  /** @return true for public transport (bus, tram, train, ...), false for walking, cycling, cars etc. */
  public boolean isTransit()
  {
    return mTransit;
  }

  /** Maps a MOTIS mode name, including the deprecated aliases METRO and AREAL_LIFT. */
  @NonNull
  public static TransitMode fromApi(@Nullable String value)
  {
    if (value == null)
      return UNKNOWN;
    switch (value)
    {
    case "METRO": return SUBURBAN;
    case "AREAL_LIFT": return AERIAL_LIFT;
    }
    try
    {
      return valueOf(value);
    }
    catch (IllegalArgumentException e)
    {
      return UNKNOWN;
    }
  }
}
