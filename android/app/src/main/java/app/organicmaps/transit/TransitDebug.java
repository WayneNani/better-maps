package app.organicmaps.transit;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitDeparture;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.util.log.Logger;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Developer helpers for the online public transport service until the real UI exists (AP3b).
 * Not localized on purpose.
 */
public final class TransitDebug
{
  private static final String TAG = TransitDebug.class.getSimpleName();
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

  // Berlin Hauptbahnhof -> Alexanderplatz.
  private static final TransitLocation BERLIN_HBF = TransitLocation.fromCoordinates(52.5250, 13.3694);
  private static final TransitLocation ALEXANDERPLATZ = TransitLocation.fromCoordinates(52.5219, 13.4132);

  private TransitDebug() {}

  /** Requests connections Berlin Hbf -> Alexanderplatz, logs them and shows them in a dialog. */
  public static void planBerlinHbfToAlexanderplatz(@NonNull Context context)
  {
    Logger.i(TAG, "Requesting plan " + BERLIN_HBF + " -> " + ALEXANDERPLATZ);
    TransitServices.get(context)
        .plan(BERLIN_HBF, ALEXANDERPLATZ, null, false, null, TransitRoutingOptions.defaults())
        .onSuccess(plan -> {
          final String text = formatPlan(plan);
          Logger.i(TAG, "Plan result:\n" + text);
          showDialog(context, "Berlin Hbf → Alexanderplatz", text);
        })
        .onFailure(error -> {
          Logger.e(TAG, "Plan failed", error);
          showDialog(context, "Transit plan failed", String.valueOf(error.getMessage()));
        });
  }

  @NonNull
  public static String formatPlan(@NonNull TransitPlan plan)
  {
    if (plan.isEmpty())
      return "No connections found.";
    final StringBuilder sb = new StringBuilder();
    for (TransitItinerary itinerary : plan.getItineraries())
    {
      sb.append(time(itinerary.getStartTime())).append(" – ").append(time(itinerary.getEndTime()));
      sb.append(" (").append(itinerary.getDurationSeconds() / 60).append(" min, ");
      sb.append(itinerary.getTransfers()).append(" transfers)\n");
      for (TransitLeg leg : itinerary.getLegs())
      {
        sb.append("  ").append(time(leg.getStartTime())).append(delay(leg.getDepartureDelaySeconds())).append(' ');
        if (leg.isTransit())
        {
          sb.append(leg.getLineName()).append(" → ").append(leg.getDirection());
          sb.append(" (").append(leg.getFrom().getName());
          if (leg.getFrom().getTrack() != null)
            sb.append(", track ").append(leg.getFrom().getTrack());
          sb.append(')');
          if (leg.isRealTime())
            sb.append(" [RT]");
          if (leg.isCancelled())
            sb.append(" [CANCELLED]");
        }
        else
        {
          sb.append(leg.getMode().name().toLowerCase()).append(' ');
          if (leg.getDistanceMeters() != null)
            sb.append(Math.round(leg.getDistanceMeters())).append(" m ");
          sb.append("to ").append(leg.getTo().getName());
        }
        sb.append('\n');
      }
      sb.append('\n');
    }
    sb.append("next page: ").append(plan.getNextPageCursor());
    return sb.toString();
  }

  @NonNull
  public static String formatDepartures(@NonNull TransitDepartures departures)
  {
    final StringBuilder sb = new StringBuilder(departures.getStop().getName()).append("\n\n");
    for (TransitDeparture departure : departures.getDepartures())
    {
      sb.append(time(departure.getDeparture())).append(delay(departure.getDelaySeconds())).append("  ");
      sb.append(departure.getLineName()).append(" → ").append(departure.getDirection());
      if (departure.getPlace().getTrack() != null)
        sb.append(" (").append(departure.getPlace().getTrack()).append(')');
      if (departure.isCancelled())
        sb.append(" [CANCELLED]");
      sb.append('\n');
    }
    return sb.toString();
  }

  @NonNull
  private static String time(Instant instant)
  {
    return instant == null ? "--:--" : TIME.format(instant);
  }

  @NonNull
  private static String delay(long seconds)
  {
    final long minutes = Math.round(seconds / 60.0);
    if (minutes == 0)
      return "";
    return minutes > 0 ? " +" + minutes : " " + minutes;
  }

  private static void showDialog(@NonNull Context context, @NonNull String title, @NonNull String message)
  {
    new MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }
}
