package app.organicmaps.ev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.Router;
import java.util.List;
import org.junit.Test;

public class EvSettingsTest
{
  private final EvTestStore mStore = new EvTestStore();
  private final EvSettings mSettings = new EvSettings(mStore);

  @Test
  public void offAndWithoutProfileByDefault()
  {
    assertFalse(mSettings.isEnabled());
    assertNull(mSettings.getProfile());
    assertFalse(mSettings.isAvailable(Router.Vehicle));
    assertTrue(mSettings.isCarSocEnabled());
  }

  @Test
  public void availableOnlyForCarsWithProfileWhenEnabled()
  {
    mSettings.setProfile(EvVehicleProfile.fromJson(EvTestData.ELROQ_JSON));
    assertFalse(mSettings.isAvailable(Router.Vehicle));
    mSettings.setEnabled(true);
    assertTrue(mSettings.isAvailable(Router.Vehicle));
    assertFalse(mSettings.isAvailable(Router.Pedestrian));
    assertFalse(mSettings.isAvailable(Router.Bicycle));
    assertFalse(mSettings.isAvailable(null));

    // An invalid profile, e.g. without sockets.
    final EvVehicleProfile broken = EvVehicleProfile.fromJson(EvTestData.ELROQ_JSON);
    assertNotNull(broken);
    broken.sockets.clear();
    mSettings.setProfile(broken);
    assertFalse(mSettings.isAvailable(Router.Vehicle));

    mSettings.setProfile(null);
    assertNull(mSettings.getProfile());
  }

  @Test
  public void profileIsStoredAsJson()
  {
    final EvVehicleProfile elroq = EvVehicleProfile.fromJson(EvTestData.ELROQ_JSON);
    assertNotNull(elroq);
    elroq.consumptionFactor = 1.2;
    elroq.setSocket(EvVehicleProfile.SOCKET_CHADEMO, true);
    mSettings.setProfile(elroq);

    final EvVehicleProfile read = mSettings.getProfile();
    assertNotNull(read);
    assertEquals("skoda_elroq_85", read.id);
    assertEquals("Škoda Elroq 85", read.name);
    assertEquals(77, read.usableCapacityKwh, 1e-9);
    assertEquals(1.2, read.consumptionFactor, 1e-9);
    assertEquals(List.of("type2_combo", "type2", "chademo"), read.sockets);
    assertEquals(5, read.chargeCurve.size());
    assertEquals(135, read.getCurvePeakKw(), 1e-9);
  }

  @Test
  public void optionsWithDefaultsOfTheMockup()
  {
    final EvPlanOptions o = mSettings.getOptions();
    assertEquals(80, o.startSoc, 1e-9);
    assertEquals(20, o.minArrivalSoc, 1e-9);
    assertEquals(10, o.minStopSoc, 1e-9);
    assertEquals(EvPlanOptions.PRIORITY_FASTEST, o.priority);
    assertEquals(50, o.minPowerKw, 1e-9);
    assertTrue(o.preferredProviders.isEmpty());
    assertTrue(o.preferredAmenities.isEmpty());
  }

  @Test
  public void optionsAreStoredWithoutExcludedStations()
  {
    final EvPlanOptions o = new EvPlanOptions();
    o.startSoc = 82;
    o.minArrivalSoc = 25;
    o.minStopSoc = 15;
    o.priority = EvPlanOptions.PRIORITY_LONG_BREAKS;
    o.preferredProviders.add("enbw");
    o.preferredProviders.add("ionity");
    o.excludedProviders.add("tesla");
    o.minPowerKw = 150;
    o.preferredAmenities.add("toilets");
    o.preferredAmenities.add("playground");
    o.excludedStations.add("Germany:1");
    mSettings.setOptions(o);

    final EvPlanOptions read = mSettings.getOptions();
    assertEquals(82, read.startSoc, 1e-9);
    assertEquals(25, read.minArrivalSoc, 1e-9);
    assertEquals(15, read.minStopSoc, 1e-9);
    assertEquals(EvPlanOptions.PRIORITY_LONG_BREAKS, read.priority);
    assertEquals(List.of("enbw", "ionity"), read.preferredProviders);
    assertEquals(List.of("tesla"), read.excludedProviders);
    assertEquals(150, read.minPowerKw, 1e-9);
    assertEquals(List.of("toilets", "playground"), read.preferredAmenities);
    assertTrue(read.excludedStations.isEmpty());
  }

  @Test
  public void vehicleProfileJson()
  {
    final EvVehicleProfile elroq = EvVehicleProfile.fromJson(EvTestData.ELROQ_JSON);
    assertNotNull(elroq);
    assertTrue(elroq.isValid());
    assertFalse(elroq.isCustom());
    assertTrue(elroq.hasSocket(EvVehicleProfile.SOCKET_CCS));
    assertFalse(elroq.hasSocket(EvVehicleProfile.SOCKET_CHADEMO));

    final EvVehicleProfile copy = elroq.copy();
    copy.consumptionMotorway = copy.consumptionCountry;
    assertFalse(copy.isValid());
    assertTrue(elroq.isValid());

    final List<EvVehicleProfile> list = EvVehicleProfile.listFromJson("[" + EvTestData.ELROQ_JSON + "]");
    assertEquals(1, list.size());
    assertNull(EvVehicleProfile.fromJson("{broken"));
    assertTrue(EvVehicleProfile.listFromJson("nonsense").isEmpty());
  }
}
