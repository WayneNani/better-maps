package app.organicmaps.transit;

import androidx.annotation.NonNull;
import java.util.Locale;

/** Simple English texts for tests. */
class FakeTexts implements TransitTexts
{
  @NonNull
  @Override
  public String minutes(long minutes)
  {
    return minutes + " min";
  }

  @NonNull
  @Override
  public String hoursMinutes(long hours, long minutes)
  {
    return minutes == 0 ? hours + " h" : hours + " h " + minutes + " min";
  }

  @NonNull
  @Override
  public String transfers(int transfers)
  {
    return transfers == 0 ? "direct" : transfers + (transfers == 1 ? " transfer" : " transfers");
  }

  @NonNull
  @Override
  public String departNow()
  {
    return "Depart now";
  }

  @NonNull
  @Override
  public String departAt(@NonNull String time)
  {
    return "Depart " + time;
  }

  @NonNull
  @Override
  public String arriveBy(@NonNull String time)
  {
    return "Arrive by " + time;
  }

  @NonNull
  @Override
  public String track(@NonNull String track)
  {
    return "Pl. " + track;
  }

  @NonNull
  @Override
  public String walk(@NonNull String duration, @NonNull String distance)
  {
    return "Walk " + duration + (distance.isEmpty() ? "" : " (" + distance + ")");
  }

  @NonNull
  @Override
  public String distance(double meters)
  {
    return meters < 1000 ? Math.round(meters) + " m" : String.format(Locale.ROOT, "%.1f km", meters / 1000);
  }

  @NonNull
  @Override
  public String start()
  {
    return "Start";
  }

  @NonNull
  @Override
  public String destination()
  {
    return "Destination";
  }

  @NonNull
  @Override
  public String stops(int count)
  {
    return count + " stops";
  }

  @NonNull
  @Override
  public String searching()
  {
    return "Searching";
  }

  @NonNull
  @Override
  public String noConnections()
  {
    return "No connections";
  }

  @NonNull
  @Override
  public String connections(int count)
  {
    return count + (count == 1 ? " connection" : " connections");
  }

  @NonNull
  @Override
  public String myPosition()
  {
    return "My position";
  }

  @NonNull
  @Override
  public String inTime(@NonNull String duration)
  {
    return "in " + duration;
  }

  @NonNull
  @Override
  public String departingNow()
  {
    return "now";
  }

  @NonNull
  @Override
  public String onTheWay()
  {
    return "on the way";
  }
}
