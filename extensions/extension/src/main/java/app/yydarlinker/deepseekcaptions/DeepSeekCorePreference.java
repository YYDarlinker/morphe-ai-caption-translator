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
                ? "dev.1 默认核心：固定翻译单元 + 只读上下文；关闭可立即回退 v2.2.0 核心"
                : "已手动回退 v2.2.0 Semantic Ledger；开启可返回 dev.1 实验核心");
    }
}