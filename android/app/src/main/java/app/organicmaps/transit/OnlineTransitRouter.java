package app.organicmaps.transit;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.transit.TransitLocation;

/**
 * Takes over the public transport mode of the route planner when the user agreed to online public transport.
 * Without a decision, the opt-in dialog is shown first; if the user declines, the built-in (offline) transit router
 * computes the route as before.
 */
public final class OnlineTransitRouter implements RoutingController.ExternalRouter, TransitConnectionsPresenter.Listener
{
  /** Shows the opt-in dialog; implemented by the activity. */
  public interface ConsentPrompter
  {
    void askForConsent(@NonNull Runnable onGranted, @NonNull Runnable onDeclined);
  }

  @NonNull
  private final TransitServices mServices;
  @Nullable
  private ConsentPrompter mPrompter;
  // Shown instead of a search by the next build().
  @Nullable
  private SavedConnection mPendingSaved;
  // The shown saved connection was opened from the list of saved connections (back returns there).
  private boolean mSavedFromList;

  public OnlineTransitRouter(@NonNull TransitServices services)
  {
    mServices = services;
  }

  public void setConsentPrompter(@Nullable ConsentPrompter prompter)
  {
    mPrompter = prompter;
  }

  /**
   * Opens the route planner with the start and destination of a saved connection and shows the connection in the
   * detail view and on the map, without any request.
   */
  @MainThread
  public void showSaved(@NonNull SavedConnection saved)
  {
    showSaved(saved, true);
  }

  /** @param fromList true if opened from the list of saved connections, false e.g. from the card on the map. */
  @MainThread
  public void showSaved(@NonNull SavedConnection saved, boolean fromList)
  {
    mPendingSaved = saved;
    mSavedFromList = fromList;
    final MapObject start = MapObject.createMapObject(FeatureId.EMPTY, MapObject.API_POINT, saved.getFromName(), "",
                                                      saved.getFrom().getLat(), saved.getFrom().getLon());
    final MapObject end = MapObject.createMapObject(FeatureId.EMPTY, MapObject.API_POINT, saved.getToName(), "",
                                                    saved.getTo().getLat(), saved.getTo().getLon());
    RoutingController.get().prepare(start, end, Router.Transit);
  }

  /** True if back from the shown saved connection should return to the list of saved connections. */
  public boolean isSavedFromList()
  {
    return mSavedFromList;
  }

  @Override
  public boolean handles(@NonNull Router router)
  {
    if (router != Router.Transit)
      return false;
    // A saved connection is shown without going online.
    return mPendingSaved != null || mServices.getConsent().getState() != TransitConsent.State.DECLINED;
  }

  @MainThread
  @Override
  public void build(@NonNull MapObject start, @NonNull MapObject end)
  {
    final SavedConnection saved = mPendingSaved;
    if (saved != null)
    {
      mPendingSaved = null;
      final TransitConnectionsPresenter presenter = mServices.getConnectionsPresenter();
      presenter.setListener(this);
      presenter.showSaved(saved);
      return;
    }
    final TransitConsent consent = mServices.getConsent();
    if (consent.isGranted())
    {
      search(start, end);
      return;
    }
    if (mPrompter == null)
    {
      RoutingController.get().buildWithBuiltInRouter();
      return;
    }
    mPrompter.askForConsent(() -> {
      final RoutingController controller = RoutingController.get();
      // The user may have closed the planner meanwhile.
      if (!controller.isExternalRoute() || !controller.isBuilding())
        return;
      final MapObject s = controller.getStartPoint();
      final MapObject e = controller.getEndPoint();
      if (s != null && e != null)
        search(s, e);
    }, () -> RoutingController.get().buildWithBuiltInRouter());
  }

  @MainThread
  @Override
  public void cancel()
  {
    final TransitConnectionsPresenter presenter = mServices.getConnectionsPresenter();
    presenter.setListener(null);
    presenter.clear();
  }

  @Override
  public void onQueryFinished(boolean success)
  {
    RoutingController.get().onExternalRouteReady();
  }

  private void search(@NonNull MapObject start, @NonNull MapObject end)
  {
    final TransitConnectionsPresenter presenter = mServices.getConnectionsPresenter();
    presenter.setListener(this);
    presenter.search(TransitLocation.fromCoordinates(start.getLat(), start.getLon()),
                     TransitLocation.fromCoordinates(end.getLat(), end.getLon()), name(start), name(end));
  }

  /** Name for saved connections; empty for the own position, which means nothing later. */
  @NonNull
  private static String name(@NonNull MapObject point)
  {
    return point.isMyPosition() ? "" : point.getTitle();
  }
}
