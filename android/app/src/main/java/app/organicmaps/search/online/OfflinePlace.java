package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An offline search result, reduced to what is needed to recognize the same place among the online results.
 * <p>
 * The MWM files of CoMaps don't contain OSM ids, so offline results usually have none and duplicates are found by
 * name and distance; an OSM reference is used when available.
 */
public final class OfflinePlace
{
  @NonNull
  private final String mName;
  private final double mLat;
  private final double mLon;
  @Nullable
  private final String mOsmRef;

  public OfflinePlace(@NonNull String name, double lat, double lon)
  {
    this(name, lat, lon, null);
  }

  /** @param osmRef OSM reference like "W259055080" (type letter and id), or null. */
  public OfflinePlace(@NonNull String name, double lat, double lon, @Nullable String osmRef)
  {
    mName = name;
    mLat = lat;
    mLon = lon;
    mOsmRef = osmRef;
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

  @Nullable
  public String getOsmRef()
  {
    return mOsmRef;
  }

  @NonNull
  @Override
  public String toString()
  {
    return mName + " " + mLat + "," + mLon;
  }
}
