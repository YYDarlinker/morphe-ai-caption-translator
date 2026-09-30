package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Inline slider that persists on finger release; no additional dialog or Save button is used. */
@SuppressWarnings("deprecation")
public final class DeepSeekSliderPreference extends android.preference.Preference {
    static final String KEY_TEXT_SIZE = "deepseek_caption_text_size";
    static final String KEY_OPACITY = "deepseek_caption_background_opacity";
    private static final String[] SIZE_TIER_KEYS = {
            "size_tier_xs", "size_tier_s", "size_tier_standard", "size_tier_l", "size_tier_xl"
    };

    public DeepSeekSliderPreference(Context context) {
        super(context);
        initialize();
    }

    public DeepSeekSliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public DeepSeekSliderPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public DeepSeekSliderPreference(
            Context context,
            AttributeSet attrs,
            int defStyleAttr,
            int defStyleRes
    ) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize();
    }

    private void initialize() {
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    public View getView(View convertView, ViewGroup parent) {
        // Text-size and opacity share this class but bind to different ranges and values.
        String key = getKey();
        View safeView = convertView != null && key != null && key.equals(convertView.getTag())
                ? convertView
                : null;
        return super.getView(safeView, parent);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout root = new LinearLayout(context);
        root.setTag(getKey());
        root.setOrientation(LinearLayout.VERTICAL);
        CaptionSettingsStyle.row(root);

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(context);
        title.setText(getTitle());
        CaptionSettingsStyle.title(title);
        title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        heading.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView valueLabel = new TextView(context);
        CaptionSettingsStyle.caption(valueLabel);
        valueLabel.setTextSize(14);
        valueLabel.setTextColor(CaptionSettingsStyle.primary(context));
        valueLabel.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        valueLabel.setPadding(dp(12),0,0,0);
        heading.addView(valueLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        boolean sizeSlider = KEY_TEXT_SIZE.equals(getKey());
        SeekBar slider = sizeSlider ? new SizeTierSeekBar(context) : new SeekBar(context);
        CaptionSettingsStyle.slider(slider);
        slider.setTag(sizeSlider ? "ai_size_tier_slider" : "ai_opacity_slider");
        slider.setMinimumHeight(dp(48));
        slider.setContentDescription(getTitle());
        int minimum = minimum();
        int maximum = maximum();
        int current = currentValue();
        slider.setMax(maximum - minimum);
        slider.setProgress(current - minimum);
        valueLabel.setText(format(current));
        root.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout tierNames = sizeSlider ? tierNames(slider) : null;
        if (tierNames != null) {
            root.addView(tierNames, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            updateTierNames(slider, tierNames, current);
        }

        CharSequence summaryText = KEY_TEXT_SIZE.equals(getKey()) ? tierDescription(current) : getSummary();
        TextView summary = new TextView(context);
        if (summaryText != null && summaryText.length() > 0) {
            summary.setText(summaryText);
            CaptionSettingsStyle.caption(summary);
            root.addView(summary, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueLabel.setText(format(minimum + progress));
                if (sizeSlider) {
                    summary.setText(tierDescription(minimum + progress));
                    updateTierNames(slider, tierNames, minimum + progress);
                    slider.invalidate();
                }
                if(fromUser) SubtitleStylePreview.update(getKey(),minimum+progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                saveValue(minimum + seekBar.getProgress());
            }
        });
        return root;
    }

    private LinearLayout tierNames(SeekBar slider) {
        LinearLayout names = new LinearLayout(getContext()) {
            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                for (int i = 0; i < getChildCount(); i++) {
                    View label = getChildAt(i);
                    float center = ((SizeTierSeekBar) slider).tickCenterX(i);
                    int x = Math.round(center - label.getMeasuredWidth() / 2f);
                    label.layout(x, label.getTop(), x + label.getMeasuredWidth(), label.getBottom());
                }
            }
        };
        names.setTag("ai_size_tier_names");
        names.setOrientation(LinearLayout.HORIZONTAL);
        names.setPadding(0, 0, 0, dp(4));
        names.setClipChildren(false);
        names.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (String key : SIZE_TIER_KEYS) {
            TextView name = new TextView(getContext());
            name.setText(CaptionStrings.settings(getContext(), key));
            CaptionSettingsStyle.caption(name);
            name.setGravity(Gravity.CENTER);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            names.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return names;
    }

    private void updateTierNames(SeekBar slider, LinearLayout names, int tier) {
        int selected = CaptionFontSize.clampTier(tier);
        String current = CaptionStrings.settings(getContext(), SIZE_TIER_KEYS[selected]);
        String description = getTitle() + ": " + current;
        slider.setContentDescription(description);
        names.setContentDescription(description);
        for (int i = 0; i < names.getChildCount(); i++) {
            TextView name = (TextView) names.getChildAt(i);
            name.setTextColor(i == selected ? CaptionSettingsStyle.primary(getContext())
                    : CaptionSettingsStyle.secondary(getContext()));
            name.setTypeface(Typeface.DEFAULT, i == selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** Five native thumb positions, with visible ticks even when the theme omits tick marks. */
    static final class SizeTierSeekBar extends SeekBar {
        private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float nativeHalfThumb;

        SizeTierSeekBar(Context context) {
            super(context);
            nativeHalfThumb = getThumb() == null ? getThumbOffset() : getThumb().getIntrinsicWidth() / 2f;
            setTickMark(null);
        }

        float tickCenterX(int tier) {
            float halfThumb = getThumb() == null ? nativeHalfThumb
                    : getThumb().getIntrinsicWidth() / 2f;
            float trackWidth = getWidth() - getPaddingLeft() - getPaddingRight()
                    + 2f * getThumbOffset() - 2f * halfThumb;
            float fraction = CaptionFontSize.clampTier(tier) / (float) (CaptionFontSize.COUNT - 1);
            if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) fraction = 1f - fraction;
            return getPaddingLeft() - getThumbOffset() + halfThumb + fraction * trackWidth;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerY = getPaddingTop()
                    + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
            float radius = CaptionSettingsStyle.dp(getContext(), 3);
            for (int tier = 0; tier < CaptionFontSize.COUNT; tier++) {
                tickPaint.setColor(tier == getProgress() ? CaptionSettingsStyle.primary(getContext())
                        : CaptionSettingsStyle.sliderUnfilled(getContext()));
                canvas.drawCircle(tickCenterX(tier), centerY, radius, tickPaint);
            }
        }
    }

    private int minimum() {
        return 0;
    }

    private int maximum() {
        return KEY_TEXT_SIZE.equals(getKey()) ? CaptionFontSize.COUNT - 1 : 100;
    }

    private int currentValue() {
        DeepSeekConfig.Snapshot current = DeepSeekConfig.displayStyle(getContext());
        return KEY_TEXT_SIZE.equals(getKey())
                ? current.captionSizeTier
                : current.backgroundOpacity;
    }

    private String format(int value) {
        if (!KEY_TEXT_SIZE.equals(getKey())) return value + "%";
        return pixels(CaptionFontSize.detailGlyphHeightPx(value)) + " px";
    }

    private String tierDescription(int tier) {
        return String.format(java.util.Locale.ROOT, CaptionStrings.settings(getContext(), "size_tier_hint"),
                CaptionStrings.settings(getContext(), SIZE_TIER_KEYS[CaptionFontSize.clampTier(tier)]),
                pixels(CaptionFontSize.detailGlyphHeightPx(tier)),
                pixels(CaptionFontSize.fullScreenGlyphHeightPx(tier)));
    }

    private static String pixels(float value) {
        return value == Math.round(value) ? Integer.toString(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private void saveValue(int value) {
        if (KEY_TEXT_SIZE.equals(getKey())) {
            DeepSeekConfig.saveCaptionSizeTier(getContext(), value);
        } else {
            DeepSeekConfig.saveBackgroundOpacity(getContext(), value);
        }
        CaptionOverlay.refreshStyle(getContext());
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
