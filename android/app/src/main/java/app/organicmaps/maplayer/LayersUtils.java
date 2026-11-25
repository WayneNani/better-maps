package app.organicmaps.maplayer;

import app.organicmaps.sdk.maplayer.Mode;
import java.util.ArrayList;
import java.util.List;

public class LayersUtils
{
  public static List<Mode> getAvailableLayers()
  {
    List<Mode> availableLayers = new ArrayList<>();
    availableLayers.add(Mode.OUTDOORS);
    availableLayers.add(Mode.ISOLINES);
    availableLayers.add(Mode.SUBWAY);
    availableLayers.add(Mode.TRAFFIC);
    // better-maps: aerial imagery from the user's own server (Settings -> Aerial imagery server).
    availableLayers.add(Mode.AERIAL);
    return availableLayers;
  }
}
