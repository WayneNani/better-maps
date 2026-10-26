package app.organicmaps.transit.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.transit.ConnectionsState;
import app.organicmaps.transit.ItineraryItem;
import app.organicmaps.transit.LineBadge;
import app.organicmaps.transit.TransitConnectionsPresenter;
import app.organicmaps.util.UiUtils;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

/** Connection list with "earlier" and "later" rows. */
final class ConnectionsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
{
  private static final int TYPE_EARLIER = 0;
  private static final int TYPE_ITEM = 1;
  private static final int TYPE_LATER = 2;

  @NonNull
  private final TransitConnectionsPresenter mPresenter;
  @NonNull
  private List<ItineraryItem> mItems = new ArrayList<>();
  private boolean mShowEarlier;
  private boolean mShowLater;
  private boolean mLoadingEarlier;
  private boolean mLoadingLater;

  ConnectionsAdapter(@NonNull TransitConnectionsPresenter presenter)
  {
    mPresenter = presenter;
  }

  void setState(@NonNull ConnectionsState state)
  {
    mItems = state.getItems();
    mShowEarlier = state.canLoadEarlier() || state.isLoadingEarlier();
    mShowLater = state.canLoadLater() || state.isLoadingLater();
    mLoadingEarlier = state.isLoadingEarlier();
    mLoadingLater = state.isLoadingLater();
    notifyDataSetChanged();
  }

  @Override
  public int getItemCount()
  {
    return mItems.size() + (mShowEarlier ? 1 : 0) + (mShowLater ? 1 : 0);
  }

  @Override
  public int getItemViewType(int position)
  {
    if (mShowEarlier && position == 0)
      return TYPE_EARLIER;
    if (mShowLater && position == getItemCount() - 1)
      return TYPE_LATER;
    return TYPE_ITEM;
  }

  private int itemIndex(int position)
  {
    return position - (mShowEarlier ? 1 : 0);
  }

  @NonNull
  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
  {
    final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    if (viewType == TYPE_ITEM)
      return new ItemHolder(inflater.inflate(R.layout.item_transit_connection, parent, false));
    return new PagingHolder(inflater.inflate(R.layout.item_transit_paging, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position)
  {
    final int type = getItemViewType(position);
    if (type == TYPE_ITEM)
    {
      final int index = itemIndex(position);
      ((ItemHolder) holder).bind(mItems.get(index));
      holder.itemView.setOnClickListener(v -> mPresenter.select(index));
      return;
    }
    final PagingHolder paging = (PagingHolder) holder;
    final boolean earlier = type == TYPE_EARLIER;
    paging.mButton.setText(earlier ? R.string.transit_earlier : R.string.transit_later);
    final boolean loading = earlier ? mLoadingEarlier : mLoadingLater;
    paging.mButton.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
    UiUtils.showIf(loading, paging.mProgress);
    paging.mButton.setOnClickListener(v -> {
      if (earlier)
        mPresenter.loadEarlier();
      else
        mPresenter.loadLater();
    });
  }

  private static final class PagingHolder extends RecyclerView.ViewHolder
  {
    final MaterialButton mButton;
    final View mProgress;

    PagingHolder(@NonNull View view)
    {
      super(view);
      mButton = view.findViewById(R.id.paging_button);
      mProgress = view.findViewById(R.id.paging_progress);
    }
  }

  static final class ItemHolder extends RecyclerView.ViewHolder
  {
    private final TextView mDeparture;
    private final TextView mDepartureDelay;
    private final TextView mArrival;
    private final TextView mArrivalDelay;
    private final TextView mDuration;
    private final JourneyBarView mBar;
    private final TextView mFirstBadge;
    private final TextView mDetails;
    private final View mCancelled;

    ItemHolder(@NonNull View view)
    {
      super(view);
      mDeparture = view.findViewById(R.id.departure);
      mDepartureDelay = view.findViewById(R.id.departure_delay);
      mArrival = view.findViewById(R.id.arrival);
      mArrivalDelay = view.findViewById(R.id.arrival_delay);
      mDuration = view.findViewById(R.id.duration);
      mBar = view.findViewById(R.id.bar);
      mFirstBadge = view.findViewById(R.id.first_badge);
      mDetails = view.findViewById(R.id.details);
      mCancelled = view.findViewById(R.id.cancelled);
    }

    void bind(@NonNull ItineraryItem item)
    {
      TransitViews.bindTime(mDeparture, mDepartureDelay, item.getDeparture(), item.getDepartureDelay(),
                            item.isCancelled());
      final String arrival =
          item.getArrivalDayOffset() > 0 ? item.getArrival() + " +" + item.getArrivalDayOffset() : item.getArrival();
      TransitViews.bindTime(mArrival, mArrivalDelay, arrival, item.getArrivalDelay(), item.isCancelled());
      mDuration.setText(item.isWalkOnly() ? item.getDuration() : item.getDuration() + " · " + item.getTransfers());
      mBar.setTimeline(item.getTimeline());

      // First vehicle with its stop and track below the bar.
      final List<LineBadge> badges = item.getBadges();
      if (item.isWalkOnly() || badges.isEmpty())
        UiUtils.hide(mFirstBadge);
      else
      {
        UiUtils.show(mFirstBadge);
        TransitViews.bindBadge(mFirstBadge, badges.get(0));
      }
      if (item.isWalkOnly())
        mDetails.setText(mDetails.getContext().getString(R.string.transit_walk, item.getDuration()));
      else
        UiUtils.setTextAndHideIfEmpty(mDetails, item.getBoarding());
      UiUtils.showIf(item.isCancelled(), mCancelled);
    }
  }
}
