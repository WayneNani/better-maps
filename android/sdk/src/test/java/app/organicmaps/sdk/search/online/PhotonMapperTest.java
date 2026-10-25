package app.organicmaps.sdk.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParseException;
import java.util.List;
import org.junit.Test;

/** Mapping of recorded Photon responses to {@link OnlineSearchResult}. */
public class PhotonMapperTest
{
  private static final double EPS = 1e-7;

  @Test
  public void marienplatzMuenchenGerman()
  {
    final List<OnlineSearchResult> results =
        PhotonMapper.toResults(PhotonFixtures.read("search_marienplatz_muenchen_de.json"));
    assertEquals(2, results.size());

    final OnlineSearchResult street = results.get(0);
    assertEquals("Marienplatz", street.getName());
    assertEquals("Marienplatz", street.getTitle());
    assertEquals("W", street.getOsmType());
    assertEquals(259055080L, street.getOsmId());
    assertEquals("W259055080", street.getOsmRef());
    assertEquals("highway", street.getOsmKey());
    assertEquals("pedestrian", street.getOsmValue());
    assertEquals("highway-pedestrian", street.getCategory());
    assertEquals(OnlineSearchResult.PlaceType.STREET, street.getPlaceType());
    assertEquals(48.137569, street.getLat(), EPS);
    assertEquals(11.57498, street.getLon(), EPS);
    assertEquals("Kreuzviertel", street.getLocality());
    assertEquals("Altstadt", street.getDistrict());
    assertEquals("München", street.getCity());
    assertEquals("Bayern", street.getState());
    assertEquals("Deutschland", street.getCountry());
    assertEquals("DE", street.getCountryCode());
    assertEquals("80331", street.getPostcode());
    assertEquals("Altstadt, 80331 München, Deutschland", street.getAddress());

    // Photon's extent is [minLon, maxLat, maxLon, minLat].
    final OnlineSearchResult.BoundingBox extent = street.getExtent();
    assertNotNull(extent);
    assertEquals(48.1375576, extent.getMinLat(), EPS);
    assertEquals(48.1375805, extent.getMaxLat(), EPS);
    assertEquals(11.5749262, extent.getMinLon(), EPS);
    assertEquals(11.5750338, extent.getMaxLon(), EPS);
    assertTrue(extent.contains(48.13757, 11.5750));

    final OnlineSearchResult square = results.get(1);
    assertEquals("R7366154", square.getOsmRef());
    assertEquals("place-square", square.getCategory());
  }

  @Test
  public void marienplatzEnglishWithStationWithoutExtent()
  {
    final List<OnlineSearchResult> results = PhotonMapper.toResults(PhotonFixtures.read("search_marienplatz_en.json"));
    assertEquals(3, results.size());
    assertEquals("Munich", results.get(0).getCity());
    assertEquals("Old Town", results.get(0).getDistrict());
    assertEquals(OnlineSearchResult.PlaceType.LOCALITY, results.get(1).getPlaceType());

    final OnlineSearchResult station = results.get(2);
    assertEquals("N2941687118", station.getOsmRef());
    assertEquals(OnlineSearchResult.PlaceType.HOUSE, station.getPlaceType());
    assertNull(station.getExtent());
    assertEquals("Marienplatz", station.getStreet());
    // The street equals the name and is not repeated in the address.
    assertEquals("Old Town, 80331 Munich, Germany", station.getAddress());
  }

  @Test
  public void addressWithoutName()
  {
    final List<OnlineSearchResult> results =
        PhotonMapper.toResults(PhotonFixtures.read("search_address_leopoldstrasse.json"));
    assertEquals(1, results.size());
    final OnlineSearchResult house = results.get(0);
    assertNull(house.getName());
    assertEquals("Leopoldstraße 10", house.getTitle());
    assertEquals("Leopoldstraße 10", house.getStreetLine());
    assertEquals("building-office", house.getCategory());
    assertEquals("Schwabing, 80802 München, Deutschland", house.getAddress());
    assertEquals(OnlineSearchResult.PlaceType.HOUSE, house.getPlaceType());
  }

  @Test
  public void emptyCollection()
  {
    assertTrue(PhotonMapper.toResults(PhotonFixtures.read("search_empty.json")).isEmpty());
  }

  @Test
  public void invalidDocuments()
  {
    assertThrows(JsonParseException.class, () -> PhotonMapper.toResults("[]"));
    assertThrows(JsonParseException.class, () -> PhotonMapper.toResults("{\"features\": {}}"));
    assertThrows(RuntimeException.class, () -> PhotonMapper.toResults("<html>"));
  }

  @Test
  public void featuresWithoutValidPointAreSkipped()
  {
    final String body = "{\"features\": ["
                      + "{\"geometry\": {\"coordinates\": [11.5, 48.1]}, \"properties\": {\"name\": \"A\"}},"
                      + "{\"geometry\": {\"coordinates\": [11.5]}, \"properties\": {\"name\": \"B\"}},"
                      + "{\"geometry\": {\"coordinates\": [\"x\", 48.1]}, \"properties\": {\"name\": \"C\"}},"
                      + "{\"geometry\": {\"coordinates\": [200, 48.1]}, \"properties\": {\"name\": \"D\"}},"
                      + "{\"properties\": {\"name\": \"E\"}},"
                      + "42,"
                      + "{\"geometry\": {\"coordinates\": [1, 2]}}"
                      + "]}";
    final List<OnlineSearchResult> results = PhotonMapper.toResults(body);
    assertEquals(2, results.size());
    assertEquals("A", results.get(0).getName());
    // Without properties only the coordinate is known.
    assertNull(results.get(1).getName());
    assertEquals("2.00000, 1.00000", results.get(1).getTitle());
    assertNull(results.get(1).getOsmRef());
    assertEquals(OnlineSearchResult.PlaceType.OTHER, results.get(1).getPlaceType());
  }

  @Test
  public void unexpectedPropertyTypes()
  {
    final String body = "{\"features\": [{\"geometry\": {\"coordinates\": [11.5, 48.1]}, \"properties\": {"
                      + "\"name\": {\"de\": \"x\"}, \"osm_id\": \"abc\", \"osm_type\": \"n\", \"type\": \"village\","
                      + "\"extent\": [1, 2, 3], \"city\": \"  \"}}]}";
    final OnlineSearchResult result = PhotonMapper.toResults(body).get(0);
    assertNull(result.getName());
    assertEquals(0, result.getOsmId());
    assertNull(result.getOsmRef());
    assertEquals(OnlineSearchResult.PlaceType.OTHER, result.getPlaceType());
    assertNull(result.getExtent());
    assertNull(result.getCity());
  }

  @Test
  public void serverMessages()
  {
    assertEquals("lang: Language is not supported. Supported are: default, de, en, fr",
                 PhotonMapper.parseServerMessage(PhotonFixtures.read("error_unsupported_language.json")));
    assertEquals("missing search term 'q'",
                 PhotonMapper.parseServerMessage("{\"message\": \"missing search term 'q'\"}"));
    assertNull(PhotonMapper.parseServerMessage("<html>Bad gateway</html>"));
    assertNull(PhotonMapper.parseServerMessage(""));
    assertNull(PhotonMapper.parseServerMessage(null));
    assertNull(PhotonMapper.parseServerMessage("[1, 2]"));
    assertNull(PhotonMapper.parseServerMessage("{\"lang\": [1]}"));
  }
}
