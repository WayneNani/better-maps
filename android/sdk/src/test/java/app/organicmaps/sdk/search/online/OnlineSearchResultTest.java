package app.organicmaps.sdk.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnlineSearchResultTest
{
  @Test
  public void titleFallbacks()
  {
    assertEquals("Café Luitpold", OnlineSearchResult.builder(48.1, 11.5).setName(" Café Luitpold ").build().getTitle());
    assertEquals("Brienner Straße 11",
                 OnlineSearchResult.builder(48.1, 11.5).setStreet("Brienner Straße", "11").build().getTitle());
    assertEquals("Brienner Straße",
                 OnlineSearchResult.builder(48.1, 11.5).setStreet("Brienner Straße", null).build().getTitle());
    assertEquals("Schwabing",
                 OnlineSearchResult.builder(48.1, 11.5).setDistrict("Schwabing").setCity("München").build().getTitle());
    assertEquals("Bayern", OnlineSearchResult.builder(48.1, 11.5).setState("Bayern").build().getTitle());
    assertEquals("48.10000, 11.50000", OnlineSearchResult.builder(48.1, 11.5).setName("  ").build().getTitle());
  }

  @Test
  public void addressOfAPoi()
  {
    final OnlineSearchResult cafe = OnlineSearchResult.builder(48.14, 11.57)
                                        .setName("Café Luitpold")
                                        .setTag("amenity", "cafe")
                                        .setStreet("Brienner Straße", "11")
                                        .setPostcode("80333")
                                        .setDistrict("Maxvorstadt")
                                        .setCity("München")
                                        .setState("Bayern")
                                        .setCountry("Deutschland", "de")
                                        .build();
    assertEquals("Brienner Straße 11, Maxvorstadt, 80333 München, Deutschland", cafe.getAddress());
    assertEquals("amenity-cafe", cafe.getCategory());
    assertEquals("DE", cafe.getCountryCode());
  }

  @Test
  public void addressOfACity()
  {
    final OnlineSearchResult city = OnlineSearchResult.builder(48.137, 11.575)
                                        .setName("München")
                                        .setTag("place", "city")
                                        .setPlaceType(OnlineSearchResult.PlaceType.CITY)
                                        .setCity("München")
                                        .setPostcode("80331")
                                        .setState("Bayern")
                                        .setCountry("Deutschland", "DE")
                                        .build();
    // Neither the city itself nor a single postcode of a city is part of its address; the state helps instead.
    assertEquals("Bayern, Deutschland", city.getAddress());
  }

  @Test
  public void addressOfACountry()
  {
    final OnlineSearchResult country = OnlineSearchResult.builder(51, 10)
                                           .setName("Deutschland")
                                           .setPlaceType(OnlineSearchResult.PlaceType.COUNTRY)
                                           .setCountry("Deutschland", "DE")
                                           .build();
    assertEquals("", country.getAddress());
  }

  @Test
  public void addressWithLocalityOrCountyInsteadOfCity()
  {
    assertEquals("83043 Bad Aibling, Deutschland", OnlineSearchResult.builder(47.8, 12.0)
                                                       .setName("Kurpark")
                                                       .setLocality("Bad Aibling")
                                                       .setPostcode("83043")
                                                       .setCountry("Deutschland", null)
                                                       .build()
                                                       .getAddress());
    assertEquals("Landkreis Rosenheim", OnlineSearchResult.builder(47.8, 12.0)
                                            .setName("Wendelstein")
                                            .setCounty("Landkreis Rosenheim")
                                            .build()
                                            .getAddress());
    assertEquals("12345",
                 OnlineSearchResult.builder(47.8, 12.0).setName("X").setPostcode("12345").build().getAddress());
  }

  @Test
  public void osmReference()
  {
    assertEquals("N42", OnlineSearchResult.builder(1, 2).setOsm("n", 42).build().getOsmRef());
    assertNull(OnlineSearchResult.builder(1, 2).setOsm(null, 42).build().getOsmRef());
    assertNull(OnlineSearchResult.builder(1, 2).setOsm("W", 0).build().getOsmRef());
    assertNull(OnlineSearchResult.builder(1, 2).setTag("amenity", null).build().getCategory());
    assertNull(OnlineSearchResult.builder(1, 2).setTag(null, "cafe").build().getCategory());
  }

  @Test
  public void placeTypes()
  {
    assertEquals(OnlineSearchResult.PlaceType.HOUSE, OnlineSearchResult.PlaceType.parse("house"));
    assertEquals(OnlineSearchResult.PlaceType.CITY, OnlineSearchResult.PlaceType.parse(" City "));
    assertEquals(OnlineSearchResult.PlaceType.OTHER, OnlineSearchResult.PlaceType.parse("village"));
    assertEquals(OnlineSearchResult.PlaceType.OTHER, OnlineSearchResult.PlaceType.parse(null));
  }

  @Test
  public void boundingBoxIsNormalized()
  {
    final OnlineSearchResult.BoundingBox box = new OnlineSearchResult.BoundingBox(48.2, 11.6, 48.1, 11.5);
    assertEquals(48.1, box.getMinLat(), 0);
    assertEquals(48.2, box.getMaxLat(), 0);
    assertEquals(11.5, box.getMinLon(), 0);
    assertEquals(11.6, box.getMaxLon(), 0);
    assertTrue(box.contains(48.15, 11.55));
    assertFalse(box.contains(48.25, 11.55));
    assertFalse(box.contains(48.15, 11.45));
  }

  @Test
  public void invalidCoordinates()
  {
    assertThrows(IllegalArgumentException.class, () -> OnlineSearchResult.builder(90.1, 0));
    assertThrows(IllegalArgumentException.class, () -> OnlineSearchResult.builder(0, 180.5));
    assertThrows(IllegalArgumentException.class, () -> OnlineSearchResult.builder(Double.NaN, 0));
  }
}
