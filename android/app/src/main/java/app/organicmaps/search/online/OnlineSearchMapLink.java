package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Builds a CoMaps map API link ("cm://map?ll=...&z=...&n=...") for an online result. The app shows such a link as a
 * marked point with a place page, also in regions without a downloaded map (where the map offers the download).
 */
public final class OnlineSearchMapLink
{
  private OnlineSearchMapLink() {}

  @NonNull
  public static String toApiUrl(@NonNull OnlineSearchResult result)
  {
    // "ll" must come before "n" (the name belongs to the last point).
    return String.format(Locale.ROOT, "cm://map?ll=%.7f,%.7f&z=%d&n=%s", result.getLat(), result.getLon(),
                         zoomFor(result.getPlaceType()), encode(result.getTitle()));
  }

  /** Map zoom level that shows the whole place: a house closer than a city. */
  public static int zoomFor(@NonNull OnlineSearchResult.PlaceType type)
  {
    return switch (type)
    {
      case HOUSE, OTHER -> 17;
      case STREET -> 16;
      case LOCALITY, DISTRICT -> 14;
      case CITY -> 12;
      case COUNTY -> 10;
      case STATE -> 8;
      case COUNTRY -> 5;
    };
  }

  @NonNull
  static String encode(@NonNull String s)
  {
    try
    {
      // URLEncoder writes spaces as "+", which the map API would keep literally.
      return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }
    catch (UnsupportedEncodingException e)
    {
      throw new IllegalStateException(e);
    }
  }
}
