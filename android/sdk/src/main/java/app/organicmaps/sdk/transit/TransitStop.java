package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.Set;

/** A stop found by (reverse) geocoding. */
public final class TransitStop
{
  @NonNull
  String mId = "";
  @NonNull
  String mName = "";
  double mLat;
  double mLon;
  @NonNull
  Set<TransitMode> mModes = Collections.emptySet();

  TransitStop() {}

  /** MOTIS stop id, usable with {@link TransitLocation#fromStopId(String)} and departures. */
  @NonNull
  public String getId()
  {
    return mId;
  }

  @NonNull
  public String getName()
  {
    return mName;
  }

  public double getLat()
  {
    return mLat;
  }

  public double getLon()
  {
    return mLon;
  }

  /** Modes served at this stop, if known. */
  @NonNull
  public Set<TransitMode> getModes()
  {
    return mModes;
  }

  @NonNull
  @Override
  public String toString()
  {
    return mName + " [" + mId + "]";
  }
}
