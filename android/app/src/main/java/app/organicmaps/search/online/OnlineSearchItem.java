package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.online.OnlineSearchResult;

/** An online result for the list, with the map region at its position (for the download offer). */
public final class OnlineSearchItem
{
  @NonNull
  private final OnlineSearchResult mResult;
  @Nullable
  private final RegionInfo mRegion;

  public OnlineSearchItem(@NonNull OnlineSearchResult result, @Nullable RegionInfo region)
  {
    mResult = result;
    mRegion = region;
  }

  @NonNull
  public OnlineSearchResult getResult()
  {
    return mResult;
  }

  /** Map region at the result, null if none or unknown. */
  @Nullable
  public RegionInfo getRegion()
  {
    return mRegion;
  }

  /** True if the map of the result's region can be downloaded (it is not on the device yet). */
  public boolean canDownloadRegion()
  {
    return mRegion != null && !mRegion.isDownloaded();
  }

  @NonNull
  @Override
  public String toString()
  {
    return mResult.getTitle() + (mRegion != null ? " in " + mRegion : "");
  }
}
