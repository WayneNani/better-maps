package app.organicmaps.settings;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Toast;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.TwoStatePreference;
import app.organicmaps.R;

/**
 * Settings of server-assisted car routing (better-maps). Values live in the "routing_server" SharedPreferences, see
 * {@link RoutingServerSettings}. The token is shown as "set"/"not set" only.
 */
@Keep
public class RoutingServerSettingsFragment extends BaseXmlSettingsFragment
{
  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_routing_server;
  }

  @Override
  public void onCreatePreferences(Bundle bundle, String root)
  {
    getPreferenceManager().setSharedPreferencesName(RoutingServerSettings.PREFS_NAME);
    super.onCreatePreferences(bundle, root);
    initEnabled();
    initUrl();
    initToken();
  }

  @NonNull
  private RoutingServerSettings settings()
  {
    return RoutingServerSettings.get(requireContext());
  }

  /** The preference stores the new value after its listener returned: apply afterwards. */
  private void applyLater()
  {
    final Context context = requireContext().getApplicationContext();
    new Handler(Looper.getMainLooper()).post(() -> RoutingServerSettings.applyToCore(context));
  }

  private void initEnabled()
  {
    final TwoStatePreference pref = getPreference(RoutingServerSettings.KEY_ENABLED);
    pref.setOnPreferenceChangeListener((p, value) -> {
      if ((Boolean) value
          && (!settings().hasToken() || RoutingServerSettings.checkUrl(settings().getUrl()) != null))
        Toast.makeText(requireContext(), R.string.routing_server_needs_url_and_token, Toast.LENGTH_LONG).show();
      applyLater();
      return true;
    });
  }

  private void initUrl()
  {
    final EditTextPreference pref = getPreference(RoutingServerSettings.KEY_URL);
    pref.setSummaryProvider(p -> {
      final String url = settings().getUrl();
      return url.isEmpty() ? getString(R.string.routing_server_not_set) : url;
    });
    pref.setOnBindEditTextListener(editText -> {
      editText.setSingleLine(true);
      editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
      editText.setHint("https://route.example.org");
    });
    pref.setOnPreferenceChangeListener((p, value) -> {
      final String url = value == null ? "" : value.toString().trim();
      final RoutingServerSettings.UrlProblem problem = RoutingServerSettings.checkUrl(url);
      if (problem == RoutingServerSettings.UrlProblem.INVALID)
      {
        Toast.makeText(requireContext(), R.string.routing_server_url_invalid, Toast.LENGTH_LONG).show();
        return false;
      }
      if (problem == RoutingServerSettings.UrlProblem.INSECURE)
      {
        Toast.makeText(requireContext(), R.string.routing_server_url_insecure, Toast.LENGTH_LONG).show();
        return false;
      }
      applyLater();
      return true;
    });
  }

  private void initToken()
  {
    final EditTextPreference pref = getPreference(RoutingServerSettings.KEY_TOKEN);
    pref.setSummaryProvider(
        p -> getString(settings().hasToken() ? R.string.routing_server_token_set : R.string.routing_server_not_set));
    pref.setOnBindEditTextListener(editText -> {
      editText.setSingleLine(true);
      editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      // Don't show the stored token again; an empty field keeps it unless the user clears it on purpose.
      editText.setText("");
    });
    // Not persistent (prefs_routing_server.xml): the token is stored here, trimmed; the preference keeps no copy.
    pref.setOnPreferenceChangeListener((p, value) -> {
      final String token = value == null ? "" : value.toString().trim();
      if (token.isEmpty())
        return false;  // Empty input keeps the stored token.
      settings().setToken(token);
      applyLater();
      // true: the preference updates its summary ("set").
      return true;
    });
  }
}
