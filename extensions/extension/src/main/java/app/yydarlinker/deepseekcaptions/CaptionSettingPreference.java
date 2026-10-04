package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Original Preference navigation/actions; metadata and explicitly owned text slots refresh in place. */
@SuppressWarnings("deprecation")
public class CaptionSettingPreference extends android.preference.Preference {
    private final String titleSlot, summarySlot;
    private final List<TextSlot> textSlots = new ArrayList<>();
    private WeakReference<View> currentView = new WeakReference<>(null);
    private static final class TextSlot {
        final WeakReference<TextView> view;
        final Supplier<String> value;
        final boolean hint;
        TextSlot(TextView view, Supplier<String> value, boolean hint) {
            this.view = new WeakReference<>(view); this.value = value; this.hint = hint;
        }
        void refresh() {
            TextView target = view.get();
            if (target != null) { if (hint) target.setHint(value.get()); else target.setText(value.get()); }
        }
    }
    public CaptionSettingPreference(Context c) { this(c, null); }
    public CaptionSettingPreference(Context c, AttributeSet a) { this(c, a, android.R.attr.preferenceStyle); }
    public CaptionSettingPreference(Context c, AttributeSet a, int d) { this(c, a, d, 0); }
    public CaptionSettingPreference(Context c, AttributeSet a, int d, int r) {
        super(c, a, d, r);
        titleSlot = slot(c, a, "title"); summarySlot = slot(c, a, "summary");
    }
    static String slot(Context c, AttributeSet a, String attribute) {
        if (a == null) return null;
        int id = a.getAttributeResourceValue("http://schemas.android.com/apk/res/android", attribute, 0);
        if (id != 0) try {
            String name = c.getResources().getResourceEntryName(id);
            if (name.startsWith("cap_")) return name.substring(4);
        } catch (RuntimeException unavailable) { /* No owned resource slot. */ }
        return null;
    }
    protected final void uiText(TextView view, String key, Object... arguments) {
        uiText(view, () -> arguments.length==0?CaptionStrings.settings(getContext(),key):String.format(Locale.ROOT, CaptionStrings.settings(getContext(), key), arguments));
    }
    protected final void uiText(TextView view, Supplier<String> value) { bind(view, value, false); }
    protected final void uiHint(TextView view, String key) { bind(view, () -> CaptionStrings.settings(getContext(), key), true); }
    private void bind(TextView view, Supplier<String> value, boolean hint) {
        textSlots.removeIf(slot -> slot.view.get() == null || (slot.view.get() == view && slot.hint == hint));
        TextSlot slot = new TextSlot(view, value, hint); textSlots.add(slot); slot.refresh();
        CaptionTextResolver.direction(view, view instanceof android.widget.EditText && !DeepSeekTextPreference.KEY_PROMPT.equals(getKey()));
    }
    protected void refreshDynamicText() {}
    final void refreshCaptionText() {
        if (titleSlot != null) setTitle(CaptionStrings.settings(getContext(), titleSlot));
        if (summarySlot != null) setSummary(CaptionStrings.settings(getContext(), summarySlot));
        refreshDynamicText();
        textSlots.removeIf(slot -> slot.view.get() == null);
        for (TextSlot slot : textSlots) {
            slot.refresh();
            TextView target = slot.view.get();
            if (target != null) CaptionTextResolver.direction(target,
                    target instanceof android.widget.EditText && !DeepSeekTextPreference.KEY_PROMPT.equals(getKey()));
        }
        View row = currentView.get();
        if (row != null) refreshRow(row);
    }
    @Override public View getView(View convert, ViewGroup parent) {
        refreshCaptionText();
        View row = super.getView(convert, parent);
        currentView = new WeakReference<>(row); return row;
    }
    @Override protected void onBindView(View row) { super.onBindView(row); refreshRow(row); }
    private void refreshRow(View row) {
        CaptionTextResolver.direction(row, false);
        for (int id : new int[]{android.R.id.title, android.R.id.summary}) {
            TextView label = row.findViewById(id);
            if (label != null) {
                label.setText(id == android.R.id.title ? getTitle() : getSummary());
                label.setSingleLine(false); label.setMaxLines(Integer.MAX_VALUE); label.setEllipsize(null);
                CaptionTextResolver.direction(label, false);
            }
        }
    }
}
