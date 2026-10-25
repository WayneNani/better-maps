package app.organicmaps.search.online;

import static app.organicmaps.search.online.OnlineSearchPolicy.Decision.CATEGORY;
import static app.organicmaps.search.online.OnlineSearchPolicy.Decision.ENOUGH_OFFLINE_RESULTS;
import static app.organicmaps.search.online.OnlineSearchPolicy.Decision.FEW_OFFLINE_RESULTS;
import static app.organicmaps.search.online.OnlineSearchPolicy.Decision.QUERY_TOO_SHORT;
import static app.organicmaps.search.online.OnlineSearchPolicy.Decision.REGION_NOT_DOWNLOADED;
import static app.organicmaps.search.online.OnlineSearchPolicy.Decision.WAIT_FOR_OFFLINE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The rule "when does the online search supplement the offline search". */
public class OnlineSearchPolicyTest
{
  private static final RegionInfo BERLIN = new RegionInfo("Germany_Berlin", "Berlin", true, 0);
  private static final RegionInfo BAVARIA = new RegionInfo("Germany_Bavaria", "Upper Bavaria", false, 200_000_000);

  @Test
  public void shortQueriesNeverGoOnline()
  {
    assertEquals(QUERY_TOO_SHORT, OnlineSearchPolicy.decide(null, false, BAVARIA, 0, true));
    assertEquals(QUERY_TOO_SHORT, OnlineSearchPolicy.decide("", false, BAVARIA, 0, true));
    assertEquals(QUERY_TOO_SHORT, OnlineSearchPolicy.decide("Ma", false, BAVARIA, 0, true));
    assertEquals(QUERY_TOO_SHORT, OnlineSearchPolicy.decide("  Ma  ", false, BAVARIA, 0, true));
    assertEquals(REGION_NOT_DOWNLOADED, OnlineSearchPolicy.decide("Mar", false, BAVARIA, 0, false));
    // Code points, not UTF-16 units: three emoji are three characters.
    assertTrue(OnlineSearchPolicy.isLongEnough("🍺🍺🍺"));
    assertFalse(OnlineSearchPolicy.isLongEnough("🍺🍺"));
  }

  @Test
  public void categoriesStayOffline()
  {
    assertEquals(CATEGORY, OnlineSearchPolicy.decide("cafe", true, BAVARIA, 0, true));
  }

  @Test
  public void missingMapGoesOnlineRightAway()
  {
    // No need to wait for the offline search, and even many offline results don't prevent it.
    assertEquals(REGION_NOT_DOWNLOADED, OnlineSearchPolicy.decide("Marienplatz", false, BAVARIA, 0, false));
    assertEquals(REGION_NOT_DOWNLOADED, OnlineSearchPolicy.decide("Marienplatz", false, BAVARIA, 20, true));
    assertTrue(REGION_NOT_DOWNLOADED.isOnline());
  }

  @Test
  public void downloadedMapWaitsForOfflineResults()
  {
    assertEquals(WAIT_FOR_OFFLINE, OnlineSearchPolicy.decide("Marienplatz München", false, BERLIN, 0, false));
    assertFalse(WAIT_FOR_OFFLINE.isOnline());
  }

  @Test
  public void fewOfflineResultsGoOnline()
  {
    assertEquals(FEW_OFFLINE_RESULTS, OnlineSearchPolicy.decide("Marienplatz München", false, BERLIN, 0, true));
    assertEquals(FEW_OFFLINE_RESULTS, OnlineSearchPolicy.decide("Marienplatz München", false, BERLIN,
                                                                OnlineSearchPolicy.FEW_RESULTS - 1, true));
    assertEquals(ENOUGH_OFFLINE_RESULTS,
                 OnlineSearchPolicy.decide("Alexanderplatz", false, BERLIN, OnlineSearchPolicy.FEW_RESULTS, true));
    assertTrue(FEW_OFFLINE_RESULTS.isOnline());
    assertFalse(ENOUGH_OFFLINE_RESULTS.isOnline());
  }

  @Test
  public void unknownRegionUsesTheOfflineResults()
  {
    // E.g. the map center is on the open sea.
    assertEquals(WAIT_FOR_OFFLINE, OnlineSearchPolicy.decide("Helgoland", false, null, 0, false));
    assertEquals(FEW_OFFLINE_RESULTS, OnlineSearchPolicy.decide("Helgoland", false, null, 1, true));
    assertEquals(ENOUGH_OFFLINE_RESULTS, OnlineSearchPolicy.decide("Helgoland", false, null, 5, true));
  }
}
