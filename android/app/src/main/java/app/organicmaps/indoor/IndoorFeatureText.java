package app.organicmaps.indoor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import app.organicmaps.R;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** better-maps: texts of the details of a tapped indoor room or place, without Android views (testable). */
public final class IndoorFeatureText
{
  /** Resolves string resources (Context#getString in the app, a fake in tests). */
  public interface Strings
  {
    @NonNull
    String get(@StringRes int id, @NonNull Object... args);
  }

  private IndoorFeatureText() {}

  /** String resource of a kind from the server ("shop", "gate", ...). */
  @StringRes
  public static int kindLabel(@NonNull String kind)
  {
    switch (kind)
    {
    case "level": return R.string.indoor_kind_level;
    case "corridor": return R.string.indoor_kind_corridor;
    case "room": return R.string.indoor_kind_room;
    case "area": return R.string.indoor_kind_area;
    case "shop": return R.string.indoor_kind_shop;
    case "food": return R.string.indoor_kind_food;
    case "toilets": return R.string.indoor_kind_toilets;
    case "stairs":
    case "steps": return R.string.indoor_kind_stairs;
    case "elevator": return R.string.indoor_kind_elevator;
    case "escalator": return R.string.indoor_kind_escalator;
    case "gate": return R.string.indoor_kind_gate;
    case "service": return R.string.indoor_kind_service;
    case "platform": return R.string.indoor_kind_platform;
    case "restricted": return R.string.indoor_kind_restricted;
    case "door": return R.string.indoor_kind_door;
    case "entrance": return R.string.indoor_kind_entrance;
    case "info": return R.string.indoor_kind_info;
    case "atm": return R.string.indoor_kind_atm;
    case "pharmacy": return R.string.indoor_kind_pharmacy;
    case "luggage": return R.string.indoor_kind_luggage;
    case "ticket": return R.string.indoor_kind_ticket;
    default: return R.string.indoor_kind_poi;
    }
  }

  /** Title: the name; else a gate's "Gate B44", another ref; else the kind. */
  @NonNull
  public static String title(@NonNull Strings s, @NonNull String name, @NonNull String ref, @NonNull String kind)
  {
    if (!name.isEmpty())
      return name;
    if (!ref.isEmpty())
      return "gate".equals(kind) ? s.get(R.string.indoor_gate, ref) : ref;
    return s.get(kindLabel(kind));
  }

  /** Subtitle: "Shop · Level 0 · Terminal 1", with the ref if the title is the name. */
  @NonNull
  public static String subtitle(@NonNull Strings s, @NonNull String name, @NonNull String ref, @NonNull String kind,
                                @NonNull String levelLabel, @NonNull String venueName)
  {
    final List<String> parts = new ArrayList<>();
    if (!name.isEmpty() && !ref.isEmpty())
      parts.add("gate".equals(kind) ? s.get(R.string.indoor_gate, ref) : s.get(R.string.indoor_ref, ref));
    if (!name.isEmpty() || !ref.isEmpty())
      parts.add(s.get(kindLabel(kind)));
    if (!levelLabel.isEmpty())
      parts.add(s.get(R.string.indoor_level, levelLabel));
    if (!venueName.isEmpty())
      parts.add(venueName);
    return String.join(" · ", parts);
  }

  /** Tags as key, value, key, value, ... to a map (keeps the order, ignores a trailing key). */
  @NonNull
  public static Map<String, String> tags(@Nullable String[] keyValues)
  {
    final Map<String, String> map = new LinkedHashMap<>();
    if (keyValues == null)
      return map;
    for (int i = 0; i + 1 < keyValues.length; i += 2)
      map.put(keyValues[i], keyValues[i + 1]);
    return map;
  }

  /** Detail lines from the tags, in a fixed order; unknown values are shown as they are. */
  @NonNull
  public static List<String> details(@NonNull Strings s, @NonNull Map<String, String> tags)
  {
    final List<String> lines = new ArrayList<>();
    final String hours = tags.get("opening_hours");
    if (hours != null && !hours.isEmpty())
      lines.add(s.get(R.string.indoor_opening_hours, hours));
    final String cuisine = tags.get("cuisine");
    if (cuisine != null && !cuisine.isEmpty())
      lines.add(s.get(R.string.indoor_cuisine, cuisine.replace('_', ' ').replace(";", ", ")));
    final String wheelchair = tags.get("wheelchair");
    if ("yes".equals(wheelchair) || "designated".equals(wheelchair))
      lines.add(s.get(R.string.indoor_wheelchair_yes));
    else if ("limited".equals(wheelchair))
      lines.add(s.get(R.string.indoor_wheelchair_limited));
    else if ("no".equals(wheelchair))
      lines.add(s.get(R.string.indoor_wheelchair_no));
    final String fee = tags.get("fee");
    if ("yes".equals(fee))
      lines.add(s.get(R.string.indoor_fee_yes));
    else if ("no".equals(fee))
      lines.add(s.get(R.string.indoor_fee_no));
    String operator = tags.get("operator");
    if (operator == null || operator.isEmpty())
      operator = tags.get("brand");
    if (operator != null && !operator.isEmpty())
      lines.add(s.get(R.string.indoor_operator, operator));
    final String description = tags.get("description");
    if (description != null && !description.isEmpty())
      lines.add(description);
    final String website = tags.get("website");
    if (website != null && (website.startsWith("https://") || website.startsWith("http://")))
      lines.add(website);
    return lines;
  }
}
