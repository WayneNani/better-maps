package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitItineraryJson;
import app.organicmaps.sdk.transit.TransitLocation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Connections saved by the user, stored as one JSON file in the app's private storage. Nothing is sent anywhere.
 * <p>
 * The file is read once on first access; every change is written in the background (atomically via a temporary
 * file). A broken file is kept aside as "*.broken" and the list starts empty; single broken entries are skipped. A
 * file of a newer, unknown format version is kept aside as well instead of being overwritten.
 * <p>
 * All methods must be called on one thread (the main thread).
 */
public final class SavedConnectionsStore
{
  /** Version of the file format; increase on incompatible changes and keep reading older ones. */
  public static final int VERSION = 1;
  /** At most this many connections are kept; saving one more drops the one that ended first. */
  public static final int MAX_ENTRIES = 100;
  static final String SUFFIX_BROKEN = ".broken";
  static final String SUFFIX_TMP = ".tmp";

  public interface Listener
  {
    void onSavedConnectionsChanged();
  }

  @NonNull
  private final File mFile;
  @NonNull
  private final Executor mIoExecutor;
  @NonNull
  private final TransitConnectionsPresenter.Clock mClock;
  @NonNull
  private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
  @Nullable
  private List<SavedConnection> mEntries;
  // Entries of the file that could not be read, for tests and diagnostics.
  private int mSkipped;

  /**
   * @param ioExecutor runs the file writes in order, e.g. a single thread executor.
   */
  public SavedConnectionsStore(@NonNull File file, @NonNull Executor ioExecutor,
                               @NonNull TransitConnectionsPresenter.Clock clock)
  {
    mFile = file;
    mIoExecutor = ioExecutor;
    mClock = clock;
  }

  public void addListener(@NonNull Listener listener)
  {
    mListeners.add(listener);
  }

  public void removeListener(@NonNull Listener listener)
  {
    mListeners.remove(listener);
  }

  /** Upcoming connections first (earliest departure first), then past ones (latest first). */
  @NonNull
  public List<SavedConnection> getAll()
  {
    final List<SavedConnection> result = new ArrayList<>(entries());
    final Instant now = mClock.now();
    result.sort(order(now));
    return Collections.unmodifiableList(result);
  }

  public int size()
  {
    return entries().size();
  }

  @Nullable
  public SavedConnection get(@NonNull String key)
  {
    for (SavedConnection entry : entries())
      if (entry.getKey().equals(key))
        return entry;
    return null;
  }

  public boolean contains(@NonNull String key)
  {
    return get(key) != null;
  }

  /**
   * Saves a connection, replacing an earlier copy of it.
   *
   * @param from coordinates of the search start; fromName its name.
   */
  @NonNull
  public SavedConnection save(@NonNull TransitItinerary itinerary, @NonNull String fromName, @NonNull String toName,
                              @NonNull TransitLocation from, @NonNull TransitLocation to)
  {
    final SavedConnection saved = new SavedConnection(itinerary, fromName, toName, from, to, mClock.now());
    final List<SavedConnection> entries = entries();
    removeEntry(saved.getKey());
    while (entries.size() >= MAX_ENTRIES)
      entries.remove(oldest(entries));
    entries.add(saved);
    persist();
    return saved;
  }

  /** Puts back a connection removed before (e.g. "undo"), with its original save time. */
  public void restore(@NonNull SavedConnection saved)
  {
    final List<SavedConnection> entries = entries();
    removeEntry(saved.getKey());
    while (entries.size() >= MAX_ENTRIES)
      entries.remove(oldest(entries));
    entries.add(saved);
    persist();
  }

  /** @return false if there was no such connection. */
  public boolean remove(@NonNull String key)
  {
    if (!removeEntry(key))
      return false;
    persist();
    return true;
  }

  @VisibleForTesting
  int getSkippedCount()
  {
    entries();
    return mSkipped;
  }

  private boolean removeEntry(@NonNull String key)
  {
    final List<SavedConnection> entries = entries();
    for (int i = 0; i < entries.size(); ++i)
    {
      if (entries.get(i).getKey().equals(key))
      {
        entries.remove(i);
        return true;
      }
    }
    return false;
  }

  @NonNull
  private static SavedConnection oldest(@NonNull List<SavedConnection> entries)
  {
    SavedConnection oldest = entries.get(0);
    for (SavedConnection entry : entries)
      if (entry.getItinerary().getEndTime().isBefore(oldest.getItinerary().getEndTime()))
        oldest = entry;
    return oldest;
  }

  @NonNull
  static Comparator<SavedConnection> order(@NonNull Instant now)
  {
    return (a, b) ->
    {
      final boolean pastA = a.isPast(now);
      final boolean pastB = b.isPast(now);
      if (pastA != pastB)
        return pastA ? 1 : -1;
      final int byStart = a.getItinerary().getStartTime().compareTo(b.getItinerary().getStartTime());
      return pastA ? -byStart : byStart;
    };
  }

  @NonNull
  private List<SavedConnection> entries()
  {
    if (mEntries == null)
      mEntries = load();
    return mEntries;
  }

  @NonNull
  private List<SavedConnection> load()
  {
    final List<SavedConnection> result = new ArrayList<>();
    if (!mFile.exists())
      return result;
    final JsonArray connections;
    try
    {
      final String text = read(mFile);
      final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
      final JsonElement version = root.get("version");
      if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber())
        throw new JsonParseException("No version");
      if (version.getAsInt() > VERSION)
      {
        // Written by a newer app version: keep it for a later update instead of overwriting it.
        keepAside(SUFFIX_BROKEN);
        return result;
      }
      connections = root.getAsJsonArray("connections");
      if (connections == null)
        throw new JsonParseException("No connections");
    }
    catch (IOException | RuntimeException e)
    {
      // Unreadable or broken (e.g. cut off). Keep it for diagnostics and start empty.
      keepAside(SUFFIX_BROKEN);
      return result;
    }
    final Set<String> keys = new HashSet<>();
    for (JsonElement element : connections)
    {
      try
      {
        final SavedConnection entry = fromJson(element.getAsJsonObject());
        if (keys.add(entry.getKey()))
          result.add(entry);
        else
          ++mSkipped;
      }
      catch (RuntimeException e)
      {
        ++mSkipped;
      }
    }
    return result;
  }

  @NonNull
  private static String read(@NonNull File file) throws IOException
  {
    try (InputStream in = new FileInputStream(file))
    {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buffer = new byte[16384];
      int n;
      while ((n = in.read(buffer)) != -1)
        out.write(buffer, 0, n);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  private void keepAside(@NonNull String suffix)
  {
    final File target = new File(mFile.getPath() + suffix);
    // noinspection ResultOfMethodCallIgnored
    target.delete();
    if (!mFile.renameTo(target))
      // noinspection ResultOfMethodCallIgnored
      mFile.delete();
  }

  private void persist()
  {
    final JsonObject root = new JsonObject();
    root.addProperty("version", VERSION);
    final JsonArray connections = new JsonArray();
    for (SavedConnection entry : entries())
      connections.add(toJson(entry));
    root.add("connections", connections);
    final String text = root.toString();
    mIoExecutor.execute(() -> write(text));
    for (Listener listener : mListeners)
      listener.onSavedConnectionsChanged();
  }

  private void write(@NonNull String text)
  {
    final File tmp = new File(mFile.getPath() + SUFFIX_TMP);
    try
    {
      final File dir = mFile.getParentFile();
      if (dir != null && !dir.isDirectory() && !dir.mkdirs())
        throw new IOException("Cannot create " + dir);
      try (FileOutputStream out = new FileOutputStream(tmp))
      {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      if (!tmp.renameTo(mFile))
        throw new IOException("Cannot rename " + tmp);
    }
    catch (IOException e)
    {
      // The list stays in memory; the next change tries again.
      // noinspection ResultOfMethodCallIgnored
      tmp.delete();
    }
  }

  @NonNull
  static JsonObject toJson(@NonNull SavedConnection entry)
  {
    final JsonObject json = new JsonObject();
    json.addProperty("savedAt", entry.getSavedAt().toString());
    json.add("from", place(entry.getFromName(), entry.getFrom()));
    json.add("to", place(entry.getToName(), entry.getTo()));
    json.add("itinerary", TransitItineraryJson.toJson(entry.getItinerary()));
    return json;
  }

  @NonNull
  static SavedConnection fromJson(@NonNull JsonObject json)
  {
    final JsonObject from = json.getAsJsonObject("from");
    final JsonObject to = json.getAsJsonObject("to");
    final JsonObject itinerary = json.getAsJsonObject("itinerary");
    if (from == null || to == null || itinerary == null)
      throw new JsonParseException("Incomplete entry");
    Instant savedAt;
    try
    {
      savedAt = Instant.parse(json.get("savedAt").getAsString());
    }
    catch (RuntimeException e)
    {
      savedAt = Instant.EPOCH;
    }
    return new SavedConnection(TransitItineraryJson.fromJson(itinerary), name(from), name(to), location(from),
                               location(to), savedAt);
  }

  @NonNull
  private static JsonObject place(@NonNull String name, @NonNull TransitLocation location)
  {
    final JsonObject json = new JsonObject();
    json.addProperty("name", name);
    json.addProperty("lat", location.getLat());
    json.addProperty("lon", location.getLon());
    return json;
  }

  @NonNull
  private static String name(@NonNull JsonObject place)
  {
    final JsonElement name = place.get("name");
    return name != null && name.isJsonPrimitive() ? name.getAsString() : "";
  }

  @NonNull
  private static TransitLocation location(@NonNull JsonObject place)
  {
    // Throws for missing or invalid coordinates; the entry is skipped then.
    return TransitLocation.fromCoordinates(place.get("lat").getAsDouble(), place.get("lon").getAsDouble());
  }
}
