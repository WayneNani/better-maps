package app.organicmaps.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Hiding online results that duplicate offline results or each other. */
public class OnlineResultFilterTest
{
  @NonNull
  private static OnlineSearchResult result(@NonNull String name, double lat, double lon, @NonNull String osmType,
                                           long osmId, @NonNull OnlineSearchResult.PlaceType type)
  {
    return OnlineSearchResult.builder(lat, lon).setName(name).setOsm(osmType, osmId).setPlaceType(type).build();
  }

  // Recorded Photon results for "Marienplatz München" (see sdk fixtures).
  private static final OnlineSearchResult MARIENPLATZ_STREET =
      result("Marienplatz", 48.137569, 11.57498, "W", 259055080, OnlineSearchResult.PlaceType.STREET);
  private static final OnlineSearchResult MARIENPLATZ_SQUARE =
      result("Marienplatz", 48.1370314, 11.5759258, "R", 7366154, OnlineSearchResult.PlaceType.STREET);
  private static final OnlineSearchResult CAFE =
      result("Café Glockenspiel", 48.13735, 11.57610, "N", 1, OnlineSearchResult.PlaceType.HOUSE);

  @NonNull
  private static List<String> refs(@NonNull List<OnlineSearchResult> results)
  {
    final List<String> refs = new ArrayList<>();
    for (OnlineSearchResult r : results)
      refs.add(r.getOsmRef());
    return refs;
  }

  @Test
  public void nothingOfflineKeepsAll()
  {
    final List<OnlineSearchResult> online = Arrays.asList(MARIENPLATZ_STREET, MARIENPLATZ_SQUARE, CAFE);
    assertEquals(online, OnlineResultFilter.filter(online, Collections.emptyList()));
  }

  @Test
  public void sameOsmObjectTwiceOnlineIsShownOnce()
  {
    final List<OnlineSearchResult> filtered =
        OnlineResultFilter.filter(Arrays.asList(MARIENPLATZ_STREET, CAFE, MARIENPLATZ_STREET), Collections.emptyList());
    assertEquals(Arrays.asList("W259055080", "N1"), refs(filtered));
  }

  @Test
  public void sameOsmReferenceOfflineIsHidden()
  {
    final List<OfflinePlace> offline =
        Collections.singletonList(new OfflinePlace("Other name", 0, 0, "R7366154", OfflinePlace.Match.FULL));
    assertEquals(Arrays.asList("W259055080", "N1"),
                 refs(OnlineResultFilter.filter(Arrays.asList(MARIENPLATZ_STREET, MARIENPLATZ_SQUARE, CAFE), offline)));
  }

  @Test
  public void sameNameNearbyIsHidden()
  {
    // The offline map has the Marienplatz a few metres from Photon's point.
    final List<OfflinePlace> offline = Collections.singletonList(new OfflinePlace("Marienplatz", 48.13710, 11.57560));
    assertEquals(Collections.singletonList("N1"),
                 refs(OnlineResultFilter.filter(Arrays.asList(MARIENPLATZ_STREET, MARIENPLATZ_SQUARE, CAFE), offline)));
  }

  @Test
  public void sameNameFarAwayIsKept()
  {
    // Marienplatz in Stuttgart is another place.
    final List<OfflinePlace> offline = Collections.singletonList(new OfflinePlace("Marienplatz", 48.7647, 9.1680));
    assertEquals(2, OnlineResultFilter.filter(Arrays.asList(MARIENPLATZ_STREET, MARIENPLATZ_SQUARE), offline).size());
  }

  @Test
  public void otherNameNearbyIsKept()
  {
    final List<OfflinePlace> offline = Collections.singletonList(new OfflinePlace("Rathaus", 48.13735, 11.57610));
    assertEquals(1, OnlineResultFilter.filter(Collections.singletonList(CAFE), offline).size());
  }

  @Test
  public void namesAreComparedWithoutCaseAccentsAndPunctuation()
  {
    final OnlineSearchResult cafe =
        result("Café Müller-Straße", 52.5, 13.4, "N", 5, OnlineSearchResult.PlaceType.HOUSE);
    final List<OfflinePlace> offline =
        Collections.singletonList(new OfflinePlace("cafe  MULLER strasse", 52.5005, 13.4));
    assertTrue(OnlineResultFilter.filter(Collections.singletonList(cafe), offline).isEmpty());
    assertEquals("cafe muller strasse", OnlineResultFilter.normalizeName("Café Müller-Straße"));
    assertEquals("", OnlineResultFilter.normalizeName(" -- "));
    assertEquals("", OnlineResultFilter.normalizeName(null));
  }

  @Test
  public void radiusDependsOnTheKindOfPlace()
  {
    // 2 km apart: a different café, but the same city.
    final OnlineSearchResult house = result("Mitte", 52.52, 13.40, "N", 1, OnlineSearchResult.PlaceType.HOUSE);
    final OnlineSearchResult city = result("Mitte", 52.52, 13.40, "R", 2, OnlineSearchResult.PlaceType.CITY);
    final List<OfflinePlace> offline = Collections.singletonList(new OfflinePlace("Mitte", 52.538, 13.40));
    assertEquals(Collections.singletonList("N1"), refs(OnlineResultFilter.filter(Arrays.asList(house, city), offline)));
    assertTrue(OnlineResultFilter.matchRadiusMeters(OnlineSearchResult.PlaceType.HOUSE)
               < OnlineResultFilter.matchRadiusMeters(OnlineSearchResult.PlaceType.STREET));
    assertTrue(OnlineResultFilter.matchRadiusMeters(OnlineSearchResult.PlaceType.CITY)
               < OnlineResultFilter.matchRadiusMeters(OnlineSearchResult.PlaceType.COUNTRY));
  }

  @Test
  public void offlinePlaceInsideTheExtentIsTheSamePlace()
  {
    // A long street: the offline point lies far from Photon's point, but inside the street's bounding box.
    final OnlineSearchResult street = OnlineSearchResult.builder(52.50, 13.30)
                                          .setName("Kurfürstendamm")
                                          .setOsm("W", 9)
                                          .setPlaceType(OnlineSearchResult.PlaceType.STREET)
                                          .setExtent(new OnlineSearchResult.BoundingBox(52.49, 13.28, 52.51, 13.34))
                                          .build();
    final List<OfflinePlace> offline = Collections.singletonList(new OfflinePlace("Kurfürstendamm", 52.503, 13.335));
    assertTrue(OnlineResultFilter.filter(Collections.singletonList(street), offline).isEmpty());
  }

  @Test
  public void addressesWithoutNameUseTheTitle()
  {
    final OnlineSearchResult address = OnlineSearchResult.builder(48.1533, 11.5834)
                                           .setStreet("Leopoldstraße", "10")
                                           .setOsm("W", 82108986)
                                           .setPlaceType(OnlineSearchResult.PlaceType.HOUSE)
                                           .build();
    final List<OfflinePlace> offline =
        Collections.singletonList(new OfflinePlace("Leopoldstraße 10", 48.1532, 11.5835));
    assertTrue(OnlineResultFilter.filter(Collections.singletonList(address), offline).isEmpty());
  }

  @Test
  public void distance()
  {
    // Berlin Hbf -> Alexanderplatz is about 3 km.
    final double d = OnlineResultFilter.distanceMeters(52.5250, 13.3694, 52.5219, 13.4132);
    assertTrue(String.valueOf(d), d > 2900 && d < 3100);
    assertEquals(0, OnlineResultFilter.distanceMeters(1, 2, 1, 2), 1e-9);
    assertFalse(OnlineResultFilter.isNearSameName(CAFE, Collections.emptyList()));
  }
}
