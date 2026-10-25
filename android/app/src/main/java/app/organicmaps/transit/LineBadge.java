package app.organicmaps.transit;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitMode;
import java.util.Locale;
import java.util.Objects;

/** A line label ("S5", "M10", "Bus 100") in the line's colors. */
public final class LineBadge
{
  @NonNull
  private final String mText;
  @NonNull
  private final TransitMode mMode;
  @ColorInt
  private final int mBackgroundColor;
  @ColorInt
  private final int mTextColor;

  LineBadge(@NonNull String text, @NonNull TransitMode mode, @ColorInt int backgroundColor, @ColorInt int textColor)
  {
    mText = text;
    mMode = mode;
    mBackgroundColor = backgroundColor;
    mTextColor = textColor;
  }

  /**
   * @param routeColor hex RGB without '#', e.g. "eb7405"; null or invalid for a neutral color by mode.
   * @param routeTextColor hex RGB without '#'; null or invalid to pick black or white by contrast.
   */
  @NonNull
  public static LineBadge create(@Nullable String lineName, @NonNull TransitMode mode, @Nullable String routeColor,
                                 @Nullable String routeTextColor)
  {
    final Integer parsed = parseColor(routeColor);
    final int background = parsed != null ? parsed : defaultColor(mode);
    final Integer parsedText = parseColor(routeTextColor);
    int text = parsedText != null ? parsedText : contrastColor(background);
    // Some feeds publish identical background and text colors.
    if (text == background)
      text = contrastColor(background);
    final String name = lineName == null || lineName.trim().isEmpty() ? fallbackName(mode) : lineName.trim();
    return new LineBadge(name, mode, background, text);
  }

  @NonNull
  public String getText()
  {
    return mText;
  }

  @NonNull
  public TransitMode getMode()
  {
    return mMode;
  }

  @ColorInt
  public int getBackgroundColor()
  {
    return mBackgroundColor;
  }

  @ColorInt
  public int getTextColor()
  {
    return mTextColor;
  }

  /** Opaque ARGB color from "RRGGBB" or "#RRGGBB", null if invalid. */
  @Nullable
  static Integer parseColor(@Nullable String hex)
  {
    if (hex == null)
      return null;
    String value = hex.trim();
    if (value.startsWith("#"))
      value = value.substring(1);
    if (value.length() != 6)
      return null;
    try
    {
      return 0xFF000000 | Integer.parseInt(value, 16);
    }
    catch (NumberFormatException e)
    {
      return null;
    }
  }

  /**
   * White text if it has a contrast of at least 3:1 (WCAG AA for bold text, badges are bold), otherwise black.
   * White is preferred on saturated colors like most line colors, even where black would be slightly stronger.
   */
  @ColorInt
  static int contrastColor(@ColorInt int background)
  {
    final double contrastWithWhite = 1.05 / (luminance(background) + 0.05);
    return contrastWithWhite >= 3.0 ? 0xFFFFFFFF : 0xFF000000;
  }

  static double luminance(@ColorInt int color)
  {
    return 0.2126 * channel((color >> 16) & 0xFF) + 0.7152 * channel((color >> 8) & 0xFF)
  + 0.0722 * channel(color & 0xFF);
  }

  private static double channel(int value)
  {
    final double c = value / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  /** Neutral colors for lines without published colors. */
  @ColorInt
  static int defaultColor(@NonNull TransitMode mode)
  {
    switch (mode)
    {
    case BUS:
    case COACH: return 0xFF8E2C7A;
    case TRAM: return 0xFFBE1414;
    case SUBWAY: return 0xFF115D91;
    case SUBURBAN: return 0xFF008D4F;
    case REGIONAL_RAIL:
    case REGIONAL_FAST_RAIL:
    case RAIL: return 0xFFC0272D;
    case HIGHSPEED_RAIL:
    case LONG_DISTANCE:
    case NIGHT_RAIL: return 0xFF4F5559;
    case FERRY: return 0xFF0080BA;
    default: return 0xFF5C5C5C;
    }
  }

  @NonNull
  private static String fallbackName(@NonNull TransitMode mode)
  {
    final String name = mode.name().replace('_', ' ').toLowerCase(Locale.ROOT);
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o)
      return true;
    if (!(o instanceof LineBadge))
      return false;
    final LineBadge that = (LineBadge) o;
    return mBackgroundColor == that.mBackgroundColor && mTextColor == that.mTextColor && mText.equals(that.mText)
 && mMode == that.mMode;
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(mText, mMode, mBackgroundColor, mTextColor);
  }

  @NonNull
  @Override
  public String toString()
  {
    return mText;
  }
}
