package app.organicmaps.search.online;

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import app.organicmaps.BuildConfig;
import app.organicmaps.sdk.search.online.OnlineSearchProvider;
import app.organicmaps.sdk.search.online.OnlineSearchService;
import app.organicmaps.sdk.search.online.PhotonConfig;
import app.organicmaps.sdk.search.online.PhotonSearchProvider;
import app.organicmaps.sdk.util.concurrency.Debouncer;
import app.organicmaps.transit.KeyValueStore;
import java.util.concurrent.Executor;

/**
 * Application-wide access to the online search: settings, consent and the {@link OnlineSearchService} with the
 * Photon provider. All online requests go through the service, which sends nothing without the user's consent.
 */
public final class OnlineSearchServices
{
  /** Contact for server operators. */
  private static final String CONTACT_URL = "https://github.com/WayneNani/better-maps";

  // Holds the application context only.
  @SuppressLint("StaticFieldLeak")
  private static OnlineSearchServices sInstance;

  @NonNull
  private final Executor mMainExecutor;
  @NonNull
  private final OnlineSearchSettings mSettings;
  @NonNull
  private final OnlineSearchConsent mConsent;
  @NonNull
  private final OnlineSearchService mService;
  @NonNull
  private String mServerUrl = "";
  @NonNull
  private RegionInfo.Lookup mRegions = new MapManagerRegionLookup();

  private OnlineSearchServices(@NonNull Context context)
  {
    final Context appContext = context.getApplicationContext();
    mMainExecutor = ContextCompat.getMainExecutor(appContext);
    final KeyValueStore store =
        KeyValueStore.of(appContext.getSharedPreferences(OnlineSearchSettings.PREFS_NAME, Context.MODE_PRIVATE));
    mSettings = new OnlineSearchSettings(store);
    mConsent = new OnlineSearchConsent(store);
    mService = new OnlineSearchService(mMainExecutor, System::currentTimeMillis, mConsent::isGranted);
    registerProvider();
  }

  @NonNull
  public static synchronized OnlineSearchServices get(@NonNull Context context)
  {
    if (sInstance == null)
      sInstance = new OnlineSearchServices(context);
    return sInstance;
  }

  @NonNull
  public OnlineSearchSettings getSettings()
  {
    return mSettings;
  }

  @NonNull
  public OnlineSearchConsent getConsent()
  {
    return mConsent;
  }

  @NonNull
  public OnlineSearchService getService()
  {
    return mService;
  }

  /** A presenter for one search screen. */
  @MainThread
  @NonNull
  public OnlineSearchPresenter createPresenter()
  {
    return new OnlineSearchPresenter(mService, mConsent, mRegions,
                                     Debouncer.onMainThread(OnlineSearchPresenter.DEBOUNCE_MS));
  }

  @NonNull
  public RegionInfo.Lookup getRegionLookup()
  {
    return mRegions;
  }

  /** Call after a setting changed: applies a new server. */
  @MainThread
  public void onSettingsChanged()
  {
    if (!mServerUrl.equals(mSettings.getServerUrl()))
      registerProvider();
  }

  /** Call after the user agreed or declined. */
  @MainThread
  public void onConsentChanged()
  {
    mService.clearCache();
  }

  /** Replaces the provider, e.g. with one talking to a mock server in instrumented tests. */
  @VisibleForTesting
  public void setProviderForTesting(@NonNull OnlineSearchProvider provider)
  {
    mService.setProvider(provider);
  }

  /** Replaces the region lookup, e.g. in instrumented tests without downloaded maps. */
  @VisibleForTesting
  public void setRegionLookupForTesting(@NonNull RegionInfo.Lookup regions)
  {
    mRegions = regions;
  }

  private void registerProvider()
  {
    mServerUrl = mSettings.getServerUrl();
    final PhotonConfig config = PhotonConfig.builder(getUserAgent()).setBaseUrl(mServerUrl).build();
    mService.setProvider(new PhotonSearchProvider(config, mMainExecutor));
  }

  @NonNull
  static String getUserAgent()
  {
    return "better-maps/" + BuildConfig.VERSION_NAME + " (CoMaps fork; +" + CONTACT_URL + ")";
  }
}
