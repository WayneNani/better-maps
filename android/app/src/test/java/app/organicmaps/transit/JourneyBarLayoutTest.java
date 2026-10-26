package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class JourneyBarLayoutTest
{
  private static final LineBadge S5 = LineBadge.create("S5", TransitMode.SUBURBAN, "eb7405", null);
  private static final LineBadge U2 = LineBadge.create("U2", TransitMode.SUBWAY, null, null);

  @NonNull
  static ItineraryTimeline.Segment ride(long start, long end)
  {
    return new ItineraryTimeline.Segment(ItineraryTimeline.Kind.RIDE, start, end, S5, false);
  }

  @NonNull
  static ItineraryTimeline.Segment walk(long start, long end)
  {
    return new ItineraryTimeline.Segment(ItineraryTimeline.Kind.WALK, start, end, null, false);
  }

  @NonNull
  static ItineraryTimeline timeline(long start, long end, @NonNull ItineraryTimeline.Segment... segments)
  {
    return new ItineraryTimeline(start, end, new ArrayList<>(Arrays.asList(segments)));
  }

  @NonNull
  private static String describe(@NonNull List<JourneyBarLayout.Piece> pieces)
  {
    return pieces.toString();
  }

  @Test
  public void ridesAreProportionalAndWaitsStayEmpty()
  {
    // 10 min ride, 5 min wait, 5 min ride.
    final List<JourneyBarLayout.Piece> pieces =
        JourneyBarLayout.layout(timeline(0, 1200, ride(0, 600), ride(900, 1200)), 300, 6, 2);
    assertEquals("[RIDE 0..150, RIDE 225..300]", describe(pieces));
    assertSame(S5, pieces.get(0).getBadge());
    assertEquals(150, pieces.get(0).getWidth());
  }

  @Test
  public void walksAreDottedPiecesBetweenRides()
  {
    // The S5 fixture: walk 2 min, wait 2 min (the train is 2 min late), ride 10 min, walk 3 min = 17 min.
    final ItineraryTimeline timeline = ItineraryTimeline.of(Fixtures.s5("a", 0));
    assertEquals(1020, timeline.getDuration());
    final List<JourneyBarLayout.Piece> pieces = JourneyBarLayout.layout(timeline, 1020, 6, 2);
    assertEquals("[WALK 0..120, RIDE 240..840, WALK 840..1020]", describe(pieces));
    assertEquals("S5", pieces.get(1).getBadge().getText());
    assertEquals(0xFFEB7405, pieces.get(1).getBadge().getBackgroundColor());
  }

  @Test
  public void transferWalkAndWait()
  {
    // Bus 8 min, walk 30 s, wait 90 s, U5 5 min.
    final ItineraryTimeline timeline = ItineraryTimeline.of(Fixtures.busAndSubway("b"));
    final List<JourneyBarLayout.Piece> pieces = JourneyBarLayout.layout(timeline, 900, 6, 2);
    assertEquals("[RIDE 0..480, WALK 480..510, RIDE 600..900]", describe(pieces));
    assertEquals("100", pieces.get(0).getBadge().getText());
    assertEquals("U5", pieces.get(2).getBadge().getText());
  }

  @Test
  public void roundingFillsTheWidthExactly()
  {
    // Three equal rides without waits do not divide 100 px evenly.
    final ItineraryTimeline timeline = timeline(0, 300, ride(0, 100), ride(100, 200), ride(200, 300));
    assertEquals("[RIDE 0..33, RIDE 33..67, RIDE 67..100]", describe(JourneyBarLayout.layout(timeline, 100, 6, 0)));
    // With a gap, the earlier ride gives way; the bar still ends at the full width.
    assertEquals("[RIDE 0..31, RIDE 33..65, RIDE 67..100]", describe(JourneyBarLayout.layout(timeline, 100, 6, 2)));
  }

  @Test
  public void veryShortRideGetsMinimumWidth()
  {
    // A 30 s ride in a 1 h connection would be 2.5 px wide.
    final List<JourneyBarLayout.Piece> pieces =
        JourneyBarLayout.layout(timeline(0, 3600, walk(0, 1800), ride(1800, 1830), walk(1830, 3600)), 300, 8, 2);
    assertEquals("[WALK 0..148, RIDE 148..156, WALK 156..300]", describe(pieces));
  }

  @Test
  public void shortRidesAtTheEdgesStayInside()
  {
    final ItineraryTimeline timeline = timeline(0, 3600, ride(0, 10), ride(3590, 3600));
    assertEquals("[RIDE 0..8, RIDE 292..300]", describe(JourneyBarLayout.layout(timeline, 300, 8, 2)));
  }

  @Test
  public void shortRidesInARowPushEachOtherAside()
  {
    final ItineraryTimeline timeline =
        timeline(0, 3600, ride(0, 1800), ride(1800, 1810), ride(1810, 1820), ride(1820, 3600));
    final List<JourneyBarLayout.Piece> pieces = JourneyBarLayout.layout(timeline, 300, 8, 2);
    assertEquals(4, pieces.size());
    int previousRight = -2;
    for (JourneyBarLayout.Piece piece : pieces)
    {
      assertTrue(piece.toString(), piece.getWidth() >= 8);
      assertTrue(piece.toString(), piece.getLeft() >= previousRight + 2);
      previousRight = piece.getRight();
    }
    assertEquals(300, previousRight);
  }

  @Test
  public void tooManyRidesForTheWidthStayInside()
  {
    final ItineraryTimeline.Segment[] rides = new ItineraryTimeline.Segment[20];
    for (int i = 0; i < rides.length; ++i)
      rides[i] = ride(i * 60, i * 60 + 60);
    final List<JourneyBarLayout.Piece> pieces = JourneyBarLayout.layout(timeline(0, 1200, rides), 100, 8, 2);
    int previousRight = 0;
    for (JourneyBarLayout.Piece piece : pieces)
    {
      assertTrue(piece.toString(), piece.getLeft() >= previousRight);
      assertTrue(piece.toString(), piece.getRight() <= 100);
      assertTrue(piece.toString(), piece.getWidth() > 0);
      previousRight = piece.getRight();
    }
    // Every ride is still visible.
    assertEquals(20, pieces.size());
  }

  @Test
  public void walksDoNotOverlapWidenedRides()
  {
    final List<JourneyBarLayout.Piece> pieces =
        JourneyBarLayout.layout(timeline(0, 3600, walk(0, 1800), ride(1800, 1810), walk(1810, 3600)), 360, 20, 0);
    assertEquals("[WALK 0..171, RIDE 171..191, WALK 191..360]", describe(pieces));
  }

  @Test
  public void walkOnlyConnectionIsOneDottedLine()
  {
    final List<JourneyBarLayout.Piece> pieces = JourneyBarLayout.layout(timeline(0, 900, walk(0, 900)), 250, 6, 2);
    assertEquals("[WALK 0..250]", describe(pieces));
  }

  @Test
  public void nothingWithoutDurationOrWidth()
  {
    assertTrue(JourneyBarLayout.layout(timeline(100, 100), 300, 6, 2).isEmpty());
    assertTrue(JourneyBarLayout.layout(timeline(0, 600, ride(0, 600)), 0, 6, 2).isEmpty());
  }

  @Test
  public void timelineOfItinerary()
  {
    final ItineraryTimeline timeline = ItineraryTimeline.of(Fixtures.busAndSubway("b"));
    assertEquals(3, timeline.getSegments().size());
    assertEquals(ItineraryTimeline.Kind.RIDE, timeline.getSegments().get(0).getKind());
    assertEquals(ItineraryTimeline.Kind.WALK, timeline.getSegments().get(1).getKind());
    assertEquals(30, timeline.getSegments().get(1).getDuration());
    // The bus has no color: the neutral bus color.
    assertEquals(LineBadge.defaultColor(TransitMode.BUS),
                 timeline.getSegments().get(0).getBadge().getBackgroundColor());
    assertFalse(timeline.getSegments().get(0).isCancelled());
    assertEquals(U2.getMode(), timeline.getSegments().get(2).getBadge().getMode());
  }

  @Test
  public void labelFitsOnlyWithPadding()
  {
    assertTrue(JourneyBarLayout.fitsLabel(40, 20f, 10));
    assertFalse(JourneyBarLayout.fitsLabel(40, 20.5f, 10));
    assertFalse(JourneyBarLayout.fitsLabel(10, 4f, 4));
  }

  @Test
  public void shorterLabelsForLongLineNames()
  {
    assertEquals(List.of("S5"), JourneyBarLayout.labelCandidates("S5"));
    assertEquals(List.of("RE8 (62012)", "RE8"), JourneyBarLayout.labelCandidates("RE8 (62012)"));
    assertEquals(List.of("FlixTrain FLX10", "FLX10"), JourneyBarLayout.labelCandidates("FlixTrain FLX10"));
    assertEquals(List.of("ICE 1601 (Hamburg)", "ICE 1601", "1601"),
                 JourneyBarLayout.labelCandidates("ICE 1601 (Hamburg)"));
    assertEquals(List.of("(x)"), JourneyBarLayout.labelCandidates("(x)"));
  }
}
