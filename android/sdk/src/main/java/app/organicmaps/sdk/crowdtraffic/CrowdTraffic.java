package app.organicmaps.sdk.crowdtraffic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.log.Logger;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Anonymous contribution to the congestion detection (Feature 5, docs/CROWD_TRAFFIC_PRIVACY.md).
 * <p>
 * The native client (libs/crowd_traffic, connected to the navigation in libs/map/crowd_traffic_recorder)
 * turns the route-matched positions of a car navigation into anonymous reports and prepares one bundle per
 * 15-minute window. This class connects the settings, the scheduler and the OHTTP sender.
 */
public final class CrowdTraffic
{
  private static final String TAG = CrowdTraffic.class.getSimpleName();

  /** Shown in the settings. */
  public static final String PRIVACY_URL =
      "https://github.com/WayneNani/better-maps/blob/main/docs/CROWD_TRAFFIC_PRIVACY.md";

  @Nullable
  private static CrowdTraffic sInstance;

  @NonNull
  private final CrowdTrafficSettings mSettings;
  @NonNull
  private final CrowdTrafficScheduler mScheduler;

  private CrowdTraffic()
  {
    final CrowdTrafficSender.Log log = message -> Logger.i(TAG, message);
    final CrowdTrafficSender.Clock clock = System::currentTimeMillis;
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread t = new Thread(r, "CrowdTraffic");
      t.setDaemon(true);
      return t;
    });
    final CrowdTrafficScheduler.NativeQueue queue = new CrowdTrafficScheduler.NativeQueue() {
      @NonNull
      @Override
      public CrowdTrafficRequest[] takeDue(long nowMs)
      {
        return nativeTakeDueRequests(nowMs);
      }

      @Override
      public boolean hasPendingWork()
      {
        return nativeHasPendingWork();
      }
    };
    mScheduler = new CrowdTrafficScheduler(executor, queue, clock, new SecureRandom(), log);
    mSettings = new CrowdTrafficSettings(new ConfigStore(), (active, endpoints) -> {
      Logger.i(TAG, "Anonymous congestion reports " + (active ? "active" : "inactive"));
      nativeSetActive(active);
      mScheduler.setTransport(active && endpoints != null ? new CrowdTrafficSender(endpoints, clock, log)::send : null);
    });
  }

  /** Called once after the native core has been initialized. */
  public static void initialize()
  {
    if (sInstance != null)
      return;
    sInstance = new CrowdTraffic();
    sInstance.mSettings.notifyListener();
  }

  @NonNull
  public static CrowdTrafficSettings getSettings()
  {
    if (sInstance == null)
      throw new IllegalStateException("CrowdTraffic is not initialized");
    return sInstance.mSettings;
  }

  public static void onNavigationStarted()
  {
    if (sInstance != null)
      sInstance.mScheduler.onNavigationStarted();
  }

  public static void onNavigationStopped()
  {
    if (sInstance != null)
      sInstance.mScheduler.onNavigationStopped();
  }

  private static final class ConfigStore implements CrowdTrafficSettings.Store
  {
    @Override
    public boolean getBoolean(@NonNull String key, boolean def)
    {
      return Config.getCrowdTrafficBoolean(key, def);
    }

    @Override
    public void setBoolean(@NonNull String key, boolean value)
    {
      Config.setCrowdTrafficBoolean(key, value);
    }

    @NonNull
    @Override
    public String getString(@NonNull String key, @NonNull String def)
    {
      return Config.getCrowdTrafficString(key, def);
    }

    @Override
    public void setString(@NonNull String key, @NonNull String value)
    {
      Config.setCrowdTrafficString(key, value);
    }
  }

  private static native void nativeSetActive(boolean active);

  private static native boolean nativeHasPendingWork();

  @NonNull
  private static native CrowdTrafficRequest[] nativeTakeDueRequests(long nowMs);
}
