package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.text.NoCopySpan;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Original Preference navigation and actions. The title, the summary and the text slots that the
 * row binds are refreshed in place when the interface language changes.
 */
@SuppressWarnings("deprecation")
public class CaptionSettingPreference extends android.preference.Preference {
    private final String titleSlot, summarySlot;
    private final List<TextSlot> textSlots = new ArrayList<>();
    private boolean binding;
    private WeakReference<View> currentView = new WeakReference<>(null);

    public CaptionSettingPreference(Context c) {
        this(c, null);
    }

    public CaptionSettingPreference(Context c, AttributeSet a) {
        super(c, a, android.R.attr.preferenceStyle);
        titleSlot = slot(c, a, "title");
        summarySlot = slot(c, a, "summary");
    }

    /** A text a view shows, or the hint of an editor, that follows the interface language. */
    private static final class TextSlot {
        final WeakReference<TextView> view;
        final Supplier<String> value;
        final boolean hint;

        TextSlot(TextView view, Supplier<String> value, boolean hint) {
            this.view = new WeakReference<>(view);
            this.value = value;
            this.hint = hint;
        }

        void refresh() {
            TextView target = view.get();
            if (target == null) return;
            CharSequence next = value.get();
            if (sameText(hint ? target.getHint() : target.getText(), next)) return;
            if (hint) target.setHint(next);
            else target.setText(next);
        }
    }

    /**
     * The name of the "cap_*" string resource that [attribute] of the XML node points to, else
     * null.
     */
    static String slot(Context c, AttributeSet a, String attribute) {
        if (a == null) return null;
        int id =
                a.getAttributeResourceValue(
                        "http://schemas.android.com/apk/res/android", attribute, 0);
        if (id == 0) return null;
        try {
            String name = c.getResources().getResourceEntryName(id);
            return name.startsWith("cap_") ? name.substring(4) : null;
        } catch (RuntimeException unavailable) {
            return null; // Not a resource of this add-on.
        }
    }

    /** Text equality that also sees real span and style changes, not just equal characters. */
    static boolean sameText(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (!TextUtils.equals(a, b)) return false;
        List<Object> left = styleSpans(a), right = styleSpans(b);
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            Object x = left.get(i), y = right.get(i);
            Spanned sx = (Spanned) a, sy = (Spanned) b;
            if (!x.equals(y)
                    || sx.getSpanStart(x) != sy.getSpanStart(y)
                    || sx.getSpanEnd(x) != sy.getSpanEnd(y)
                    || sx.getSpanFlags(x) != sy.getSpanFlags(y)) return false;
        }
        return true;
    }

    private static List<Object> styleSpans(CharSequence value) {
        List<Object> result = new ArrayList<>();
        if (value instanceof Spanned)
            for (Object span : ((Spanned) value).getSpans(0, value.length(), Object.class))
                if (!(span instanceof NoCopySpan)) result.add(span);
        return result;
    }

    // ---- Text that follows the language ----

    protected final void uiText(TextView view, String key, Object... arguments) {
        uiText(
                view,
                () ->
                        arguments.length == 0
                                ? CaptionStrings.get(getContext(), key)
                                : String.format(
                                        Locale.ROOT,
                                        CaptionStrings.get(getContext(), key),
                                        arguments));
    }

    protected final void uiText(TextView view, Supplier<String> value) {
        bind(view, value, false);
    }

    protected final void uiHint(TextView view, String key) {
        bind(view, () -> CaptionStrings.get(getContext(), key), true);
    }

    private void bind(TextView view, Supplier<String> value, boolean hint) {
        textSlots.removeIf(
                slot -> slot.view.get() == null || (slot.view.get() == view && slot.hint == hint));
        TextSlot slot = new TextSlot(view, value, hint);
        textSlots.add(slot);
        slot.refresh();
        CaptionTextResolver.direction(view, view instanceof EditText);
    }

    /** Subclasses refresh the texts they set themselves. */
    protected void refreshDynamicText() {}

    final void refreshCaptionText() {
        refreshMeta();
        refreshDynamicText();
        textSlots.removeIf(slot -> slot.view.get() == null);
        for (TextSlot slot : textSlots) {
            slot.refresh();
            TextView target = slot.view.get();
            if (target != null) CaptionTextResolver.direction(target, target instanceof EditText);
        }
        View row = currentView.get();
        if (row != null && !binding) refreshRow(row);
    }

    private void refreshMeta() {
        if (titleSlot != null) {
            String next = CaptionStrings.get(getContext(), titleSlot);
            if (!sameText(getTitle(), next)) setTitle(next);
        }
        if (summarySlot != null) {
            String next = CaptionStrings.get(getContext(), summarySlot);
            if (!sameText(getSummary(), next)) setSummary(next);
        }
    }

    // ---- The row ----

    @Override
    public View getView(View convert, ViewGroup parent) {
        binding = true;
        try {
            refreshCaptionText();
            View row = super.getView(convert, parent);
            currentView = new WeakReference<>(row);
            return row;
        } finally {
            binding = false;
        }
    }

    @Override
    protected void onBindView(View row) {
        super.onBindView(row);
        refreshRow(row);
    }

    /** Title and summary show in full, in the direction of the language. */
    private void refreshRow(View row) {
        CaptionTextResolver.direction(row, false);
        for (int id : new int[] {android.R.id.title, android.R.id.summary}) {
            TextView label = row.findViewById(id);
            if (label == null) continue;
            CharSequence next = id == android.R.id.title ? getTitle() : getSummary();
            if (!sameText(label.getText(), next)) label.setText(next);
            if (label.getMaxLines() == 1) label.setSingleLine(false);
            if (label.getMaxLines() != Integer.MAX_VALUE) label.setMaxLines(Integer.MAX_VALUE);
            if (label.getEllipsize() != null) label.setEllipsize(null);
            CaptionTextResolver.direction(label, false);
        }
    }
}
