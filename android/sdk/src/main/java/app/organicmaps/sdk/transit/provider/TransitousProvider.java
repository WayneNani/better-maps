package app.organicmaps.sdk.transit.provider;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.transit.TransitService;
import app.organicmaps.sdk.util.concurrency.AsyncResult;

/**
 * Transitous (https://transitous.org), a community-run MOTIS instance with timetables and realtime data from many
 * operators. Also usable for any other MOTIS server with the same API (e.g. an own server).
 */
public class TransitousProvider implements TransitRoutingProvider, TransitDepartureProvider
{
  public static final String ID = "transitous";
  /** List of the data sources and licenses; Transitous requires a visible link to it. */
  public static final String SOURCES_URL = "https://transitous.org/sources/";
  public static final String WEBSITE_URL = "https://transitous.org/";

  @NonNull
  private final TransitService mService;
  @NonNull
  private final String mName;
  @NonNull
  private final String mAttributionUrl;

  public TransitousProvider(@NonNull TransitService service)
  {
    this(service, "Transitous", SOURCES_URL);
  }

  /** For another MOTIS server with the same API, e.g. an own server with its own name and data sources page. */
  public TransitousProvider(@NonNull TransitService service, @NonNull String name, @NonNull String attributionUrl)
  {
    mService = service;
    mName = name;
    mAttributionUrl = attributionUrl;
  }

  @NonNull
  @Override
  public String getId()
  {
    return ID;
  }

  @NonNull
  @Override
  public String getName()
  {
    return mName;
  }

  @NonNull
  @Override
  public String getAttributionUrl()
  {
    return mAttributionUrl;
  }

  @NonNull
  @Override
  public AsyncResult<TransitPlan> plan(@NonNull TransitPlanRequest request)
  {
    return mService.plan(request.getFrom(), request.getTo(), request.getTime(), request.isArriveBy(),
                         request.getPageCursor(), request.getOptions());
  }

  @NonNull
  @Override
  public AsyncResult<TransitDepartures> departuresNear(double lat, double lon, int limit)
  {
    return mService.closestStopWithTimes(lat, lon, limit);
  }
}
