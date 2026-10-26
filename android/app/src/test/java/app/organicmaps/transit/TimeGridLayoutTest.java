package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.t;
import static app.organicmaps.transit.JourneyBarLayoutTest.ride;
import static app.organicmaps.transit.JourneyBarLayoutTest.timeline;
import static app.organicmaps.transit.JourneyBarLayoutTest.walk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TimeGridLayoutTest
{
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  /** 2026-10-25 16:00 UTC, 17:00 in Berlin. */
  private static final long T0 = t("2026-10-25T16:00:00Z").getEpochSecond();

  private static long min(int minutes)
  {
    return T0 + minutes * 60L;
  }

  @NonNull
  private static TimeGridLayout.Params params(int minTickSpacing)
  {
    final TimeGridLayout.Params params = new TimeGridLayout.Params();
    params.height = 400;
    params.top = 20;
    params.bottom = 20;
    params.axisWidth = 40;
    params.columnWidth = 50;
    params.columnGap = 10;
    params.minTickSpacing = minTickSpacing;
    params.maxPixelsPerMinute = 20;
    params.minBlockHeight = 6;
    return params;
  }

  /** Walk 5 min, ride 20 min, walk 5 min. */
  @NonNull
  private static ItineraryTimeline connection(int startMinute)
  {
    return timeline(min(startMinute), min(startMinute + 30), walk(min(startMinute), min(startMinute + 5)),
                    ride(min(startMinute + 5), min(startMinute + 25)),
                    walk(min(startMinute + 25), min(startMinute + 30)));
  }

  @NonNull
  private static List<ItineraryTimeline> connections(int... startMinutes)
  {
    final List<ItineraryTimeline> result = new ArrayList<>();
    for (int minute : startMinutes)
      result.add(connection(minute));
    return result;
  }

  @NonNull
  private static List<Long> tickTimes(@NonNull TimeGridLayout layout)
  {
    final List<Long> result = new ArrayList<>();
    for (TimeGridLayout.Tick tick : layout.getTicks())
      result.add(tick.getTime());
    return result;
  }

  @Test
  public void rangeFollowsTheConnections()
  {
    // 16:10-16:40 and 16:20-16:50; now is long before.
    final TimeGridLayout layout = TimeGridLayout.compute(connections(10, 20), min(-60), BERLIN, params(30));
    assertEquals(min(10), layout.getRangeStart());
    assertEquals(min(50), layout.getRangeEnd());
    // 360 px for 40 min.
    assertEquals(9.0, layout.getPixelsPerMinute(), 1e-9);
    assertNull(layout.getNowY());
  }

  @Test
  public void rangeIsRoundedToFiveMinutes()
  {
    final List<ItineraryTimeline> timelines =
        Collections.singletonList(timeline(min(12), min(47), ride(min(12), min(47))));
    final TimeGridLayout layout = TimeGridLayout.compute(timelines, min(-60), BERLIN, params(30));
    assertEquals(min(10), layout.getRangeStart());
    assertEquals(min(50), layout.getRangeEnd());
  }

  @Test
  public void nowShortlyBeforeTheFirstDepartureIsIncluded()
  {
    final TimeGridLayout layout = TimeGridLayout.compute(connections(10, 20), min(-2), BERLIN, params(30));
    assertEquals(min(-5), layout.getRangeStart());
    assertEquals(Integer.valueOf(layout.y(min(-2))), layout.getNowY());
    // Too early: not included.
    assertNull(TimeGridLayout.compute(connections(10, 20), min(-6), BERLIN, params(30)).getNowY());
  }

  @Test
  public void nowLineInsideAndAfterTheRange()
  {
    final TimeGridLayout layout = TimeGridLayout.compute(connections(10, 20), min(30), BERLIN, params(30));
    assertEquals(min(10), layout.getRangeStart());
    assertEquals(Integer.valueOf(20 + 20 * 9), layout.getNowY());
    assertNull(TimeGridLayout.compute(connections(10, 20), min(51), BERLIN, params(30)).getNowY());
  }

  @Test
  public void ticksDependOnTheScale()
  {
    // 9 px per minute: every 5 min is 45 px.
    TimeGridLayout layout = TimeGridLayout.compute(connections(10, 20), min(-60), BERLIN, params(30));
    assertEquals(5, layout.getTickMinutes());
    assertEquals(9, layout.getTicks().size());
    assertEquals(min(10), layout.getTicks().get(0).getTime());
    assertEquals(20, layout.getTicks().get(0).getY());
    assertEquals(65, layout.getTicks().get(1).getY());

    // At least 50 px between ticks: every 10 min.
    layout = TimeGridLayout.compute(connections(10, 20), min(-60), BERLIN, params(50));
    assertEquals(10, layout.getTickMinutes());
    assertEquals(List.of(min(10), min(20), min(30), min(40), min(50)), tickTimes(layout));
    assertEquals(380, layout.getTicks().get(4).getY());
    assertEquals(t("2026-10-25T16:10:00Z"), layout.getTicks().get(0).getInstant());

    // Very dense: hours.
    layout = TimeGridLayout.compute(connections(10, 20), min(-60), BERLIN, params(400));
    assertEquals(60, layout.getTickMinutes());
    assertTrue(layout.getTicks().isEmpty());
  }

  @Test
  public void ticksAreOnLocalClockTimes()
  {
    // India is UTC+5:30: 16:07 UTC is 21:37 local.
    final ZoneId india = ZoneId.of("Asia/Kolkata");
    final List<ItineraryTimeline> timelines =
        Collections.singletonList(timeline(min(7), min(67), ride(min(7), min(67))));
    final TimeGridLayout layout = TimeGridLayout.compute(timelines, min(-60), india, params(100));
    // 21:35 to 22:40 local.
    assertEquals(min(5), layout.getRangeStart());
    assertEquals(min(70), layout.getRangeEnd());
    assertEquals(20, layout.getTickMinutes());
    // 21:40, 22:00, 22:20, 22:40 local.
    assertEquals(List.of(min(10), min(30), min(50), min(70)), tickTimes(layout));
  }

  @Test
  public void localRounding()
  {
    final ZoneId india = ZoneId.of("Asia/Kolkata");
    assertEquals(min(0), TimeGridLayout.floorLocal(min(3), 300, BERLIN));
    assertEquals(min(5), TimeGridLayout.ceilLocal(min(3), 300, BERLIN));
    assertEquals(min(5), TimeGridLayout.ceilLocal(min(5), 300, BERLIN));
    // Hours start at :30 UTC in India.
    assertEquals(min(-30), TimeGridLayout.floorLocal(min(10), 3600, india));
    assertEquals(min(30), TimeGridLayout.ceilLocal(min(10), 3600, india));
  }

  @Test
  public void columnsSideBySide()
  {
    final TimeGridLayout layout = TimeGridLayout.compute(connections(10, 20), min(-60), BERLIN, params(30));
    assertEquals(2, layout.getColumns().size());
    final TimeGridLayout.Column first = layout.getColumns().get(0);
    final TimeGridLayout.Column second = layout.getColumns().get(1);
    assertEquals(50, first.getLeft());
    assertEquals(100, first.getRight());
    assertEquals(75, first.getCenterX());
    assertEquals(110, second.getLeft());
    assertEquals(160, second.getRight());
    assertEquals(170, layout.getContentWidth());

    assertEquals(0, layout.columnAt(75));
    assertEquals(0, layout.columnAt(104));
    assertEquals(1, layout.columnAt(106));
    assertEquals(1, layout.columnAt(164));
    assertEquals(-1, layout.columnAt(20));
    assertEquals(-1, layout.columnAt(200));
  }

  @Test
  public void blocksAtTheirTimes()
  {
    final TimeGridLayout layout = TimeGridLayout.compute(connections(10, 20), min(-60), BERLIN, params(30));
    final TimeGridLayout.Column first = layout.getColumns().get(0);
    assertEquals(20, first.getTop());
    assertEquals(290, first.getBottom());
    assertEquals(3, first.getBlocks().size());
    final TimeGridLayout.Block walk = first.getBlocks().get(0);
    final TimeGridLayout.Block ride = first.getBlocks().get(1);
    assertEquals(ItineraryTimeline.Kind.WALK, walk.getKind());
    assertEquals(20, walk.getTop());
    assertEquals(65, walk.getBottom());
    assertEquals(ItineraryTimeline.Kind.RIDE, ride.getKind());
    assertEquals(65, ride.getTop());
    assertEquals(245, ride.getBottom());
    assertEquals(180, ride.getHeight());
    assertEquals("S5", ride.getBadge().getText());
    // The second connection is 10 min = 90 px later.
    assertEquals(110, layout.getColumns().get(1).getTop());
  }

  @Test
  public void delayedRideIsDrawnAtItsExpectedTime()
  {
    // S5 2 min late: boards at 16:04 instead of 16:02.
    final List<ItineraryTimeline> timelines = Collections.singletonList(ItineraryTimeline.of(Fixtures.s5("a", 0)));
    final TimeGridLayout layout = TimeGridLayout.compute(timelines, min(-60), BERLIN, params(30));
    final TimeGridLayout.Block ride = layout.getColumns().get(0).getBlocks().get(1);
    assertEquals(layout.y(min(4)), ride.getTop());
    assertEquals(layout.y(min(14)), ride.getBottom());
    assertEquals(layout.y(min(17)), layout.getColumns().get(0).getBottom());
  }

  @Test
  public void veryShortRideGetsMinimumHeight()
  {
    final List<ItineraryTimeline> timelines = Collections.singletonList(
        timeline(min(10), min(50), walk(min(10), min(30)), ride(min(30), min(30) + 20), walk(min(30) + 20, min(50))));
    final TimeGridLayout layout = TimeGridLayout.compute(timelines, min(-60), BERLIN, params(30));
    final TimeGridLayout.Block ride = layout.getColumns().get(0).getBlocks().get(1);
    assertEquals(layout.y(min(30)), ride.getTop());
    assertEquals(6, ride.getHeight());
  }

  @Test
  public void shortRideAtTheEndStaysInside()
  {
    final List<ItineraryTimeline> timelines =
        Collections.singletonList(timeline(min(10), min(50), walk(min(10), min(50) - 10), ride(min(50) - 10, min(50))));
    final TimeGridLayout layout = TimeGridLayout.compute(timelines, min(-60), BERLIN, params(30));
    final TimeGridLayout.Block ride = layout.getColumns().get(0).getBlocks().get(1);
    assertEquals(layout.y(min(50)), ride.getBottom());
    assertEquals(6, ride.getHeight());
  }

  @Test
  public void shortConnectionsAreNotStretched()
  {
    // 10 min would be 36 px per minute; the maximum is 20.
    final List<ItineraryTimeline> timelines =
        Collections.singletonList(timeline(min(10), min(20), ride(min(10), min(20))));
    final TimeGridLayout layout = TimeGridLayout.compute(timelines, min(-60), BERLIN, params(30));
    assertEquals(20.0, layout.getPixelsPerMinute(), 1e-9);
    assertEquals(min(10), layout.getRangeStart());
    // 360 px show 18 min.
    assertEquals(min(28), layout.getRangeEnd());
    assertEquals(20 + 200, layout.getColumns().get(0).getBottom());
  }

  @Test
  public void withoutConnectionsTheNextHourIsShown()
  {
    final TimeGridLayout layout = TimeGridLayout.compute(Collections.emptyList(), min(3), BERLIN, params(30));
    assertTrue(layout.getColumns().isEmpty());
    assertEquals(min(0), layout.getRangeStart());
    assertEquals(min(65), layout.getRangeEnd());
    assertEquals(50, layout.getContentWidth());
    assertEquals(Integer.valueOf(layout.y(min(3))), layout.getNowY());
    assertEquals(-1, layout.columnAt(60));
  }

  @Test
  public void earlierConnectionsAddAColumnInFront()
  {
    final TimeGridLayout before = TimeGridLayout.compute(connections(10, 20), min(-60), BERLIN, params(30));
    final TimeGridLayout after = TimeGridLayout.compute(connections(-20, 10, 20), min(-60), BERLIN, params(30));
    assertEquals(min(-20), after.getRangeStart());
    assertEquals(3, after.getColumns().size());
    assertEquals(before.getContentWidth() + 60, after.getContentWidth());
    // The former first connection is now the second column, at the same time but with a smaller scale.
    assertEquals(110, after.getColumns().get(1).getLeft());
    assertEquals(after.y(min(10)), after.getColumns().get(1).getTop());
    assertTrue(after.getPixelsPerMinute() < before.getPixelsPerMinute());
    assertEquals(1, after.columnAt(130));
  }
}
