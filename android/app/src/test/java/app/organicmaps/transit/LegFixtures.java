package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.itinerary;
import static app.organicmaps.sdk.transit.TransitTestData.leg;
import static app.organicmaps.sdk.transit.TransitTestData.place;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import app.organicmaps.sdk.transit.TransitMode;
import app.organicmaps.sdk.transit.TransitPlace;
import app.organicmaps.sdk.transit.TransitTestData;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Connections like Bonn-Oberkassel -> Köln Hbf: walk 13 min, tram 63 09:29-09:39 to Bonn Hbf, walk 7 min, ICE 1022
 * 09:44 (+3, i.e. 09:47) - 10:06, walk 2 min. Times are local (Europe/Berlin) on 2026-11-25.
 */
final class LegFixtures
{
  static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  static final double START_LAT = 50.7068;
  static final double START_LON = 7.1406;

  private LegFixtures() {}

  /** Local time "hh:mm" on the test day. */
  @NonNull
  static Instant at(@NonNull String hhmm)
  {
    return LocalDateTime.parse("2026-11-25T" + hhmm).atZone(BERLIN).toInstant();
  }

  @NonNull
  static TransitPlace start()
  {
    return place("START").at(START_LAT, START_LON).build();
  }

  @NonNull
  static TransitPlace end()
  {
    return place("END").at(50.9430, 6.9589).build();
  }

  @NonNull
  static TransitPlace stop(@NonNull String name, @NonNull String id)
  {
    return place(name).stop(id).at(50.73, 7.1).build();
  }

  @NonNull
  static TransitLeg walk(@NonNull TransitPlace from, @NonNull TransitPlace to, @NonNull String start,
                         @NonNull String end)
  {
    return leg(TransitMode.WALK).from(from).to(to).times(at(start), at(end)).distance(500.0).build();
  }

  @NonNull
  static TransitLeg ride(@NonNull TransitMode mode, @NonNull String line, @Nullable String trip,
                         @NonNull TransitPlace from, @NonNull TransitPlace to, @NonNull String start,
                         @NonNull String end)
  {
    final TransitTestData.LegBuilder builder =
        leg(mode).from(from).to(to).times(at(start), at(end)).line(line, "8FCBEA", "0B2A3A").direction("Somewhere");
    final TransitLeg leg = builder.build();
    if (trip != null)
      TransitTestData.tripId(leg, trip);
    return leg;
  }

  static TransitPlace olofPalme()
  {
    return stop("Bonn Gronau Olof-Palme-Allee", "opa");
  }

  static TransitPlace bonnTram()
  {
    return stop("Bonn Hbf", "bhf-tram");
  }

  static TransitPlace bonnTrain()
  {
    return stop("Bonn Hbf", "bhf");
  }

  static TransitPlace koeln()
  {
    return stop("Köln Hbf", "koeln");
  }

  /** The found connection: 63 at 09:29 with a tight transfer to the late ICE 1022. */
  @NonNull
  static TransitItinerary connection()
  {
    final TransitLeg ice = leg(TransitMode.HIGHSPEED_RAIL)
                               .from(bonnTrain())
                               .to(koeln())
                               .times(at("09:44"), at("10:03"))
                               .delay(180, 180)
                               .line("ICE 1022", "45494E", "FFFFFF")
                               .direction("Essen Hbf")
                               .build();
    TransitTestData.tripId(ice, "ice-1022");
    return itinerary("found", walk(start(), olofPalme(), "09:16", "09:29"),
                     ride(TransitMode.TRAM, "63", "63-0929", olofPalme(), bonnTram(), "09:29", "09:39"),
                     walk(bonnTram(), bonnTrain(), "09:39", "09:46"), ice, walk(koeln(), end(), "10:06", "10:08"));
  }

  /** A direct alternative from the start to the tram stop at Bonn Hbf: a 13 min walk and a 10 min ride. */
  @NonNull
  static TransitItinerary firstAlternative(@NonNull String line, @NonNull String departure)
  {
    final Instant dep = at(departure);
    final String walkStart = local(dep.minusSeconds(13 * 60));
    final String arrival = local(dep.plusSeconds(10 * 60));
    return itinerary("alt-" + line + "-" + departure, walk(start(), olofPalme(), walkStart, departure),
                     ride(TransitMode.TRAM, line, line + "-" + departure.replace(":", ""), olofPalme(), bonnTram(),
                          departure, arrival));
  }

  /** "hh:mm" of an instant in Berlin. */
  @NonNull
  static String local(@NonNull Instant time)
  {
    return time.atZone(BERLIN).toLocalTime().toString();
  }

  /** The rest from Bonn Hbf after missing the ICE: RE 5 at the given time for 23 minutes, then the walk. */
  @NonNull
  static TransitItinerary rest(@NonNull String departure)
  {
    final Instant dep = at(departure);
    final String arrival = local(dep.plusSeconds(23 * 60));
    final String end = local(dep.plusSeconds(25 * 60));
    return itinerary("rest-" + departure, walk(bonnTram(), bonnTrain(), local(dep.minusSeconds(5 * 60)), departure),
                     ride(TransitMode.REGIONAL_RAIL, "RE 5", "re5-" + departure, bonnTrain(), koeln(), departure,
                          arrival),
                     walk(koeln(), end(), arrival, end));
  }
}
