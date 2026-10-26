package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UpcomingConnectionTest
{
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.525, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);
  // Fixtures.s5(id, minute) departs at 17:<minute> Berlin time (16:<minute>Z) and arrives 17 minutes later.
  private static final Instant DEPARTURE_29 = t("2026-10-25T16:29:00Z");

  @Rule
  public final TemporaryFolder mFolder = new TemporaryFolder();

  private SavedConnectionsStore mStore;
  private final TransitItemFactory mItems = new TransitItemFactory(Fixtures.formatter());

  @Before
  public void setUp()
  {
    mStore = new SavedConnectionsStore(new File(mFolder.getRoot(), "saved.json"), Runnable::run,
                                       () -> t("2026-10-25T12:00:00Z"));
  }

  @NonNull
  private SavedConnection save(@NonNull TransitItinerary itinerary)
  {
    return mStore.save(itinerary, "Hauptbahnhof", "Alexanderplatz", HBF, ALEX);
  }

  @Test
  public void nothingWithoutSavedConnections()
  {
    assertNull(UpcomingConnection.next(mStore.getAll(), DEPARTURE_29, Set.of()));
    assertNull(UpcomingConnection.find(mStore.getAll(), DEPARTURE_29, Set.of(), mItems));
  }

  @Test
  public void nothingIfAllArePast()
  {
    save(Fixtures.s5("a", 0));
    save(Fixtures.s5("b", 10));
    // b arrives at 16:27Z.
    assertNull(UpcomingConnection.next(mStore.getAll(), t("2026-10-25T16:27:00Z"), Set.of()));
    assertNull(UpcomingConnection.find(mStore.getAll(), t("2026-10-25T18:00:00Z"), Set.of(), mItems));
  }

  @Test
  public void theNextDepartureComesFirst()
  {
    save(Fixtures.s5("late", 49));
    save(Fixtures.s5("past", 0));
    save(Fixtures.s5("early", 29));
    final Instant now = t("2026-10-25T16:20:00Z");
    assertEquals("early", UpcomingConnection.next(mStore.getAll(), now, Set.of()).getKey());
    // After the arrival of the first one, the next one.
    assertEquals("late", UpcomingConnection.next(mStore.getAll(), t("2026-10-25T16:46:00Z"), Set.of()).getKey());
  }

  @Test
  public void aConnectionOnTheWayComesFirst()
  {
    save(Fixtures.s5("onTheWay", 20));
    save(Fixtures.s5("next", 40));
    final Instant now = t("2026-10-25T16:30:00Z");
    assertEquals("onTheWay", UpcomingConnection.next(mStore.getAll(), now, Set.of()).getKey());
    final UpcomingConnection card = UpcomingConnection.find(mStore.getAll(), now, Set.of(), mItems);
    assertTrue(card.isOnTheWay());
    assertEquals("on the way", card.getWhen());
  }

  @Test
  public void hiddenConnectionsAreSkipped()
  {
    save(Fixtures.s5("a", 29));
    save(Fixtures.s5("b", 39));
    final Instant now = t("2026-10-25T16:00:00Z");
    assertEquals("b", UpcomingConnection.next(mStore.getAll(), now, Set.of("a")).getKey());
    assertNull(UpcomingConnection.next(mStore.getAll(), now, Set.of("a", "b")));
  }

  @Test
  public void timeUntilTheDeparture()
  {
    save(Fixtures.s5("a", 29));
    assertEquals("in 25 min", when(DEPARTURE_29.minus(Duration.ofMinutes(25))));
    // Rounded up while seconds are left.
    assertEquals("in 25 min", when(DEPARTURE_29.minus(Duration.ofSeconds(24 * 60 + 30))));
    assertEquals("in 1 min", when(DEPARTURE_29.minusSeconds(30)));
    assertEquals("now", when(DEPARTURE_29.minusMillis(500)));
    assertEquals("in 1 h 5 min", when(DEPARTURE_29.minus(Duration.ofMinutes(65))));
    assertEquals("in 23 h 59 min", when(DEPARTURE_29.minus(Duration.ofMinutes(23 * 60 + 59))));
    // A day or more ahead: the date.
    assertEquals("So., 25.10.2026", when(DEPARTURE_29.minus(Duration.ofHours(24))));
    assertFalse(UpcomingConnection.find(mStore.getAll(), DEPARTURE_29.minusSeconds(60), Set.of(), mItems).isOnTheWay());
  }

  @Test
  public void theCardShowsTimesAndLines()
  {
    save(Fixtures.s5("a", 29));
    final UpcomingConnection card =
        UpcomingConnection.find(mStore.getAll(), t("2026-10-25T16:04:00Z"), Set.of(), mItems);
    assertEquals("a", card.getSaved().getKey());
    assertEquals("17:29", card.getItem().getDeparture());
    assertEquals(List.of("S5"), card.getItem().getBadges().stream().map(LineBadge::getText).toList());
    assertEquals("Hauptbahnhof", card.getSaved().getFromName());
    assertEquals("Alexanderplatz", card.getSaved().getToName());
  }

  @Test
  public void refreshWhenTheTextChanges()
  {
    final SavedConnection saved = save(Fixtures.s5("a", 29));
    // "in 25 min" until 24 min are left.
    assertEquals(Duration.ofSeconds(30),
                 UpcomingConnection.untilNextChange(saved, DEPARTURE_29.minusSeconds(24 * 60 + 30)));
    assertEquals(Duration.ofMinutes(1), UpcomingConnection.untilNextChange(saved, DEPARTURE_29.minusSeconds(5 * 60)));
    // On the way: every minute, and right at the arrival.
    final Instant end = saved.getItinerary().getEndTime();
    assertEquals(Duration.ofMinutes(1), UpcomingConnection.untilNextChange(saved, DEPARTURE_29.plusSeconds(30)));
    assertEquals(Duration.ofSeconds(10), UpcomingConnection.untilNextChange(saved, end.minusSeconds(10)));
    assertEquals(Duration.ZERO, UpcomingConnection.untilNextChange(saved, end));
  }

  @Test
  public void hidingOnTheMapIsRemembered()
  {
    final MapStore store = new MapStore();
    final TransitSettings settings = new TransitSettings(store);
    assertTrue(settings.isShownOnMap());
    store.putBoolean(TransitSettings.KEY_SHOW_ON_MAP, false);
    assertFalse(settings.isShownOnMap());
    assertTrue(settings.getHiddenOnMap().isEmpty());

    settings.hideOnMap("a", Set.of("a", "b"));
    settings.hideOnMap("b", Set.of("a", "b"));
    assertEquals(Set.of("a", "b"), settings.getHiddenOnMap());

    // Keys of deleted connections are dropped with the next change.
    settings.hideOnMap("c", Set.of("b", "c"));
    assertEquals(Set.of("b", "c"), settings.getHiddenOnMap());
  }

  @NonNull
  private String when(@NonNull Instant now)
  {
    return UpcomingConnection.find(mStore.getAll(), now, Set.of(), mItems).getWhen();
  }
}
