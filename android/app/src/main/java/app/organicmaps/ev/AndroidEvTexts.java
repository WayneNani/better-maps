package app.organicmaps.ev;

import android.content.Context;
import android.content.res.Resources;
import androidx.annotation.NonNull;
import app.organicmaps.R;
import java.util.Locale;

/** {@link EvTexts} from the string resources. */
public class AndroidEvTexts implements EvTexts
{
  @NonNull
  private final Context mContext;
  @NonNull
  private final Resources mRes;

  public AndroidEvTexts(@NonNull Context context)
  {
    mContext = context;
    mRes = context.getResources();
  }

  @NonNull
  @Override
  public String summary(@NonNull String driving, @NonNull String charging, @NonNull String arrivalTime, int arrivalSoc)
  {
    return mContext.getString(R.string.ev_summary, driving, charging, arrivalTime, arrivalSoc);
  }

  @NonNull
  @Override
  public String distanceAndStops(@NonNull String distance, int stops)
  {
    return mRes.getQuantityString(R.plurals.ev_distance_and_stops, stops, distance, stops);
  }

  @NonNull
  @Override
  public String noStopNeeded(@NonNull String distance)
  {
    return mContext.getString(R.string.ev_no_stop_needed, distance);
  }

  @NonNull
  @Override
  public String inDistance(@NonNull String distance)
  {
    return mContext.getString(R.string.ev_in_distance, distance);
  }

  @NonNull
  @Override
  public String chargingPoints(int count)
  {
    return mRes.getQuantityString(R.plurals.ev_charging_points, count, count);
  }

  @NonNull
  @Override
  public String upToPower(int kw)
  {
    return mContext.getString(R.string.ev_up_to_power, kw);
  }

  @NonNull
  @Override
  public String arriveAndCharge(int arrivalSoc, int departureSoc)
  {
    return mContext.getString(R.string.ev_arrive_and_charge, arrivalSoc, departureSoc);
  }

  @NonNull
  @Override
  public String charge(int arrivalSoc, int departureSoc)
  {
    return mContext.getString(R.string.ev_charge, arrivalSoc, departureSoc);
  }

  @NonNull
  @Override
  public String chargeLonger(int departureSoc, @NonNull String extraTime, int arrivalSoc)
  {
    return mContext.getString(R.string.ev_charge_longer, departureSoc, extraTime, arrivalSoc);
  }

  @NonNull
  @Override
  public String socketName(@NonNull String type)
  {
    switch (type)
    {
    case EvVehicleProfile.SOCKET_CCS: return mContext.getString(R.string.ev_socket_ccs);
    case EvVehicleProfile.SOCKET_TYPE2:
    case "type2_cable": return mContext.getString(R.string.ev_socket_type2);
    case EvVehicleProfile.SOCKET_CHADEMO: return mContext.getString(R.string.ev_socket_chademo);
    case "schuko": return "Schuko";
    case "nacs": return "NACS";
    default: return type;
    }
  }

  @NonNull
  @Override
  public String amenityName(@NonNull String type, @NonNull String kind)
  {
    final String services = mContext.getString(R.string.ev_services);
    switch (type)
    {
    case EvPlanResult.Amenity.PLAYGROUND: return "🛝 " + mContext.getString(R.string.ev_amenity_playground);
    case EvPlanResult.Amenity.TOILETS:
      return "🚻 " + mContext.getString(R.string.ev_amenity_toilets)
    + ("services".equals(kind) ? " (" + services + ")" : "");
    case EvPlanResult.Amenity.FOOD: return "🍽 " + kindName(kind, R.string.ev_amenity_food);
    case EvPlanResult.Amenity.SHOPPING: return "🛒 " + kindName(kind, R.string.ev_amenity_shopping);
    default: return kind;
    }
  }

  @NonNull
  private String kindName(@NonNull String kind, int fallback)
  {
    switch (kind)
    {
    case "services": return mContext.getString(fallback) + " (" + mContext.getString(R.string.ev_services) + ")";
    case "restaurant": return mContext.getString(R.string.ev_kind_restaurant);
    case "fast_food": return mContext.getString(R.string.ev_kind_fast_food);
    case "cafe": return mContext.getString(R.string.ev_kind_cafe);
    case "supermarket": return mContext.getString(R.string.ev_kind_supermarket);
    case "convenience": return mContext.getString(R.string.ev_kind_convenience);
    case "bakery": return mContext.getString(R.string.ev_kind_bakery);
    default: return mContext.getString(fallback);
    }
  }

  @NonNull
  @Override
  public String openOnArrival(boolean open)
  {
    return mContext.getString(open ? R.string.ev_open_on_arrival : R.string.ev_closed_on_arrival);
  }

  @NonNull
  @Override
  public String alternativeTitle(@NonNull String stations)
  {
    return mContext.getString(R.string.ev_alternative, stations);
  }

  @NonNull
  @Override
  public String alternativeStops(int stops, int mainStops)
  {
    final int res = stops > mainStops ? R.plurals.ev_alternative_more_stops
                  : stops < mainStops ? R.plurals.ev_alternative_fewer_stops
                                      : R.plurals.ev_alternative_same_stops;
    return mRes.getQuantityString(res, stops, stops);
  }

  @NonNull
  @Override
  public String alternativeArrival(int deltaMinutes)
  {
    if (deltaMinutes > 0)
      return mContext.getString(R.string.ev_alternative_later, deltaMinutes);
    if (deltaMinutes < 0)
      return mContext.getString(R.string.ev_alternative_earlier, -deltaMinutes);
    return mContext.getString(R.string.ev_alternative_same_time);
  }

  @NonNull
  @Override
  public String alternativeSoc(int arrivalSoc)
  {
    return mContext.getString(R.string.ev_alternative_soc, arrivalSoc);
  }

  @NonNull
  @Override
  public String unreachable(@NonNull String from, @NonNull String to)
  {
    return mContext.getString(R.string.ev_unreachable, from, to);
  }

  @NonNull
  @Override
  public String noRoute()
  {
    return mContext.getString(R.string.ev_no_route);
  }

  @NonNull
  @Override
  public String error()
  {
    return mContext.getString(R.string.ev_error);
  }

  @NonNull
  @Override
  public String stationHeader(@NonNull String provider, @NonNull String openingHours)
  {
    final StringBuilder sb = new StringBuilder(mContext.getString(R.string.ev_station));
    if (!provider.isEmpty())
      sb.append(" · ").append(provider);
    if (!openingHours.isEmpty())
      sb.append(" · ").append(openingHours);
    return sb.toString();
  }

  @NonNull
  @Override
  public String availability(@NonNull String availability)
  {
    switch (availability)
    {
    case "available": return mContext.getString(R.string.ev_availability_available);
    case "occupied": return mContext.getString(R.string.ev_availability_occupied);
    default: return mContext.getString(R.string.ev_availability_unknown);
    }
  }

  @NonNull
  @Override
  public String distance(double meters)
  {
    return EvFormatter.distance(meters, Locale.getDefault());
  }
}
