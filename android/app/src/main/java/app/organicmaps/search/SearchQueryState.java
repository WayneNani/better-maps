package app.organicmaps.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Whether the text of a search field is a categorial request. A tap on a search category sets the category text; the
 * request is categorial only while the text is unchanged. Typing another text ("Italiener offen" after "Café") makes
 * it a normal query, so that it is not searched as a category name and filter words are detected.
 * <p>
 * Pure Java, so it can be unit tested; {@link app.organicmaps.widget.SearchToolbarController} feeds it the text
 * changes of its field.
 */
public final class SearchQueryState
{
  // The text set by a tap on a search category, null for a normal query.
  @Nullable
  private String mCategoryQuery;

  /** A new text set by the app: a tapped category if {@code fromCategory}, otherwise a normal query. */
  public void setQuery(@NonNull CharSequence text, boolean fromCategory)
  {
    mCategoryQuery = fromCategory ? text.toString() : null;
  }

  /** The text of the field has changed (also called for the text set by {@link #setQuery}). */
  public void onTextChanged(@NonNull CharSequence text)
  {
    if (mCategoryQuery != null && !isSameText(text, mCategoryQuery))
      mCategoryQuery = null;
  }

  public boolean isCategory()
  {
    return mCategoryQuery != null;
  }

  /** The text of the tapped category, null for a normal query. */
  @Nullable
  public String getCategoryQuery()
  {
    return mCategoryQuery;
  }

  /**
   * Restores the category of a recreated search screen, before its text field restores the text: the category is
   * kept if the restored text is the same.
   *
   * @param categoryQuery {@link #getCategoryQuery()} of the previous screen.
   */
  public void restoreCategory(@Nullable String categoryQuery)
  {
    mCategoryQuery = categoryQuery;
  }

  /**
   * Category texts end with a space (to search for the category, not a name prefix); keyboards may remove or add
   * spaces at the end, e.g. when the field loses the focus. Only other words make it another query.
   */
  private static boolean isSameText(@NonNull CharSequence text, @NonNull String categoryQuery)
  {
    return text.toString().trim().equals(categoryQuery.trim());
  }
}
