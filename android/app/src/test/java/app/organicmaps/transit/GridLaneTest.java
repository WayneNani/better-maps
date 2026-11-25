package app.organicmaps.transit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GridLaneTest
{
  private static final float DELTA = 0.001f;
  /** Columns 50 px wide, the lane 40 px; in view from 40 (right of the axis) to 400. */
  private static final int LANE = 40;
  private static final int VISIBLE_LEFT = 40;
  private static final int VISIBLE_RIGHT = 400;

  private static boolean onRight(int columnLeft)
  {
    return GridLane.onRight(columnLeft, columnLeft + 50, LANE, VISIBLE_LEFT, VISIBLE_RIGHT);
  }

  @Test
  public void laneOnTheLeftWhereItFits()
  {
    assertFalse(onRight(200));
    // Just fits.
    assertFalse(onRight(80));
    assertEquals(40, GridLane.left(80, 130, LANE, false));
  }

  @Test
  public void laneOnTheRightNextToTheAxis()
  {
    // The first column right of the axis (scroll position 0): no room on the left.
    assertTrue(onRight(48));
    assertEquals(98, GridLane.left(48, 98, LANE, true));
    // Partly below the axis.
    assertTrue(onRight(20));
  }

  @Test
  public void laneOnTheLeftAtTheRightEnd()
  {
    // The last column at the right edge, partly out of view: still left.
    assertFalse(onRight(340));
    assertFalse(onRight(380));
  }

  @Test
  public void neitherSideFitsTakesTheSideWithMoreInView()
  {
    // A narrow view: 100 px for a 50 px column.
    assertTrue(GridLane.onRight(60, 110, LANE, 40, 140));
    assertFalse(GridLane.onRight(70, 120, LANE, 40, 140));
    // A tie: left.
    assertFalse(GridLane.onRight(65, 115, LANE, 40, 140));
  }

  @Test
  public void pillsAtTheirTimes()
  {
    final float[] tops = GridLane.pillTops(new float[] {100, 150, 200}, 20, 2, 0, 400);
    assertArrayEquals(new float[] {90, 140, 190}, tops, DELTA);
  }

  @Test
  public void pillsMovedApartDownwards()
  {
    final float[] tops = GridLane.pillTops(new float[] {100, 105, 200}, 20, 2, 0, 400);
    assertArrayEquals(new float[] {90, 112, 190}, tops, DELTA);
  }

  @Test
  public void pillsOutsideTheRangeStayAtItsEdges()
  {
    // Two before the range (top 50), one in it, two after it (bottom 300).
    final float[] tops = GridLane.pillTops(new float[] {-100, 10, 120, 400, 500}, 20, 2, 50, 300);
    assertArrayEquals(new float[] {50, 72, 110, 258, 280}, tops, DELTA);
  }

  @Test
  public void tooManyPillsGoBelowTheRange()
  {
    final float[] tops = GridLane.pillTops(new float[] {60, 60, 60, 60}, 20, 2, 50, 100);
    assertArrayEquals(new float[] {50, 72, 94, 116}, tops, DELTA);
  }

  @Test
  public void noPills()
  {
    assertEquals(0, GridLane.pillTops(new float[0], 20, 2, 0, 100).length);
    assertEquals(0, GridLane.touchAreas(new float[0], 20, 44).length);
  }

  @Test
  public void touchAreasAreHighEnoughWhereTheyFit()
  {
    // Far apart: 44 px around each 20 px pill.
    assertArrayEquals(new float[] {88, 132, 188, 232}, GridLane.touchAreas(new float[] {100, 200}, 20, 44), DELTA);
    // Close: up to the middle of the space between them.
    assertArrayEquals(new float[] {88, 121, 121, 154}, GridLane.touchAreas(new float[] {100, 122}, 20, 44), DELTA);
    // Pills higher than the minimum: just the pill.
    assertArrayEquals(new float[] {100, 150}, GridLane.touchAreas(new float[] {100}, 50, 44), DELTA);
  }

  @Test
  public void liftedRideStaysInTheRange()
  {
    assertEquals(120, GridLane.liftedTop(120, 30, 50, 300), DELTA);
    assertEquals(50, GridLane.liftedTop(10, 30, 50, 300), DELTA);
    assertEquals(270, GridLane.liftedTop(290, 30, 50, 300), DELTA);
  }
}
