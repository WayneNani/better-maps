package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.itinerary;
import static app.organicmaps.sdk.transit.TransitTestData.leg;
import static app.organicmaps.sdk.transit.TransitTestData.place;
import static app.organicmaps.sdk.transit.TransitTestData.t;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/** Itineraries resembling Berlin Hbf -> Alexanderplatz. */
final class Fixtures
{
  static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  private Fixtures() {}

  @NonNull
  static TransitFormatter formatter()
  {
    return new TransitFormatter(Locale.GERMANY, BERLIN, true, new FakeTexts());
  }

  /** Walk 2 min, S5 from track 15 (+2 min, realtime), walk 3 min. Departs at the given minute after 17:00. */
  @NonNull
  static TransitItinerary s5(@NonNull String id, int minute)
  {
    final Instant start = t("2026-10-25T16:00:00Z").plusSeconds(minute * 60L);
    final Instant board = start.plusSeconds(120);
    final Instant alight = board.plusSeconds(600);
    return itinerary(
        id,
        leg(TransitMode.WALK)
            .from(place("START").build())
            .to(place("S+U Berlin Hauptbahnhof").stop("hbf").build())
            .times(start, board)
            .distance(140.0)
            .build(),
        leg(TransitMode.SUBURBAN)
            .from(place("S+U Berlin Hauptbahnhof")
                      .stop("hbf")
                      .departure(board.plusSeconds(120), board)
                      .track("15", "15")
                      .build())
            .to(place("S+U Alexanderplatz").stop("alex").arrival(alight.plusSeconds(120), alight).build())
            .times(board, alight)
            .delay(120, 120)
            .line("S5", "eb7405", "ffffff")
            .direction("S Mahlsdorf")
            .stops(place("S Friedrichstraße").departure(board.plusSeconds(300), board.plusSeconds(180)).build(),
                   place("S Hackescher Markt").departure(board.plusSeconds(420), board.plusSeconds(300)).build())
            .build(),
        leg(TransitMode.WALK)
            .from(place("S+U Alexanderplatz").stop("alex").build())
            .to(place("END").build())
            .times(alight.plusSeconds(120), alight.plusSeconds(300))
            .distance(230.0)
            .build());
  }

  /** Bus 100 (no realtime) with a transfer to U2 (on time); the transfer walk takes 30 s. */
  @NonNull
  static TransitItinerary busAndSubway(@NonNull String id)
  {
    final Instant start = t("2026-10-25T16:10:00Z");
    return itinerary(id,
                     leg(TransitMode.BUS)
                         .from(place("Hauptbahnhof").stop("b1").build())
                         .to(place("Unter den Linden").stop("b2").build())
                         .times(start, start.plusSeconds(480))
                         .line("100", null, null)
                         .direction("Alexanderplatz")
                         .build(),
                     leg(TransitMode.WALK)
                         .from(place("Unter den Linden").stop("b2").build())
                         .to(place("U Unter den Linden").stop("u1").build())
                         .times(start.plusSeconds(480), start.plusSeconds(510))
                         .build(),
                     leg(TransitMode.SUBWAY)
                         .from(place("U Unter den Linden").stop("u1").track("2", "1").build())
                         .to(place("U Alexanderplatz").stop("u2").build())
                         .times(start.plusSeconds(600), start.plusSeconds(900))
                         .delay(0, 0)
                         .line("U5", "7e5330", null)
                         .direction("Hönow")
                         .build());
  }
}
