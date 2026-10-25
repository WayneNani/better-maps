package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Recorded responses of the Transitous API (MOTIS v2.11.4, API v6), fetched once on 2026-10-25 and stored in
 * src/test/resources/transit:
 * <ul>
 * <li>plan_hbf_alex.json: /api/v6/plan?fromPlace=52.5250,13.3694&toPlace=52.5219,13.4132&numItineraries=5
 * &useRoutedTransfers=true&detailedTransfers=true (Berlin Hbf -> Alexanderplatz)</li>
 * <li>plan_hbf_alex_next.json: the same query with the nextPageCursor of plan_hbf_alex.json, trimmed to the
 * first two itineraries to keep the fixture small</li>
 * <li>plan_stopid_arriveby.json: fromPlace=de-VBB_de:11000:900100003 (stop id), toPlace=Potsdam,
 * arriveBy=true, time=2026-10-25T18:00:00Z, pedestrianProfile=WHEELCHAIR, transitModes=...</li>
 * <li>plan_regional.json: Berlin Hbf -> Cottbus with transfers, an early arrival (-1 min) of RE2</li>
 * <li>plan_empty.json: a query in the Atlantic Ocean, no itineraries</li>
 * <li>stoptimes_alex.json: /api/v6/stoptimes?stopId=de-VBB_de:11000:900100003&n=20, S7 delayed by 1 min</li>
 * <li>reverse_geocode_alex.json, geocode_hbf.json: /api/v1/(reverse-)geocode with type=STOP</li>
 * <li>error_bad_place.json (HTTP 404), error_bad_request.json (HTTP 400)</li>
 * </ul>
 */
final class Fixtures
{
  private Fixtures() {}

  @NonNull
  static String read(@NonNull String name)
  {
    try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("transit/" + name))
    {
      if (in == null)
        throw new IllegalArgumentException("Missing fixture " + name);
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buffer = new byte[16384];
      int n;
      while ((n = in.read(buffer)) != -1)
        out.write(buffer, 0, n);
      return out.toString(StandardCharsets.UTF_8.name());
    }
    catch (IOException e)
    {
      throw new IllegalStateException(e);
    }
  }
}
