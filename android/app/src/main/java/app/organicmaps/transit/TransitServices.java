package app.organicmaps.transit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.text.format.DateFormat;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import app.organicmaps.BuildConfig;
import app.organicmaps.sdk.transit.OnlineTransitService;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import app.organicmaps.sdk.transit.provider.TransitProvider;
import app.organicmaps.sdk.transit.provider.TransitProviderRegistry;
import app.organicmaps.sdk.transit.provider.TransitousProvider;
import app.organicmaps.sdk.util.concurrency.Debouncer;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Application-wide access to online public transport: settings, consent, the provider registry (Transitous by
 * default) and the presenter of the connection list. All online requests go through the registry, which sends
 * nothing without the user's consent.
 */
public final class TransitServices
{
  /** Contact for the server operator, required by the Transitous usage policy. */
  private static final String CONTACT_URL = "https://github.com/WayneNani/better-maps";

  // Holds the application context only.
  @SuppressLint("StaticFieldLeak")
  private static TransitServices sInstance;

  @NonNull
  private final Context mContext;
  @NonNull
  private final Executor mMainExecutor;
  @NonNull
  private final TransitSettings mSettings;
  @NonNull
  private final TransitConsent mConsent;
  @NonNull
  private final TransitProviderRegistry mRegistry;
  @NonNull
  private final SavedConnectionsStore mSavedConnections;
  @NonNull
  private String mServerUrl = "";
  private TransitConnectionsPresenter mConnections;
  private OnlineTransitRouter mRouter;
  @NonNull
  private TransitConnectionsPresenter.RouteDisplay mRouteDisplay = new MapRouteDisplay();

  private TransitServices(@NonNull Context context)
  {
    mContext = context.getApplicationContext();
    mMainExecutor = ContextCompat.getMainExecutor(mContext);
    final KeyValueStore store =
        KeyValueStore.of(mContext.getSharedPreferences(TransitSettings.PREFS_NAME, Context.MODE_PRIVATE));
    mSettings = new TransitSettings(store);
    mConsent = new TransitConsent(store);
    mRegistry = new TransitProviderRegistry(mMainExecutor, System::currentTimeMillis, mConsent::isGranted);
    registerDefaultProvider();
    mSavedConnections =
        new SavedConnectionsStore(new File(new File(mContext.getFilesDir(), "transit"), "saved_connections.json"),
                                  Executors.newSingleThreadExecutor(), Instant::now);
  }

  @NonNull
  public static synchronized TransitServices get(@NonNull Context context)
  {
    if (sInstance == null)
      sInstance = new TransitServices(context);
    return sInstance;
  }

  @NonNull
  public TransitSettings getSettings()
  {
    return mSettings;
  }

  @NonNull
  public TransitConsent getConsent()
  {
    return mConsent;
  }

  @NonNull
  public TransitProviderRegistry getRegistry()
  {
    return mRegistry;
  }

  /** Connections saved by the user (on the device only). */
  @MainThread
  @NonNull
  public SavedConnectionsStore getSavedConnections()
  {
    return mSavedConnections;
  }

  /** Call after a setting changed: applies a new server and queries the shown connections again. */
  @MainThread
  public void onSettingsChanged()
  {
    if (!mServerUrl.equals(mSettings.getServerUrl()))
      registerDefaultProvider();
    if (mConnections != null)
      mConnections.onOptionsChanged();
  }

  /** Call after the user agreed or declined. */
  @MainThread
  public void onConsentChanged()
  {
    mRegistry.clearCache();
    if (mConnections != null && !mConsent.isGranted())
      mConnections.clear();
  }

  @NonNull
  public TransitItemFactory createItemFactory(@NonNull Context context)
  {
    return new TransitItemFactory(createFormatter(context));
  }

  /** Formatter with the locale, time zone and clock format of the device. */
  @NonNull
  public static TransitFormatter createFormatter(@NonNull Context context)
  {
    final Locale locale = context.getResources().getConfiguration().getLocales().get(0);
    return new TransitFormatter(locale, ZoneId.systemDefault(), DateFormat.is24HourFormat(context),
                                new AndroidTransitTexts(context), DateFormat.getBestDateTimePattern(locale, "EEEdMMM"),
                                DateFormat.getBestDateTimePattern(locale, "EEEdMMMy"));
  }

  /** The presenter of the online connection list; it lives as long as the app (route planning survives rotation). */
  @MainThread
  @NonNull
  public TransitConnectionsPresenter getConnectionsPresenter()
  {
    if (mConnections == null)
    {
      mConnections =
          new TransitConnectionsPresenter(mRegistry, mSettings::toRoutingOptions, createItemFactory(mContext),
                                          Debouncer.onMainThread(TransitConnectionsPresenter.DEBOUNCE_MS),
                                          Instant::now, new TransitConnectionsPresenter.RouteDisplay() {
                                            @Override
                                            public void show(@NonNull TransitItinerary itinerary)
                                            {
                                              mRouteDisplay.show(itinerary);
                                            }

                                            @Override
                                            public void clear()
                                            {
                                              mRouteDisplay.clear();
                                            }
                                          }, mSettings::getDefaultViewMode, mSavedConnections);
    }
    return mConnections;
  }

  /** The route planner's public transport mode with online data. */
  @MainThread
  @NonNull
  public OnlineTransitRouter getRouter()
  {
    if (mRouter == null)
      mRouter = new OnlineTransitRouter(this);
    return mRouter;
  }

  /** Replaces the map drawing, e.g. in instrumented tests without an initialized map. */
  @VisibleForTesting
  public void setRouteDisplayForTesting(@NonNull TransitConnectionsPresenter.RouteDisplay display)
  {
    mRouteDisplay = display;
  }

  /** Replaces the default provider, e.g. with a fake one in instrumented tests. */
  @VisibleForTesting
  @MainThread
  public void setProviderForTesting(@NonNull TransitProvider provider)
  {
    for (TransitProvider p : mRegistry.getProviders())
      mRegistry.unregister(p.getId());
    mRegistry.register(provider, TransitProviderRegistry.PRIORITY_DEFAULT);
  }

  private void registerDefaultProvider()
  {
    mServerUrl = mSettings.getServerUrl();
    final TransitServiceConfig config = TransitServiceConfig.builder(getUserAgent()).setBaseUrl(mServerUrl).build();
    final OnlineTransitService service = new OnlineTransitService(config, mMainExecutor);
    final TransitousProvider provider;
    if (mSettings.isDefaultServer())
      provider = new TransitousProvider(service);
    else
    {
      // An own MOTIS server: name it by its host; its data sources are not known.
      final String host = Uri.parse(mServerUrl).getHost();
      provider = new TransitousProvider(service, host != null ? host : mServerUrl, mServerUrl);
    }
    mRegistry.register(provider, TransitProviderRegistry.PRIORITY_DEFAULT);
  }

  @NonNull
  static String getUserAgent()
  {
    return "better-maps/" + BuildConfig.VERSION_NAME + " (CoMaps fork; +" + CONTACT_URL + ")";
  }
}
