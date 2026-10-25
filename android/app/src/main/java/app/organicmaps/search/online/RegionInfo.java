package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** A downloadable map region (CoMaps "country", e.g. "Germany_Free State of Bavaria_Upper Bavaria"). */
public final class RegionInfo
{
  /** Finds the map region at a point; implemented with the CoMaps storage (MapManager) in the app. */
  public interface Lookup
  {
    /** @return null if no map region covers the point (e.g. the open sea). */
    @Nullable
    RegionInfo regionAt(double lat, double lon);
  }

  @NonNull
  private final String mId;
  @NonNull
  private final String mName;
  private final boolean mDownloaded;
  private final long mSizeBytes;

  /**
   * @param downloaded true if the map is on the device (also while an update is pending or downloading an update).
   * @param sizeBytes download size, 0 if unknown.
   */
  public RegionInfo(@NonNull String id, @NonNull String name, boolean downloaded, long sizeBytes)
  {
    mId = id;
    mName = name;
    mDownloaded = downloaded;
    mSizeBytes = sizeBytes;
  }

  /** Country id of the CoMaps storage, usable with MapManager. */
  @NonNull
  public String getId()
  {
    return mId;
  }

  /** Localized name. */
  @NonNull
  public String getName()
  {
    return mName;
  }

  public boolean isDownloaded()
  {
    return mDownloaded;
  }

  public long getSizeBytes()
  {
    return mSizeBytes;
  }

  @NonNull
  @Override
  public String toString()
  {
    return mId + (mDownloaded ? " (downloaded)" : " (not downloaded)");
  }
}
