package app.organicmaps.transit;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Router;
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

  public OnlineTransitRouter(@NonNull TransitServices services)
  {
    mServices = services;
  }

  public void setConsentPrompter(@Nullable ConsentPrompter prompter)
  {
    mPrompter = prompter;
  }

  @Override
  public boolean handles(@NonNull Router router)
  {
    return router == Router.Transit && mServices.getConsent().getState() != TransitConsent.State.DECLINED;
  }

  @MainThread
  @Override
  public void build(@NonNull MapObject start, @NonNull MapObject end)
  {
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
                     TransitLocation.fromCoordinates(end.getLat(), end.getLon()));
  }
}
