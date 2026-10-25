package app.organicmaps.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.search.online.OnlineSearchException;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** List rows of the online section, type labels, map links and error mapping. */
public class OnlineSearchRowsTest
{
  private static final OnlineSearchResult STREET = OnlineSearchResult.builder(48.137569, 11.57498)
                                                       .setName("Marienplatz")
                                                       .setTag("highway", "pedestrian")
                                                       .setPlaceType(OnlineSearchResult.PlaceType.STREET)
                                                       .build();

  @NonNull
  private static List<OnlineSearchRows.Type> types(@NonNull OnlineSearchState state)
  {
    final List<OnlineSearchRows.Type> types = new ArrayList<>();
    for (OnlineSearchRows.Row row : OnlineSearchRows.build(state))
      types.add(row.type);
    return types;
  }

  @NonNull
  private static OnlineSearchState state(@NonNull OnlineSearchState.Status status, @NonNull OnlineSearchItem... items)
  {
    return new OnlineSearchState(status, Arrays.asList(items), null, "Photon");
  }

  @Test
  public void rowsPerStatus()
  {
    assertTrue(OnlineSearchRows.build(OnlineSearchState.HIDDEN).isEmpty());
    assertEquals(Arrays.asList(OnlineSearchRows.Type.HEADER, OnlineSearchRows.Type.OFFER),
                 types(state(OnlineSearchState.Status.OFFER)));
    assertEquals(Arrays.asList(OnlineSearchRows.Type.HEADER, OnlineSearchRows.Type.LOADING),
                 types(state(OnlineSearchState.Status.LOADING)));
    assertEquals(Arrays.asList(OnlineSearchRows.Type.HEADER, OnlineSearchRows.Type.EMPTY),
                 types(state(OnlineSearchState.Status.EMPTY)));
    assertEquals(Arrays.asList(OnlineSearchRows.Type.HEADER, OnlineSearchRows.Type.ERROR),
                 types(state(OnlineSearchState.Status.ERROR)));
    final OnlineSearchItem item = new OnlineSearchItem(STREET, null);
    final List<OnlineSearchRows.Row> rows = OnlineSearchRows.build(state(OnlineSearchState.Status.RESULTS, item, item));
    assertEquals(3, rows.size());
    assertEquals(OnlineSearchRows.Type.RESULT, rows.get(1).type);
    assertEquals(item, rows.get(2).item);
  }

  @Test
  public void typeStringKeys()
  {
    assertEquals(Arrays.asList("type.highway.pedestrian", "type.highway"), OnlineSearchRows.typeStringKeys(STREET));
    assertEquals(
        Collections.singletonList("type.building.office"),
        OnlineSearchRows.typeStringKeys(OnlineSearchResult.builder(1, 2).setTag("building", "office").build()));
    assertEquals(Collections.singletonList("type.place.square"),
                 OnlineSearchRows.typeStringKeys(OnlineSearchResult.builder(1, 2).setTag("place", "square").build()));
    assertEquals(Collections.singletonList("type.shop"),
                 OnlineSearchRows.typeStringKeys(OnlineSearchResult.builder(1, 2).setTag("shop", "yes").build()));
    assertEquals(
        Collections.singletonList("type.disused_amenity.cafe"),
        OnlineSearchRows.typeStringKeys(OnlineSearchResult.builder(1, 2).setTag("disused:amenity", "cafe").build())
            .subList(0, 1));
    assertTrue(OnlineSearchRows.typeStringKeys(OnlineSearchResult.builder(1, 2).build()).isEmpty());
  }

  @Test
  public void humanize()
  {
    assertEquals("Fast food", OnlineSearchRows.humanize("fast_food"));
    assertEquals("Office", OnlineSearchRows.humanize("office"));
    assertEquals("", OnlineSearchRows.humanize("yes"));
    assertEquals("", OnlineSearchRows.humanize(null));
    assertEquals("", OnlineSearchRows.humanize("_"));
  }

  @Test
  public void mapLink()
  {
    assertEquals("cm://map?ll=48.1375690,11.5749800&z=16&n=Marienplatz", OnlineSearchMapLink.toApiUrl(STREET));
    final OnlineSearchResult cafe = OnlineSearchResult.builder(48.1, 11.5)
                                        .setName("Café & Bar 1/2")
                                        .setPlaceType(OnlineSearchResult.PlaceType.HOUSE)
                                        .build();
    assertEquals("cm://map?ll=48.1000000,11.5000000&z=17&n=Caf%C3%A9%20%26%20Bar%201%2F2",
                 OnlineSearchMapLink.toApiUrl(cafe));
    assertTrue(OnlineSearchMapLink.zoomFor(OnlineSearchResult.PlaceType.COUNTRY)
               < OnlineSearchMapLink.zoomFor(OnlineSearchResult.PlaceType.CITY));
    for (OnlineSearchResult.PlaceType type : OnlineSearchResult.PlaceType.values())
      assertTrue(OnlineSearchMapLink.zoomFor(type) >= 1 && OnlineSearchMapLink.zoomFor(type) <= 17);
  }

  @Test
  public void errorMapping()
  {
    assertEquals(OnlineSearchState.Error.NETWORK, OnlineSearchState.Error.of(new OnlineSearchException(
                                                      OnlineSearchException.Kind.NETWORK, 0, null, "", null)));
    assertEquals(OnlineSearchState.Error.SERVER, OnlineSearchState.Error.of(new OnlineSearchException(
                                                     OnlineSearchException.Kind.SERVER_ERROR, 503, null, "", null)));
    assertEquals(OnlineSearchState.Error.SERVER,
                 OnlineSearchState.Error.of(
                     new OnlineSearchException(OnlineSearchException.Kind.INVALID_RESPONSE, 200, null, "", null)));
    assertEquals(OnlineSearchState.Error.REQUEST, OnlineSearchState.Error.of(new OnlineSearchException(
                                                      OnlineSearchException.Kind.CLIENT_ERROR, 400, null, "", null)));
    // Rate limiting is the server's problem, the user may retry later.
    assertEquals(OnlineSearchState.Error.SERVER, OnlineSearchState.Error.of(new OnlineSearchException(
                                                     OnlineSearchException.Kind.CLIENT_ERROR, 429, null, "", null)));
    assertEquals(OnlineSearchState.Error.DISABLED, OnlineSearchState.Error.of(OnlineSearchException.disabled("off")));
    assertEquals(OnlineSearchState.Error.UNKNOWN, OnlineSearchState.Error.of(new IllegalStateException()));
  }
}
