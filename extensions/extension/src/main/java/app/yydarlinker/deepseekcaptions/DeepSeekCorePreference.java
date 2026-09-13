package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.util.AttributeSet;

/** Opt-in A/B gate for the contextual fixed-unit translation core. */
@SuppressWarnings("deprecation")
public final class DeepSeekCorePreference extends android.preference.SwitchPreference {
    public DeepSeekCorePreference(Context context) {
        super(context);
        initialize();
    }

    public DeepSeekCorePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public DeepSeekCorePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public DeepSeekCorePreference(
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
        boolean enabled = DeepSeekConfig.contextualUnitCoreEnabled(getContext());
        setChecked(enabled);
        updateSummary(enabled);
        setOnPreferenceChangeListener((preference, newValue) -> {
            boolean next = Boolean.TRUE.equals(newValue);
            DeepSeekConfig.saveContextualUnitCoreEnabled(getContext(), next);
            setChecked(next);
            updateSummary(next);
            DynamicCaptionController.refreshConfiguration(getContext());
            return false;
        });
    }

    private void updateSummary(boolean enabled) {
        setSummary(enabled
                ? "单次分句翻译 + 原词时间锚；无独立分句/显示 API 调用"
                : "旧版多阶段核心（可能产生更高费用）；开启使用时间锚核心");
    }
}