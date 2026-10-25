package app.organicmaps.transit.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.provider.TransitousProvider;
import app.organicmaps.transit.TransitServices;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Opt-in dialog before the first online public transport request. It explains which data is sent to whom and links
 * to Transitous and its data sources. "Allow" and "Not now" are stored; dismissing the dialog stores nothing.
 */
public final class TransitConsentDialog
{
  public interface Listener
  {
    void onResult(boolean granted);
  }

  private TransitConsentDialog() {}

  @NonNull
  public static AlertDialog show(@NonNull Context context, @NonNull Listener listener)
  {
    final TransitServices services = TransitServices.get(context);
    final boolean[] answered = {false};
    final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                                   .setTitle(R.string.transit_consent_title)
                                   .setMessage(message(context))
                                   .setPositiveButton(R.string.transit_consent_accept,
                                                      (d, which) -> {
                                                        answered[0] = true;
                                                        services.getConsent().grant(System.currentTimeMillis());
                                                        services.onConsentChanged();
                                                        listener.onResult(true);
                                                      })
                                   .setNegativeButton(R.string.transit_consent_decline,
                                                      (d, which) -> {
                                                        answered[0] = true;
                                                        services.getConsent().decline();
                                                        services.onConsentChanged();
                                                        listener.onResult(false);
                                                      })
                                   .setOnDismissListener(d -> {
                                     // Closed without an answer (back button, outside): nothing is stored, ask again
                                     // next time.
                                     if (!answered[0])
                                       listener.onResult(false);
                                   })
                                   .show();
    final TextView messageView = dialog.findViewById(android.R.id.message);
    if (messageView != null)
      messageView.setMovementMethod(LinkMovementMethod.getInstance());
    return dialog;
  }

  @NonNull
  static CharSequence message(@NonNull Context context)
  {
    final SpannableStringBuilder sb = new SpannableStringBuilder(context.getString(R.string.transit_consent_message));
    sb.append("\n\n");
    appendLink(sb, context.getString(R.string.transit_consent_learn_more), TransitousProvider.WEBSITE_URL);
    sb.append("  ·  ");
    appendLink(sb, context.getString(R.string.transit_consent_sources), TransitousProvider.SOURCES_URL);
    return sb;
  }

  private static void appendLink(@NonNull SpannableStringBuilder sb, @NonNull String text, @Nullable String url)
  {
    final int start = sb.length();
    sb.append(text);
    if (url != null)
      sb.setSpan(new URLSpan(url), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
}
