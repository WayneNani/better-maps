package app.organicmaps.ev;

import androidx.annotation.NonNull;
import java.util.Locale;

/** English texts without Android resources. */
class FakeEvTexts implements EvTexts
{
  @NonNull
  @Override
  public String summary(@NonNull String driving, @NonNull String charging, @NonNull String arrivalTime, int arrivalSoc)
  {
    return driving + " driving · " + charging + " charging · arrival " + arrivalTime + " with " + arrivalSoc + " %";
  }

  @NonNull
  @Override
  public String distanceAndStops(@NonNull String distance, int stops)
  {
    return distance + " · " + stops + (stops == 1 ? " charging stop" : " charging stops");
  }

  @NonNull
  @Override
  public String noStopNeeded(@NonNull String distance)
  {
    return distance + " · no charging stop needed";
  }

  @NonNull
  @Override
  public String inDistance(@NonNull String distance)
  {
    return "in " + distance;
  }

  @NonNull
  @Override
  public String chargingPoints(int count)
  {
    return count + " charging points";
  }

  @NonNull
  @Override
  public String upToPower(int kw)
  {
    return "up to " + kw + " kW";
  }

  @NonNull
  @Override
  public String arriveAndCharge(int arrivalSoc, int departureSoc)
  {
    return "arrive " + arrivalSoc + " % → " + departureSoc + " %";
  }

  @NonNull
  @Override
  public String charge(int arrivalSoc, int departureSoc)
  {
    return "Charge " + arrivalSoc + " % → " + departureSoc + " %";
  }

  @NonNull
  @Override
  public String chargeLonger(int departureSoc, @NonNull String extraTime, int arrivalSoc)
  {
    return "Charge longer → " + departureSoc + " % (" + extraTime + "), arrival with " + arrivalSoc + " %";
  }

  @NonNull
  @Override
  public String chargeLongerLaterShorter(int departureSoc, @NonNull String extraTime, @NonNull String savedTime,
                                         int arrivalSoc)
  {
    return "Charge longer → " + departureSoc + " % (" + extraTime + "), later stops " + savedTime
  + " shorter, arrival with " + arrivalSoc + " %";
  }

  @NonNull
  @Override
  public String chargeLongerStopNotNeeded(int departureSoc, @NonNull String extraTime, @NonNull String stop,
                                          int arrivalSoc)
  {
    return "Charge longer → " + departureSoc + " % (" + extraTime + "), stop at " + stop + " not needed, arrival with "
  + arrivalSoc + " %";
  }

  @NonNull
  @Override
  public String chargeLongerStopsNotNeeded(int departureSoc, @NonNull String extraTime, int stops, int arrivalSoc)
  {
    return "Charge longer → " + departureSoc + " % (" + extraTime + "), " + stops
  + (stops == 1 ? " later stop" : " later stops") + " not needed, arrival with " + arrivalSoc + " %";
  }

  @NonNull
  @Override
  public String socketName(@NonNull String type)
  {
    return switch (type)
    {
      case "type2_combo" -> "CCS";
      case "type2" -> "Type 2";
      default -> type;
    };
  }

  @NonNull
  @Override
  public String amenityName(@NonNull String type, @NonNull String kind)
  {
    return type + ("services".equals(kind) ? " (services)" : "");
  }

  @NonNull
  @Override
  public String openOnArrival(boolean open)
  {
    return open ? "open on arrival" : "closed on arrival";
  }

  @NonNull
  @Override
  public String alternativeTitle(@NonNull String stations)
  {
    return "Alternative: " + stations;
  }

  @NonNull
  @Override
  public String alternativeStops(int stops, int mainStops)
  {
    return stops + (stops > mainStops ? " shorter" : stops < mainStops ? " longer" : " other") + " stops";
  }

  @NonNull
  @Override
  public String alternativeArrival(int deltaMinutes)
  {
    return deltaMinutes == 0 ? "same arrival"
  : deltaMinutes > 0         ? "arrival " + deltaMinutes + " min later"
                             : "arrival " + -deltaMinutes + " min earlier";
  }

  @NonNull
  @Override
  public String alternativeSoc(int arrivalSoc)
  {
    return "with " + arrivalSoc + " %";
  }

  @NonNull
  @Override
  public String unreachable(@NonNull String from, @NonNull String to)
  {
    return "unreachable between " + from + " and " + to;
  }

  @NonNull
  @Override
  public String noRoute()
  {
    return "no route";
  }

  @NonNull
  @Override
  public String error()
  {
    return "error";
  }

  @NonNull
  @Override
  public String stationHeader(@NonNull String provider, @NonNull String openingHours)
  {
    return "Charging station · " + provider + " · " + openingHours;
  }

  @NonNull
  @Override
  public String availability(@NonNull String availability)
  {
    return "Occupancy: " + availability;
  }

  @NonNull
  @Override
  public String distance(double meters)
  {
    return EvFormatter.distance(meters, Locale.ROOT);
  }
}
