package app.organicmaps.search.online;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.testing.FragmentScenario;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.sdk.search.online.PhotonConfig;
import app.organicmaps.sdk.search.online.PhotonSearchProvider;
import app.organicmaps.search.online.ui.OnlineSearchAdapter;
import app.organicmaps.search.online.ui.OnlineSearchConsentDialog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Core flow of the online search: the section offers the online search, the opt-in dialog, then the Photon results
 * below the offline results. The provider is the real Photon client talking to a local mock server with a recorded
 * response ("Marienplatz München"), so no request leaves the device. The search screen itself needs the native map, so
 * a small host fragment shows the online section with the same adapter and presenter as the search screen. Map regions
 * are faked: Munich is not downloaded.
 */
@RunWith(AndroidJUnit4.class)
public class OnlineSearchFlowTest
{
  private static final double[] BERLIN = {52.52, 13.40};
  private static final double[] MUNICH = {48.137, 11.575};
  private static final long TIMEOUT_MS = 10_000;

  /** Hosts the online section like the search screen does (consent dialog from the "Search online" row). */
  public static class SectionHostFragment extends Fragment
  {
    static final List<OnlineSearchItem> sShown = new ArrayList<>();
    static final List<OnlineSearchItem> sDownloads = new ArrayList<>();
    OnlineSearchPresenter mPresenter;

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
      final RecyclerView list = new RecyclerView(requireContext());
      list.setId(R.id.recycler);
      list.setLayoutManager(new LinearLayoutManager(requireContext()));
      return list;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
      mPresenter = OnlineSearchServices.get(requireContext()).createPresenter();
      final OnlineSearchAdapter adapter = new OnlineSearchAdapter(new OnlineSearchAdapter.Listener() {
        @Override
        public void onEnableOnlineSearch()
        {
          OnlineSearchConsentDialog.show(requireContext(), granted -> mPresenter.onConsentChanged());
        }

        @Override
        public void onRetry()
        {
          mPresenter.retry();
        }

        @Override
        public void onShowResult(@NonNull OnlineSearchItem item)
        {
          sShown.add(item);
        }

        @Override
        public void onDownloadRegion(@NonNull OnlineSearchItem item)
        {
          sDownloads.add(item);
        }
      });
      mPresenter.setListener(adapter::setState);
      ((RecyclerView) view).setAdapter(adapter);
    }

    @Override
    public void onDestroyView()
    {
      mPresenter.setListener(null);
      mPresenter.clear();
      super.onDestroyView();
    }
  }

  private MockWebServer mServer;
  private OnlineSearchServices mServices;

  @Before
  public void setUp() throws Exception
  {
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    mServer = new MockWebServer();
    mServer.start();
    final String url = mServer.url("/").toString();
    SectionHostFragment.sShown.clear();
    SectionHostFragment.sDownloads.clear();
    onMain(() -> {
      mServices = OnlineSearchServices.get(context);
      mServices.getConsent().reset();
      mServices.getService().clearCache();
      mServices.setProviderForTesting(new PhotonSearchProvider(
          PhotonConfig.builder("better-maps-test").setBaseUrl(url).build(), ContextCompat.getMainExecutor(context)));
      mServices.setRegionLookupForTesting((lat, lon)
                                              -> lat > 50
                                                     ? new RegionInfo("Germany_Berlin", "Berlin", true, 0)
                                                     : new RegionInfo("Germany_Free State of Bavaria_Upper Bavaria",
                                                                      "Upper Bavaria", false, 190_000_000));
      return null;
    });
  }

  @After
  public void tearDown() throws Exception
  {
    onMain(() -> {
      // Leave the app as before: the online search is off and offered again; the real provider is back.
      mServices.getConsent().reset();
      mServices.getService().clearCache();
      mServices.setRegionLookupForTesting(new MapManagerRegionLookup());
      mServices.onSettingsChanged();
      return null;
    });
    mServer.shutdown();
  }

  @Test
  public void optInThenOnlineResultsWithDownloadOffer() throws Exception
  {
    mServer.enqueue(new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(asset("search_marienplatz_muenchen_de.json")));
    final FragmentScenario<SectionHostFragment> scenario =
        FragmentScenario.launchInContainer(SectionHostFragment.class, null, R.style.MwmTheme);
    // Searching in Berlin (downloaded) for a place in Munich: the offline search finds nothing.
    scenario.onFragment(f -> {
      f.mPresenter.onQueryChanged("Marienplatz München", false, "de-DE", BERLIN, null);
      f.mPresenter.onOfflineResults(new ArrayList<>());
      f.mPresenter.onOfflineSearchFinished();
    });

    // Nothing is sent before the user agreed; the section offers the online search.
    onView(withText(R.string.online_search_offer)).check(matches(isDisplayed()));
    assertEquals(0, mServer.getRequestCount());
    onView(withText(R.string.online_search_offer_button)).perform(click());
    onView(withText(R.string.online_search_consent_title)).inRoot(isDialog()).check(matches(isDisplayed()));
    onView(withText(R.string.online_search_consent_accept)).inRoot(isDialog()).perform(click());
    assertTrue(onMain(() -> mServices.getConsent().isGranted()));

    final AtomicReference<OnlineSearchPresenter> presenter = new AtomicReference<>();
    scenario.onFragment(f -> presenter.set(f.mPresenter));
    waitFor(() -> onMain(() -> presenter.get().getState().getStatus() == OnlineSearchState.Status.RESULTS));

    final RecordedRequest request = mServer.takeRequest(5, TimeUnit.SECONDS);
    final HttpUrl url = request.getRequestUrl();
    assertEquals("/api", url.encodedPath());
    assertEquals("Marienplatz München", url.queryParameter("q"));
    assertEquals("de", url.queryParameter("lang"));
    assertEquals("52.52", url.queryParameter("lat"));
    assertEquals("better-maps-test", request.getHeader("User-Agent"));

    onView(withText(startsWith("Online results (Photon"))).check(matches(isDisplayed()));
    onView(withText(R.string.online_search_attribution)).check(matches(isDisplayed()));
    // Street and square of the Marienplatz: same name and address, different kind of place.
    onView(allOf(withId(R.id.description), withText("Pedestrian Street"))).check(matches(isDisplayed()));
    onView(allOf(withId(R.id.description), withText("Square"))).check(matches(isDisplayed()));

    // Each result offers the download of its region (both are in Upper Bavaria).
    final AtomicReference<RecyclerView> list = new AtomicReference<>();
    scenario.onFragment(f -> list.set((RecyclerView) f.requireView()));
    onMain(() -> {
      final RecyclerView.ViewHolder holder = list.get().findViewHolderForAdapterPosition(1);
      assertEquals("Altstadt, 80331 München, Deutschland",
                   ((android.widget.TextView) holder.itemView.findViewById(R.id.region)).getText().toString());
      final View download = holder.itemView.findViewById(R.id.online_search_download);
      assertEquals(View.VISIBLE, download.getVisibility());
      download.performClick();
      holder.itemView.performClick();
      return null;
    });
    assertEquals(1, SectionHostFragment.sDownloads.size());
    assertEquals("Germany_Free State of Bavaria_Upper Bavaria",
                 SectionHostFragment.sDownloads.get(0).getRegion().getId());
    assertEquals(1, SectionHostFragment.sShown.size());
    assertEquals("W259055080", SectionHostFragment.sShown.get(0).getResult().getOsmRef());

    // The same query again comes from the cache.
    scenario.onFragment(f -> {
      f.mPresenter.onQueryChanged("Marienplatz München", false, "de-DE", BERLIN, null);
      f.mPresenter.onOfflineSearchFinished();
    });
    waitFor(() -> onMain(() -> presenter.get().getState().getStatus() == OnlineSearchState.Status.RESULTS));
    assertEquals(1, mServer.getRequestCount());
  }

  @Test
  public void declineSendsNothing() throws Exception
  {
    final FragmentScenario<SectionHostFragment> scenario =
        FragmentScenario.launchInContainer(SectionHostFragment.class, null, R.style.MwmTheme);
    scenario.onFragment(f -> f.mPresenter.onQueryChanged("Marienplatz", false, "en-US", MUNICH, null));
    onView(withText(R.string.online_search_offer_button)).perform(click());
    onView(withText(R.string.online_search_consent_decline)).inRoot(isDialog()).perform(click());
    assertEquals(OnlineSearchConsent.State.DECLINED, onMain(() -> mServices.getConsent().getState()));

    // The section disappears and later queries are neither offered nor sent.
    onView(withText(R.string.online_search_offer)).check(doesNotExist());
    scenario.onFragment(f -> {
      f.mPresenter.onQueryChanged("Viktualienmarkt", false, "en-US", MUNICH, null);
      f.mPresenter.onOfflineSearchFinished();
    });
    SystemClock.sleep(OnlineSearchPresenter.DEBOUNCE_MS + 300);
    onView(withText(R.string.online_search_offer)).check(doesNotExist());
    assertEquals(0, mServer.getRequestCount());
  }

  private static <T> T onMain(@NonNull Callable<T> callable) throws Exception
  {
    final AtomicReference<T> result = new AtomicReference<>();
    final AtomicReference<Throwable> error = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      try
      {
        result.set(callable.call());
      }
      catch (Throwable e)
      {
        error.set(e);
      }
    });
    if (error.get() instanceof Exception)
      throw (Exception) error.get();
    if (error.get() != null)
      throw new AssertionError(error.get());
    return result.get();
  }

  private static void waitFor(@NonNull Callable<Boolean> condition) throws Exception
  {
    final long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
    while (SystemClock.uptimeMillis() < end)
    {
      if (condition.call())
        return;
      SystemClock.sleep(100);
    }
    fail("Condition not met within " + TIMEOUT_MS + " ms");
  }

  @NonNull
  private static String asset(@NonNull String name) throws IOException
  {
    // Assets of the test APK: the SDK's recorded Photon responses (see build.gradle.kts).
    try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("photon/" + name))
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
