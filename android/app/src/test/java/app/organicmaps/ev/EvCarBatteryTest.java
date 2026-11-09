package app.organicmaps.ev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class EvCarBatteryTest
{
  @Test
  public void latestValueOfTheCar()
  {
    final long[] now = {1000};
    final EvCarBattery battery = new EvCarBattery();
    battery.setClock(() -> now[0]);
    assertNull(battery.getSoc());
    battery.onBatteryPercent(64.5f);
    assertEquals(64.5, battery.getSoc(), 1e-6);
    // Invalid values are ignored.
    battery.onBatteryPercent(-1);
    battery.onBatteryPercent(101);
    assertEquals(64.5, battery.getSoc(), 1e-6);
    // Too old.
    now[0] += EvCarBattery.MAX_AGE_MS + 1;
    assertNull(battery.getSoc());

    // Only if the user allowed it.
    battery.onBatteryPercent(50);
    final EvSettings settings = new EvSettings(new EvTestStore());
    assertNotNull(battery.getSocIfEnabled(settings));
    settings.setCarSocEnabled(false);
    assertNull(battery.getSocIfEnabled(settings));
  }
}
