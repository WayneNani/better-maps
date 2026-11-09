package app.organicmaps.ev;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** JSON of the charging planner (libs/ev_planning/plan_json.hpp). */
public final class EvJson
{
  static final Gson GSON = new Gson();

  private EvJson() {}

  /** @return the result, or a result with the status "error" if the JSON can't be read. */
  @NonNull
  public static EvPlanResult parseResult(@Nullable String json)
  {
    try
    {
      final EvPlanResult result = json == null ? null : GSON.fromJson(json, EvPlanResult.class);
      if (result != null && result.status != null)
        return result.normalize();
    }
    catch (RuntimeException ignored)
    {
      // An error below.
    }
    final EvPlanResult error = new EvPlanResult();
    error.status = EvPlanResult.STATUS_ERROR;
    return error.normalize();
  }

  /** @return the plan of EvPlanner.evaluate(), or null on an error ({"status":...}). */
  @Nullable
  public static EvPlanResult.Plan parsePlan(@Nullable String json)
  {
    if (json == null)
      return null;
    try
    {
      final JsonObject object = GSON.fromJson(json, JsonObject.class);
      if (object == null || object.has("status") || !object.has("stops"))
        return null;
      final EvPlanResult.Plan plan = GSON.fromJson(object, EvPlanResult.Plan.class);
      return plan == null ? null : plan.normalize();
    }
    catch (RuntimeException e)
    {
      return null;
    }
  }

  /** The stops for EvPlanner.evaluate(), see ev::StopInputsFromJson(). */
  @NonNull
  public static String stopsToJson(@NonNull List<EvPlanResult.Stop> stops)
  {
    final JsonArray array = new JsonArray();
    for (EvPlanResult.Stop stop : stops)
    {
      final JsonObject o = new JsonObject();
      o.addProperty("key", stop.key);
      o.addProperty("title", stop.title);
      o.addProperty("lat", stop.lat);
      o.addProperty("lon", stop.lon);
      o.addProperty("powerKw", stop.powerKw);
      o.addProperty("dc", stop.dc);
      o.addProperty("departureSoc", stop.departureSocPct);
      array.add(o);
    }
    return GSON.toJson(array);
  }
}
