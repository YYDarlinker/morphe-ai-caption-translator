package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Small theme-derived refinements; native widgets, host typography and accessibility remain in
 * charge. Colours come from the Morphe theme when it is present, else from the platform theme.
 */
final class CaptionSettingsStyle {
    private static final String THEME = "app.morphe.extension.shared.theme.ThemeUtils";
    private static final String DIALOG = "app.morphe.extension.shared.ui.CustomDialog";

    private CaptionSettingsStyle() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    // ---- Colours ----

    static int color(Context c, int attr, int fallback) {
        TypedArray a = c.obtainStyledAttributes(new int[] {attr});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    private static int themeColor(String getter, Context c, int attr, int fallback) {
        try {
            return (Integer) Class.forName(THEME).getMethod(getter).invoke(null);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return color(c, attr, fallback);
        }
    }

    static int primary(Context c) {
        return themeColor(
                "getAppForegroundColor", c, android.R.attr.textColorPrimary, Color.DKGRAY);
    }

    static int secondary(Context c) {
        return color(c, android.R.attr.textColorSecondary, primary(c));
    }

    static int surfaceColor(Context c) {
        return themeColor(
                "getDialogBackgroundColor", c, android.R.attr.colorBackground, Color.WHITE);
    }

    /** [color] with its alpha channel replaced. */
    static int tint(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    static int sliderUnfilled(Context c) {
        return tint(primary(c), 64);
    }

    // ---- Text and rows ----

    static void row(View view) {
        Context c = view.getContext();
        view.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        CaptionTextResolver.direction(view, false);
    }

    static void title(TextView view) {
        view.setTextSize(16);
        view.setTextColor(primary(view.getContext()));
        view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        view.setAlpha(1f);
        CaptionTextResolver.direction(view, false);
    }

    static void caption(TextView view) {
        view.setTextSize(13);
        view.setTextColor(secondary(view.getContext()));
        view.setAlpha(1f);
        view.setLineSpacing(dp(view.getContext(), 2), 1f);
        CaptionTextResolver.direction(view, false);
    }

    static void editor(EditText view) {
        Context c = view.getContext();
        view.setTextSize(16);
        view.setTextColor(primary(c));
        view.setHintTextColor(secondary(c));
        CaptionTextResolver.direction(view, true);
        view.setMinHeight(dp(c, 48));
        view.setMinimumHeight(dp(c, 48));
        view.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, surface(c, true));
        background.addState(new int[] {}, surface(c, false));
        view.setBackground(background);
    }

    static GradientDrawable surface(Context c, boolean focused) {
        int text = primary(c);
        GradientDrawable background = new GradientDrawable();
        background.setColor(tint(text, focused ? 12 : 7));
        background.setCornerRadius(dp(c, 8));
        background.setStroke(
                dp(c, 1), focused ? color(c, android.R.attr.colorAccent, text) : tint(text, 28));
        return background;
    }

    static GradientDrawable menuSurface(Context c) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surfaceColor(c));
        shape.setCornerRadius(dp(c, 12));
        return shape;
    }

    // ---- Controls ----

    /**
     * Shared rail geometry and colours of both settings sliders. The platform keeps thumbOffset ==
     * padding == half the thumb, so the two extreme thumb centres sit at paddingLeft and width -
     * paddingRight. Insetting the padding by half a thumb on each side puts the two end thumb
     * centres exactly on the rail endpoints that {@link DeepSeekSliderPreference.RailBar} draws,
     * which the stock progress drawable cannot do: its own bounds are shifted one further thumb
     * offset and its visible rail is inset by an opaque vendor amount.
     */
    static void slider(SeekBar view) {
        Context c = view.getContext();
        android.graphics.drawable.Drawable thumb = view.getThumb();
        int halfThumb = thumb == null ? 0 : thumb.getIntrinsicWidth() / 2;
        view.setPadding(halfThumb, view.getPaddingTop(), halfThumb, view.getPaddingBottom());
        view.setProgressTintList(ColorStateList.valueOf(primary(c)));
        view.setProgressBackgroundTintList(ColorStateList.valueOf(sliderUnfilled(c)));
        view.setThumbTintList(ColorStateList.valueOf(primary(c)));
    }

    static void button(Button view) {
        Context c = view.getContext();
        view.setAllCaps(false);
        view.setTextSize(14);
        view.setMinHeight(dp(c, 48));
        view.setMinimumHeight(dp(c, 48));
        view.setMinimumWidth(dp(c, 64));
        view.setMinWidth(dp(c, 64));
        view.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        CaptionTextResolver.direction(view, false);
    }

    /**
     * A dialog-style button: Morphe's own when installed, else a ripple pill in the theme colour.
     */
    static Button action(
            Context c, String label, boolean primaryAction, boolean destructive, Runnable click) {
        Button button = morpheButton(c, label, primaryAction, click);
        if (button == null) button = pillButton(c, label, primaryAction, click);
        button(button);
        button.setMaxLines(3);
        if (destructive) button.setTextColor(ProfileActionStrip.danger(c));
        return button;
    }

    private static Button morpheButton(
            Context c, String label, boolean primaryAction, Runnable click) {
        try {
            return (Button)
                    Class.forName(DIALOG)
                            .getMethod(
                                    "createButton",
                                    Context.class,
                                    android.app.Dialog.class,
                                    CharSequence.class,
                                    Runnable.class,
                                    boolean.class,
                                    boolean.class)
                            .invoke(null, c, null, label, click, primaryAction, false);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError unavailable) {
            return null;
        }
    }

    private static Button pillButton(
            Context c, String label, boolean primaryAction, Runnable click) {
        Button button = new Button(c, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        int foreground = primary(c);
        boolean light =
                Color.red(foreground) + Color.green(foreground) + Color.blue(foreground) > 420;
        button.setTextColor(primaryAction ? (light ? Color.BLACK : Color.WHITE) : foreground);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(c, 20));
        shape.setColor(primaryAction ? foreground : tint(foreground, 14));
        button.setBackground(
                new RippleDrawable(ColorStateList.valueOf(tint(foreground, 28)), shape, null));
        button.setOnClickListener(v -> click.run());
        return button;
    }
}
