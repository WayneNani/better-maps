package app.organicmaps.transit;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import java.time.Instant;

/** A connection the user saved, with the start and destination of the search it came from. */
public final class SavedConnection
{
  @NonNull
  private final String mKey;
  @NonNull
  private final TransitItinerary mItinerary;
  @NonNull
  private final String mFromName;
  @NonNull
  private final String mToName;
  @NonNull
  private final TransitLocation mFrom;
  @NonNull
  private final TransitLocation mTo;
  @NonNull
  private final Instant mSavedAt;

  SavedConnection(@NonNull TransitItinerary itinerary, @NonNull String fromName, @NonNull String toName,
                  @NonNull TransitLocation from, @NonNull TransitLocation to, @NonNull Instant savedAt)
  {
    mKey = TransitItemFactory.key(itinerary);
    mItinerary = itinerary;
    mFromName = fromName;
    mToName = toName;
    mFrom = from;
    mTo = to;
    mSavedAt = savedAt;
  }

  /** Identity of the connection, see {@link TransitItemFactory#key}. */
  @NonNull
  public String getKey()
  {
    return mKey;
  }

  @NonNull
  public TransitItinerary getItinerary()
  {
    return mItinerary;
  }

  /** Name of the start, e.g. "Hauptbahnhof". */
  @NonNull
  public String getFromName()
  {
    return mFromName;
  }

  @NonNull
  public String getToName()
  {
    return mToName;
  }

  /** Coordinates of the start, for searching again. */
  @NonNull
  public TransitLocation getFrom()
  {
    return mFrom;
  }

  @NonNull
  public TransitLocation getTo()
  {
    return mTo;
  }

  @NonNull
  public Instant getSavedAt()
  {
    return mSavedAt;
  }

  /** True if the connection has arrived before {@code now}. */
  public boolean isPast(@NonNull Instant now)
  {
    return mItinerary.getEndTime().isBefore(now);
  }
}
