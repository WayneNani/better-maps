package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.transit.TransitMode;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class TransitSettingsTest
{
  private static TransitRoutingOptions options(MapStore store)
  {
    return new TransitSettings(store).toRoutingOptions();
  }

  @Test
  public void defaultsUseServerDefaults()
  {
    final TransitRoutingOptions options = options(new MapStore());
    assertNull(options.getAdditionalTransferTimeMinutes());
    assertNull(options.getPedestrianSpeed());
    assertNull(options.getPedestrianProfile());
    assertNull(options.getRequireBikeTransport());
    assertEquals(Integer.valueOf(900), options.getMaxPreTransitTimeSeconds());
    assertEquals(Integer.valueOf(900), options.getMaxPostTransitTimeSeconds());
    assertTrue(options.getTransitModes().isEmpty());
    // Defaults of the SDK stay.
    assertTrue(options.isUseRoutedTransfers());
    assertTrue(options.isDetailedTransfers());
    assertEquals(TransitRoutingOptions.DEFAULT_NUM_ITINERARIES, options.getNumItineraries());
  }

  @Test
  public void transferTime()
  {
    assertEquals(
        Integer.valueOf(5),
        options(new MapStore().with(TransitSettings.KEY_TRANSFER_TIME, "MORE")).getAdditionalTransferTimeMinutes());
    assertEquals(Integer.valueOf(10), options(new MapStore().with(TransitSettings.KEY_TRANSFER_TIME, "much_more"))
                                          .getAdditionalTransferTimeMinutes());
    assertNull(
        options(new MapStore().with(TransitSettings.KEY_TRANSFER_TIME, "NORMAL")).getAdditionalTransferTimeMinutes());
    assertNull(
        options(new MapStore().with(TransitSettings.KEY_TRANSFER_TIME, "bogus")).getAdditionalTransferTimeMinutes());
  }

  @Test
  public void walkingSpeed()
  {
    assertEquals(0.9, options(new MapStore().with(TransitSettings.KEY_WALKING_SPEED, "SLOW")).getPedestrianSpeed(),
                 1e-9);
    assertEquals(1.6, options(new MapStore().with(TransitSettings.KEY_WALKING_SPEED, "FAST")).getPedestrianSpeed(),
                 1e-9);
    assertNull(options(new MapStore().with(TransitSettings.KEY_WALKING_SPEED, "NORMAL")).getPedestrianSpeed());
  }

  @Test
  public void wheelchairAndBike()
  {
    final TransitRoutingOptions options =
        options(new MapStore().with(TransitSettings.KEY_WHEELCHAIR, true).with(TransitSettings.KEY_BIKE, true));
    assertEquals(TransitRoutingOptions.PedestrianProfile.WHEELCHAIR, options.getPedestrianProfile());
    assertEquals(Boolean.TRUE, options.getRequireBikeTransport());

    final TransitRoutingOptions off =
        options(new MapStore().with(TransitSettings.KEY_WHEELCHAIR, false).with(TransitSettings.KEY_BIKE, false));
    assertNull(off.getPedestrianProfile());
    // "false" would forbid nothing, so the parameter is not sent at all.
    assertNull(off.getRequireBikeTransport());
  }

  @Test
  public void maxWalk()
  {
    final TransitRoutingOptions ten = options(new MapStore().with(TransitSettings.KEY_MAX_WALK, "10"));
    assertEquals(Integer.valueOf(600), ten.getMaxPreTransitTimeSeconds());
    assertEquals(Integer.valueOf(600), ten.getMaxPostTransitTimeSeconds());
    assertEquals(15, new TransitSettings(new MapStore().with(TransitSettings.KEY_MAX_WALK, "abc")).getMaxWalkMinutes());
    assertEquals(15, new TransitSettings(new MapStore().with(TransitSettings.KEY_MAX_WALK, "0")).getMaxWalkMinutes());
    assertEquals(15, new TransitSettings(new MapStore().with(TransitSettings.KEY_MAX_WALK, "999")).getMaxWalkMinutes());
    assertEquals(30,
                 new TransitSettings(new MapStore().with(TransitSettings.KEY_MAX_WALK, " 30 ")).getMaxWalkMinutes());
  }

  @Test
  public void excludedModes()
  {
    final Set<String> excluded = new HashSet<>();
    excluded.add("BUS");
    excluded.add("FERRY");
    excluded.add("unknown");
    final TransitSettings settings =
        new TransitSettings(new MapStore().with(TransitSettings.KEY_EXCLUDED_MODES, excluded));
    assertEquals(EnumSet.of(TransitSettings.ModeGroup.BUS, TransitSettings.ModeGroup.FERRY),
                 settings.getExcludedModeGroups());

    final Set<TransitMode> allowed = settings.toRoutingOptions().getTransitModes();
    for (TransitMode mode : EnumSet.of(TransitMode.BUS, TransitMode.COACH, TransitMode.FERRY))
      assertFalse(mode.name(), allowed.contains(mode));
    for (TransitMode mode :
         EnumSet.of(TransitMode.TRAM, TransitMode.SUBWAY, TransitMode.SUBURBAN, TransitMode.REGIONAL_RAIL,
                    TransitMode.HIGHSPEED_RAIL, TransitMode.LONG_DISTANCE, TransitMode.NIGHT_RAIL,
                    TransitMode.FUNICULAR, TransitMode.AERIAL_LIFT, TransitMode.OTHER, TransitMode.AIRPLANE))
      assertTrue(mode.name(), allowed.contains(mode));
    // Only transit modes are sent (the builder would reject others).
    for (TransitMode mode : allowed)
      assertTrue(mode.isTransit());
  }

  @Test
  public void excludingEverythingFallsBackToAllModes()
  {
    final Set<String> all = TransitSettings.toPreferenceValues(EnumSet.allOf(TransitSettings.ModeGroup.class));
    assertFalse(TransitSettings.isValidExclusion(all));
    final TransitSettings settings = new TransitSettings(new MapStore().with(TransitSettings.KEY_EXCLUDED_MODES, all));
    assertTrue(settings.getAllowedTransitModes().isEmpty());

    final Set<String> some = TransitSettings.toPreferenceValues(EnumSet.of(TransitSettings.ModeGroup.TRAM));
    assertTrue(TransitSettings.isValidExclusion(some));
    assertTrue(TransitSettings.isValidExclusion(new HashSet<>()));
  }

  @Test
  public void serverUrl()
  {
    assertEquals(TransitServiceConfig.TRANSITOUS_URL, new TransitSettings(new MapStore()).getServerUrl());
    assertTrue(new TransitSettings(new MapStore()).isDefaultServer());

    final TransitSettings custom =
        new TransitSettings(new MapStore().with(TransitSettings.KEY_SERVER_URL, " https://motis.example.org// "));
    assertEquals("https://motis.example.org", custom.getServerUrl());
    assertFalse(custom.isDefaultServer());

    assertEquals(TransitServiceConfig.TRANSITOUS_URL,
                 new TransitSettings(new MapStore().with(TransitSettings.KEY_SERVER_URL, "ftp://x")).getServerUrl());
  }

  @Test
  public void validServerUrls()
  {
    assertTrue(TransitSettings.isValidServerUrl("https://api.transitous.org"));
    assertTrue(TransitSettings.isValidServerUrl("http://10.0.2.2:8080"));
    assertTrue(TransitSettings.isValidServerUrl("HTTPS://Example.org/motis/"));
    assertFalse(TransitSettings.isValidServerUrl(null));
    assertFalse(TransitSettings.isValidServerUrl(""));
    assertFalse(TransitSettings.isValidServerUrl("api.transitous.org"));
    assertFalse(TransitSettings.isValidServerUrl("https://"));
    assertFalse(TransitSettings.isValidServerUrl("https:///path"));
    assertFalse(TransitSettings.isValidServerUrl("https://exa mple.org"));
  }
}
