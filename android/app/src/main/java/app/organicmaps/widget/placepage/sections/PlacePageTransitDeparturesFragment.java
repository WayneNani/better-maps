package app.organicmaps.widget.placepage.sections;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.R;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.transit.DepartureItem;
import app.organicmaps.transit.DeparturesState;
import app.organicmaps.transit.TransitDeparturesPresenter;
import app.organicmaps.transit.TransitError;
import app.organicmaps.transit.TransitServices;
import app.organicmaps.transit.ui.TransitConsentDialog;
import app.organicmaps.transit.ui.TransitViews;
import app.organicmaps.util.UiUtils;
import app.organicmaps.widget.placepage.PlacePageViewModel;
import java.time.Instant;

/** Next departures (with realtime data) of the selected stop, from online public transport. */
public class PlacePageTransitDeparturesFragment
    extends Fragment implements Observer<MapObject>, TransitDeparturesPresenter.View
{
  private PlacePageViewModel mViewModel;
  private TransitDeparturesPresenter mPresenter;

  private View mFrame;
  private TextView mTitle;
  private View mRefresh;
  private View mProgress;
  private TextView mStatus;
  private View mEnable;
  private LinearLayout mList;
  private TextView mFooter;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    mViewModel = new ViewModelProvider(requireActivity()).get(PlacePageViewModel.class);
    final TransitServices services = TransitServices.get(requireContext());
    mPresenter = new TransitDeparturesPresenter(
        services.getRegistry(),
        () -> services.getConsent().getState(), services.createItemFactory(requireContext()), Instant::now);
    return inflater.inflate(R.layout.place_page_transit_departures_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    mFrame = view.findViewById(R.id.transit_departures);
    mTitle = view.findViewById(R.id.departures_title);
    mRefresh = view.findViewById(R.id.departures_refresh);
    mProgress = view.findViewById(R.id.departures_progress);
    mStatus = view.findViewById(R.id.departures_status);
    mEnable = view.findViewById(R.id.departures_enable);
    mList = view.findViewById(R.id.departures_list);
    mFooter = view.findViewById(R.id.departures_footer);

    mRefresh.setOnClickListener(v -> mPresenter.refresh());
    mEnable.setOnClickListener(v -> TransitConsentDialog.show(requireContext(), granted -> {
      if (isAdded())
        mPresenter.onConsentChanged();
    }));
  }

  @Override
  public void onStart()
  {
    super.onStart();
    mPresenter.attach(this);
    mViewModel.getMapObject().observe(requireActivity(), this);
  }

  @Override
  public void onStop()
  {
    super.onStop();
    mViewModel.getMapObject().removeObserver(this);
    mPresenter.detach(this);
  }

  @Override
  public void onChanged(@Nullable MapObject mapObject)
  {
    if (mapObject == null || !mapObject.hasPublicTransport())
    {
      mPresenter.clear();
      return;
    }
    mPresenter.load(mapObject.getLat(), mapObject.getLon());
  }

  @Override
  public void render(@NonNull DeparturesState state)
  {
    if (mFrame == null)
      return;
    final DeparturesState.Status status = state.getStatus();
    UiUtils.showIf(status != DeparturesState.Status.HIDDEN, mFrame);
    if (status == DeparturesState.Status.HIDDEN)
      return;

    mTitle.setText(state.getStopName() != null ? getString(R.string.transit_departures_from, state.getStopName())
                                               : getString(R.string.transit_departures));
    UiUtils.showIf(status == DeparturesState.Status.CONSENT_REQUIRED, mEnable);
    UiUtils.showIf(status == DeparturesState.Status.CONTENT || status == DeparturesState.Status.EMPTY
                       || status == DeparturesState.Status.ERROR,
                   mRefresh);
    mRefresh.setEnabled(!state.isRefreshing());
    UiUtils.showIf(status == DeparturesState.Status.LOADING || state.isRefreshing(), mProgress);

    final TransitError error = state.getError();
    if (status == DeparturesState.Status.ERROR && error != null)
      UiUtils.setTextAndHideIfEmpty(mStatus, getString(TransitViews.errorText(error)));
    else if (status == DeparturesState.Status.EMPTY)
      UiUtils.setTextAndHideIfEmpty(mStatus,
                                    getString(error == TransitError.NO_RESULTS ? R.string.transit_departures_no_stop
                                                                               : R.string.transit_departures_empty));
    else
      UiUtils.hide(mStatus);

    mList.removeAllViews();
    final LayoutInflater inflater = LayoutInflater.from(requireContext());
    for (DepartureItem item : state.getItems())
      mList.addView(bindRow(inflater.inflate(R.layout.item_transit_departure, mList, false), item));
    UiUtils.showIf(!state.getItems().isEmpty(), mList);

    final String updated =
        state.getUpdatedAt() != null ? getString(R.string.transit_departures_updated, state.getUpdatedAt()) : null;
    TransitViews.bindAttribution(mFooter, updated, state.getProviderName(), state.getAttributionUrl());
  }

  @NonNull
  private static View bindRow(@NonNull View row, @NonNull DepartureItem item)
  {
    final TextView time = row.findViewById(R.id.departure_time);
    final TextView delay = row.findViewById(R.id.departure_delay);
    final TextView badge = row.findViewById(R.id.departure_badge);
    final TextView direction = row.findViewById(R.id.departure_direction);
    final TextView track = row.findViewById(R.id.departure_track);
    TransitViews.bindTime(time, delay, item.getTime(), item.getDelay(), item.isCancelled());
    // Keep the column aligned even without a delay.
    delay.setVisibility(item.getDelay().getText().isEmpty() ? View.INVISIBLE : View.VISIBLE);
    TransitViews.bindBadge(badge, item.getBadge());
    direction.setText(item.getDirection());
    TransitViews.setStrikeThrough(direction, item.isCancelled());
    UiUtils.setTextAndHideIfEmpty(track, item.getTrack());
    if (item.isTrackChanged())
      track.setTextColor(TransitViews.lateColor(row.getContext()));
    return row;
  }
}
