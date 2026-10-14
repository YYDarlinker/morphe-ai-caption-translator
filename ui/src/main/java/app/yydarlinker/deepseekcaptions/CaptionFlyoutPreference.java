package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.util.AttributeSet;

/** Independent menu visibility; never changes the caption engine, language or native CC state. */
public class CaptionFlyoutPreference extends AddonSwitchPreference {
    public CaptionFlyoutPreference(Context c) {
        super(c);
        initialize();
    }

    public CaptionFlyoutPreference(Context c, AttributeSet a) {
        super(c, a);
        initialize();
    }

    protected boolean saved() {
        return DeepSeekConfig.flyoutMenuEnabled(getContext());
    }

    protected void save(boolean visible) {
        DeepSeekConfig.saveFlyoutMenuEnabled(getContext(), visible);
    }

    /**
     * Catalog key for this row's title; a subclass overrides it to bind the Shorts menu instead.
     */
    protected String titleKey() {
        return "flyout_title";
    }

    /** Catalog key for this row's summary. */
    protected String summaryKey() {
        return "flyout_summary";
    }

    private void initialize() {
        setPersistent(false);
        // Resolved through the settings catalog, so the row follows the interface language rather
        // than
        // the platform locale and never needs a substring pass over an assembled sentence.
        setTitle(CaptionStrings.get(getContext(), titleKey()));
        setSummary(CaptionStrings.get(getContext(), summaryKey()));
        setChecked(saved());
        setOnPreferenceChangeListener(
                (p, value) -> {
                    boolean visible = Boolean.TRUE.equals(value);
                    save(visible);
                    setChecked(visible);
                    return false;
                });
    }

    @Override
    protected void onBindView(android.view.View view) {
        boolean visible = saved();
        if (isChecked() != visible) setChecked(visible);
        super.onBindView(view);
    }

    @Override
    protected void refreshDynamicText() {
        setTitle(CaptionStrings.get(getContext(), titleKey()));
        setSummary(CaptionStrings.get(getContext(), summaryKey()));
    }
}
