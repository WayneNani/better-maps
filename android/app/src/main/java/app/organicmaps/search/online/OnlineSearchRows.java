package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Rows of the online section in the search list, derived from an {@link OnlineSearchState}. */
public final class OnlineSearchRows
{
  public enum Type
  {
    HEADER,
    OFFER,
    LOADING,
    RESULT,
    EMPTY,
    ERROR
  }

  public static final class Row
  {
    @NonNull
    public final Type type;
    @Nullable
    public final OnlineSearchItem item;

    Row(@NonNull Type type, @Nullable OnlineSearchItem item)
    {
      this.type = type;
      this.item = item;
    }

    @NonNull
    @Override
    public String toString()
    {
      return item == null ? type.name() : type + " " + item;
    }
  }

  private OnlineSearchRows() {}

  @NonNull
  public static List<Row> build(@NonNull OnlineSearchState state)
  {
    final OnlineSearchState.Status status = state.getStatus();
    if (status == OnlineSearchState.Status.HIDDEN)
      return Collections.emptyList();
    final List<Row> rows = new ArrayList<>(state.getItems().size() + 1);
    rows.add(new Row(Type.HEADER, null));
    switch (status)
    {
    case OFFER -> rows.add(new Row(Type.OFFER, null));
    case LOADING -> rows.add(new Row(Type.LOADING, null));
    case EMPTY -> rows.add(new Row(Type.EMPTY, null));
    case ERROR -> rows.add(new Row(Type.ERROR, null));
    case RESULTS ->
    {
      for (OnlineSearchItem item : state.getItems())
        rows.add(new Row(Type.RESULT, item));
    }
    default ->
    {
    }
    }
    return rows;
  }

  /**
   * Names of the CoMaps type strings to try for the kind of a result, most specific first: for highway=pedestrian
   * "type.highway.pedestrian", then "type.highway".
   */
  @NonNull
  public static List<String> typeStringKeys(@NonNull OnlineSearchResult result)
  {
    final String key = result.getOsmKey();
    final String value = result.getOsmValue();
    if (key == null)
      return Collections.emptyList();
    final List<String> keys = new ArrayList<>(2);
    if (value != null && !"yes".equals(value))
      keys.add("type." + sanitize(key) + "." + sanitize(value));
    // The bare key is only meaningful for some tags ("building" alone says little about an address).
    if (!"building".equals(key) && !"place".equals(key) && !"boundary".equals(key))
      keys.add("type." + sanitize(key));
    return keys;
  }

  /** Fallback label from the OSM value: "pedestrian" → "Pedestrian", "fast_food" → "Fast food". */
  @NonNull
  public static String humanize(@Nullable String value)
  {
    if (value == null || value.isEmpty() || "yes".equals(value))
      return "";
    final String spaced = value.replace('_', ' ').replace(':', ' ').trim();
    if (spaced.isEmpty())
      return "";
    return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
  }

  @NonNull
  private static String sanitize(@NonNull String s)
  {
    return s.replace(':', '_').replace('-', '_');
  }
}
