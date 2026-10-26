package app.organicmaps.transit.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.transit.ItineraryItem;
import app.organicmaps.transit.LineBadge;
import app.organicmaps.transit.SavedConnection;
import app.organicmaps.transit.SavedConnectionsStore;
import app.organicmaps.transit.TransitItemFactory;
import app.organicmaps.transit.TransitServices;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.WindowInsetUtils;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * List of the saved connections: date, start and destination, times and lines. Tapping one shows it in the route
 * planner like a searched one; swiping or the menu deletes it (with undo).
 */
public class SavedConnectionsFragment extends Fragment implements SavedConnectionsStore.Listener
{
  private SavedConnectionsStore mStore;
  private TransitItemFactory mItems;
  private RecyclerView mList;
  private TextView mEmpty;
  private final Adapter mAdapter = new Adapter();

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    return inflater.inflate(R.layout.fragment_transit_saved, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    final TransitServices services = TransitServices.get(requireContext());
    mStore = services.getSavedConnections();
    mItems = services.createItemFactory(requireContext());
    mList = view.findViewById(R.id.saved_list);
    mEmpty = view.findViewById(R.id.saved_empty);
    mList.setLayoutManager(new LinearLayoutManager(requireContext()));
    mList.setAdapter(mAdapter);
    ViewCompat.setOnApplyWindowInsetsListener(mList, new WindowInsetUtils.ScrollableContentInsetsListener(mList));
    new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
      @Override
      public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                            @NonNull RecyclerView.ViewHolder target)
      {
        return false;
      }

      @Override
      public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction)
      {
        final int position = viewHolder.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION)
          delete(mAdapter.mEntries.get(position));
      }
    }).attachToRecyclerView(mList);
    mStore.addListener(this);
    refresh();
  }

  @Override
  public void onDestroyView()
  {
    mStore.removeListener(this);
    super.onDestroyView();
  }

  @Override
  public void onSavedConnectionsChanged()
  {
    refresh();
  }

  private void refresh()
  {
    mAdapter.setEntries(mStore.getAll(), Instant.now());
    UiUtils.showIf(mAdapter.mEntries.isEmpty(), mEmpty);
  }

  private void delete(@NonNull SavedConnection saved)
  {
    mStore.remove(saved.getKey());
    Snackbar.make(mList, R.string.transit_deleted, Snackbar.LENGTH_LONG)
        .setAction(R.string.transit_undo, v -> mStore.restore(saved))
        .show();
  }

  private void open(@NonNull SavedConnection saved)
  {
    final Intent intent = new Intent(requireActivity(), MwmActivity.class)
                              .putExtra(MwmActivity.EXTRA_SAVED_CONNECTION, saved.getKey())
                              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(intent);
    requireActivity().finish();
  }

  private final class Adapter extends RecyclerView.Adapter<Holder>
  {
    @NonNull
    private List<SavedConnection> mEntries = new ArrayList<>();
    @NonNull
    private Instant mNow = Instant.EPOCH;

    void setEntries(@NonNull List<SavedConnection> entries, @NonNull Instant now)
    {
      mEntries = entries;
      mNow = now;
      notifyDataSetChanged();
    }

    @Override
    public int getItemCount()
    {
      return mEntries.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
      return new Holder(
          LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transit_saved_connection, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position)
    {
      holder.bind(mEntries.get(position), mNow);
    }
  }

  private final class Holder extends RecyclerView.ViewHolder
  {
    private final View mContent;
    private final TextView mDate;
    private final View mPast;
    private final TextView mRoute;
    private final TextView mDeparture;
    private final TextView mArrival;
    private final TextView mDuration;
    private final ChipGroup mBadges;
    private final View mMore;

    Holder(@NonNull View view)
    {
      super(view);
      mContent = view.findViewById(R.id.saved_content);
      mDate = view.findViewById(R.id.saved_date);
      mPast = view.findViewById(R.id.saved_past);
      mRoute = view.findViewById(R.id.saved_route);
      mDeparture = view.findViewById(R.id.departure);
      mArrival = view.findViewById(R.id.arrival);
      mDuration = view.findViewById(R.id.duration);
      mBadges = view.findViewById(R.id.badges);
      mMore = view.findViewById(R.id.saved_more);
    }

    void bind(@NonNull SavedConnection saved, @NonNull Instant now)
    {
      final TransitItinerary itinerary = saved.getItinerary();
      final ItineraryItem item = mItems.itinerary(itinerary);
      final String zone = itinerary.getLegs().isEmpty() ? null : itinerary.getLegs().get(0).getFrom().getTimeZone();
      mDate.setText(mItems.getFormatter().formatDate(itinerary.getStartTime(), zone));
      final boolean past = saved.isPast(now);
      UiUtils.showIf(past, mPast);
      // Past connections are of less interest.
      mContent.setAlpha(past ? 0.6f : 1f);
      mRoute.setText(getString(R.string.transit_from_to, saved.getFromName(), saved.getToName()));
      TransitViews.bindTime(mDeparture, null, item.getDeparture(), item.getDepartureDelay(), item.isCancelled());
      final String arrival =
          item.getArrivalDayOffset() > 0 ? item.getArrival() + " +" + item.getArrivalDayOffset() : item.getArrival();
      TransitViews.bindTime(mArrival, null, arrival, item.getArrivalDelay(), item.isCancelled());
      mDuration.setText(item.isWalkOnly() ? item.getDuration() : item.getDuration() + " · " + item.getTransfers());

      mBadges.removeAllViews();
      final LayoutInflater inflater = LayoutInflater.from(mBadges.getContext());
      for (LineBadge badge : item.getBadges())
      {
        final TextView view = (TextView) inflater.inflate(R.layout.item_transit_badge, mBadges, false);
        TransitViews.bindBadge(view, badge);
        mBadges.addView(view);
      }
      UiUtils.showIf(!item.getBadges().isEmpty(), mBadges);

      itemView.setOnClickListener(v -> open(saved));
      mMore.setOnClickListener(v -> {
        final PopupMenu menu = new PopupMenu(v.getContext(), v);
        menu.getMenu().add(R.string.transit_delete).setOnMenuItemClickListener(m -> {
          delete(saved);
          return true;
        });
        menu.show();
      });
    }
  }
}
