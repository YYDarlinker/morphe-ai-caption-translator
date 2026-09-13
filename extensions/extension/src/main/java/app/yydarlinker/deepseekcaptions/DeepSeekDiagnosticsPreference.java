package app.yydarlinker.deepseekcaptions;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

/** Inline diagnostic readout; tapping copies the complete trace instead of a settings breadcrumb. */
@SuppressWarnings("deprecation")
public final class DeepSeekDiagnosticsPreference extends android.preference.Preference {
    public DeepSeekDiagnosticsPreference(Context context) {
        super(context);
        initialize();
    }

    public DeepSeekDiagnosticsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public DeepSeekDiagnosticsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public DeepSeekDiagnosticsPreference(
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
        refresh();
        setOnPreferenceClickListener(preference -> {
            String diagnostic = CaptionDiagnostics.uiText(getContext());
            setSummary(diagnostic);
            notifyChanged();
            ClipboardManager clipboard = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("AI 字幕链路诊断", diagnostic));
                Toast.makeText(getContext(), "完整诊断已复制", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "无法访问系统剪贴板", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    private void refresh() {
        setSummary(CaptionDiagnostics.uiText(getContext()));
        notifyChanged();
    }
}
