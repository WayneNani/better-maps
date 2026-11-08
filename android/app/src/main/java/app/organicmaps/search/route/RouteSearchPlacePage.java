package app.organicmaps.search.route;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.util.UiUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

/**
 * The place page of a result of the search along the route: where it is along the route ("in 12 km · Detour ~3 min"),
 * whether it is open on arrival, and "Add to route". The same for a result tapped on the map and in the list.
 */
public final class RouteSearchPlacePage
{
  @NonNull
  private final View mFrame;
  @NonNull
  private final MaterialTextView mPosition;
  @NonNull
  private final MaterialTextView mOpen;
  @NonNull
  private final MaterialButton mAdd;
  @NonNull
  private final RouteSearchTexts mTexts;

  public RouteSearchPlacePage(@NonNull View frame)
  {
    mFrame = frame;
    mPosition = frame.findViewById(R.id.route_search_position);
    mOpen = frame.findViewById(R.id.route_search_open);
    mAdd = frame.findViewById(R.id.route_search_add);
    mTexts = new AndroidRouteSearchTexts(frame.getContext());
  }

  /**
   * The result of the open search for the place, if any.
   *
   * @param presenter the open search along the route, if any.
   */
  @Nullable
  public static SearchResult findResult(@Nullable RouteSearchPresenter presenter, @Nullable MapObject mapObject)
  {
    if (presenter == null || mapObject == null || mapObject.isMyPosition())
      return null;
    return presenter.findResult(mapObject.getFeatureId(), mapObject.getLat(), mapObject.getLon());
  }

  /** @return true if the place is a result of the open search and the block is shown. */
  public boolean bind(@Nullable RouteSearchPresenter presenter, @Nullable MapObject mapObject)
  {
    final SearchResult result = findResult(presenter, mapObject);
    if (presenter == null || result == null)
    {
      UiUtils.hide(mFrame);
      return false;
    }
    UiUtils.show(mFrame);
    UiUtils.setTextAndHideIfEmpty(mPosition, result.routePosition != null
                                                 ? RouteSearchFormatter.formatPosition(result.routePosition, mTexts)
                                                 : "");
    final String open = RouteSearchFormatter.formatOpeningHours(result, mTexts);
    UiUtils.setTextAndHideIfEmpty(mOpen, open);
    if (open != null)
    {
      final boolean isOpen = result.description.openNow == SearchResult.OPEN_NOW_YES;
      mOpen.setTextColor(ContextCompat.getColor(mFrame.getContext(), isOpen ? R.color.base_green : R.color.base_red));
    }

    final boolean isStop = presenter.isStop(result);
    UiUtils.showIf(isStop || presenter.canAddStop(), mAdd);
    mAdd.setEnabled(!isStop);
    mAdd.setText(isStop ? R.string.route_search_added_to_route : R.string.route_search_add_to_route);
    mAdd.setIconResource(isStop ? R.drawable.ic_checkmark : R.drawable.ic_plus);
    mAdd.setOnClickListener(v -> presenter.onAddStopClicked(result));
    return true;
  }
}
