package app.organicmaps.ev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.Router;
import org.junit.Test;

public class EvPlannerEntryTest
{
  private final EvSettings mSettings = new EvSettings(new EvTestStore());

  private void setUp(boolean enabled, boolean validProfile)
  {
    final EvVehicleProfile profile = EvVehicleProfile.fromJson(EvTestData.ELROQ_JSON);
    if (!validProfile)
      profile.sockets.clear();
    mSettings.setProfile(profile);
    mSettings.setEnabled(enabled);
  }

  @Test
  public void buttonOnEveryCarRouteOnly()
  {
    // Also when the planner was never set up.
    assertTrue(EvPlannerEntry.isButtonShown(Router.Vehicle));
    for (Router router : Router.values())
      if (router != Router.Vehicle)
        assertFalse(router.name(), EvPlannerEntry.isButtonShown(router));
    assertFalse(EvPlannerEntry.isButtonShown(null));
  }

  @Test
  public void setupFirstWhenNotSetUp()
  {
    // A new user: off, no profile.
    assertEquals(EvPlannerEntry.Action.SETUP, EvPlannerEntry.onButton(mSettings, Router.Vehicle));

    // A profile but off.
    setUp(false, true);
    assertEquals(EvPlannerEntry.Action.SETUP, EvPlannerEntry.onButton(mSettings, Router.Vehicle));

    // On but the values are not usable.
    setUp(true, false);
    assertEquals(EvPlannerEntry.Action.SETUP, EvPlannerEntry.onButton(mSettings, Router.Vehicle));
  }

  @Test
  public void plannerWhenSetUp()
  {
    setUp(true, true);
    assertEquals(EvPlannerEntry.Action.PLANNER, EvPlannerEntry.onButton(mSettings, Router.Vehicle));
  }

  @Test
  public void nothingForOtherRouters()
  {
    setUp(true, true);
    assertEquals(EvPlannerEntry.Action.NONE, EvPlannerEntry.onButton(mSettings, Router.Pedestrian));
    assertEquals(EvPlannerEntry.Action.NONE, EvPlannerEntry.onButton(mSettings, Router.Bicycle));
    assertEquals(EvPlannerEntry.Action.NONE, EvPlannerEntry.onButton(mSettings, null));
    setUp(false, true);
    assertEquals(EvPlannerEntry.Action.NONE, EvPlannerEntry.onButton(mSettings, Router.Transit));
  }

  @Test
  public void plannerAfterSetupOnlyWhenEnabledWithVehicle()
  {
    // Back without enabling: just the route.
    assertFalse(EvPlannerEntry.openPlannerAfterSetup(mSettings, Router.Vehicle, true));
    setUp(false, true);
    assertFalse(EvPlannerEntry.openPlannerAfterSetup(mSettings, Router.Vehicle, true));
    setUp(true, false);
    assertFalse(EvPlannerEntry.openPlannerAfterSetup(mSettings, Router.Vehicle, true));

    // Enabled with a vehicle.
    setUp(true, true);
    assertTrue(EvPlannerEntry.openPlannerAfterSetup(mSettings, Router.Vehicle, true));

    // The route planner was closed meanwhile, or the router changed.
    assertFalse(EvPlannerEntry.openPlannerAfterSetup(mSettings, Router.Vehicle, false));
    assertFalse(EvPlannerEntry.openPlannerAfterSetup(mSettings, Router.Pedestrian, true));
  }
}
