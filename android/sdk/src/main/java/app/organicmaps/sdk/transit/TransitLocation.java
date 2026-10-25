package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

/** Start or destination of a public transport query: either coordinates or a MOTIS stop id. */
public final class TransitLocation
{
  private final double mLat;
  private final double mLon;
  @Nullable
  private final String mStopId;

  private TransitLocation(double lat, double lon, @Nullable String stopId)
  {
    mLat = lat;
    mLon = lon;
    mStopId = stopId;
  }

  @NonNull
  public static TransitLocation fromCoordinates(double lat, double lon)
  {
    if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180)
      throw new IllegalArgumentException("Invalid coordinates: " + lat + ", " + lon);
    return new TransitLocation(lat, lon, null);
  }

  /** @param stopId MOTIS stop id, e.g. from {@link TransitStop#getId()} or {@link TransitPlace#getStopId()}. */
  @NonNull
  public static TransitLocation fromStopId(@NonNull String stopId)
  {
    if (stopId.trim().isEmpty())
      throw new IllegalArgumentException("Empty stop id");
    return new TransitLocation(Double.NaN, Double.NaN, stopId);
  }

  public boolean isStop()
  {
    return mStopId != null;
  }

  @Nullable
  public String getStopId()
  {
    return mStopId;
  }

  public double getLat()
  {
    return mLat;
  }

  public double getLon()
  {
    return mLon;
  }

  /** @return the value for the MOTIS fromPlace/toPlace parameters: "lat,lon" or the stop id. */
  @NonNull
  public String toApiString()
  {
    if (mStopId != null)
      return mStopId;
    return String.format(Locale.ROOT, "%.6f,%.6f", mLat, mLon);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o)
      return true;
    if (!(o instanceof TransitLocation))
      return false;
    final TransitLocation that = (TransitLocation) o;
    return Double.compare(mLat, that.mLat) == 0 && Double.compare(mLon, that.mLon) == 0
 && Objects.equals(mStopId, that.mStopId);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(mLat, mLon, mStopId);
  }

  @NonNull
  @Override
  public String toString()
  {
    return toApiString();
  }
}
