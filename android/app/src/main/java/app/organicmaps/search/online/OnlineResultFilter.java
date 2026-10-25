package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hides online results that the offline search already shows, and repeated online results.
 * <p>
 * An online result is a duplicate of an offline result if both have the same OSM reference, or if their names are
 * equal (ignoring case, accents and punctuation) and they are close to each other. "Close" depends on the kind of
 * place: a few hundred metres for houses and POIs, more for streets, districts and cities whose reference points
 * differ between OSM-based databases.
 */
public final class OnlineResultFilter
{
  private OnlineResultFilter() {}

  /** Online results in their original order without duplicates. */
  @NonNull
  public static List<OnlineSearchResult> filter(@NonNull List<OnlineSearchResult> online,
                                                @NonNull List<OfflinePlace> offline)
  {
    final List<OnlineSearchResult> result = new ArrayList<>(online.size());
    final Set<String> seenRefs = new HashSet<>();
    final Set<String> offlineRefs = new HashSet<>();
    for (OfflinePlace place : offline)
      if (place.getOsmRef() != null)
        offlineRefs.add(place.getOsmRef());

    for (OnlineSearchResult candidate : online)
    {
      final String ref = candidate.getOsmRef();
      if (ref != null && (!seenRefs.add(ref) || offlineRefs.contains(ref)))
        continue;
      if (!isNearSameName(candidate, offline))
        result.add(candidate);
    }
    return result;
  }

  /** True if an offline place with the same name lies within {@link #matchRadiusMeters} of the online result. */
  static boolean isNearSameName(@NonNull OnlineSearchResult candidate, @NonNull List<OfflinePlace> offline)
  {
    final String name = normalizeName(candidate.getTitle());
    if (name.isEmpty())
      return false;
    final double radius = matchRadiusMeters(candidate.getPlaceType());
    for (OfflinePlace place : offline)
    {
      if (!name.equals(normalizeName(place.getName())))
        continue;
      if (distanceMeters(candidate.getLat(), candidate.getLon(), place.getLat(), place.getLon()) <= radius)
        return true;
      final OnlineSearchResult.BoundingBox extent = candidate.getExtent();
      if (extent != null && extent.contains(place.getLat(), place.getLon()))
        return true;
    }
    return false;
  }

  /** Maximum distance between the reference points of the same place in the offline and the online data. */
  static double matchRadiusMeters(@NonNull OnlineSearchResult.PlaceType type)
  {
    return switch (type)
    {
      case HOUSE, OTHER -> 250;
      case STREET -> 1_000;
      case LOCALITY, DISTRICT -> 3_000;
      case CITY -> 10_000;
      case COUNTY, STATE -> 50_000;
      case COUNTRY -> 500_000;
    };
  }

  /**
   * Lower case without accents, "ß" as "ss", punctuation as single spaces: "Café Müller-Straße" → "cafe muller
   * strasse".
   */
  @NonNull
  static String normalizeName(@Nullable String name)
  {
    if (name == null)
      return "";
    final String decomposed =
        Normalizer.normalize(name.toLowerCase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFD);
    final StringBuilder sb = new StringBuilder(decomposed.length());
    boolean space = false;
    for (int i = 0; i < decomposed.length(); ++i)
    {
      final char c = decomposed.charAt(i);
      if (Character.getType(c) == Character.NON_SPACING_MARK)
        continue;
      if (Character.isLetterOrDigit(c))
      {
        if (space && sb.length() > 0)
          sb.append(' ');
        sb.append(c);
        space = false;
      }
      else
        space = true;
    }
    return sb.toString();
  }

  /** Great-circle distance in metres. */
  static double distanceMeters(double lat1, double lon1, double lat2, double lon2)
  {
    final double earthRadius = 6_371_000;
    final double dLat = Math.toRadians(lat2 - lat1);
    final double dLon = Math.toRadians(lon2 - lon1);
    final double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(a)));
  }
}
