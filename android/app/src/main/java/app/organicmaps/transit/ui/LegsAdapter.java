package app.organicmaps.transit.ui;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.transit.AlternativesItem;
import app.organicmaps.transit.ChangeItem;
import app.organicmaps.transit.LegItem;
import app.organicmaps.transit.TransitConnectionsPresenter;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.UiUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Legs of the selected connection. A tap on a ride shows its other departures below it (and hides them again); a
 * banner before the new rest of the connection tells that the next ride is not reached any more.
 */
final class LegsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
{
  private static final int TYPE_TRANSIT = 0;
  private static final int TYPE_WALK = 1;
  private static final int TYPE_ALTERNATIVES = 2;
  private static final int TYPE_BANNER = 3;

  @NonNull
  private final TransitConnectionsPresenter mPresenter;
  // LegItem, AlternativesItem or ChangeItem (banner), in display order.
  @NonNull
  private List<Object> mRows = new ArrayList<>();
  @Nullable
  private AlternativesItem mAlternatives;
  private boolean mCanChoose;
  // Open ride whose chips were scrolled to the chosen one.
  private int mScrolledLeg = -1;

  LegsAdapter(@NonNull TransitConnectionsPresenter presenter)
  {
    mPresenter = presenter;
  }

  void setLegs(@NonNull List<LegItem> legs)
  {
    setLegs(legs, null, null, false);
  }

  /**
   * @param alternatives other departures of the open ride, shown below it.
   * @param change the user's change of the connection; a banner is shown if its rest was searched again.
   * @param canChoose rides can be tapped to show their other departures.
   */
  void setLegs(@NonNull List<LegItem> legs, @Nullable AlternativesItem alternatives, @Nullable ChangeItem change,
               boolean canChoose)
  {
    final List<Object> rows = new ArrayList<>(legs.size() + 2);
    boolean banner = change == null || !change.isReplanned();
    for (LegItem leg : legs)
    {
      if (!banner && leg.getIndex() >= change.getRestLegIndex())
      {
        rows.add(change);
        banner = true;
      }
      rows.add(leg);
      if (alternatives != null && leg.getIndex() == alternatives.getLegIndex())
        rows.add(alternatives);
    }
    if (!banner)
      rows.add(change);
    if (alternatives == null)
      mScrolledLeg = -1;
    mRows = rows;
    mAlternatives = alternatives;
    mCanChoose = canChoose;
    notifyDataSetChanged();
  }

  /** Position of the row with the other departures, -1 if none. */
  int alternativesPosition()
  {
    for (int i = 0; i < mRows.size(); ++i)
      if (mRows.get(i) instanceof AlternativesItem)
        return i;
    return -1;
  }

  @Override
  public int getItemCount()
  {
    return mRows.size();
  }

  @Override
  public int getItemViewType(int position)
  {
    final Object row = mRows.get(position);
    if (row instanceof AlternativesItem)
      return TYPE_ALTERNATIVES;
    if (row instanceof ChangeItem)
      return TYPE_BANNER;
    return ((LegItem) row).getType() == LegItem.Type.TRANSIT ? TYPE_TRANSIT : TYPE_WALK;
  }

  @NonNull
  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
  {
    final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    switch (viewType)
    {
    case TYPE_TRANSIT: return new TransitHolder(inflater.inflate(R.layout.item_transit_leg, parent, false));
    case TYPE_ALTERNATIVES:
      return new AlternativesHolder(inflater.inflate(R.layout.item_transit_alternatives, parent, false));
    case TYPE_BANNER: return new BannerHolder(inflater.inflate(R.layout.item_transit_alt_banner, parent, false));
    default: return new WalkHolder(inflater.inflate(R.layout.item_transit_walk, parent, false));
    }
  }

  @Override
  public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position)
  {
    final Object row = mRows.get(position);
    if (holder instanceof TransitHolder)
    {
      final LegItem leg = (LegItem) row;
      final boolean open = mAlternatives != null && mAlternatives.getLegIndex() == leg.getIndex();
      ((TransitHolder) holder).bind(leg, mPresenter, mCanChoose, open);
    }
    else if (holder instanceof AlternativesHolder)
    {
      final AlternativesItem alternatives = (AlternativesItem) row;
      final boolean scroll = mScrolledLeg != alternatives.getLegIndex() && !alternatives.getChips().isEmpty();
      if (scroll)
        mScrolledLeg = alternatives.getLegIndex();
      ((AlternativesHolder) holder).bind(alternatives, mPresenter, scroll);
    }
    else if (holder instanceof BannerHolder)
      ((BannerHolder) holder).bind((ChangeItem) row, mPresenter);
    else
      ((WalkHolder) holder).bind((LegItem) row);
  }

  private static final class AlternativesHolder extends RecyclerView.ViewHolder
  {
    private final TextView mTitle;
    private final TextView mTransfer;
    private final HorizontalScrollView mScroll;
    private final LinearLayout mChips;
    private final View mProgress;
    private final TextView mMessage;

    AlternativesHolder(@NonNull View view)
    {
      super(view);
      mTitle = view.findViewById(R.id.alt_title);
      mTransfer = view.findViewById(R.id.alt_transfer);
      mScroll = view.findViewById(R.id.alt_scroll);
      mChips = view.findViewById(R.id.alt_chips);
      mProgress = view.findViewById(R.id.alt_progress);
      mMessage = view.findViewById(R.id.alt_message);
    }

    void bind(@NonNull AlternativesItem alternatives, @NonNull TransitConnectionsPresenter presenter,
              boolean scrollToSelected)
    {
      mTitle.setText(AlternativesViews.title(mTitle.getContext(), alternatives));
      UiUtils.setTextAndHideIfEmpty(
          mTransfer, alternatives.getTransfer() != null
                         ? mTransfer.getContext().getString(R.string.transit_alt_transfer, alternatives.getTransfer())
                         : null);
      AlternativesViews.bindChips(mChips, alternatives, R.layout.item_transit_alternative_chip, false, presenter);
      UiUtils.showIf(!alternatives.getChips().isEmpty(), mScroll);
      UiUtils.showIf(alternatives.isLoading() || alternatives.isReplanning(), mProgress);
      UiUtils.setTextAndHideIfEmpty(mMessage, AlternativesViews.message(mMessage.getContext(), alternatives));
      if (scrollToSelected)
        mScroll.post(() -> {
          final List<AlternativesItem.Chip> chips = alternatives.getChips();
          for (int i = 0; i < chips.size() && i < mChips.getChildCount(); ++i)
            if (chips.get(i).isSelected())
            {
              // The chosen departure in view, with earlier ones beside it.
              final View chip = mChips.getChildAt(i);
              mScroll.scrollTo(Math.max(0, chip.getLeft() - mScroll.getWidth() / 2 + chip.getWidth() / 2), 0);
            }
        });
    }
  }

  private static final class BannerHolder extends RecyclerView.ViewHolder
  {
    private final TextView mText;
    private final View mUndo;

    BannerHolder(@NonNull View view)
    {
      super(view);
      mText = view.findViewById(R.id.banner_text);
      mUndo = view.findViewById(R.id.banner_undo);
    }

    void bind(@NonNull ChangeItem change, @NonNull TransitConnectionsPresenter presenter)
    {
      mText.setText(AlternativesViews.banner(mText.getContext(), change));
      mUndo.setOnClickListener(v -> presenter.undoChange());
    }
  }

  private static final class WalkHolder extends RecyclerView.ViewHolder
  {
    private final TextView mTime;
    private final TextView mSummary;
    private final TextView mTo;

    WalkHolder(@NonNull View view)
    {
      super(view);
      mTime = view.findViewById(R.id.walk_time);
      mSummary = view.findViewById(R.id.walk_summary);
      mTo = view.findViewById(R.id.walk_to);
    }

    void bind(@NonNull LegItem leg)
    {
      mTime.setText(leg.getDeparture());
      mSummary.setText(leg.getSummary());
      mTo.setText(mTo.getContext().getString(R.string.transit_direction, leg.getToName()));
    }
  }

  private static final class TransitHolder extends RecyclerView.ViewHolder
  {
    private final TextView mDeparture;
    private final TextView mDepartureDelay;
    private final TextView mArrival;
    private final TextView mArrivalDelay;
    private final View mLine;
    private final View mDotStart;
    private final View mDotEnd;
    private final TextView mFrom;
    private final TextView mFromTrack;
    private final TextView mBadge;
    private final TextView mDirection;
    private final View mCancelled;
    private final TextView mAlerts;
    private final TextView mSummary;
    private final LinearLayout mStops;
    private final TextView mTo;
    private final TextView mToTrack;

    TransitHolder(@NonNull View view)
    {
      super(view);
      mDeparture = view.findViewById(R.id.leg_departure);
      mDepartureDelay = view.findViewById(R.id.leg_departure_delay);
      mArrival = view.findViewById(R.id.leg_arrival);
      mArrivalDelay = view.findViewById(R.id.leg_arrival_delay);
      mLine = view.findViewById(R.id.leg_line);
      mDotStart = view.findViewById(R.id.leg_dot_start);
      mDotEnd = view.findViewById(R.id.leg_dot_end);
      mFrom = view.findViewById(R.id.leg_from);
      mFromTrack = view.findViewById(R.id.leg_from_track);
      mBadge = view.findViewById(R.id.leg_badge);
      mDirection = view.findViewById(R.id.leg_direction);
      mCancelled = view.findViewById(R.id.leg_cancelled);
      mAlerts = view.findViewById(R.id.leg_alerts);
      mSummary = view.findViewById(R.id.leg_summary);
      mStops = view.findViewById(R.id.leg_stops);
      mTo = view.findViewById(R.id.leg_to);
      mToTrack = view.findViewById(R.id.leg_to_track);
    }

    void bind(@NonNull LegItem leg, @NonNull TransitConnectionsPresenter presenter, boolean canChoose, boolean open)
    {
      // A tap shows or hides the other departures; the row and the departures below look like one card.
      itemView.setOnClickListener(canChoose ? v -> presenter.toggleAlternatives(leg.getIndex()) : null);
      itemView.setClickable(canChoose);
      if (open)
        itemView.setBackgroundResource(R.drawable.bg_transit_alt_area_top);
      else if (canChoose)
        itemView.setBackgroundResource(ThemeUtils.getResource(itemView.getContext(), android.R.attr.selectableItemBackground));
      else
        itemView.setBackground(null);
      ViewCompat.replaceAccessibilityAction(
          itemView, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
          canChoose ? itemView.getContext().getString(open ? R.string.transit_alt_hide : R.string.transit_alt_show)
                    : null,
          null);

      TransitViews.bindTime(mDeparture, mDepartureDelay, leg.getDeparture(), leg.getDepartureDelay(),
                            leg.isCancelled());
      TransitViews.bindTime(mArrival, mArrivalDelay, leg.getArrival(), leg.getArrivalDelay(), leg.isCancelled());

      final ColorStateList color =
          ColorStateList.valueOf(leg.getBadge() != null ? leg.getBadge().getBackgroundColor() : 0xFF808080);
      mLine.setBackgroundColor(color.getDefaultColor());
      mDotStart.setBackgroundTintList(color);
      mDotEnd.setBackgroundTintList(color);

      mFrom.setText(leg.getFromName());
      bindTrack(mFromTrack, leg.getFromTrack(), leg.isFromTrackChanged());
      mTo.setText(leg.getToName());
      bindTrack(mToTrack, leg.getToTrack(), leg.isToTrackChanged());

      if (leg.getBadge() != null)
        TransitViews.bindBadge(mBadge, leg.getBadge());
      UiUtils.setTextAndHideIfEmpty(
          mDirection, leg.getDirection() != null
                          ? mDirection.getContext().getString(R.string.transit_direction, leg.getDirection())
                          : null);
      UiUtils.showIf(leg.isCancelled(), mCancelled);
      UiUtils.setTextAndHideIfEmpty(mAlerts, leg.getAlerts().isEmpty() ? null : String.join("\n", leg.getAlerts()));

      mSummary.setText(leg.getSummary());
      if (leg.hasStops())
      {
        mSummary.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, leg.isExpanded() ? R.drawable.ic_expand_less : R.drawable.ic_expand_more, 0);
        TextViewCompat.setCompoundDrawableTintList(
            mSummary,
            ColorStateList.valueOf(ThemeUtils.getColor(mSummary.getContext(), android.R.attr.textColorSecondary)));
        mSummary.setOnClickListener(v -> presenter.toggleStops(leg.getIndex()));
        mSummary.setClickable(true);
      }
      else
      {
        mSummary.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        mSummary.setOnClickListener(null);
        mSummary.setClickable(false);
      }

      mStops.removeAllViews();
      if (leg.isExpanded())
      {
        final LayoutInflater inflater = LayoutInflater.from(mStops.getContext());
        for (LegItem.Stop stop : leg.getStops())
        {
          final View row = inflater.inflate(R.layout.item_transit_stop, mStops, false);
          final TextView time = row.findViewById(R.id.stop_time);
          final TextView name = row.findViewById(R.id.stop_name);
          TransitViews.bindTime(time, null, stop.getTime(), stop.getDelay(), stop.isCancelled());
          name.setText(stop.getName());
          TransitViews.setStrikeThrough(name, stop.isCancelled());
          mStops.addView(row);
        }
      }
      UiUtils.showIf(leg.isExpanded(), mStops);
    }

    private static void bindTrack(@NonNull TextView view, @androidx.annotation.Nullable String track, boolean changed)
    {
      UiUtils.setTextAndHideIfEmpty(view, track);
      view.setTextColor(changed ? TransitViews.lateColor(view.getContext())
                                : ThemeUtils.getColor(view.getContext(), android.R.attr.textColorSecondary));
    }
  }
}
