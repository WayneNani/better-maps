package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitDeparture;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import app.organicmaps.sdk.transit.TransitPlace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Converts SDK models to formatted view models. */
public final class TransitItemFactory
{
  @NonNull
  private final TransitFormatter mFormatter;

  public TransitItemFactory(@NonNull TransitFormatter formatter)
  {
    mFormatter = formatter;
  }

  @NonNull
  public TransitFormatter getFormatter()
  {
    return mFormatter;
  }

  @NonNull
  public static LineBadge badge(@NonNull TransitLeg leg)
  {
    return LineBadge.create(leg.getLineName(), leg.getMode(), leg.getRouteColor(), leg.getRouteTextColor());
  }

  @NonNull
  public static LineBadge badge(@NonNull TransitDeparture departure)
  {
    return LineBadge.create(departure.getLineName(), departure.getMode(), departure.getRouteColor(),
                            departure.getRouteTextColor());
  }

  @NonNull
  public ItineraryItem itinerary(@NonNull TransitItinerary itinerary)
  {
    final List<LineBadge> badges = new ArrayList<>();
    for (TransitLeg leg : itinerary.getLegs())
      if (leg.isTransit())
        badges.add(badge(leg));

    final TransitLeg first = itinerary.getFirstTransitLeg();
    final TransitLeg last = itinerary.getLastTransitLeg();
    final String startZone = itinerary.getLegs().isEmpty() ? null : itinerary.getLegs().get(0).getFrom().getTimeZone();
    final String endZone = itinerary.getLegs().isEmpty()
                             ? null
                             : itinerary.getLegs().get(itinerary.getLegs().size() - 1).getTo().getTimeZone();

    String boarding = null;
    if (first != null)
    {
      boarding = mFormatter.placeName(first.getFrom());
      final String track = first.getFrom().getTrack();
      if (track != null && !track.isEmpty())
        boarding += " · " + mFormatter.getTexts().track(track);
    }

    final boolean transit = first != null;
    return new ItineraryItem(
        key(itinerary), mFormatter.formatTime(itinerary.getStartTime(), startZone),
        transit ? mFormatter.delay(first.getDepartureDelaySeconds(), first.isRealTime())
                : TransitFormatter.Delay.UNKNOWN,
        mFormatter.formatTime(itinerary.getEndTime(), endZone),
        transit ? mFormatter.delay(last.getArrivalDelaySeconds(), last.isRealTime()) : TransitFormatter.Delay.UNKNOWN,
        mFormatter.dayOffset(itinerary.getStartTime(), itinerary.getEndTime(), endZone),
        mFormatter.formatDuration(itinerary.getDurationSeconds()), mFormatter.formatTransfers(itinerary.getTransfers()),
        Collections.unmodifiableList(badges), boarding, itinerary.isCancelled(), !transit, itinerary.hasRealTime());
  }

  /** Server id, or a key from times and lines for servers without ids. */
  @NonNull
  public static String key(@NonNull TransitItinerary itinerary)
  {
    if (!itinerary.getId().isEmpty())
      return itinerary.getId();
    final StringBuilder sb = new StringBuilder();
    sb.append(itinerary.getStartTime().getEpochSecond()).append('-').append(itinerary.getEndTime().getEpochSecond());
    for (TransitLeg leg : itinerary.getLegs())
      sb.append('-').append(leg.getMode()).append(':').append(leg.getLineName());
    return sb.toString();
  }

  @NonNull
  public List<LegItem> legs(@NonNull TransitItinerary itinerary, @NonNull Set<Integer> expanded)
  {
    final List<LegItem> result = new ArrayList<>();
    final List<TransitLeg> legs = itinerary.getLegs();
    for (int i = 0; i < legs.size(); ++i)
    {
      final TransitLeg leg = legs.get(i);
      // Very short transfers within a station (a few meters) only add noise.
      if (leg.isWalk() && leg.getDurationSeconds() < 60 && i > 0 && i < legs.size() - 1)
        continue;
      result.add(leg(i, leg, expanded.contains(i)));
    }
    return Collections.unmodifiableList(result);
  }

  @NonNull
  LegItem leg(int index, @NonNull TransitLeg leg, boolean expanded)
  {
    final TransitPlace from = leg.getFrom();
    final TransitPlace to = leg.getTo();
    final String duration = mFormatter.formatDuration(leg.getDurationSeconds());
    final LegItem.Type type =
        leg.isTransit() ? LegItem.Type.TRANSIT : (leg.isWalk() ? LegItem.Type.WALK : LegItem.Type.OTHER);

    final List<LegItem.Stop> stops = new ArrayList<>();
    String summary;
    if (type == LegItem.Type.TRANSIT)
    {
      for (TransitPlace stop : leg.getIntermediateStops())
      {
        final Instant time = stop.getDeparture() != null ? stop.getDeparture() : stop.getArrival();
        final Long delay =
            stop.getDeparture() != null ? stop.getDepartureDelaySeconds() : stop.getArrivalDelaySeconds();
        stops.add(new LegItem.Stop(stop.getName(), mFormatter.formatTime(time, stop.getTimeZone()),
                                   mFormatter.delay(delay, leg.isRealTime()), stop.isCancelled()));
      }
      summary = duration;
      if (!stops.isEmpty())
        summary += " · " + mFormatter.getTexts().stops(stops.size() + 1);
    }
    else
    {
      final Double meters = leg.getDistanceMeters();
      summary = mFormatter.getTexts().walk(duration, meters != null ? mFormatter.getTexts().distance(meters) : "");
    }

    return new LegItem(index, type, type == LegItem.Type.TRANSIT ? badge(leg) : null, leg.getDirection(),
                       mFormatter.placeName(from), mFormatter.placeName(to),
                       mFormatter.formatTime(leg.getStartTime(), from.getTimeZone()),
                       type == LegItem.Type.TRANSIT ? mFormatter.delay(leg.getDepartureDelaySeconds(), leg.isRealTime())
                                                    : TransitFormatter.Delay.UNKNOWN,
                       mFormatter.formatTime(leg.getEndTime(), to.getTimeZone()),
                       type == LegItem.Type.TRANSIT ? mFormatter.delay(leg.getArrivalDelaySeconds(), leg.isRealTime())
                                                    : TransitFormatter.Delay.UNKNOWN,
                       track(from.getTrack()), from.isTrackChanged(), track(to.getTrack()), to.isTrackChanged(),
                       summary, Collections.unmodifiableList(stops), expanded && !stops.isEmpty(), leg.isCancelled(),
                       leg.getAlerts(), leg.getAgencyName());
  }

  @NonNull
  public DepartureItem departure(@NonNull TransitDeparture departure)
  {
    final TransitPlace place = departure.getPlace();
    return new DepartureItem(badge(departure), departure.getDirection(),
                             mFormatter.formatTime(departure.getDeparture(), place.getTimeZone()),
                             mFormatter.delay(departure.getDelaySeconds(), departure.isRealTime()),
                             track(place.getTrack()), place.isTrackChanged(), departure.isCancelled());
  }

  @Nullable
  private String track(@Nullable String track)
  {
    return track == null || track.isEmpty() ? null : mFormatter.getTexts().track(track);
  }
}
