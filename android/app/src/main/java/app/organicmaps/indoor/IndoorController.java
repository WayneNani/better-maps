package app.organicmaps.indoor;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.indoor.IndoorListener;
import app.organicmaps.util.ThemeUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.List;
import java.util.Map;

/**
 * better-maps: indoor maps on the map screen. Shows the level switcher (with the OSM attribution) while the core draws an
 * indoor map and the details of a tapped room or place in a small bottom sheet. Registered with the core while the map
 * activity is resumed.
 */
public final class IndoorController implements IndoorListener
{
  @NonNull
  private final Activity mActivity;
  @NonNull
  private final View mPanel;
  @NonNull
  private final ScrollView mScroll;
  @NonNull
  private final LinearLayout mLevels;
  @NonNull
  private final View mCard;

  private double[] mShownLevels = new double[0];
  private String[] mShownLabels = new String[0];
  private double mShownLevel = Double.NaN;
  private String mVenueName = "";
  private BottomSheetDialog mSheet;

  public IndoorController(@NonNull Activity activity)
  {
    mActivity = activity;
    mPanel = activity.findViewById(R.id.indoor_panel);
    mScroll = activity.findViewById(R.id.indoor_level_scroll);
    mLevels = activity.findViewById(R.id.indoor_levels);
    mCard = activity.findViewById(R.id.indoor_level_card);
  }

  public void onResume()
  {
    Framework.nativeSetIndoorListener(this);
  }

  public void onPause()
  {
    Framework.nativeSetIndoorListener(null);
  }

  public void onDestroy()
  {
    if (mSheet != null)
    {
      mSheet.dismiss();
      mSheet = null;
    }
  }

  @Override
  public void onIndoorStateChanged(boolean visible, @NonNull String venueName, @NonNull String attribution,
                                   @NonNull double[] levels, @NonNull String[] labels, double level)
  {
    if (!visible || levels.length == 0)
    {
      mPanel.setVisibility(View.GONE);
      mShownLevels = new double[0];
      return;
    }
    mVenueName = venueName;
    final boolean sameLevels = java.util.Arrays.equals(levels, mShownLevels)
                               && java.util.Arrays.equals(labels, mShownLabels);
    final boolean sameLevel = IndoorLevelModel.sameLevel(level, mShownLevel);
    mPanel.setVisibility(View.VISIBLE);
    if (sameLevels && sameLevel)
      return;
    mShownLevels = levels.clone();
    mShownLabels = labels.clone();
    mShownLevel = level;
    buildButtons(IndoorLevelModel.items(levels, labels, level));
  }

  private void buildButtons(@NonNull List<IndoorLevelModel.Item> items)
  {
    mLevels.removeAllViews();
    final int size = mActivity.getResources().getDimensionPixelSize(R.dimen.indoor_level_button_size);
    final int selectedText = ThemeUtils.getColor(mActivity, com.google.android.material.R.attr.colorOnPrimaryContainer);
    final int text = ThemeUtils.getColor(mActivity, android.R.attr.textColorPrimary);
    for (IndoorLevelModel.Item item : items)
    {
      final TextView button = new TextView(mActivity);
      button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size));
      button.setGravity(Gravity.CENTER);
      button.setMaxLines(1);
      button.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                         mActivity.getResources().getDimension(R.dimen.indoor_level_text));
      button.setText(item.label);
      button.setTextColor(item.selected ? selectedText : text);
      button.setTypeface(button.getTypeface(), item.selected ? android.graphics.Typeface.BOLD
                                                             : android.graphics.Typeface.NORMAL);
      if (item.selected)
        button.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.indoor_level_selected));
      else
      {
        final TypedValue ripple = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
      }
      button.setContentDescription(mActivity.getString(R.string.indoor_level, item.label));
      button.setSelected(item.selected);
      ViewCompat.setStateDescription(button, item.selected ? mActivity.getString(R.string.indoor_level_shown) : null);
      ViewCompat.setAccessibilityDelegate(button, new AccessibilityDelegateCompat() {
        @Override
        public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info)
        {
          super.onInitializeAccessibilityNodeInfo(host, info);
          info.setClassName(android.widget.Button.class.getName());
        }
      });
      final double level = item.level;
      button.setOnClickListener(v -> Framework.nativeSetIndoorLevel(level));
      mLevels.addView(button);
    }
    final ViewGroup.LayoutParams lp = mScroll.getLayoutParams();
    lp.height = size * IndoorLevelModel.visibleCount(items.size());
    mScroll.setLayoutParams(lp);
    mCard.setContentDescription(mVenueName.isEmpty() ? mActivity.getString(R.string.indoor_levels)
                                                      : mActivity.getString(R.string.indoor_levels_of, mVenueName));
    // Keep the shown level in view.
    final int selected = IndoorLevelModel.selectedIndex(items);
    if (selected >= 0)
      mScroll.post(() -> mScroll.scrollTo(0, Math.max(0, (selected - 2) * size)));
  }

  @Override
  public void onIndoorFeatureTapped(@NonNull String name, @NonNull String ref, @NonNull String kind,
                                    @NonNull String levelLabel, @NonNull String venueName, @NonNull String osmId,
                                    @NonNull String[] tags, double lat, double lon)
  {
    if (mActivity.isFinishing() || mActivity.isDestroyed())
      return;
    final IndoorFeatureText.Strings strings = (id, args) -> mActivity.getString(id, args);
    final View view = LayoutInflater.from(mActivity).inflate(R.layout.indoor_feature_sheet, null, false);
    ((TextView) view.findViewById(R.id.indoor_feature_title))
        .setText(IndoorFeatureText.title(strings, name, ref, kind));
    ((TextView) view.findViewById(R.id.indoor_feature_subtitle))
        .setText(IndoorFeatureText.subtitle(strings, name, ref, kind, levelLabel, venueName));
    final Map<String, String> tagMap = IndoorFeatureText.tags(tags);
    final List<String> details = IndoorFeatureText.details(strings, tagMap);
    final TextView detailsView = view.findViewById(R.id.indoor_feature_details);
    if (!details.isEmpty())
    {
      detailsView.setText(String.join("\n", details));
      detailsView.setVisibility(View.VISIBLE);
    }
    if (mSheet != null)
      mSheet.dismiss();
    mSheet = new BottomSheetDialog(mActivity, R.style.MwmTheme_BottomSheetDialog);
    mSheet.setContentView(view);
    mSheet.setOnDismissListener(d -> mSheet = null);
    mSheet.show();
  }
}
