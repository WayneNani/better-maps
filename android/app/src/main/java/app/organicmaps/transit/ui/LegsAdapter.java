package app.organicmaps.transit.ui;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.transit.LegItem;
import app.organicmaps.transit.TransitConnectionsPresenter;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.UiUtils;
import java.util.ArrayList;
import java.util.List;

/** Legs of the selected connection. */
final class LegsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
{
  private static final int TYPE_TRANSIT = 0;
  private static final int TYPE_WALK = 1;

  @NonNull
  private final TransitConnectionsPresenter mPresenter;
  @NonNull
  private List<LegItem> mLegs = new ArrayList<>();

  LegsAdapter(@NonNull TransitConnectionsPresenter presenter)
  {
    mPresenter = presenter;
  }

  void setLegs(@NonNull List<LegItem> legs)
  {
    mLegs = legs;
    notifyDataSetChanged();
  }

  @Override
  public int getItemCount()
  {
    return mLegs.size();
  }

  @Override
  public int getItemViewType(int position)
  {
    return mLegs.get(position).getType() == LegItem.Type.TRANSIT ? TYPE_TRANSIT : TYPE_WALK;
  }

  @NonNull
  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
  {
    final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    if (viewType == TYPE_TRANSIT)
      return new TransitHolder(inflater.inflate(R.layout.item_transit_leg, parent, false));
    return new WalkHolder(inflater.inflate(R.layout.item_transit_walk, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position)
  {
    final LegItem leg = mLegs.get(position);
    if (holder instanceof TransitHolder)
      ((TransitHolder) holder).bind(leg, mPresenter);
    else
      ((WalkHolder) holder).bind(leg);
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

    void bind(@NonNull LegItem leg, @NonNull TransitConnectionsPresenter presenter)
    {
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
