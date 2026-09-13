package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.util.AttributeSet;

/** Opt-in source/canonical text fields for display-selection diagnostics. */
@SuppressWarnings("deprecation")
public final class DeepSeekDisplayTextDebugPreference extends android.preference.SwitchPreference {
    public DeepSeekDisplayTextDebugPreference(Context context) {
        super(context);
        initialize();
    }

    public DeepSeekDisplayTextDebugPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public DeepSeekDisplayTextDebugPreference(
            Context context,
            AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public DeepSeekDisplayTextDebugPreference(
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
        boolean enabled = DeepSeekConfig.displayTextDebugEnabled(getContext());
        setChecked(enabled);
        updateSummary(enabled);
        setOnPreferenceChangeListener((preference, newValue) -> {
            boolean next = Boolean.TRUE.equals(newValue);
            DeepSeekConfig.saveDisplayTextDebugEnabled(getContext(), next);
            setChecked(next);
            updateSummary(next);
            return false;
        });
    }

    private void updateSummary(boolean enabled) {
        setSummary(enabled
                ? "DISPLAY_SELECTED 附带 source 与 canonical，仅用于人工排查"
                : "默认关闭；日志不记录字幕原文与规范译文");
    }
}