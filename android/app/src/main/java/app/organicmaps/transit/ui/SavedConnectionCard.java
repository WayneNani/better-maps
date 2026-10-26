package app.organicmaps.transit.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.transit.LineBadge;
import app.organicmaps.transit.SavedConnection;
import app.organicmaps.transit.SavedConnectionsStore;
import app.organicmaps.transit.TransitConnectionsPresenter;
import app.organicmaps.transit.TransitItemFactory;
import app.organicmaps.transit.TransitServices;
import app.organicmaps.transit.TransitSettings;
import app.organicmaps.transit.UpcomingConnection;
import app.organicmaps.util.UiUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Card on the map with the next saved connection (see {@link UpcomingConnection}), e.g.
 * "11:27 [ICE 175] in 25 min / Hauptbahnhof → Tempelhof" or "11:27 – 11:57 [ICE 175] on the way". Tapping it opens the
 * connection in the route planner; the counter opens the list of saved connections; the close button hides this
 * connection on the map. <p> Shown only if the setting allows it, there is an upcoming connection and the host has room
 * for it (not during route planning, navigation, with a place page or the search). It follows changes of the saved
 * connections and updates its time ("in 25 min") every minute while started.
 */
public final class SavedConnectionCard implements SavedConnectionsStore.Listener
{
  /** At most this many line badges; the card stays one line high. */
  private static final int MAX_BADGES = 3;

  public interface Host
  {
    /** @return false while the space is needed otherwise (route planning, navigation, place page, search...). */
    boolean canShowSavedConnection();

    /** Opens the connection like from the list of saved connections. */
    void openSavedConnection(@NonNull SavedConnection saved);

    /** The card appeared, disappeared or changed its height (e.g. to move map widgets above it). */
    void onSavedConnectionCardChanged();
  }

  @NonNull
  private final Activity mActivity;
  @NonNull
  private final View mCard;
  @NonNull
  private final Host mHost;
  @NonNull
  private final SavedConnectionsStore mStore;
  @NonNull
  private final TransitSettings mSettings;
  @NonNull
  private final TransitItemFactory mItems;
  @NonNull
  private final TransitConnectionsPresenter.Clock mClock;
  private final View mContent;
  private final TextView mTime;
  private final LinearLayout mBadges;
  private final TextView mWhen;
  private final TextView mRoute;
  private final View mAll;
  private final TextView mCount;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final Runnable mTick = this::update;
  private boolean mStarted;
  @Nullable
  private UpcomingConnection mShown;

  public SavedConnectionCard(@NonNull Activity activity, @NonNull View card, @NonNull Host host)
  {
    this(activity, card, host, Instant::now);
  }

  public SavedConnectionCard(@NonNull Activity activity, @NonNull View card, @NonNull Host host,
                             @NonNull TransitConnectionsPresenter.Clock clock)
  {
    mActivity = activity;
    mCard = card;
    mHost = host;
    mClock = clock;
    final TransitServices services = TransitServices.get(activity);
    mStore = services.getSavedConnections();
    mSettings = services.getSettings();
    mItems = services.createItemFactory(activity);
    mContent = card.findViewById(R.id.transit_card_content);
    mTime = card.findViewById(R.id.transit_card_time);
    mBadges = card.findViewById(R.id.transit_card_badges);
    mWhen = card.findViewById(R.id.transit_card_when);
    mRoute = card.findViewById(R.id.transit_card_route);
    mAll = card.findViewById(R.id.transit_card_all);
    mCount = card.findViewById(R.id.transit_card_count);

    mContent.setOnClickListener(v -> {
      if (mShown != null)
        mHost.openSavedConnection(mShown.getSaved());
    });
    mAll.setOnClickListener(v -> SavedConnectionsActivity.start(mActivity));
    card.findViewById(R.id.transit_card_hide).setOnClickListener(v -> {
      if (mShown == null)
        return;
      final Set<String> keys = new HashSet<>();
      for (SavedConnection saved : mStore.getAll())
        keys.add(saved.getKey());
      mSettings.hideOnMap(mShown.getSaved().getKey(), keys);
      update();
    });
    card.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
      if (b - t != oldB - oldT)
        mHost.onSavedConnectionCardChanged();
    });
  }

  /** Follows the saved connections and the time; call when the activity starts. */
  public void start()
  {
    if (mStarted)
      return;
    mStarted = true;
    mStore.addListener(this);
    update();
  }

  public void stop()
  {
    if (!mStarted)
      return;
    mStarted = false;
    mStore.removeListener(this);
    mHandler.removeCallbacks(mTick);
  }

  @Override
  public void onSavedConnectionsChanged()
  {
    update();
  }

  public boolean isShown()
  {
    return UiUtils.isVisible(mCard);
  }

  /** Height of the card including its margins, 0 if hidden. */
  public int getOccupiedHeight()
  {
    if (!isShown())
      return 0;
    final ViewGroup.LayoutParams params = mCard.getLayoutParams();
    int margins = 0;
    if (params instanceof ViewGroup.MarginLayoutParams margin)
      margins = margin.topMargin + margin.bottomMargin;
    return mCard.getHeight() + margins;
  }

  /** Shows, updates or hides the card; call also when the host's room for it changes. */
  public void update()
  {
    mHandler.removeCallbacks(mTick);
    final Instant now = mClock.now();
    final UpcomingConnection next =
        mStarted && mSettings.isShownOnMap()
            ? UpcomingConnection.find(mStore.getAll(), now, mSettings.getHiddenOnMap(), mItems)
            : null;
    mShown = next;
    final boolean show = next != null && mHost.canShowSavedConnection();
    final boolean changed = show != isShown();
    UiUtils.showIf(show, mCard);
    if (next != null)
    {
      bind(next);
      // E.g. "in 25 min" -> "in 24 min"; also hides the card after the arrival.
      final Duration delay = UpcomingConnection.untilNextChange(next.getSaved(), now);
      mHandler.postDelayed(mTick, delay.toMillis() + 50);
    }
    if (changed)
      mHost.onSavedConnectionCardChanged();
  }

  private void bind(@NonNull UpcomingConnection next)
  {
    final SavedConnection saved = next.getSaved();
    // On the way, the arrival matters: "11:27 – 11:57".
    final String time = next.isOnTheWay() ? next.getItem().getDeparture() + " – " + next.getItem().getArrival()
                                          : next.getItem().getDeparture();
    mTime.setText(time);
    mWhen.setText(next.getWhen());
    final String route = mActivity.getString(R.string.transit_from_to, saved.getFromName(), saved.getToName());
    mRoute.setText(" · " + route);

    mBadges.removeAllViews();
    final List<LineBadge> badges = next.getItem().getBadges();
    final StringBuilder lines = new StringBuilder();
    final LayoutInflater inflater = LayoutInflater.from(mBadges.getContext());
    final int gap = mActivity.getResources().getDimensionPixelSize(R.dimen.margin_quarter);
    for (int i = 0; i < badges.size() && i < MAX_BADGES; ++i)
    {
      final TextView view = (TextView) inflater.inflate(R.layout.item_transit_badge, mBadges, false);
      TransitViews.bindBadge(view, badges.get(i));
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
      params.setMarginEnd(gap);
      mBadges.addView(view, params);
      lines.append(' ').append(badges.get(i).getText());
    }
    mContent.setContentDescription(
        mActivity.getString(R.string.transit_card_open, time + lines + ", " + route + ", " + next.getWhen()));
    final int count = mStore.size();
    mCount.setText(String.valueOf(count));
    mAll.setContentDescription(mActivity.getString(R.string.transit_card_all, count));
  }
}
