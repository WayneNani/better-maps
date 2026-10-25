package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.motis.model.Mode;
import org.junit.Test;

public class TransitModeTest
{
  @Test
  public void mapsApiNames()
  {
    assertEquals(TransitMode.SUBURBAN, TransitMode.fromApi("SUBURBAN"));
    assertEquals(TransitMode.REGIONAL_RAIL, TransitMode.fromApi("REGIONAL_RAIL"));
    assertEquals(TransitMode.WALK, TransitMode.fromApi("WALK"));
  }

  @Test
  public void mapsDeprecatedAliases()
  {
    assertEquals(TransitMode.SUBURBAN, TransitMode.fromApi("METRO"));
    assertEquals(TransitMode.AERIAL_LIFT, TransitMode.fromApi("AREAL_LIFT"));
  }

  @Test
  public void unknownValues()
  {
    assertEquals(TransitMode.UNKNOWN, TransitMode.fromApi(null));
    assertEquals(TransitMode.UNKNOWN, TransitMode.fromApi("HOVERCRAFT"));
    assertEquals(TransitMode.UNKNOWN, TransitMode.fromApi("unknown_default_open_api"));
  }

  @Test
  public void transitFlag()
  {
    assertTrue(TransitMode.BUS.isTransit());
    assertTrue(TransitMode.TRANSIT.isTransit());
    assertFalse(TransitMode.WALK.isTransit());
    assertFalse(TransitMode.BIKE.isTransit());
    assertFalse(TransitMode.UNKNOWN.isTransit());
  }

  /** Every mode (except UNKNOWN) must exist in the API, otherwise requests with it would be invalid. */
  @Test
  public void allModesExistInApi()
  {
    for (TransitMode mode : TransitMode.values())
    {
      if (mode == TransitMode.UNKNOWN)
        continue;
      assertEquals(mode.name(), Mode.fromValue(mode.name()).getValue());
    }
  }
}
