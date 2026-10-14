package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.BidiFormatter;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

/** Inline slider that persists on finger release; no additional dialog or Save button is used. */
@SuppressWarnings("deprecation")
public final class DeepSeekSliderPreference extends CaptionSettingPreference {
    static final String KEY_TEXT_SIZE = "deepseek_caption_text_size";
    static final String KEY_OPACITY = "deepseek_caption_background_opacity";
    private static final String[] SIZE_TIER_KEYS = {
        "size_tier_xs", "size_tier_s", "size_tier_standard", "size_tier_l", "size_tier_xl"
    };

    /**
     * A tier name may wrap onto a second line at a word boundary when one translated name cannot
     * fit the width its neighbour allows. It is never ellipsized, never shrunk below the shared
     * caption size and never clipped: the row grows taller instead.
     */
    private static final int MAX_TIER_LABEL_LINES = 2;

    /** Upper bound for one label box, so a long translation wraps instead of starving the rest. */
    private static final int MAX_TIER_LABEL_PX = 84;

    /** Reference settings-row width and padding, used only to resolve the rail inset. */
    private static final int ROW_REFERENCE_DP = 380, ROW_PADDING_DP = 20;

    private String measuredLabelKey = "";
    private int[] measuredLabelWidths;
    private WeakReference<RailBar> ownRail = new WeakReference<>(null);
    private WeakReference<LinearLayout> ownNames = new WeakReference<>(null);

    public DeepSeekSliderPreference(Context context) {
        this(context, null);
    }

    public DeepSeekSliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setSelectable(false);
    }

    private boolean sizeSlider() {
        return KEY_TEXT_SIZE.equals(getKey());
    }

    @Override
    public View getView(View convertView, ViewGroup parent) {
        // Text-size and opacity share this class but bind to different ranges and values.
        String key = getKey();
        boolean reusable = convertView != null && key != null && key.equals(convertView.getTag());
        return super.getView(reusable ? convertView : null, parent);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        boolean sizeSlider = sizeSlider();
        LinearLayout root = new LinearLayout(context);
        root.setTag(getKey());
        root.setOrientation(LinearLayout.VERTICAL);
        CaptionSettingsStyle.row(root);

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(heading, wide());
        TextView title = new TextView(context);
        uiText(title, () -> String.valueOf(getTitle()));
        CaptionSettingsStyle.title(title);
        title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        heading.addView(
                title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // The size slider states both pixel sizes in the tier line below, so only the opacity
        // slider carries a live value in its heading.
        TextView valueLabel = sizeSlider ? null : headerValue(context, heading);

        RailBar slider =
                sizeSlider ? new SizeTierSeekBar(this, context) : new OpacitySeekBar(this, context);
        ownRail = new WeakReference<>(slider);
        CaptionSettingsStyle.slider(slider);
        CaptionTextResolver.direction(slider, false); // The physical fraction mapping handles RTL.
        slider.setTag(sizeSlider ? "ai_size_tier_slider" : "ai_opacity_slider");
        slider.setMinimumHeight(CaptionSettingsStyle.dp(context, 48));
        slider.setContentDescription(getTitle());
        int current = currentValue();
        slider.setMax(maximum());
        slider.setProgress(current);
        if (valueLabel != null) valueLabel.setText(format(current));
        root.addView(slider, wide());

        LinearLayout tierNames = sizeSlider ? tierNames((SizeTierSeekBar) slider) : null;
        ownNames = new WeakReference<>(tierNames);
        if (tierNames != null) {
            root.addView(tierNames, wide());
            updateTierNames(slider, tierNames, current);
        }

        TextView summary = new TextView(context);
        CharSequence summaryText = sizeSlider ? tierDescription(current) : getSummary();
        if (summaryText != null && summaryText.length() > 0) {
            uiText(
                    summary,
                    () ->
                            sizeSlider()
                                    ? tierDescription(slider.getProgress())
                                    : String.valueOf(getSummary()));
            CaptionSettingsStyle.caption(summary);
            root.addView(summary, wide());
        }

        slider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (valueLabel != null) valueLabel.setText(format(progress));
                        if (sizeSlider) {
                            summary.setText(tierDescription(progress));
                            updateTierNames(slider, tierNames, progress);
                            slider.invalidate();
                        }
                        if (!fromUser) return;
                        SubtitleStylePreview.update(getKey(), progress);
                        if (!slider.trackingTouch) saveValue(progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        slider.trackingTouch = true;
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        slider.trackingTouch = false;
                        saveValue(seekBar.getProgress());
                    }
                });
        return root;
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView headerValue(Context context, LinearLayout heading) {
        TextView valueLabel = new TextView(context);
        CaptionSettingsStyle.caption(valueLabel);
        valueLabel.setTextSize(14);
        valueLabel.setTextColor(CaptionSettingsStyle.primary(context));
        valueLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        valueLabel.setPaddingRelative(CaptionSettingsStyle.dp(context, 12), 0, 0, 0);
        heading.addView(
                valueLabel,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return valueLabel;
    }

    // ---- The tier names ----

    /**
     * The tier label row. Every label is centred on its own tick, which is only possible if the two
     * outer labels have somewhere to go: both sliders are therefore inset from their padded edges
     * by exactly the room the end labels need, so a centred end label still lands inside the row
     * and nothing has to be clipped, ellipsized or nudged off its tick. The inset is the same for
     * both sliders, so the two rails stay the same length with the same margins.
     */
    private LinearLayout tierNames(SizeTierSeekBar sizeBar) {
        LinearLayout names =
                new LinearLayout(getContext()) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = MeasureSpec.getSize(widthSpec);
                        int[] widths = tierLabelWidths();
                        int span = width - sizeBar.baseLeft - sizeBar.baseRight;
                        int inset = tierLabelInset(widths, span);
                        float step = Math.max(1, (span - 2f * inset) / 4f);
                        int tallest = 0;
                        for (int i = 0; i < getChildCount(); i++) {
                            View child = getChildAt(i);
                            int cap = Math.max(1, Math.min(widths[i], (int) step));
                            child.measure(
                                    MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY),
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                            tallest = Math.max(tallest, child.getMeasuredHeight());
                        }
                        setMeasuredDimension(
                                resolveSize(width, widthSpec), resolveSize(tallest, heightSpec));
                    }

                    @Override
                    protected void onLayout(
                            boolean changed, int left, int top, int right, int bottom) {
                        // Logical indices are stable; only the physical placement mirrors.
                        for (int i = 0; i < getChildCount(); i++) {
                            View label = getChildAt(i);
                            int x =
                                    Math.round(
                                            sizeBar.thumbCenterX(i / 4f)
                                                    - label.getMeasuredWidth() / 2f);
                            label.layout(
                                    x, 0, x + label.getMeasuredWidth(), label.getMeasuredHeight());
                        }
                    }
                };
        names.setTag("ai_size_tier_names");
        names.setOrientation(LinearLayout.HORIZONTAL);
        names.setClipChildren(false);
        names.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (String key : SIZE_TIER_KEYS) {
            TextView name = tierLabel(key);
            uiText(name, key);
            names.addView(
                    name,
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return names;
    }

    /**
     * One tier label. The selected tier is drawn bold and bold is wider, so every measurement that
     * decides room for a label — the row's inset and the label's own cap — uses the bold face.
     */
    private TextView tierLabel(String key) {
        TextView name = new TextView(getContext());
        name.setText(CaptionStrings.get(getContext(), key));
        CaptionSettingsStyle.caption(name);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(false);
        name.setMaxLines(MAX_TIER_LABEL_LINES);
        name.setEllipsize(null);
        name.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
        name.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxWidth(tierLabelCeilingPx());
        return name;
    }

    /** Natural one-line width of every tier label with the face the row draws it in. */
    int[] tierLabelWidths() {
        Context context = getContext();
        Configuration configuration = context.getResources().getConfiguration();
        String key =
                CaptionTextResolver.locale(context).toLanguageTag()
                        + ":"
                        + configuration.fontScale
                        + ":"
                        + context.getResources().getDisplayMetrics().densityDpi;
        if (key.equals(measuredLabelKey) && measuredLabelWidths != null)
            return measuredLabelWidths.clone();
        int[] widths = new int[SIZE_TIER_KEYS.length];
        for (int tier = 0; tier < widths.length; tier++) {
            TextView name = tierLabel(SIZE_TIER_KEYS[tier]);
            name.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            widths[tier] = name.getMeasuredWidth();
        }
        measuredLabelKey = key;
        measuredLabelWidths = widths;
        return widths;
    }

    /** The label-box ceiling in pixels, for the geometry fixtures. */
    int tierLabelCeilingPx() {
        return CaptionSettingsStyle.dp(getContext(), MAX_TIER_LABEL_PX);
    }

    /** The reference content width of one settings row, for the geometry fixtures. */
    int referenceSpanPx() {
        return CaptionSettingsStyle.dp(getContext(), ROW_REFERENCE_DP - 2 * ROW_PADDING_DP);
    }

    /**
     * The inset both rails in the row carry, and with it the whole labelled rail.
     *
     * <p>With inset {@code i} the five tick centres are {@code i + k*(S-2i)/4}, so a label of
     * natural width {@code w_j} centred on its tick spans {@code (S-2i)/4} of its own and needs:
     *
     * <ul>
     *   <li>{@code w_0/2 <= i} and {@code w_4/2 <= i} — the two end labels stay inside the row;
     *   <li>{@code w_j + w_j+1 <= (S-2i)/2} — no two neighbours touch, the same gap being shared by
     *       the two half-widths that meet in it.
     * </ul>
     *
     * Rearranging the last one gives {@code i >= (2*(w_j + w_j+1) - S)/4}. Taking the smallest
     * inset that satisfies all six inequalities keeps every label on its tick whenever the row can
     * hold the names at all, and keeps the rail as long as possible; the two-fifths cap leaves a
     * sliver of rail even in a hopeless case, and the row falls back to confining over-wide names
     * to the gap between two ticks.
     */
    static int tierLabelInset(int[] widths, int span) {
        if (widths == null || widths.length == 0 || span <= 0) return 0;
        int count = widths.length;
        int required = (widths[0] + 1) / 2;
        if (count > 1) required = Math.max(required, (widths[count - 1] + 1) / 2);
        for (int j = 0; j + 1 < count; j++) {
            int pair = Math.max(0, widths[j]) + Math.max(0, widths[j + 1]);
            required = Math.max(required, (2 * pair - span + 3) / 4);
        }
        return Math.max(0, Math.min(span * 2 / 5, required));
    }

    private void updateTierNames(SeekBar slider, LinearLayout names, int tier) {
        int selected = CaptionFontSize.clampTier(tier);
        String description =
                getTitle() + ": " + CaptionStrings.get(getContext(), SIZE_TIER_KEYS[selected]);
        slider.setContentDescription(description);
        names.setContentDescription(description);
        for (int i = 0; i < names.getChildCount(); i++) {
            TextView name = (TextView) names.getChildAt(i);
            name.setTextColor(
                    i == selected
                            ? CaptionSettingsStyle.primary(getContext())
                            : CaptionSettingsStyle.secondary(getContext()));
            name.setTypeface(
                    Typeface.create(
                            Typeface.DEFAULT, i == selected ? Typeface.BOLD : Typeface.NORMAL));
        }
        names.invalidate();
    }

    @Override
    protected void refreshDynamicText() {
        RailBar rail = ownRail.get();
        if (rail == null) return;
        rail.refreshTextGeometry();
        rail.setContentDescription(getTitle());
        LinearLayout names = ownNames.get();
        if (names != null) {
            updateTierNames(rail, names, rail.getProgress());
            names.requestLayout();
        }
    }

    // ---- The rail ----

    /**
     * One geometry for the whole slider. The rail runs over the inclusive padded frame, narrowed at
     * both ends by the row's label inset — first endpoint at {@code paddingLeft + inset}, last at
     * {@code width - paddingRight - inset} — the ticks and the extreme thumb centres sit on exactly
     * those two coordinates, and every tier divides that same travel evenly.
     *
     * <p>{@code AbsSeekBar} cannot supply this: its stock rail is the only source of the intrinsic
     * measured height but is additionally inset by a vendor amount (about 6px on API 28) that does
     * not scale with the layout width, and its own thumb stamping follows a private offset. This
     * class therefore keeps {@code AbsSeekBar}'s dragging and progress handling untouched and takes
     * over only painting: the rail and ticks are drawn here and the real native thumb drawable is
     * stamped from the same geometry.
     */
    abstract static class RailBar extends SeekBar {
        /** Rasterization hook: suppresses only the two rail bands so ticks can be sampled. */
        static boolean RAIL_BANDS_HIDDEN = false;

        /** The preference this rail belongs to; it owns the labels the inset is derived from. */
        private final DeepSeekSliderPreference preference;

        /** The row inset for the width this rail currently has; zero until it has been laid out. */
        private int inset;

        int baseLeft, baseRight;
        private boolean applyingPadding;
        boolean trackingTouch;
        private final Paint railPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RailBar(DeepSeekSliderPreference preference, Context context) {
            super(context);
            this.preference = preference;
            baseLeft = getPaddingLeft();
            baseRight = getPaddingRight();
            CaptionTextResolver.direction(this, false);
            // The rail is drawn by this view. The stock drawable is the only source of AbsSeekBar's
            // intrinsic measured height, so onMeasure below replaces that contribution explicitly.
            setProgressDrawable(null);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && rtl()) {
                // API 35 AbsSeekBar computes the grab offset with the LTR fraction even in RTL.
                // Suppress only that native calculation; drag, cancel, keys and a11y stay native.
                int offset = getThumbOffset();
                setThumbOffset(0);
                try {
                    return super.onTouchEvent(event);
                } finally {
                    setThumbOffset(offset);
                }
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            resolveTravel(width);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            Drawable thumb = getThumb();
            int intrinsic =
                    Math.max(
                            getSuggestedMinimumHeight(),
                            thumb == null ? 0 : thumb.getIntrinsicHeight());
            int height = Math.max(intrinsic, CaptionSettingsStyle.dp(getContext(), 24));
            setMeasuredDimension(
                    resolveSize(MeasureSpec.getSize(widthSpec), widthSpec),
                    resolveSize(height, heightSpec));
        }

        private void resolveTravel(int width) {
            int span = width - baseLeft - baseRight;
            inset =
                    preference == null || span <= 0
                            ? 0
                            : tierLabelInset(preference.tierLabelWidths(), span);
            int left = baseLeft + inset, right = baseRight + inset;
            if (getPaddingLeft() != left || getPaddingRight() != right) {
                applyingPadding = true;
                super.setPadding(left, getPaddingTop(), right, getPaddingBottom());
                applyingPadding = false;
            }
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            if (!applyingPadding) {
                baseLeft = left;
                baseRight = right;
            }
            super.setPadding(left, top, right, bottom);
            if (preference != null) resolveTravel(getWidth());
        }

        @Override
        public void onRtlPropertiesChanged(int direction) {
            super.onRtlPropertiesChanged(direction);
            // Android may call this while resolving padding: never read or set padding here.
            if (preference != null) {
                requestLayout();
                invalidate();
            }
        }

        void refreshTextGeometry() {
            CaptionTextResolver.direction(this, false);
            resolveTravel(getWidth());
            requestLayout();
            invalidate();
        }

        final int centerY() {
            return getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2;
        }

        /** Inclusive visible rail: first endpoint at paddingLeft plus the row's label inset. */
        final float railStart() {
            return getPaddingLeft();
        }

        final float railEnd() {
            return getWidth() - getPaddingRight();
        }

        /**
         * The row inset this rail resolved for its current width, for the geometry fixtures. Both
         * sliders in the row take the same width from the same parent and read the same labels, so
         * they resolve the same number and the two rails stay the same length.
         */
        final int railInsetPx() {
            return inset;
        }

        /** The widths of the five tier labels this rail's preference renders. */
        final int[] labelWidths() {
            return preference == null ? new int[0] : preference.tierLabelWidths();
        }

        /** Bold width of the widest tier label, for the geometry fixtures. */
        final int largestTierLabelPx() {
            int largest = 0;
            for (int width : labelWidths()) largest = Math.max(largest, width);
            return largest;
        }

        final int tierLabelCeilingPx() {
            return preference == null ? 0 : preference.tierLabelCeilingPx();
        }

        final int referenceSpanPx() {
            return preference == null ? 0 : preference.referenceSpanPx();
        }

        /** Thumb centre travel: paddingLeft to width - paddingRight, divided evenly by progress. */
        final float thumbCenterX(float logicalFraction) {
            return physicalCenterX(physicalFraction(logicalFraction));
        }

        private float progressFraction() {
            return getProgress() / (float) Math.max(1, getMax());
        }

        /**
         * The single logical-to-physical mapping for the whole rail. Every mirroring decision lives
         * here, exactly once: in RTL the logical fraction is measured from the opposite edge, so
         * the physical fraction is {@code 1 - logical}. Thumb placement, tick placement, the filled
         * rail band and the tier label row all consume this one value, which is what keeps a drag
         * and its on-screen position in agreement instead of applying the mirror twice.
         */
        final boolean rtl() {
            return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        }

        final float physicalFraction(float logicalFraction) {
            return rtl() ? 1f - logicalFraction : logicalFraction;
        }

        final float physicalFraction() {
            return physicalFraction(progressFraction());
        }

        /** Physical X of a physical fraction; the extreme thumb centre for both rails. */
        final float physicalCenterX(float physicalFraction) {
            return railStart() + physicalFraction * (railEnd() - railStart());
        }

        /** Extra decoration hook for the size tier ticks; drawn between the rail and the thumb. */
        void drawOverRail(Canvas canvas) {}

        /**
         * AbsSeekBar is not allowed to draw here: its own rail is inset by a vendor amount and its
         * thumb is stamped at a platform position this class does not share. Rail, ticks and the
         * native thumb drawable are all positioned from one geometry instead, so the visible rail
         * endpoints, the first and last tick centres and the two end thumb centres are the same two
         * coordinates.
         */
        @Override
        public void draw(Canvas canvas) {
            float radius = Math.max(1, CaptionSettingsStyle.dp(getContext(), 2)) / 2f;
            float centerY = centerY();
            float top = centerY - radius;
            float bottom = centerY + radius;
            float start = railStart();
            float end = railEnd();
            railPaint.setStyle(Paint.Style.FILL);
            railPaint.setAlpha(255);
            railPaint.setColor(CaptionSettingsStyle.sliderUnfilled(getContext()));
            if (!RAIL_BANDS_HIDDEN && end > start)
                canvas.drawRoundRect(start, top, end, bottom, radius, radius, railPaint);
            if (!RAIL_BANDS_HIDDEN && progressFraction() > 0f && end > start) {
                railPaint.setColor(CaptionSettingsStyle.primary(getContext()));
                float filled = (end - start) * progressFraction();
                boolean rtl = rtl();
                canvas.save();
                canvas.clipRect(
                        rtl ? end - filled : start, top, rtl ? end : start + filled, bottom);
                canvas.drawRoundRect(start, top, end, bottom, radius, radius, railPaint);
                canvas.restore();
            }
            drawOverRail(canvas);
            Drawable thumb = getThumb();
            if (thumb == null) return;
            // One mirrored fraction drives the filled band and the thumb; they never disagree.
            int cx = Math.round(physicalCenterX(physicalFraction()));
            int halfWidth = thumb.getIntrinsicWidth() / 2;
            int halfHeight = thumb.getIntrinsicHeight() / 2;
            thumb.setBounds(
                    cx - halfWidth,
                    Math.round(centerY) - halfHeight,
                    cx + halfWidth,
                    Math.round(centerY) + halfHeight);
            thumb.setState(getDrawableState());
            thumb.draw(canvas);
        }
    }

    /** Background opacity rail: identical geometry to the size rail, no tick marks. */
    static final class OpacitySeekBar extends RailBar {
        OpacitySeekBar(DeepSeekSliderPreference preference, Context context) {
            super(preference, context);
        }
    }

    /** Five thumb positions, with visible ticks even when the theme omits tick marks. */
    static final class SizeTierSeekBar extends RailBar {
        private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SizeTierSeekBar(DeepSeekSliderPreference preference, Context context) {
            super(preference, context);
            setTickMark(null);
        }

        /** Tick for a tier sits on the thumb centre that tier produces. */
        float tickCenterX(int tier) {
            return thumbCenterX(
                    CaptionFontSize.clampTier(tier) / (float) (CaptionFontSize.COUNT - 1));
        }

        @Override
        void drawOverRail(Canvas canvas) {
            float centerY = centerY();
            float radius = CaptionSettingsStyle.dp(getContext(), 3);
            for (int tier = 0; tier < CaptionFontSize.COUNT; tier++) {
                tickPaint.setColor(
                        tier == getProgress()
                                ? CaptionSettingsStyle.primary(getContext())
                                : CaptionSettingsStyle.sliderUnfilled(getContext()));
                canvas.drawCircle(tickCenterX(tier), centerY, radius, tickPaint);
            }
        }
    }

    // ---- Values ----

    private int maximum() {
        return sizeSlider() ? CaptionFontSize.COUNT - 1 : 100;
    }

    private int currentValue() {
        DeepSeekConfig.Appearance current = DeepSeekConfig.displayStyle(getContext());
        return sizeSlider() ? current.captionSizeTier : current.backgroundOpacity;
    }

    private String format(int value) {
        return sizeSlider()
                ? pixels(CaptionFontSize.detailGlyphHeightPx(value)) + " px"
                : BidiFormatter.getInstance(CaptionTextResolver.locale(getContext()))
                        .unicodeWrap(value + "%");
    }

    private String tierDescription(int tier) {
        return String.format(
                Locale.ROOT,
                CaptionStrings.get(getContext(), "size_tier_hint"),
                CaptionStrings.get(getContext(), SIZE_TIER_KEYS[CaptionFontSize.clampTier(tier)]),
                pixels(CaptionFontSize.detailGlyphHeightPx(tier)),
                pixels(CaptionFontSize.fullScreenGlyphHeightPx(tier)));
    }

    private static String pixels(float value) {
        return value == Math.round(value)
                ? Integer.toString(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private void saveValue(int value) {
        if (sizeSlider()) DeepSeekConfig.saveCaptionSizeTier(getContext(), value);
        else DeepSeekConfig.saveBackgroundOpacity(getContext(), value);
        CaptionOverlay.refreshStyle(getContext());
    }
}
