package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import org.junit.Test;

public class RideDragSnapTest
{
  /** Pills 40 px apart; the connection uses the second departure (y 140). */
  private static final float[] PILLS = {100, 140, 180, 220};
  private static final float HYSTERESIS = 4;

  @NonNull
  private static RideDragSnap snap(float fingerY)
  {
    final RideDragSnap snap = new RideDragSnap(HYSTERESIS);
    snap.setTargets(PILLS, 1, 0, fingerY);
    return snap;
  }

  @Test
  public void nearest()
  {
    assertEquals(-1, RideDragSnap.nearest(new float[0], 10));
    assertEquals(0, RideDragSnap.nearest(PILLS, -1000));
    assertEquals(3, RideDragSnap.nearest(PILLS, 1000));
    assertEquals(1, RideDragSnap.nearest(PILLS, 150));
    assertEquals(2, RideDragSnap.nearest(PILLS, 170));
    // A tie goes to the earlier departure.
    assertEquals(1, RideDragSnap.nearest(PILLS, 160));
  }

  @Test
  public void nearestTime()
  {
    final long[] departures = {1000, 1600, 2200};
    assertEquals(-1, RideDragSnap.nearestTime(new long[0], 10));
    assertEquals(0, RideDragSnap.nearestTime(departures, 0));
    assertEquals(1, RideDragSnap.nearestTime(departures, 1500));
    assertEquals(2, RideDragSnap.nearestTime(departures, 1_000_000));
  }

  @Test
  public void startsAtTheOriginalAndChangesNothingThere()
  {
    // The finger can be anywhere on the ride, e.g. 25 px below its departure.
    final RideDragSnap snap = snap(165);
    assertTrue(snap.hasTargets());
    assertEquals(1, snap.getOriginal());
    assertEquals(1, snap.getIndex());
    assertEquals(-1, snap.result());
    assertFalse(snap.move(170));
    assertEquals(-1, snap.result());
  }

  @Test
  public void followsTheFingerRelativeToWhereItGrabbed()
  {
    final RideDragSnap snap = snap(165);
    // 30 px down: the point is at 170, nearer to 180 than to 140 by 20 px.
    assertTrue(snap.move(195));
    assertEquals(2, snap.getIndex());
    assertEquals(2, snap.result());
    // Further down beyond the last pill: the last one.
    assertTrue(snap.move(400));
    assertEquals(3, snap.getIndex());
    // Up beyond the first one.
    assertTrue(snap.move(-200));
    assertEquals(0, snap.getIndex());
    assertEquals(0, snap.result());
    // Back to where it started: no change on release.
    assertTrue(snap.move(165));
    assertEquals(1, snap.getIndex());
    assertEquals(-1, snap.result());
  }

  @Test
  public void hysteresisKeepsTheTargetNearTheMiddle()
  {
    final RideDragSnap snap = snap(140);
    // Middle between 140 and 180 is 160: 1 px past it is not enough.
    assertFalse(snap.move(161));
    assertEquals(1, snap.getIndex());
    // 2 px past the middle: nearer by 4 px, still not more than the hysteresis.
    assertFalse(snap.move(162));
    assertEquals(1, snap.getIndex());
    assertTrue(snap.move(163));
    assertEquals(2, snap.getIndex());
    // And back: the switch needs to pass the middle by more than half the hysteresis again.
    assertFalse(snap.move(158));
    assertEquals(2, snap.getIndex());
    assertTrue(snap.move(157));
    assertEquals(1, snap.getIndex());
  }

  @Test
  public void eachTargetIsReportedOnceWhenPassingSeveral()
  {
    final RideDragSnap snap = snap(140);
    int ticks = 0;
    for (float y = 140; y <= 240; y += 1)
      if (snap.move(y))
        ++ticks;
    assertEquals(2, ticks);
    assertEquals(3, snap.getIndex());
  }

  @Test
  public void withoutTargetsNothingHappens()
  {
    final RideDragSnap snap = new RideDragSnap(HYSTERESIS);
    assertFalse(snap.hasTargets());
    assertFalse(snap.move(300));
    assertEquals(-1, snap.getIndex());
    assertEquals(-1, snap.result());
    // Nothing found: still nothing.
    snap.setTargets(new float[0], 0, 0, 300);
    assertFalse(snap.hasTargets());
    assertFalse(snap.move(10));
    assertEquals(-1, snap.result());
  }

  @Test
  public void targetsArrivingLateStartAtTheFingerWithoutAJump()
  {
    final RideDragSnap snap = new RideDragSnap(HYSTERESIS);
    // The finger moved while the departures were loading.
    assertFalse(snap.move(260));
    snap.setTargets(PILLS, 1, 0, 260);
    assertEquals(1, snap.getIndex());
    assertEquals(-1, snap.result());
    assertTrue(snap.move(300));
    assertEquals(2, snap.getIndex());
  }

  @Test
  public void newLayoutKeepsTheTargetAndTheOffset()
  {
    final RideDragSnap snap = snap(140);
    assertTrue(snap.move(185));
    assertEquals(2, snap.getIndex());
    // The grid is laid out again with another scale: the pills are 60 px apart now.
    snap.setTargets(new float[] {100, 160, 220, 280}, 1, 0, 185);
    assertEquals(2, snap.getIndex());
    // The finger was 5 px below the target; it now is 5 px below 220. 20 px further down (245) is short of the
    // middle (250), 31 px (256) is well past it.
    assertFalse(snap.move(205));
    assertTrue(snap.move(216));
    assertEquals(3, snap.getIndex());
  }

  @Test
  public void sameTargetsAgainChangeNothing()
  {
    final RideDragSnap snap = snap(140);
    assertFalse(snap.move(155));
    snap.setTargets(PILLS.clone(), 1, 0, 155);
    // Still 15 px below the original: 9 more pixels pass the middle by more than the hysteresis.
    assertFalse(snap.move(161));
    assertTrue(snap.move(164));
    assertEquals(2, snap.getIndex());
  }

  @Test
  public void fewerTargetsKeepTheIndexInRange()
  {
    final RideDragSnap snap = snap(140);
    assertTrue(snap.move(240));
    assertEquals(3, snap.getIndex());
    snap.setTargets(new float[] {100, 140}, 1, 0, 240);
    assertEquals(1, snap.getIndex());
    assertEquals(-1, snap.result());
  }

  @Test
  public void unknownOriginalIsTheDepartureNearestToTheRide()
  {
    final RideDragSnap snap = new RideDragSnap(HYSTERESIS);
    snap.setTargets(PILLS, -1, 183, 50);
    assertEquals(2, snap.getOriginal());
    assertEquals(2, snap.getIndex());
    assertEquals(-1, snap.result());
    assertTrue(snap.move(10));
    assertEquals(1, snap.result());
  }
}
