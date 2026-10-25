package app.organicmaps.transit;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import app.organicmaps.sdk.transit.TransitMode;
import java.util.List;

/**
 * Draws an online itinerary with the map's own route renderer, like a route of the built-in transit router: lines
 * in (the nearest palette color to) their colors, dashed walks, stop marks with names. Removing the route of the
 * routing session ({@link Framework#nativeRemoveRoute()}) removes it as well.
 */
final class MapRouteDisplay implements TransitConnectionsPresenter.RouteDisplay
{
  // Values of the core enum TransitType (libs/map/transit/transit_display.hpp).
  static final int TYPE_PEDESTRIAN = 1;
  static final int TYPE_SUBWAY = 2;
  static final int TYPE_TRAIN = 3;
  static final int TYPE_LIGHT_RAIL = 4;
  static final int TYPE_TRAM = 6;
  static final int TYPE_BUS = 7;
  static final int TYPE_FERRY = 8;
  static final int TYPE_CABLE_TRAM = 9;
  static final int TYPE_AERIAL_LIFT = 10;
  static final int TYPE_FUNICULAR = 11;
  static final int TYPE_AIR_SERVICE = 13;

  @Override
  public void show(@NonNull TransitItinerary itinerary)
  {
    final List<TransitLeg> legs = itinerary.getLegs();
    final int n = legs.size();
    final int[] types = new int[n];
    final int[] colors = new int[n];
    final double[][] polylines = new double[n][];
    final String[] from = new String[n];
    final String[] to = new String[n];
    for (int i = 0; i < n; ++i)
    {
      final TransitLeg leg = legs.get(i);
      types[i] = coreType(leg.getMode());
      final Integer color = leg.isTransit() ? LineBadge.parseColor(leg.getRouteColor()) : null;
      colors[i] = color != null ? (color & 0xFFFFFF) : -1;
      polylines[i] = polyline(leg);
      from[i] = leg.getFrom().isStop() ? leg.getFrom().getName() : "";
      to[i] = leg.getTo().isStop() ? leg.getTo().getName() : "";
    }
    Framework.nativeShowExternalTransitRoute(types, colors, polylines, from, to);
  }

  @Override
  public void clear()
  {
    Framework.nativeRemoveRoute();
  }

  /** The leg geometry, or a straight line between its ends if the server sent none. */
  @NonNull
  static double[] polyline(@NonNull TransitLeg leg)
  {
    final double[] points = leg.getPolyline();
    if (points.length >= 4)
      return points;
    return new double[] {leg.getFrom().getLat(), leg.getFrom().getLon(), leg.getTo().getLat(), leg.getTo().getLon()};
  }

  static int coreType(@NonNull TransitMode mode)
  {
    switch (mode)
    {
    case SUBWAY: return TYPE_SUBWAY;
    case TRAM: return TYPE_TRAM;
    case BUS:
    case COACH:
    case OTHER:
    case TRANSIT: return TYPE_BUS;
    case FERRY: return TYPE_FERRY;
    case CABLE_CAR: return TYPE_CABLE_TRAM;
    case AERIAL_LIFT: return TYPE_AERIAL_LIFT;
    case FUNICULAR: return TYPE_FUNICULAR;
    case AIRPLANE: return TYPE_AIR_SERVICE;
    case SUBURBAN: return TYPE_LIGHT_RAIL;
    case RAIL:
    case HIGHSPEED_RAIL:
    case LONG_DISTANCE:
    case NIGHT_RAIL:
    case REGIONAL_FAST_RAIL:
    case REGIONAL_RAIL: return TYPE_TRAIN;
    default: return mode.isTransit() ? TYPE_BUS : TYPE_PEDESTRIAN;
    }
  }
}
