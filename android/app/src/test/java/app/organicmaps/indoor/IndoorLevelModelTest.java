package app.organicmaps.indoor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class IndoorLevelModelTest
{
  @Test
  public void highestLevelOnTopAndShownSelected()
  {
    final List<IndoorLevelModel.Item> items = IndoorLevelModel.items(
        new double[] {-1, 0, 0.5, 1}, new String[] {"U1", "EG", "", "1"}, 0.5000000001);
    assertEquals(4, items.size());
    assertEquals("1", items.get(0).label);
    assertEquals("0.5", items.get(1).label);
    assertEquals("EG", items.get(2).label);
    assertEquals("U1", items.get(3).label);
    assertTrue(items.get(1).selected);
    assertFalse(items.get(0).selected);
    assertEquals(1, IndoorLevelModel.selectedIndex(items));
    assertEquals(-1.0, items.get(3).level, 0);
  }

  @Test
  public void unsortedInputAndMissingLabels()
  {
    final List<IndoorLevelModel.Item> items =
        IndoorLevelModel.items(new double[] {2, -2, 0}, new String[] {"2"}, 7);
    assertEquals("2", items.get(0).label);
    assertEquals("0", items.get(1).label);
    assertEquals("-2", items.get(2).label);
    assertEquals(-1, IndoorLevelModel.selectedIndex(items));
  }

  @Test
  public void formatLevel()
  {
    assertEquals("0", IndoorLevelModel.formatLevel(0));
    assertEquals("0", IndoorLevelModel.formatLevel(-0.0));
    assertEquals("-1", IndoorLevelModel.formatLevel(-1));
    assertEquals("0.5", IndoorLevelModel.formatLevel(0.5));
    assertEquals("-0.5", IndoorLevelModel.formatLevel(-0.5));
    assertEquals("0.25", IndoorLevelModel.formatLevel(0.25));
    assertEquals("12", IndoorLevelModel.formatLevel(12));
    assertEquals("0.3", IndoorLevelModel.formatLevel(0.1 + 0.2));
  }

  @Test
  public void visibleCount()
  {
    assertEquals(1, IndoorLevelModel.visibleCount(1));
    assertEquals(5, IndoorLevelModel.visibleCount(5));
    assertEquals(IndoorLevelModel.MAX_VISIBLE, IndoorLevelModel.visibleCount(25));
  }

  @Test
  public void sameLevel()
  {
    assertTrue(IndoorLevelModel.sameLevel(0.3, 0.1 + 0.2));
    assertFalse(IndoorLevelModel.sameLevel(0, 0.5));
    assertFalse(IndoorLevelModel.sameLevel(0, Double.NaN));
  }
}
