package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLocation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SavedConnectionsStoreTest
{
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.525, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);

  @Rule
  public final TemporaryFolder mFolder = new TemporaryFolder();

  private Instant mNow = t("2026-10-25T16:00:00Z");
  private File mFile;
  private final List<Runnable> mPendingWrites = new ArrayList<>();
  private boolean mWriteImmediately = true;

  @Before
  public void setUp()
  {
    mFile = new File(new File(mFolder.getRoot(), "transit"), "saved_connections.json");
  }

  @NonNull
  private SavedConnectionsStore store()
  {
    return new SavedConnectionsStore(mFile, command -> {
      if (mWriteImmediately)
        command.run();
      else
        mPendingWrites.add(command);
    }, () -> mNow);
  }

  @NonNull
  private static SavedConnection save(@NonNull SavedConnectionsStore store, @NonNull TransitItinerary itinerary)
  {
    return store.save(itinerary, "Hauptbahnhof", "Alexanderplatz", HBF, ALEX);
  }

  private void writeFile(@NonNull String text) throws IOException
  {
    // noinspection ResultOfMethodCallIgnored
    mFile.getParentFile().mkdirs();
    Files.write(mFile.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  @NonNull
  private String readFile(@NonNull File file) throws IOException
  {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  @Test
  public void emptyWithoutFile()
  {
    final SavedConnectionsStore store = store();
    assertTrue(store.getAll().isEmpty());
    assertFalse(store.contains("a"));
    assertNull(store.get("a"));
    // Reading creates nothing.
    assertFalse(mFile.exists());
  }

  @Test
  public void savedConnectionsSurviveARestart()
  {
    final SavedConnectionsStore store = store();
    save(store, Fixtures.s5("a", 29));
    store.save(Fixtures.busAndSubway("b"), "Hbf", "Alex", ALEX, HBF);
    assertTrue(mFile.exists());

    final SavedConnectionsStore reloaded = store();
    assertEquals(2, reloaded.size());
    final SavedConnection a = reloaded.get("a");
    assertNotNull(a);
    assertEquals("Hauptbahnhof", a.getFromName());
    assertEquals("Alexanderplatz", a.getToName());
    assertEquals(HBF, a.getFrom());
    assertEquals(ALEX, a.getTo());
    assertEquals(mNow, a.getSavedAt());
    final TransitItinerary itinerary = a.getItinerary();
    assertEquals(Fixtures.s5("a", 29).getStartTime(), itinerary.getStartTime());
    assertEquals(3, itinerary.getLegs().size());
    assertEquals("S5", itinerary.getLegs().get(1).getLineName());
    assertEquals("eb7405", itinerary.getLegs().get(1).getRouteColor());
    assertEquals(120, itinerary.getLegs().get(1).getDepartureDelaySeconds());
    assertEquals("15", itinerary.getLegs().get(1).getFrom().getTrack());
    assertEquals(2, itinerary.getLegs().get(1).getIntermediateStops().size());
    assertEquals(HBF, reloaded.get("b").getTo());
  }

  @Test
  public void fileHasAVersion() throws IOException
  {
    save(store(), Fixtures.s5("a", 29));
    final JsonObject root = JsonParser.parseString(readFile(mFile)).getAsJsonObject();
    assertEquals(SavedConnectionsStore.VERSION, root.get("version").getAsInt());
    assertEquals(1, root.getAsJsonArray("connections").size());
    final JsonObject entry = root.getAsJsonArray("connections").get(0).getAsJsonObject();
    assertEquals("2026-10-25T16:00:00Z", entry.get("savedAt").getAsString());
    assertEquals("Hauptbahnhof", entry.getAsJsonObject("from").get("name").getAsString());
    assertEquals(52.525, entry.getAsJsonObject("from").get("lat").getAsDouble(), 1e-9);
    assertTrue(entry.getAsJsonObject("itinerary").has("legs"));
    // No temporary file is left behind.
    assertFalse(new File(mFile.getPath() + SavedConnectionsStore.SUFFIX_TMP).exists());
  }

  @Test
  public void removeDeletesFromFile()
  {
    final SavedConnectionsStore store = store();
    save(store, Fixtures.s5("a", 29));
    save(store, Fixtures.s5("b", 39));
    assertTrue(store.remove("a"));
    assertFalse(store.remove("a"));
    assertFalse(store.contains("a"));
    assertEquals(1, store().size());
    assertTrue(store().contains("b"));
  }

  @Test
  public void savingAgainReplaces()
  {
    final SavedConnectionsStore store = store();
    save(store, Fixtures.s5("a", 29));
    mNow = mNow.plusSeconds(60);
    store.save(Fixtures.s5("a", 29), "Home", "Work", HBF, ALEX);
    assertEquals(1, store.size());
    assertEquals("Home", store.get("a").getFromName());
    assertEquals(mNow, store.get("a").getSavedAt());
  }

  @Test
  public void upcomingFirstThenPast()
  {
    final SavedConnectionsStore store = store();
    // Now is 16:00 UTC; s5 runs from the given minute after 16:00 for 17 minutes.
    save(store, Fixtures.s5("later", 90));
    save(store, Fixtures.s5("past-long-ago", -300));
    save(store, Fixtures.s5("soon", 10));
    save(store, Fixtures.s5("running", -5));
    save(store, Fixtures.s5("past-recent", -60));
    final List<String> keys = new ArrayList<>();
    for (SavedConnection saved : store.getAll())
      keys.add(saved.getKey());
    assertEquals(List.of("running", "soon", "later", "past-recent", "past-long-ago"), keys);
    assertTrue(store.get("past-recent").isPast(mNow));
    assertFalse(store.get("running").isPast(mNow));
  }

  @Test
  public void limitDropsTheConnectionThatEndedFirst()
  {
    final SavedConnectionsStore store = store();
    for (int i = 0; i < SavedConnectionsStore.MAX_ENTRIES; ++i)
      save(store, Fixtures.s5("c" + i, i));
    assertEquals(SavedConnectionsStore.MAX_ENTRIES, store.size());

    save(store, Fixtures.s5("new", 500));
    assertEquals(SavedConnectionsStore.MAX_ENTRIES, store.size());
    assertFalse(store.contains("c0"));
    assertTrue(store.contains("c1"));
    assertTrue(store.contains("new"));

    // The connection just saved is kept even if it is the oldest.
    save(store, Fixtures.s5("oldest", -1000));
    assertEquals(SavedConnectionsStore.MAX_ENTRIES, store.size());
    assertTrue(store.contains("oldest"));
    assertFalse(store.contains("c1"));
    assertEquals(SavedConnectionsStore.MAX_ENTRIES, store().size());
  }

  @Test
  public void brokenFileIsKeptAsideAndListStartsEmpty() throws IOException
  {
    writeFile("{\"version\":1,\"connections\":[{\"savedAt\":\"2026-");
    final SavedConnectionsStore store = store();
    assertTrue(store.getAll().isEmpty());
    final File broken = new File(mFile.getPath() + SavedConnectionsStore.SUFFIX_BROKEN);
    assertTrue(broken.exists());
    assertEquals("{\"version\":1,\"connections\":[{\"savedAt\":\"2026-", readFile(broken));

    // Saving works again and writes a valid file.
    save(store, Fixtures.s5("a", 29));
    assertEquals(1, store().size());
  }

  @Test
  public void garbageAndWrongTypesAreBroken() throws IOException
  {
    for (String text : new String[] {"", "garbage", "[]", "{\"connections\":[]}", "{\"version\":\"one\"}",
                                     "{\"version\":1}", "{\"version\":1,\"connections\":{}}"})
    {
      writeFile(text);
      assertTrue(text, store().getAll().isEmpty());
      assertFalse(text, mFile.exists());
    }
  }

  @Test
  public void newerVersionIsNotOverwritten() throws IOException
  {
    final String newer = "{\"version\":2,\"connections\":[{\"something\":\"new\"}]}";
    writeFile(newer);
    final SavedConnectionsStore store = store();
    assertTrue(store.getAll().isEmpty());
    save(store, Fixtures.s5("a", 29));
    assertEquals(newer, readFile(new File(mFile.getPath() + SavedConnectionsStore.SUFFIX_BROKEN)));
  }

  @Test
  public void brokenEntriesAreSkipped() throws IOException
  {
    save(store(), Fixtures.s5("a", 29));
    final JsonObject root = JsonParser.parseString(readFile(mFile)).getAsJsonObject();
    final JsonObject good = root.getAsJsonArray("connections").get(0).getAsJsonObject();
    final JsonObject noItinerary = good.deepCopy();
    noItinerary.remove("itinerary");
    final JsonObject badPlace = good.deepCopy();
    badPlace.getAsJsonObject("from").addProperty("lat", 123.0);
    final JsonObject badTime = good.deepCopy();
    badTime.getAsJsonObject("itinerary").addProperty("start", "soon");
    final JsonObject badSavedAt = good.deepCopy();
    badSavedAt.addProperty("savedAt", "never");
    badSavedAt.getAsJsonObject("itinerary").addProperty("id", "a2");
    root.getAsJsonArray("connections").add(noItinerary);
    root.getAsJsonArray("connections").add(badPlace);
    root.getAsJsonArray("connections").add(badTime);
    root.getAsJsonArray("connections").add("text");
    root.getAsJsonArray("connections").add(badSavedAt);
    // A duplicate is read once.
    root.getAsJsonArray("connections").add(good.deepCopy());
    writeFile(root.toString());

    final SavedConnectionsStore store = store();
    // The good one and the one without a valid save time (that is not essential).
    assertEquals(2, store.size());
    assertEquals(5, store.getSkippedCount());
    assertTrue(store.contains("a"));
    assertEquals(Instant.EPOCH, store.get("a2").getSavedAt());
  }

  @Test
  public void listenersHearChanges()
  {
    final SavedConnectionsStore store = store();
    final List<String> events = new ArrayList<>();
    final SavedConnectionsStore.Listener listener = () -> events.add("changed " + store.size());
    store.addListener(listener);
    save(store, Fixtures.s5("a", 29));
    store.remove("a");
    store.remove("a");
    store.removeListener(listener);
    save(store, Fixtures.s5("a", 29));
    assertEquals(List.of("changed 1", "changed 0"), events);
  }

  @Test
  public void writesRunOnTheExecutorInOrder() throws IOException
  {
    mWriteImmediately = false;
    final SavedConnectionsStore store = store();
    save(store, Fixtures.s5("a", 29));
    save(store, Fixtures.s5("b", 39));
    store.remove("a");
    // Nothing is written on the calling thread.
    assertFalse(mFile.exists());
    assertEquals(3, mPendingWrites.size());
    // The in-memory list is up to date anyway.
    assertTrue(store.contains("b"));
    for (Runnable write : mPendingWrites)
      write.run();
    final SavedConnectionsStore reloaded = store();
    assertEquals(1, reloaded.size());
    assertTrue(reloaded.contains("b"));
  }

  @Test
  public void unwritableLocationKeepsTheListInMemory() throws IOException
  {
    // The parent "directory" is a file, so nothing can be written.
    final File blocker = mFolder.newFile("blocker");
    mFile = new File(blocker, "saved.json");
    final SavedConnectionsStore store = store();
    save(store, Fixtures.s5("a", 29));
    assertTrue(store.contains("a"));
    assertFalse(mFile.exists());
  }
}
