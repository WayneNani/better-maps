package app.organicmaps.transit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TimePicker;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.testing.FragmentScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.OnlineTransitService;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import app.organicmaps.sdk.transit.provider.TransitousProvider;
import app.organicmaps.transit.ui.TransitTimeDialog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Departure or arrival on another date: the time dialog of the connection panel, the Material calendar (text input)
 * and the query to Transitous with the chosen moment as "time" parameter. The provider is the real Transitous client
 * talking to a local mock server, so no request leaves the device.
 */
@RunWith(AndroidJUnit4.class)
public class TransitTimeDialogTest
{
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.5250, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);
  private static final long TIMEOUT_MS = 10_000;

  private MockWebServer mServer;
  private TransitServices mServices;
  private FragmentScenario<OnlineTransitFlowTest.PanelHostFragment> mScenario;

  @Before
  public void setUp() throws Exception
  {
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    mServer = new MockWebServer();
    mServer.start();
    final String url = mServer.url("/").toString();
    onMain(() -> {
      mServices = TransitServices.get(context);
      mServices.setProviderForTesting(new TransitousProvider(
          new OnlineTransitService(TransitServiceConfig.builder("better-maps-test").setBaseUrl(url).build(),
                                   androidx.core.content.ContextCompat.getMainExecutor(context))));
      mServices.setRouteDisplayForTesting(new TransitConnectionsPresenter.RouteDisplay() {
        @Override
        public void show(@NonNull TransitItinerary itinerary)
        {}

        @Override
        public void clear()
        {}
      });
      mServices.getConsent().grant(System.currentTimeMillis());
      mServices.getConnectionsPresenter().clear();
      mServices.getConnectionsPresenter().setTime(null, false);
      return null;
    });
  }

  @After
  public void tearDown() throws Exception
  {
    if (mScenario != null)
    {
      closeDialogs();
      // Closing the activity also removes the time dialog (an AlertDialog, not a fragment).
      mScenario.close();
      mScenario = null;
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    onMain(() -> {
      mServices.getConnectionsPresenter().clear();
      mServices.getConnectionsPresenter().setTime(null, false);
      mServices.getConsent().reset();
      return null;
    });
    mServer.shutdown();
  }

  /** Opens the panel and waits for the first query (departing now). */
  private void searchNow() throws Exception
  {
    mServer.enqueue(plan());
    mServer.enqueue(plan());
    mScenario =
        FragmentScenario.launchInContainer(OnlineTransitFlowTest.PanelHostFragment.class, null, R.style.MwmTheme);
    onMain(() -> {
      mServices.getConnectionsPresenter().search(HBF, ALEX);
      return null;
    });
    final RecordedRequest first = mServer.takeRequest(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNull("Departing now has no time", first.getRequestUrl().queryParameter("time"));
    onView(withId(R.id.transit_time)).check(matches(withText(R.string.transit_depart_now)));
  }

  @Test
  public void otherDateIsSentAsTime() throws Exception
  {
    searchNow();
    final ZoneId zone = ZoneId.systemDefault();
    final LocalDate date = LocalDate.now(zone).plusDays(10);
    final Instant expected = date.atTime(9, 30).atZone(zone).toInstant();

    onView(withId(R.id.transit_time)).perform(click());
    onView(withId(R.id.transit_day_other)).inRoot(isDialog()).perform(click());
    typeDateInCalendar(date);
    clickCalendarButton(com.google.android.material.R.id.confirm_button);

    // Back in the time dialog: the chip shows the chosen date.
    final TransitFormatter formatter = formatter();
    onView(withId(R.id.transit_day_other))
        .inRoot(isDialog())
        .check(matches(allOf(isChecked(), withText(formatter.formatShortDate(date, LocalDate.now(zone))))));
    onView(withId(R.id.transit_time_picker)).inRoot(isDialog()).perform(setTime(9, 30));
    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());

    final RecordedRequest request = mServer.takeRequest(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals("/api/v6/plan", request.getRequestUrl().encodedPath());
    assertEquals(expected, OffsetDateTime.parse(request.getRequestUrl().queryParameter("time")).toInstant());
    assertNull(request.getRequestUrl().queryParameter("arriveBy"));
    assertEquals(expected, onMain(() -> mServices.getConnectionsPresenter().getTime()));
    // The panel header shows date and time, e.g. "Depart Fri, Nov 14, 09:30".
    onView(withId(R.id.transit_time))
        .check(matches(withText(formatter.formatQueryTime(expected, false, Instant.now()))));
  }

  @Test
  public void tomorrowArrival() throws Exception
  {
    searchNow();
    final ZoneId zone = ZoneId.systemDefault();
    final Instant expected = LocalDate.now(zone).plusDays(1).atTime(18, 5).atZone(zone).toInstant();

    onView(withId(R.id.transit_time)).perform(click());
    onView(withId(R.id.transit_time_arrival)).inRoot(isDialog()).perform(click());
    onView(withId(R.id.transit_day_1)).inRoot(isDialog()).perform(click());
    onView(withId(R.id.transit_time_picker)).inRoot(isDialog()).perform(setTime(18, 5));
    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());

    final RecordedRequest request = mServer.takeRequest(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals(expected, OffsetDateTime.parse(request.getRequestUrl().queryParameter("time")).toInstant());
    assertEquals("true", request.getRequestUrl().queryParameter("arriveBy"));
  }

  @Test
  public void calendarRejectsPastAndTooFarDates() throws Exception
  {
    searchNow();
    final LocalDate today = LocalDate.now(ZoneId.systemDefault());

    onView(withId(R.id.transit_time)).perform(click());
    onView(withId(R.id.transit_day_other)).inRoot(isDialog()).perform(click());
    typeDateInCalendar(today.minusDays(1));
    onView(withId(com.google.android.material.R.id.confirm_button)).inRoot(isDialog()).check(matches(not(isEnabled())));
    replaceDate(today.plusDays(TransitDateRange.MAX_DAYS_AHEAD + 1));
    onView(withId(com.google.android.material.R.id.confirm_button)).inRoot(isDialog()).check(matches(not(isEnabled())));
    replaceDate(today.plusDays(TransitDateRange.MAX_DAYS_AHEAD));
    onView(withId(com.google.android.material.R.id.confirm_button)).inRoot(isDialog()).check(matches(isEnabled()));
    clickCalendarButton(com.google.android.material.R.id.cancel_button);

    // Cancelled: the time dialog still shows today.
    onView(withId(R.id.transit_day_0)).inRoot(isDialog()).check(matches(isChecked()));
    onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
    assertEquals(1, mServer.getRequestCount());
  }

  /** Switches the Material calendar to text input and types the date. */
  private void typeDateInCalendar(@NonNull LocalDate date) throws Exception
  {
    onView(withId(com.google.android.material.R.id.mtrl_picker_header_toggle)).inRoot(isDialog()).perform(click());
    replaceDate(date);
  }

  /**
   * Types the date into the text input of the calendar. The calendar shows the soft keyboard for its text input
   * asynchronously (not tracked by Espresso); while it slides in, the dialog is panned and a click on its buttons may
   * miss them. So the keyboard is closed again: Espresso waits until it is hidden.
   */
  private void replaceDate(@NonNull LocalDate date) throws Exception
  {
    onView(allOf(isDescendantOfA(withId(com.google.android.material.R.id.mtrl_picker_text_input_date)),
                 isAssignableFrom(EditText.class)))
        .inRoot(isDialog())
        .perform(replaceText(dateText(date)), closeSoftKeyboard());
  }

  /**
   * Clicks the confirm or cancel button of the calendar and waits until the calendar is closed, so that the next
   * check sees the time dialog below it (and not the closing calendar, which is a dialog too).
   */
  private void clickCalendarButton(int buttonId) throws Exception
  {
    onView(withId(buttonId)).inRoot(isDialog()).check(matches(isDisplayed())).perform(click());
    final long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
    while (isDatePickerShown())
    {
      if (SystemClock.uptimeMillis() > deadline)
        fail("The calendar is still shown after the click on its button");
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      SystemClock.sleep(50);
    }
  }

  private boolean isDatePickerShown() throws Exception
  {
    final AtomicReference<Boolean> shown = new AtomicReference<>(false);
    mScenario.onFragment(fragment -> {
      final Fragment picker =
          fragment.requireActivity().getSupportFragmentManager().findFragmentByTag(TransitTimeDialog.DATE_PICKER_TAG);
      shown.set(picker instanceof DialogFragment && ((DialogFragment) picker).getDialog() != null
                && ((DialogFragment) picker).getDialog().isShowing());
    });
    return shown.get();
  }

  /** Closes what a failed test may have left open: the calendar, the time dialog and the soft keyboard. */
  private void closeDialogs()
  {
    if (mScenario == null)
      return;
    mScenario.onFragment(fragment -> {
      for (Fragment f : fragment.requireActivity().getSupportFragmentManager().getFragments())
        if (f instanceof DialogFragment)
          ((DialogFragment) f).dismissAllowingStateLoss();
      final InputMethodManager imm = fragment.requireContext().getSystemService(InputMethodManager.class);
      final View focus = fragment.requireActivity().getWindow().getDecorView();
      imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  /** The date as the calendar's text input expects it (see {@link TransitTimeDialog#textInputPattern}). */
  @NonNull
  private String dateText(@NonNull LocalDate date) throws Exception
  {
    final AtomicReference<Locale> locale = new AtomicReference<>();
    mScenario.onFragment(fragment -> locale.set(fragment.getResources().getConfiguration().getLocales().get(0)));
    final SimpleDateFormat format =
        new SimpleDateFormat(TransitTimeDialog.textInputPattern(locale.get()), locale.get());
    // The calendar works with UTC dates.
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(new Date(TransitDateRange.toPickerMillis(date)));
  }

  @NonNull
  private TransitFormatter formatter()
  {
    final AtomicReference<TransitFormatter> formatter = new AtomicReference<>();
    mScenario.onFragment(fragment -> formatter.set(TransitServices.createFormatter(fragment.requireActivity())));
    return formatter.get();
  }

  @NonNull
  private static ViewAction setTime(int hour, int minute)
  {
    return new ViewAction() {
      @Override
      public Matcher<View> getConstraints()
      {
        return isAssignableFrom(TimePicker.class);
      }

      @Override
      public String getDescription()
      {
        return "set time " + hour + ":" + minute;
      }

      @Override
      public void perform(UiController uiController, View view)
      {
        ((TimePicker) view).setHour(hour);
        ((TimePicker) view).setMinute(minute);
      }
    };
  }

  @NonNull
  private static MockResponse plan() throws IOException
  {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(asset("plan_hbf_alex.json"));
  }

  private static <T> T onMain(@NonNull Callable<T> callable) throws Exception
  {
    final AtomicReference<T> result = new AtomicReference<>();
    final AtomicReference<Exception> error = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      try
      {
        result.set(callable.call());
      }
      catch (Exception e)
      {
        error.set(e);
      }
    });
    if (error.get() != null)
      throw error.get();
    return result.get();
  }

  @NonNull
  private static String asset(@NonNull String name) throws IOException
  {
    try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("transit/" + name))
    {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buffer = new byte[16384];
      int n;
      while ((n = in.read(buffer)) != -1)
        out.write(buffer, 0, n);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }
}
