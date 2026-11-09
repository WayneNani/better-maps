package app.organicmaps.ev;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/** The bar of a charging plan: driving and charging pieces over the total time. Without Android. */
public final class EvBarLayout
{
  public enum Kind
  {
    DRIVE,
    CHARGE
  }

  public static final class Piece
  {
    public final Kind kind;
    /** Share of the total time, 0..1. */
    public final double fraction;

    Piece(@NonNull Kind kind, double fraction)
    {
      this.kind = kind;
      this.fraction = fraction;
    }
  }

  private EvBarLayout() {}

  @NonNull
  public static List<Piece> layout(@NonNull EvPlanResult.Plan plan)
  {
    final List<Piece> pieces = new ArrayList<>();
    final double total = plan.totalTimeSec;
    if (total <= 0)
      return pieces;
    double time = 0;
    for (EvPlanResult.Stop stop : plan.stops)
    {
      add(pieces, Kind.DRIVE, (stop.arrivalTimeSec - time) / total);
      add(pieces, Kind.CHARGE, stop.stopTimeSec / total);
      time = stop.arrivalTimeSec + stop.stopTimeSec;
    }
    add(pieces, Kind.DRIVE, (total - time) / total);
    return pieces;
  }

  private static void add(@NonNull List<Piece> pieces, @NonNull Kind kind, double fraction)
  {
    if (fraction > 0)
      pieces.add(new Piece(kind, Math.min(1, fraction)));
  }
}
