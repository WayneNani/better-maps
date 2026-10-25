package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.downloader.CountryItem;
import app.organicmaps.sdk.downloader.MapManager;
import app.organicmaps.sdk.util.log.Logger;

/**
 * {@link RegionInfo.Lookup} with the CoMaps storage: the region at a point comes from the country borders
 * (CountryInfoGetter via {@link MapManager#nativeFindCountry}), its state from the downloader.
 */
public final class MapManagerRegionLookup implements RegionInfo.Lookup
{
  private static final String TAG = MapManagerRegionLookup.class.getSimpleName();

  @Nullable
  @Override
  public RegionInfo regionAt(double lat, double lon)
  {
    try
    {
      final String id = MapManager.nativeFindCountry(lat, lon);
      if (id == null || id.isEmpty())
        return null;
      final CountryItem item = CountryItem.fill(id);
      return new RegionInfo(id, item.name != null ? item.name : id, isDownloaded(item), item.totalSize);
    }
    catch (RuntimeException | UnsatisfiedLinkError e)
    {
      // The native library is not ready (e.g. very early after the start): treat as unknown.
      Logger.w(TAG, "Cannot look up the region at " + lat + ", " + lon, e);
      return null;
    }
  }

  static boolean isDownloaded(@NonNull CountryItem item)
  {
    return item.present || item.status == CountryItem.STATUS_DONE || item.status == CountryItem.STATUS_UPDATABLE;
  }
}
