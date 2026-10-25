package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.organicmaps.sdk.transit.TransitMode;
import org.junit.Test;

public class LineBadgeTest
{
  private static final int WHITE = 0xFFFFFFFF;
  private static final int BLACK = 0xFF000000;

  @Test
  public void usesPublishedColors()
  {
    final LineBadge badge = LineBadge.create("S5", TransitMode.SUBURBAN, "eb7405", "ffffff");
    assertEquals("S5", badge.getText());
    assertEquals(TransitMode.SUBURBAN, badge.getMode());
    assertEquals(0xFFEB7405, badge.getBackgroundColor());
    assertEquals(WHITE, badge.getTextColor());
  }

  @Test
  public void computesReadableTextColor()
  {
    assertEquals(WHITE, LineBadge.create("U2", TransitMode.SUBWAY, "#DA421E", null).getTextColor());
    assertEquals(BLACK, LineBadge.create("U4", TransitMode.SUBWAY, "F0D722", null).getTextColor());
    assertEquals(WHITE, LineBadge.create("U4", TransitMode.SUBWAY, "000000", "000000").getTextColor());
    assertEquals(BLACK, LineBadge.create("X", TransitMode.BUS, "ffffff", "ffffff").getTextColor());
  }

  @Test
  public void fallsBackToModeColorAndName()
  {
    final LineBadge bus = LineBadge.create(null, TransitMode.BUS, null, null);
    assertEquals("Bus", bus.getText());
    assertEquals(LineBadge.defaultColor(TransitMode.BUS), bus.getBackgroundColor());
    assertEquals(WHITE, bus.getTextColor());

    assertEquals("Regional rail", LineBadge.create("  ", TransitMode.REGIONAL_RAIL, "nothex", "12").getText());
    assertEquals(LineBadge.defaultColor(TransitMode.REGIONAL_RAIL),
                 LineBadge.create("RE1", TransitMode.REGIONAL_RAIL, "nothex", null).getBackgroundColor());
  }

  @Test
  public void parseColor()
  {
    assertEquals(Integer.valueOf(0xFF0066AD), LineBadge.parseColor("0066ad"));
    assertEquals(Integer.valueOf(0xFF0066AD), LineBadge.parseColor(" #0066AD "));
    assertNull(LineBadge.parseColor(null));
    assertNull(LineBadge.parseColor("06a"));
    assertNull(LineBadge.parseColor("zzzzzz"));
  }

  @Test
  public void luminance()
  {
    assertEquals(0.0, LineBadge.luminance(BLACK), 1e-9);
    assertEquals(1.0, LineBadge.luminance(WHITE), 1e-9);
  }

  @Test
  public void equality()
  {
    assertEquals(LineBadge.create("M10", TransitMode.TRAM, "be1414", null),
                 LineBadge.create("M10", TransitMode.TRAM, "BE1414", null));
    assertEquals("M10", LineBadge.create("M10", TransitMode.TRAM, null, null).toString());
  }
}
