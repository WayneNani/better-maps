package app.organicmaps.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnlineSearchConsentTest
{
  @Test
  public void offByDefault()
  {
    final OnlineSearchConsent consent = new OnlineSearchConsent(new MapStore());
    assertEquals(OnlineSearchConsent.State.UNDECIDED, consent.getState());
    assertFalse(consent.isGranted());
  }

  @Test
  public void grantStoresVersionAndTime()
  {
    final MapStore store = new MapStore();
    final OnlineSearchConsent consent = new OnlineSearchConsent(store);
    consent.grant(1234);
    assertTrue(consent.isGranted());
    assertEquals(Boolean.TRUE, store.mValues.get(OnlineSearchSettings.KEY_ONLINE));
    assertEquals(OnlineSearchConsent.VERSION, store.mValues.get(OnlineSearchConsent.KEY_VERSION));
    assertEquals(1234L, store.mValues.get(OnlineSearchConsent.KEY_TIME));
  }

  @Test
  public void declineIsRemembered()
  {
    final MapStore store = new MapStore();
    final OnlineSearchConsent consent = new OnlineSearchConsent(store);
    consent.grant(1);
    consent.decline();
    assertEquals(OnlineSearchConsent.State.DECLINED, consent.getState());
    assertFalse(store.mValues.containsKey(OnlineSearchConsent.KEY_TIME));
    // A new object on the same store (app restart) sees the same decision.
    assertEquals(OnlineSearchConsent.State.DECLINED, new OnlineSearchConsent(store).getState());
  }

  @Test
  public void switchOnWithoutCurrentVersionAsksAgain()
  {
    // E.g. the switch was turned on by an older version with another consent text.
    final MapStore store = new MapStore().with(OnlineSearchSettings.KEY_ONLINE, true);
    assertEquals(OnlineSearchConsent.State.UNDECIDED, new OnlineSearchConsent(store).getState());
    store.with(OnlineSearchConsent.KEY_VERSION, OnlineSearchConsent.VERSION - 1);
    assertEquals(OnlineSearchConsent.State.UNDECIDED, new OnlineSearchConsent(store).getState());
    store.with(OnlineSearchConsent.KEY_VERSION, OnlineSearchConsent.VERSION);
    assertEquals(OnlineSearchConsent.State.GRANTED, new OnlineSearchConsent(store).getState());
  }

  @Test
  public void reset()
  {
    final MapStore store = new MapStore();
    final OnlineSearchConsent consent = new OnlineSearchConsent(store);
    consent.grant(1);
    consent.reset();
    assertEquals(OnlineSearchConsent.State.UNDECIDED, consent.getState());
    assertTrue(store.mValues.isEmpty());
  }

  @Test
  public void independentOfTransitConsent()
  {
    // The public transport consent uses other keys; agreeing there does not enable the online search.
    final MapStore store = new MapStore().with("transit_online", true).with("transit_consent_version", 1);
    assertEquals(OnlineSearchConsent.State.UNDECIDED, new OnlineSearchConsent(store).getState());
  }
}
