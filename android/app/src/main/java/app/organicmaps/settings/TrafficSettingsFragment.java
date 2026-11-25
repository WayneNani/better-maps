package app.organicmaps.settings;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import app.organicmaps.R;
import app.organicmaps.sdk.crowdtraffic.CrowdTraffic;
import app.organicmaps.sdk.crowdtraffic.CrowdTrafficSettings;
import app.organicmaps.sdk.traffxml.AndroidTransport;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.util.Utils;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Keep
public class TrafficSettingsFragment extends BaseXmlSettingsFragment
{
  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_traffic;
  }

  @Override
  public void onResume()
  {
    super.onResume();
    updateTrafficHttpUrlSummary();
    updateTrafficAppsSummary();
    updateCrowdTrafficRelayUrlSummary();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    initTrafficHttpEnabledPrefsCallbacks();
    initTrafficHttpUrlPrefsCallbacks();
    initTrafficHttpTokenPrefs();
    initTrafficAppsPrefs();
    initTrafficLegacyEnabledPrefsCallbacks();
    initCrowdTrafficPrefs();
  }

  private void initTrafficHttpEnabledPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_traffic_http_enabled));

    ((TwoStatePreference)pref).setChecked(Config.getTrafficHttpEnabled());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      final boolean oldVal = Config.getTrafficHttpEnabled();
      final boolean newVal = (Boolean) newValue;
      if (oldVal != newVal)
        Config.setTrafficHttpEnabled(newVal);

      return true;
    });
  }

  private void initTrafficHttpUrlPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_traffic_http_url));

    ((EditTextPreference)pref).setText(Config.getTrafficHttpUrl());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      final String oldVal = Config.getTrafficHttpUrl();
      final String newVal = newValue == null ? "" : ((String) newValue).trim();
      // better-maps: same rules as the other servers (https://, http:// only for local addresses), so that the access
      // token never travels in plain text over the internet. Empty stays allowed ("Not set").
      final RoutingServerSettings.UrlProblem problem = RoutingServerSettings.checkUrl(newVal);
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
      if (!oldVal.equals(newVal))
      {
        // The token for the new URL first, so that a token never goes to a URL it is not meant for.
        TrafficServerSettings.applyToCore(requireContext(), newVal);
        Config.setTrafficHttpUrl(newVal);
      }
      new Handler(Looper.getMainLooper()).post(this::updateTrafficHttpUrlSummary);
      return true;
    });
  }

  /**
   * better-maps: access token of the TraFF server (services/traff-server). Stored by {@link TrafficServerSettings}
   * (app-private, not backed up), shown as "Set"/"Not set" only, never logged.
   */
  private void initTrafficHttpTokenPrefs()
  {
    final EditTextPreference pref = getPreference(getString(R.string.pref_traffic_http_token));
    final Preference.SummaryProvider<EditTextPreference> summary =
        p -> getString(TrafficServerSettings.get(requireContext()).hasToken() ? R.string.routing_server_token_set
                                                                               : R.string.routing_server_not_set);
    pref.setSummaryProvider(summary);
    pref.setOnBindEditTextListener(editText -> {
      editText.setSingleLine(true);
      editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      // Don't show the stored token again; an empty field keeps it.
      editText.setText("");
    });
    pref.setOnPreferenceChangeListener((p, value) -> {
      final String token = value == null ? "" : value.toString().trim();
      if (token.isEmpty())
        return false;  // Empty input keeps the stored token.
      TrafficServerSettings.get(requireContext()).setToken(token);
      TrafficServerSettings.applyToCore(requireContext());
      // The preference keeps no copy (not persistent, and false skips setText); refresh the summary.
      pref.setSummaryProvider(summary);
      return false;
    });
  }

  private void initTrafficAppsPrefs()
  {
    final MultiSelectListPreference pref = getPreference(getString(R.string.pref_traffic_apps));
    
    PackageManager pm = getContext().getPackageManager();
    List<ResolveInfo> receivers = pm.queryBroadcastReceivers(new Intent(AndroidTransport.ACTION_TRAFF_GET_CAPABILITIES), 0);
    
    if (receivers == null || receivers.isEmpty())
    {
      pref.setSummary(R.string.traffic_apps_not_available);
      pref.setEnabled(false);
      return;
    }
    
    pref.setEnabled(true);

    List<String> entryList = new ArrayList<>(receivers.size());
    List<String> valueList = new ArrayList<>(receivers.size());

    for (ResolveInfo receiver : receivers)
    {
      // friendly name
      entryList.add(receiver.loadLabel(pm).toString());
      // actual value (we just need the package name, broadcasts are sent to any receiver in the package)
      valueList.add(receiver.activityInfo.applicationInfo.packageName);
    }
    
    pref.setEntries(entryList.toArray(new CharSequence[0]));
    pref.setEntryValues(valueList.toArray(new CharSequence[0]));
    
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      // newValue is a Set<String>, each item is a package ID
      String[] apps = ((Set<String>)newValue).toArray(new String[0]);
      Config.setTrafficApps(apps);
      updateTrafficAppsSummary();

      return true;
    });
  }

  private void initTrafficLegacyEnabledPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_traffic_legacy_enabled));

    ((TwoStatePreference)pref).setChecked(Config.getTrafficLegacyEnabled());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      final boolean oldVal = Config.getTrafficLegacyEnabled();
      final boolean newVal = (Boolean) newValue;
      if (oldVal != newVal)
        Config.setTrafficLegacyEnabled(newVal);

      return true;
    });
  }

  private void updateTrafficHttpUrlSummary()
  {
    final Preference pref = getPreference(getString(R.string.pref_traffic_http_url));
    String summary = Config.getTrafficHttpUrl();
    if (summary.length() == 0)
      pref.setSummary(R.string.traffic_http_url_not_set);
    else
      pref.setSummary(summary);
  }

  private void updateTrafficAppsSummary()
  {
    final MultiSelectListPreference pref = getPreference(getString(R.string.pref_traffic_apps));
    /*
     * If the preference is disabled, it has not been initialized. This is the case if no TraFF
     * apps were found. The code below would crash when trying to access the entries, and there
     * is no need to update the summary if the setting cannot be changed.
     */
    if (!pref.isEnabled())
      return;
    String[] apps = Config.getTrafficApps();
    if (apps.length == 0)
      pref.setSummary(R.string.traffic_apps_none_selected);
    else
    {
      String summary = "";
      for (int i = 0; i < apps.length; i++)
      {
        if (i > 0)
          summary = summary + ", ";
        int index = pref.findIndexOfValue(apps[i]);
        if (i >= 0)
          summary = summary + pref.getEntries()[index];
        else
          summary = summary + apps[i];
      }
      pref.setSummary(summary);
    }
  }

  /**
   * Anonymous congestion reports (opt-in, off by default) and the relay URL (expert setting, empty by
   * default). Both are stored in the native settings via CrowdTrafficSettings.
   */
  private void initCrowdTrafficPrefs()
  {
    final CrowdTrafficSettings settings = CrowdTraffic.getSettings();

    final TwoStatePreference enabled = getPreference(getString(R.string.pref_crowd_traffic_enabled));
    enabled.setChecked(settings.isEnabled());
    enabled.setOnPreferenceChangeListener((preference, newValue) -> {
      settings.setEnabled((Boolean) newValue);
      return true;
    });

    final Preference privacy = getPreference(getString(R.string.pref_crowd_traffic_privacy));
    privacy.setOnPreferenceClickListener(preference -> {
      Utils.openUrl(requireContext(), CrowdTraffic.PRIVACY_URL);
      return true;
    });

    final EditTextPreference relay = getPreference(getString(R.string.pref_crowd_traffic_relay_url));
    relay.setText(settings.getRelayUrl());
    relay.setOnPreferenceChangeListener((preference, newValue) -> {
      if (!settings.setRelayUrl((String) newValue))
      {
        Toast.makeText(requireContext(), R.string.crowd_traffic_relay_url_invalid, Toast.LENGTH_LONG).show();
        return false;
      }
      relay.setText(settings.getRelayUrl());
      updateCrowdTrafficRelayUrlSummary();
      return false; // The value is stored by CrowdTrafficSettings, not by the preference.
    });
  }

  private void updateCrowdTrafficRelayUrlSummary()
  {
    final Preference pref = getPreference(getString(R.string.pref_crowd_traffic_relay_url));
    final String url = CrowdTraffic.getSettings().getRelayUrl();
    if (url.isEmpty())
      pref.setSummary(R.string.crowd_traffic_relay_url_not_set);
    else
      pref.setSummary(url);
  }
}
