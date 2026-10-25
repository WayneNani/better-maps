package app.organicmaps.sdk.transit.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitMode;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.Test;

public class TransitPlanRequestTest
{
  private static final TransitLocation A = TransitLocation.fromCoordinates(52.5, 13.3);
  private static final TransitLocation B = TransitLocation.fromCoordinates(52.6, 13.4);

  @Test
  public void equalRequests()
  {
    final TransitPlanRequest r1 =
        new TransitPlanRequest(A, B, Instant.ofEpochSecond(10), true, "c", TransitRoutingOptions.defaults());
    final TransitPlanRequest r2 =
        new TransitPlanRequest(TransitLocation.fromCoordinates(52.5, 13.3), B, Instant.ofEpochSecond(10), true, "c",
                               TransitRoutingOptions.builder().build());
    assertEquals(r1, r2);
    assertEquals(r1.hashCode(), r2.hashCode());
  }

  @Test
  public void differentRequests()
  {
    final TransitPlanRequest base = new TransitPlanRequest(A, B, null, false, null, TransitRoutingOptions.defaults());
    assertNotEquals(base, new TransitPlanRequest(B, A, null, false, null, TransitRoutingOptions.defaults()));
    assertNotEquals(base, new TransitPlanRequest(A, B, Instant.EPOCH, false, null, TransitRoutingOptions.defaults()));
    assertNotEquals(base, new TransitPlanRequest(A, B, null, true, null, TransitRoutingOptions.defaults()));
    assertNotEquals(base, base.withPageCursor("next"));
    assertNotEquals(base, new TransitPlanRequest(
                              A, B, null, false, null,
                              TransitRoutingOptions.builder().setTransitModes(EnumSet.of(TransitMode.BUS)).build()));
  }

  @Test
  public void withPageCursorKeepsQuery()
  {
    final TransitPlanRequest base =
        new TransitPlanRequest(A, B, Instant.ofEpochSecond(5), true, null, TransitRoutingOptions.defaults());
    final TransitPlanRequest page = base.withPageCursor("p");
    assertEquals("p", page.getPageCursor());
    assertEquals(A, page.getFrom());
    assertEquals(B, page.getTo());
    assertEquals(Instant.ofEpochSecond(5), page.getTime());
    assertTrue(page.isArriveBy());
    assertNull(base.getPageCursor());
    assertFalse(base.toString().isEmpty());
  }

  @Test
  public void optionsEqualityCoversAllFields()
  {
    final TransitRoutingOptions full = TransitRoutingOptions.builder()
                                           .setMaxTransfers(2)
                                           .setMinTransferTimeMinutes(3)
                                           .setAdditionalTransferTimeMinutes(5)
                                           .setTransferTimeFactor(1.5)
                                           .setPedestrianProfile(TransitRoutingOptions.PedestrianProfile.WHEELCHAIR)
                                           .setPedestrianSpeed(1.0)
                                           .setRequireBikeTransport(true)
                                           .setTransitModes(EnumSet.of(TransitMode.TRAM))
                                           .setMaxPreTransitTimeSeconds(600)
                                           .setMaxPostTransitTimeSeconds(900)
                                           .setNumItineraries(3)
                                           .setSearchWindowSeconds(3600)
                                           .setRealtimeMode(TransitRoutingOptions.RealtimeMode.OFF)
                                           .build();
    assertEquals(full, full.toBuilder().build());
    assertEquals(full.hashCode(), full.toBuilder().build().hashCode());
    assertNotEquals(full, full.toBuilder().setMaxTransfers(3).build());
    assertNotEquals(full, full.toBuilder().setAdditionalTransferTimeMinutes(0).build());
    assertNotEquals(full, full.toBuilder().setPedestrianProfile(null).build());
    assertNotEquals(full, full.toBuilder().setPedestrianSpeed(1.4).build());
    assertNotEquals(full, full.toBuilder().setRequireBikeTransport(null).build());
    assertNotEquals(full, full.toBuilder().setMaxPreTransitTimeSeconds(60).build());
    assertNotEquals(full, full.toBuilder().setMaxPostTransitTimeSeconds(60).build());
    assertNotEquals(full, full.toBuilder().setRealtimeMode(null).build());
    assertFalse(full.toString().isEmpty());
  }
}
