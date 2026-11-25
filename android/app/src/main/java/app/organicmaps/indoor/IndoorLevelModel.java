package app.organicmaps.indoor;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** better-maps: the entries of the indoor level switcher, without Android views (testable). */
public final class IndoorLevelModel
{
  /** At most this many level buttons are visible at once; more scroll. */
  public static final int MAX_VISIBLE = 5;

  private static final double EPS = 1e-6;

  public static final class Item
  {
    public final double level;
    @NonNull
    public final String label;
    public final boolean selected;

    Item(double level, @NonNull String label, boolean selected)
    {
      this.level = level;
      this.label = label;
      this.selected = selected;
    }
  }

  private IndoorLevelModel() {}

  public static boolean sameLevel(double a, double b)
  {
    return Math.abs(a - b) < EPS;
  }

  /**
   * The switcher's buttons from top to bottom: the highest level first, as in a building. A missing or empty label
   * becomes the formatted number.
   */
  @NonNull
  public static List<Item> items(@NonNull double[] levels, @NonNull String[] labels, double shown)
  {
    final List<Item> items = new ArrayList<>(levels.length);
    for (int i = 0; i < levels.length; ++i)
    {
      final String label = i < labels.length && labels[i] != null && !labels[i].trim().isEmpty()
                             ? labels[i].trim()
                             : formatLevel(levels[i]);
      items.add(new Item(levels[i], label, sameLevel(levels[i], shown)));
    }
    Collections.sort(items, (a, b) -> Double.compare(b.level, a.level));
    return items;
  }

  /** Index of the shown level in |items|, -1 if none. */
  public static int selectedIndex(@NonNull List<Item> items)
  {
    for (int i = 0; i < items.size(); ++i)
      if (items.get(i).selected)
        return i;
    return -1;
  }

  public static int visibleCount(int levelCount)
  {
    return Math.min(levelCount, MAX_VISIBLE);
  }

  /** "0", "-1", "0.5" (no trailing zeros, independent of the locale). */
  @NonNull
  public static String formatLevel(double level)
  {
    final long thousandths = Math.round(level * 1000);
    if (thousandths % 1000 == 0)
      return Long.toString(thousandths / 1000);
    final String sign = thousandths < 0 ? "-" : "";
    final long abs = Math.abs(thousandths);
    String frac = String.format(java.util.Locale.ROOT, "%03d", abs % 1000);
    while (frac.endsWith("0"))
      frac = frac.substring(0, frac.length() - 1);
    return sign + (abs / 1000) + "." + frac;
  }
}
