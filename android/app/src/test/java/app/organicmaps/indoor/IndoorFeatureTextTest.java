package app.organicmaps.indoor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.R;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class IndoorFeatureTextTest
{
  /** German texts of the strings used here. */
  private static final IndoorFeatureText.Strings DE = new IndoorFeatureText.Strings() {
    final Map<Integer, String> mTexts = new HashMap<>();
    {
      mTexts.put(R.string.indoor_gate, "Gate %s");
      mTexts.put(R.string.indoor_ref, "Nr. %s");
      mTexts.put(R.string.indoor_level, "Ebene %s");
      mTexts.put(R.string.indoor_kind_shop, "Geschäft");
      mTexts.put(R.string.indoor_kind_gate, "Gate");
      mTexts.put(R.string.indoor_kind_toilets, "Toiletten");
      mTexts.put(R.string.indoor_kind_poi, "Ort");
      mTexts.put(R.string.indoor_opening_hours, "Öffnungszeiten: %s");
      mTexts.put(R.string.indoor_cuisine, "Küche: %s");
      mTexts.put(R.string.indoor_operator, "Betreiber: %s");
      mTexts.put(R.string.indoor_wheelchair_yes, "Rollstuhlgerecht");
      mTexts.put(R.string.indoor_wheelchair_limited, "Eingeschränkt rollstuhlgerecht");
      mTexts.put(R.string.indoor_fee_no, "Kostenlos");
    }

    @NonNull
    @Override
    public String get(int id, @NonNull Object... args)
    {
      final String text = mTexts.get(id);
      if (text == null)
        throw new AssertionError("unexpected string " + id);
      return String.format(text, args);
    }
  };

  @Test
  public void titles()
  {
    assertEquals("Relay", IndoorFeatureText.title(DE, "Relay", "B12", "shop"));
    assertEquals("Gate B44", IndoorFeatureText.title(DE, "", "B44", "gate"));
    assertEquals("K1", IndoorFeatureText.title(DE, "", "K1", "room"));
    assertEquals("Toiletten", IndoorFeatureText.title(DE, "", "", "toilets"));
    assertEquals("Ort", IndoorFeatureText.title(DE, "", "", "something_new"));
  }

  @Test
  public void subtitles()
  {
    assertEquals("Nr. B12 · Geschäft · Ebene 0 · Terminal 1",
                 IndoorFeatureText.subtitle(DE, "Relay", "B12", "shop", "0", "Terminal 1"));
    assertEquals("Gate · Ebene 1", IndoorFeatureText.subtitle(DE, "", "B44", "gate", "1", ""));
    // The kind is the title already.
    assertEquals("Ebene -1 · Köln Hbf", IndoorFeatureText.subtitle(DE, "", "", "toilets", "-1", "Köln Hbf"));
  }

  @Test
  public void tagsAndDetails()
  {
    final Map<String, String> tags = IndoorFeatureText.tags(new String[] {
        "website", "https://example.org", "opening_hours", "Mo-Su 05:00-23:00", "cuisine", "coffee_shop;bagel",
        "wheelchair", "limited", "fee", "no", "brand", "Starbucks", "dangling"});
    assertEquals(6, tags.size());
    final List<String> lines = IndoorFeatureText.details(DE, tags);
    assertEquals("Öffnungszeiten: Mo-Su 05:00-23:00", lines.get(0));
    assertEquals("Küche: coffee shop, bagel", lines.get(1));
    assertEquals("Eingeschränkt rollstuhlgerecht", lines.get(2));
    assertEquals("Kostenlos", lines.get(3));
    assertEquals("Betreiber: Starbucks", lines.get(4));
    assertEquals("https://example.org", lines.get(5));

    assertTrue(IndoorFeatureText.details(DE, IndoorFeatureText.tags(null)).isEmpty());
    // Only web links are shown.
    final Map<String, String> bad = IndoorFeatureText.tags(new String[] {"website", "javascript:alert(1)"});
    assertTrue(IndoorFeatureText.details(DE, bad).isEmpty());
  }

  @Test
  public void everyKindHasALabel()
  {
    for (String kind : new String[] {"level", "corridor", "room", "area", "shop", "food", "toilets", "stairs", "steps",
                                     "elevator", "escalator", "gate", "service", "platform", "restricted", "door",
                                     "entrance", "info", "atm", "pharmacy", "luggage", "ticket", "poi"})
      assertTrue(kind, IndoorFeatureText.kindLabel(kind) != 0);
    assertEquals(R.string.indoor_kind_poi, IndoorFeatureText.kindLabel("unknown"));
  }
}
