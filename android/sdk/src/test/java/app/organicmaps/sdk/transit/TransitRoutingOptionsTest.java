package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;

public class TransitRoutingOptionsTest
{
  @Test
  public void defaults()
  {
    final TransitRoutingOptions options = TransitRoutingOptions.defaults();
    assertTrue(options.isUseRoutedTransfers());
    assertTrue(options.isDetailedTransfers());
    assertEquals(5, options.getNumItineraries());
    assertNull(options.getMaxTransfers());
    assertNull(options.getMinTransferTimeMinutes());
    assertNull(options.getAdditionalTransferTimeMinutes());
    assertNull(options.getTransferTimeFactor());
    assertNull(options.getPedestrianProfile());
    assertNull(options.getPedestrianSpeed());
    assertNull(options.getRequireBikeTransport());
    assertTrue(options.getTransitModes().isEmpty());
    assertTrue(options.getPreTransitModes().isEmpty());
    assertTrue(options.getPostTransitModes().isEmpty());
    assertNull(options.getMaxPreTransitTimeSeconds());
    assertNull(options.getMaxPostTransitTimeSeconds());
    assertNull(options.getSearchWindowSeconds());
    assertNull(options.getRealtimeMode());
  }

  @Test
  public void builderSetsAllValues()
  {
    final TransitRoutingOptions options = TransitRoutingOptions.builder()
                                              .setMaxTransfers(2)
                                              .setMinTransferTimeMinutes(3)
                                              .setAdditionalTransferTimeMinutes(4)
                                              .setTransferTimeFactor(1.5)
                                              .setUseRoutedTransfers(false)
                                              .setDetailedTransfers(false)
                                              .setPedestrianProfile(TransitRoutingOptions.PedestrianProfile.WHEELCHAIR)
                                              .setPedestrianSpeed(0.8)
                                              .setRequireBikeTransport(true)
                                              .setTransitModes(EnumSet.of(TransitMode.BUS, TransitMode.TRAM))
                                              .setPreTransitModes(EnumSet.of(TransitMode.BIKE))
                                              .setPostTransitModes(EnumSet.of(TransitMode.WALK))
                                              .setMaxPreTransitTimeSeconds(600)
                                              .setMaxPostTransitTimeSeconds(1200)
                                              .setNumItineraries(3)
                                              .setSearchWindowSeconds(3600)
                                              .setRealtimeMode(TransitRoutingOptions.RealtimeMode.OFF)
                                              .build();
    assertEquals(Integer.valueOf(2), options.getMaxTransfers());
    assertEquals(Integer.valueOf(3), options.getMinTransferTimeMinutes());
    assertEquals(Integer.valueOf(4), options.getAdditionalTransferTimeMinutes());
    assertEquals(1.5, options.getTransferTimeFactor(), 0);
    assertFalse(options.isUseRoutedTransfers());
    assertFalse(options.isDetailedTransfers());
    assertEquals(TransitRoutingOptions.PedestrianProfile.WHEELCHAIR, options.getPedestrianProfile());
    assertEquals(0.8, options.getPedestrianSpeed(), 0);
    assertTrue(options.getRequireBikeTransport());
    assertEquals(EnumSet.of(TransitMode.BUS, TransitMode.TRAM), options.getTransitModes());
    assertEquals(EnumSet.of(TransitMode.BIKE), options.getPreTransitModes());
    assertEquals(EnumSet.of(TransitMode.WALK), options.getPostTransitModes());
    assertEquals(Integer.valueOf(600), options.getMaxPreTransitTimeSeconds());
    assertEquals(Integer.valueOf(1200), options.getMaxPostTransitTimeSeconds());
    assertEquals(3, options.getNumItineraries());
    assertEquals(Integer.valueOf(3600), options.getSearchWindowSeconds());
    assertEquals(TransitRoutingOptions.RealtimeMode.OFF, options.getRealtimeMode());
  }

  @Test
  public void toBuilderCopiesAndDoesNotModifyOriginal()
  {
    final TransitRoutingOptions original =
        TransitRoutingOptions.builder().setMaxTransfers(1).setTransitModes(EnumSet.of(TransitMode.BUS)).build();
    final TransitRoutingOptions copy =
        original.toBuilder().setNumItineraries(9).setTransitModes(EnumSet.of(TransitMode.TRAM)).build();
    assertEquals(Integer.valueOf(1), copy.getMaxTransfers());
    assertEquals(9, copy.getNumItineraries());
    assertEquals(EnumSet.of(TransitMode.TRAM), copy.getTransitModes());
    assertEquals(5, original.getNumItineraries());
    assertEquals(EnumSet.of(TransitMode.BUS), original.getTransitModes());
  }

  @Test
  public void modeSetsAreImmutableAndDefensivelyCopied()
  {
    final Set<TransitMode> modes = EnumSet.of(TransitMode.BUS);
    final TransitRoutingOptions options = TransitRoutingOptions.builder().setTransitModes(modes).build();
    modes.add(TransitMode.TRAM);
    assertEquals(EnumSet.of(TransitMode.BUS), options.getTransitModes());
    assertThrows(UnsupportedOperationException.class, () -> options.getTransitModes().add(TransitMode.RAIL));
  }

  @Test
  public void nullResetsToServerDefault()
  {
    final TransitRoutingOptions options =
        TransitRoutingOptions.builder().setMaxTransfers(3).build().toBuilder().setMaxTransfers(null).build();
    assertNull(options.getMaxTransfers());
  }

  @Test
  public void rejectsInvalidValues()
  {
    final TransitRoutingOptions.Builder b = TransitRoutingOptions.builder();
    assertThrows(IllegalArgumentException.class, () -> b.setMaxTransfers(-1));
    assertThrows(IllegalArgumentException.class, () -> b.setMinTransferTimeMinutes(-1));
    assertThrows(IllegalArgumentException.class, () -> b.setAdditionalTransferTimeMinutes(-5));
    assertThrows(IllegalArgumentException.class, () -> b.setTransferTimeFactor(0.5));
    assertThrows(IllegalArgumentException.class, () -> b.setTransferTimeFactor(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> b.setPedestrianSpeed(0.0));
    assertThrows(IllegalArgumentException.class, () -> b.setNumItineraries(0));
    assertThrows(IllegalArgumentException.class, () -> b.setSearchWindowSeconds(-1));
    assertThrows(IllegalArgumentException.class, () -> b.setMaxPreTransitTimeSeconds(-1));
    assertThrows(IllegalArgumentException.class, () -> b.setMaxPostTransitTimeSeconds(-1));
    assertThrows(IllegalArgumentException.class, () -> b.setTransitModes(EnumSet.of(TransitMode.WALK)));
    assertThrows(IllegalArgumentException.class, () -> b.setPreTransitModes(EnumSet.of(TransitMode.BUS)));
    assertThrows(IllegalArgumentException.class, () -> b.setPostTransitModes(EnumSet.of(TransitMode.UNKNOWN)));
  }
}
