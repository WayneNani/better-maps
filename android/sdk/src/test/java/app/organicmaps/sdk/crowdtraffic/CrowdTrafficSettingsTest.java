package app.organicmaps.sdk.crowdtraffic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CrowdTrafficSettingsTest
{
  private static final class MapStore implements CrowdTrafficSettings.Store
  {
    final Map<String, Object> values = new HashMap<>();

    @Override
    public boolean getBoolean(@NonNull String key, boolean def)
    {
      final Object v = values.get(key);
      return v == null ? def : (Boolean) v;
    }

    @Override
    public void setBoolean(@NonNull String key, boolean value)
    {
      values.put(key, value);
    }

    @NonNull
    @Override
    public String getString(@NonNull String key, @NonNull String def)
    {
      final Object v = values.get(key);
      return v == null ? def : (String) v;
    }

    @Override
    public void setString(@NonNull String key, @NonNull String value)
    {
      values.put(key, value);
    }
  }

  private final MapStore mStore = new MapStore();
  private final List<Boolean> mStates = new ArrayList<>();
  private final List<RelayEndpoints> mEndpoints = new ArrayList<>();
  private final CrowdTrafficSettings mSettings = new CrowdTrafficSettings(mStore, (active, endpoints) -> {
    mStates.add(active);
    mEndpoints.add(endpoints);
  });

  @Test
  public void offByDefault()
  {
    assertFalse(mSettings.isEnabled());
    assertEquals("", mSettings.getRelayUrl());
    assertFalse(mSettings.isActive());
    assertNull(mSettings.getEndpoints());
    mSettings.notifyListener();
    assertEquals(List.of(false), mStates);
  }

  @Test
  public void activeOnlyWithOptInAndRelay()
  {
    mSettings.setEnabled(true);
    // Opt-in without relay: nothing is collected or sent.
    assertTrue(mSettings.isEnabled());
    assertFalse(mSettings.isActive());
    assertEquals(List.of(false), mStates);

    assertTrue(mSettings.setRelayUrl(" http://10.0.2.2:18090 "));
    assertEquals("http://10.0.2.2:18090", mSettings.getRelayUrl());
    assertTrue(mSettings.isActive());
    assertEquals(List.of(false, true), mStates);
    assertNotNull(mEndpoints.get(1));
    assertEquals("http://10.0.2.2:18090/relay", mEndpoints.get(1).relayUrl);

    // Opting out deactivates (the listener drops unsent data).
    mSettings.setEnabled(false);
    assertFalse(mSettings.isActive());
    assertEquals(List.of(false, true, false), mStates);
    // Values are stored under the keys read by the native client.
    assertEquals(false, mStore.values.get(CrowdTrafficSettings.KEY_ENABLED));
    assertEquals("http://10.0.2.2:18090", mStore.values.get(CrowdTrafficSettings.KEY_RELAY_URL));
    assertEquals("CrowdTrafficEnabled", CrowdTrafficSettings.KEY_ENABLED);
    assertEquals("CrowdTrafficRelayUrl", CrowdTrafficSettings.KEY_RELAY_URL);
  }

  @Test
  public void relayUrlValidation()
  {
    mSettings.setEnabled(true);
    assertTrue(mSettings.setRelayUrl("https://relay.example/crowd/relay"));
    final int notifications = mStates.size();
    assertFalse(mSettings.setRelayUrl("not a url"));
    assertFalse(mSettings.setRelayUrl("ftp://relay.example"));
    assertEquals("https://relay.example/crowd/relay", mSettings.getRelayUrl());
    // Setting the same value again does not notify.
    assertTrue(mSettings.setRelayUrl("https://relay.example/crowd/relay"));
    assertEquals(notifications, mStates.size());
    // Clearing the URL disables the reports.
    assertTrue(mSettings.setRelayUrl(""));
    assertFalse(mSettings.isActive());
    assertTrue(mSettings.setRelayUrl(null));
    assertTrue(CrowdTrafficSettings.isAcceptableRelayUrl(null));
    assertTrue(CrowdTrafficSettings.isAcceptableRelayUrl(" "));
    assertFalse(CrowdTrafficSettings.isAcceptableRelayUrl("relay"));
    // Enabling twice notifies once.
    final int n = mStates.size();
    mSettings.setEnabled(true);
    assertEquals(n, mStates.size());
  }
}
