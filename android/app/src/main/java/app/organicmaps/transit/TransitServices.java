package app.organicmaps.transit;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import app.organicmaps.BuildConfig;
import app.organicmaps.sdk.transit.OnlineTransitService;
import app.organicmaps.sdk.transit.TransitService;
import app.organicmaps.sdk.transit.TransitServiceConfig;

/** Application-wide access to the online public transport service (MOTIS, by default Transitous). */
public final class TransitServices
{
  /** Contact for the server operator, required by the Transitous usage policy. */
  private static final String CONTACT_URL = "https://github.com/WayneNani/better-maps";

  private static TransitService sInstance;

  private TransitServices() {}

  @NonNull
  public static synchronized TransitService get(@NonNull Context context)
  {
    if (sInstance == null)
    {
      // TODO(AP3b): make the server URL configurable in the settings (own MOTIS server, AP3d).
      final TransitServiceConfig config =
          TransitServiceConfig.builder(getUserAgent()).setBaseUrl(TransitServiceConfig.TRANSITOUS_URL).build();
      sInstance = new OnlineTransitService(config, ContextCompat.getMainExecutor(context.getApplicationContext()));
    }
    return sInstance;
  }

  @NonNull
  static String getUserAgent()
  {
    return "better-maps/" + BuildConfig.VERSION_NAME + " (CoMaps fork; +" + CONTACT_URL + ")";
  }
}
