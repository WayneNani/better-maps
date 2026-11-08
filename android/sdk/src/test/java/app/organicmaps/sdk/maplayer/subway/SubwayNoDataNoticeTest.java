package app.organicmaps.sdk.maplayer.subway;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SubwayNoDataNoticeTest
{
  @Test
  public void automaticActivationIsSilent()
  {
    // App start or public transport map mode: the core reports "no data" without a user action.
    final SubwayNoDataNotice notice = new SubwayNoDataNotice();
    assertFalse(notice.onNoData(1_000));
    assertFalse(notice.onNoData(60_000));
  }

  @Test
  public void shownRightAfterTheUserEnabledTheLayer()
  {
    final SubwayNoDataNotice notice = new SubwayNoDataNotice();
    notice.onUserEnabled(5_000);
    assertTrue(notice.onNoData(5_200));
  }

  @Test
  public void shownOnlyOncePerUserAction()
  {
    final SubwayNoDataNotice notice = new SubwayNoDataNotice();
    notice.onUserEnabled(5_000);
    assertTrue(notice.onNoData(5_200));
    // Panning the map flips between "data" and "no data"; that must not repeat the toast.
    assertFalse(notice.onNoData(6_000));
    assertFalse(notice.onNoData(7_000));
  }

  @Test
  public void lateReportsAreSilent()
  {
    final SubwayNoDataNotice notice = new SubwayNoDataNotice();
    notice.onUserEnabled(5_000);
    assertFalse(notice.onNoData(5_000 + SubwayNoDataNotice.USER_ACTION_WINDOW_MS + 1));
  }

  @Test
  public void eachUserActionGetsOneNotice()
  {
    final SubwayNoDataNotice notice = new SubwayNoDataNotice();
    notice.onUserEnabled(5_000);
    assertTrue(notice.onNoData(5_100));
    notice.onUserEnabled(30_000);
    assertTrue(notice.onNoData(30_100));
  }
}
