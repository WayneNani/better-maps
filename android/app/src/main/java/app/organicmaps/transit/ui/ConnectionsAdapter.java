package app.organicmaps.transit.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.transit.AlternativesItem;
import app.organicmaps.transit.ChangeItem;
import app.organicmaps.transit.ConnectionsState;
import app.organicmaps.transit.ItineraryItem;
import app.organicmaps.transit.ItineraryTimeline;
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
  @Nullable
  private AlternativesItem mAlternatives;
  private boolean mCanChoose;

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
    mAlternatives = state.getAlternatives();
    mCanChoose = state.canChooseAlternatives();
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

  /** Position of the card with the other departures, -1 if none. */
  int alternativesPosition()
  {
    if (mAlternatives == null || mAlternatives.getConnection() >= mItems.size())
      return -1;
    return mAlternatives.getConnection() + (mShowEarlier ? 1 : 0);
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
      final ItemHolder item = (ItemHolder) holder;
      item.bind(mItems.get(index));
      item.bindAlternatives(index, mItems.get(index),
                            mAlternatives != null && mAlternatives.getConnection() == index ? mAlternatives : null,
                            mCanChoose, mPresenter);
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
    private final MaterialButton mAlternativesButton;
    private final TextView mChanged;
    private final View mAlternatives;
    private final TextView mAlternativesTitle;
    private final View mAlternativesScroll;
    private final LinearLayout mAlternativesChips;
    private final View mAlternativesProgress;
    private final TextView mAlternativesMessage;
    private final View mChange;
    private final TextView mChangeText;
    private final View mUndo;

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
      mAlternativesButton = view.findViewById(R.id.alternatives_button);
      mChanged = view.findViewById(R.id.changed);
      mAlternatives = view.findViewById(R.id.alternatives);
      mAlternativesTitle = view.findViewById(R.id.alternatives_title);
      mAlternativesScroll = view.findViewById(R.id.alternatives_scroll);
      mAlternativesChips = view.findViewById(R.id.alternatives_chips);
      mAlternativesProgress = view.findViewById(R.id.alternatives_progress);
      mAlternativesMessage = view.findViewById(R.id.alternatives_message);
      mChange = view.findViewById(R.id.alternatives_change);
      mChangeText = view.findViewById(R.id.alternatives_change_text);
      mUndo = view.findViewById(R.id.alternatives_undo);
    }

    /**
     * The first ride's other departures: a tap on it in the bar or on "Departures" shows them in the card.
     *
     * @param alternatives the open ride's departures if they belong to this card.
     */
    void bindAlternatives(int index, @NonNull ItineraryItem item, @Nullable AlternativesItem alternatives,
                          boolean canChoose, @NonNull TransitConnectionsPresenter presenter)
    {
      final int firstRide = firstRide(item);
      final boolean possible = canChoose && firstRide >= 0;
      final boolean open = alternatives != null && alternatives.getLegIndex() == firstRide;
      UiUtils.showIf(possible, mAlternativesButton);
      mAlternativesButton.setIconResource(open ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
      mAlternativesButton.setOnClickListener(possible ? v -> presenter.toggleAlternatives(index, firstRide) : null);
      mBar.setRideClickListener(possible ? legIndex -> presenter.toggleAlternatives(index, legIndex) : null,
                                firstRide, open);

      final ChangeItem change = item.getChange();
      final Context context = itemView.getContext();
      UiUtils.setTextAndHideIfEmpty(mChanged, change != null && !open ? AlternativesViews.changed(context, change) : null);

      UiUtils.showIf(open, mAlternatives);
      if (!open)
        return;
      mAlternativesTitle.setText(
          alternatives.getNextLine() != null && (change == null || !change.isReplanned())
              ? context.getString(R.string.transit_alt_list_title, alternatives.getNextLine())
              : context.getString(R.string.transit_alt_list_title_only));
      AlternativesViews.bindChips(mAlternativesChips, alternatives, R.layout.item_transit_alternative_pill, true,
                                  presenter);
      UiUtils.showIf(!alternatives.getChips().isEmpty(), mAlternativesScroll);
      UiUtils.showIf(alternatives.isLoading() || alternatives.isReplanning(), mAlternativesProgress);
      UiUtils.setTextAndHideIfEmpty(mAlternativesMessage, AlternativesViews.message(context, alternatives));
      UiUtils.showIf(change != null, mChange);
      if (change != null)
      {
        mChangeText.setText(change.isReplanned() ? AlternativesViews.banner(context, change)
                                                 : AlternativesViews.changed(context, change));
        mUndo.setOnClickListener(v -> presenter.undoChange(index));
      }
    }

    /** Leg index of the first ride, -1 without rides. */
    private static int firstRide(@NonNull ItineraryItem item)
    {
      for (ItineraryTimeline.Segment segment : item.getTimeline().getSegments())
        if (segment.getKind() == ItineraryTimeline.Kind.RIDE)
          return segment.getLegIndex();
      return -1;
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
