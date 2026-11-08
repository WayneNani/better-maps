package app.organicmaps.search.route;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.util.UiUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import java.util.List;

/** Results of the search along the route: "in 12 km · Detour ~2 min", open on arrival, add as a stop. */
class RouteSearchAdapter extends RecyclerView.Adapter<RouteSearchAdapter.ViewHolder>
{
  interface Listener
  {
    void onResultClicked(int index);

    void onAddStopClicked(@NonNull SearchResult result);

    boolean canAddStop();

    /** The result is already a stop of the route. */
    boolean isStop(@NonNull SearchResult result);
  }

  @NonNull
  private final Listener mListener;
  @NonNull
  private final RouteSearchTexts mTexts;
  @NonNull
  private List<SearchResult> mResults = List.of();

  RouteSearchAdapter(@NonNull Listener listener, @NonNull RouteSearchTexts texts)
  {
    mListener = listener;
    mTexts = texts;
  }

  void setResults(@NonNull List<SearchResult> results)
  {
    mResults = results;
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
  {
    return new ViewHolder(
        LayoutInflater.from(parent.getContext()).inflate(R.layout.item_route_search_result, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position)
  {
    holder.bind(mResults.get(position), position);
  }

  @Override
  public int getItemCount()
  {
    return mResults.size();
  }

  class ViewHolder extends RecyclerView.ViewHolder
  {
    @NonNull
    private final MaterialTextView mTitle;
    @NonNull
    private final MaterialTextView mDescription;
    @NonNull
    private final MaterialTextView mRoutePosition;
    @NonNull
    private final MaterialTextView mOpen;
    @NonNull
    private final MaterialTextView mRegion;
    @NonNull
    private final MaterialButton mAddStop;

    ViewHolder(@NonNull View view)
    {
      super(view);
      mTitle = view.findViewById(R.id.title);
      mDescription = view.findViewById(R.id.description);
      mRoutePosition = view.findViewById(R.id.route_position);
      mOpen = view.findViewById(R.id.open);
      mRegion = view.findViewById(R.id.region);
      mAddStop = view.findViewById(R.id.add_stop);
    }

    void bind(@NonNull SearchResult result, int index)
    {
      mTitle.setText(result.getFormattedTitle(itemView.getContext()));
      final String type = result.description != null ? result.description.localizedFeatureType : "";
      // The type is the title of places without a name.
      UiUtils.setTextAndHideIfEmpty(mDescription, type.contentEquals(mTitle.getText()) ? "" : type);
      UiUtils.setTextAndHideIfEmpty(
          mRoutePosition,
          result.routePosition != null ? RouteSearchFormatter.formatPosition(result.routePosition, mTexts) : "");

      final String open = RouteSearchFormatter.formatOpeningHours(result, mTexts);
      UiUtils.setTextAndHideIfEmpty(mOpen, open);
      if (open != null)
      {
        final boolean isOpen = result.description.openNow == SearchResult.OPEN_NOW_YES;
        mOpen.setTextColor(
            ContextCompat.getColor(itemView.getContext(), isOpen ? R.color.base_green : R.color.base_red));
      }
      UiUtils.setTextAndHideIfEmpty(mRegion, result.getFormattedAddress(itemView.getContext()));

      itemView.setOnClickListener(v -> mListener.onResultClicked(index));
      // An added result stays in the list, marked as a stop.
      final boolean isStop = mListener.isStop(result);
      UiUtils.showIf(isStop || mListener.canAddStop(), mAddStop);
      mAddStop.setEnabled(!isStop);
      mAddStop.setIconResource(isStop ? R.drawable.ic_checkmark : R.drawable.ic_plus);
      final String description = itemView.getContext().getString(isStop ? R.string.route_search_added_to_route
                                                                        : R.string.route_search_add_stop);
      mAddStop.setContentDescription(description);
      mAddStop.setTooltipText(description);
      mAddStop.setOnClickListener(v -> mListener.onAddStopClicked(result));
    }
  }
}
