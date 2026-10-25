package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TransitConsentTest
{
  @Test
  public void undecidedUntilAsked()
  {
    final TransitConsent consent = new TransitConsent(new MapStore());
    assertEquals(TransitConsent.State.UNDECIDED, consent.getState());
    assertTrue(consent.shouldAsk());
    assertFalse(consent.isGranted());
  }

  @Test
  public void grantIsStoredWithVersionAndTime()
  {
    final MapStore store = new MapStore();
    final TransitConsent consent = new TransitConsent(store);
    consent.grant(1234L);
    assertEquals(TransitConsent.State.GRANTED, consent.getState());
    assertTrue(consent.isGranted());
    assertFalse(consent.shouldAsk());
    assertEquals(true, store.mValues.get(TransitSettings.KEY_ONLINE));
    assertEquals(TransitConsent.VERSION, store.mValues.get(TransitConsent.KEY_VERSION));
    assertEquals(1234L, store.mValues.get(TransitConsent.KEY_TIME));
    // A new instance on the same storage sees the decision.
    assertTrue(new TransitConsent(store).isGranted());
  }

  @Test
  public void declineIsFinalUntilChangedInSettings()
  {
    final MapStore store = new MapStore();
    final TransitConsent consent = new TransitConsent(store);
    consent.decline();
    assertEquals(TransitConsent.State.DECLINED, consent.getState());
    assertFalse(consent.shouldAsk());
    assertFalse(consent.isGranted());
  }

  @Test
  public void revokeAfterGrant()
  {
    final MapStore store = new MapStore();
    final TransitConsent consent = new TransitConsent(store);
    consent.grant(1L);
    consent.decline();
    assertEquals(TransitConsent.State.DECLINED, consent.getState());
    assertFalse(store.contains(TransitConsent.KEY_TIME));
  }

  @Test
  public void switchingOnInSettingsWithoutDialogIsNotConsent()
  {
    // The settings switch alone (e.g. restored from a backup of an older version) is not enough.
    final MapStore store = new MapStore().with(TransitSettings.KEY_ONLINE, true);
    assertEquals(TransitConsent.State.UNDECIDED, new TransitConsent(store).getState());
  }

  @Test
  public void olderConsentVersionAsksAgain()
  {
    final MapStore store = new MapStore()
                               .with(TransitSettings.KEY_ONLINE, true)
                               .with(TransitConsent.KEY_VERSION, TransitConsent.VERSION - 1);
    assertTrue(new TransitConsent(store).shouldAsk());
  }

  @Test
  public void resetForgetsDecision()
  {
    final MapStore store = new MapStore();
    final TransitConsent consent = new TransitConsent(store);
    consent.grant(5L);
    consent.reset();
    assertEquals(TransitConsent.State.UNDECIDED, consent.getState());
    assertTrue(store.mValues.isEmpty());
  }
}
