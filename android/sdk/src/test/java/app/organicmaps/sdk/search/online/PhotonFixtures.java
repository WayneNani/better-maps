package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Recorded responses of the public Photon instance (photon.komoot.io), fetched once on 2026-10-25 with the
 * better-maps User-Agent and stored in src/test/resources/photon:
 * <ul>
 * <li>search_marienplatz_muenchen_de.json: /api?q=Marienplatz München&lat=52.52&lon=13.40&limit=10&lang=de</li>
 * <li>search_marienplatz_en.json: /api?q=Marienplatz&lat=48.14&lon=11.58&limit=10&lang=en (street, square and a
 * station without extent)</li>
 * <li>search_address_leopoldstrasse.json: /api?q=Leopoldstraße 10 München&limit=3&lang=de (an address without
 * name)</li>
 * <li>search_empty.json: /api?q=xqzvbnwplkjh&limit=10&lang=de (nothing found)</li>
 * <li>error_unsupported_language.json: /api?q=Marienplatz&lang=xx, HTTP 400</li>
 * </ul>
 * The files are also assets of the instrumented tests of the app (see app/build.gradle.kts).
 */
public final class PhotonFixtures
{
  private PhotonFixtures() {}

  @NonNull
  public static String read(@NonNull String name)
  {
    try (InputStream in = PhotonFixtures.class.getClassLoader().getResourceAsStream("photon/" + name))
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
