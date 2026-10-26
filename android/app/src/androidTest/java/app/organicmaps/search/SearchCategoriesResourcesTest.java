package app.organicmaps.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.res.Configuration;
import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The quick categories for single places to eat and drink (libs/search/displayed_categories.cpp) need a text and an
 * icon, looked up by the key like CategoriesAdapter does. Tapping a category searches for its text, so the texts must
 * stay full category names in data/categories.txt (checked in search_tests, category_synonyms_tests.cpp).
 */
@RunWith(AndroidJUnit4.class)
public class SearchCategoriesResourcesTest
{
  private static final String[] KEYS = {"category_restaurant", "category_cafe", "category_bakery", "category_bar_club"};
  private static final String[] ENGLISH = {"Restaurant", "Café", "Bakery", "Bars & Clubs"};
  private static final String[] GERMAN = {"Restaurant", "Café", "Bäckerei", "Bars & Clubs"};
  // A language without own texts falls back to English, which is always matched by the search.
  private static final String[] FRENCH = ENGLISH;

  private static Context localized(Context context, Locale locale)
  {
    final Configuration config = new Configuration(context.getResources().getConfiguration());
    config.setLocale(locale);
    return context.createConfigurationContext(config);
  }

  private static void checkTexts(Context context, Locale locale, String[] expected)
  {
    final Context localizedContext = localized(context, locale);
    for (int i = 0; i < KEYS.length; i++)
    {
      final int id =
          CategoriesAdapter.getStringResIdByKey(localizedContext.getResources(), context.getPackageName(), KEYS[i]);
      assertNotEquals(KEYS[i], 0, id);
      assertEquals(KEYS[i] + " " + locale, expected[i], localizedContext.getString(id));
    }
  }

  @Test
  public void textsInEnglishGermanAndFallback()
  {
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    checkTexts(context, Locale.ENGLISH, ENGLISH);
    checkTexts(context, Locale.GERMAN, GERMAN);
    checkTexts(context, Locale.FRENCH, FRENCH);
  }

  @Test
  public void iconsExist()
  {
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    for (String key : KEYS)
    {
      final int id = CategoriesAdapter.getDrawableResIdByKey(context, context.getPackageName(), key);
      assertNotEquals(key, 0, id);
      assertNotNull(key, ContextCompat.getDrawable(context, id));
    }
  }
}
