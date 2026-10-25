package app.organicmaps.transit.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.widget.TextView;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.transit.LineBadge;
import app.organicmaps.transit.TransitError;
import app.organicmaps.transit.TransitFormatter;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.UiUtils;

/** Shared view helpers for the public transport UI. */
public final class TransitViews
{
  private TransitViews() {}

  public static void bindBadge(@NonNull TextView view, @NonNull LineBadge badge)
  {
    view.setText(badge.getText());
    view.setTextColor(badge.getTextColor());
    view.setBackgroundTintList(ColorStateList.valueOf(badge.getBackgroundColor()));
    view.setContentDescription(badge.getText());
  }

  @ColorInt
  public static int lateColor(@NonNull Context context)
  {
    return ContextCompat.getColor(context, R.color.base_red);
  }

  @ColorInt
  public static int onTimeColor(@NonNull Context context)
  {
    return ContextCompat.getColor(context, R.color.base_green);
  }

  /**
   * Colors a time by its delay (red if late, green with realtime data if on time or early, default otherwise) and
   * shows the delay text ("+3") next to it.
   */
  public static void bindTime(@NonNull TextView time, @Nullable TextView delayView, @NonNull String text,
                              @NonNull TransitFormatter.Delay delay, boolean cancelled)
  {
    final Context context = time.getContext();
    time.setText(text);
    switch (delay.getStatus())
    {
    case LATE: time.setTextColor(lateColor(context)); break;
    case ON_TIME:
    case EARLY: time.setTextColor(onTimeColor(context)); break;
    default: time.setTextColor(ThemeUtils.getColor(context, android.R.attr.textColorPrimary));
    }
    setStrikeThrough(time, cancelled);
    if (delayView != null)
    {
      UiUtils.setTextAndHideIfEmpty(delayView, delay.getText());
      delayView.setTextColor(delay.isLate() ? lateColor(context) : onTimeColor(context));
    }
  }

  public static void setStrikeThrough(@NonNull TextView view, boolean strike)
  {
    if (strike)
      view.setPaintFlags(view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
    else
      view.setPaintFlags(view.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
  }

  @StringRes
  public static int errorText(@NonNull TransitError error)
  {
    switch (error)
    {
    case NETWORK: return R.string.transit_error_network;
    case SERVER: return R.string.transit_error_server;
    case REQUEST: return R.string.transit_error_request;
    case NO_RESULTS: return R.string.transit_departures_no_stop;
    case DISABLED: return R.string.transit_error_disabled;
    default: return R.string.transit_error_unknown;
    }
  }

  /** "Data: Transitous and sources" (or a prefix + that), linked to the provider's sources page. */
  public static void bindAttribution(@NonNull TextView view, @Nullable String prefix, @Nullable String providerName,
                                     @Nullable String url)
  {
    if (providerName == null || url == null)
    {
      UiUtils.setTextAndHideIfEmpty(view, prefix);
      return;
    }
    final String attribution = view.getContext().getString(R.string.transit_attribution, providerName);
    final String text = prefix == null ? attribution : prefix + " · " + attribution;
    final SpannableString spannable = new SpannableString(text);
    spannable.setSpan(new URLSpan(url), text.length() - attribution.length(), text.length(),
                      Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    view.setText(spannable);
    view.setMovementMethod(LinkMovementMethod.getInstance());
    UiUtils.show(view);
  }
}
