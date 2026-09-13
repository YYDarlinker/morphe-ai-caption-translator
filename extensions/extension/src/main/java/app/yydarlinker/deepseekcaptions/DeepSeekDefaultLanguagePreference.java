package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Inline selector for the AI language chosen when the player captions button is enabled. */
@SuppressWarnings("deprecation")
public final class DeepSeekDefaultLanguagePreference extends android.preference.Preference {
    private static final String VIEW_TAG = "deepseek_caption_default_language_view";
    private static final String[] CODES = {
            "", "zh-Hans", "zh-Hant", "en", "ja", "ko", "es", "fr", "de", "ru",
            "pt", "it", "ar", "hi", "tr", "vi", "id", "th", "pl", "nl", "sv",
            "no", "da", "fi", "el", "cs", "hu", "ro", "bg", "uk", "he", "fa",
            "bn", "ta", "te", "ur", "ms", "fil", "sw", "hr", "sr", "sk", "sl",
            "et", "lv", "lt", "ca", "af"
    };

    private boolean populating;

    public DeepSeekDefaultLanguagePreference(Context context) {
        super(context);
        initialize();
    }

    public DeepSeekDefaultLanguagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public DeepSeekDefaultLanguagePreference(
            Context context,
            AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public DeepSeekDefaultLanguagePreference(
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
        View safe = convertView != null && VIEW_TAG.equals(convertView.getTag())
                ? convertView
                : null;
        return super.getView(safe, parent);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout root = new LinearLayout(context);
        root.setTag(VIEW_TAG);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(9), dp(16), dp(8));

        TextView title = new TextView(context);
        title.setText(getTitle());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(title, matchWrap());

        Spinner spinner = new Spinner(context);
        List<String> labels = new ArrayList<>(CODES.length);
        labels.add("跟随 YouTube，不自动改选");
        for (int i = 1; i < CODES.length; i++) {
            TargetLanguage language = TargetLanguage.fromCode(CODES[i]);
            labels.add(language.displayName + "（" + language.code + "）");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String selected = DeepSeekConfig.defaultTargetLanguage(context);
        int selection = indexOf(selected);
        populating = true;
        spinner.setSelection(selection, false);
        populating = false;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View view, int position, long id) {
                if (populating || position < 0 || position >= CODES.length) return;
                String next = CODES[position];
                if (next.equals(DeepSeekConfig.defaultTargetLanguage(context))) return;
                DeepSeekConfig.saveDefaultTargetLanguage(context, next);
                CaptionButtonController.onDefaultLanguageChanged();
            }

            @Override public void onNothingSelected(AdapterView<?> parentView) {}
        });
        root.addView(spinner, matchWrap());

        TextView summary = new TextView(context);
        summary.setText("点按播放器字幕按钮时默认启用；从字幕菜单手动选择的语言仍优先");
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        summary.setAlpha(0.72f);
        root.addView(summary, matchWrap());
        return root;
    }

    private static int indexOf(String code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equalsIgnoreCase(code)) return i;
        }
        return 0;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
