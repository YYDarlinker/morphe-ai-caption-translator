package app.yydarlinker.deepseekcaptions;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.yydarlinker.extension.BuildConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Quiet collapsed rows create no report views and do not inspect diagnostic snapshots. */
@SuppressWarnings("deprecation")
public final class DeepSeekDiagnosticsPreference extends CaptionSettingPreference {
    /** One clipboard chunk of an export, for Android 9 which cannot publish a file. */
    private static final int COPY_PAGE_CHARS = 60000;

    private WeakReference<Panel> currentPanel = new WeakReference<>(null);

    public DeepSeekDiagnosticsPreference(Context c) {
        this(c, null);
    }

    public DeepSeekDiagnosticsPreference(Context c, AttributeSet a) {
        super(c, a);
        setPersistent(false);
        setSelectable(false);
    }

    /** The expandable report of one row; it exists in full only once the row was opened. */
    private final class Panel {
        final DeepSeekDiagnosticsPreference owner = DeepSeekDiagnosticsPreference.this;
        final WeakReference<LinearLayout> root;
        WeakReference<TextView> body = new WeakReference<>(null);
        WeakReference<LinearLayout> expanded = new WeakReference<>(null);
        long request;
        String lastLocale = "";
        CaptionDiagnosticSnapshot.Snapshot complete;
        CaptionDiagnosticSnapshot.Callback callback;

        Panel(LinearLayout root) {
            this.root = new WeakReference<>(root);
        }

        boolean open() {
            View view = expanded.get();
            return view != null && view.getVisibility() == View.VISIBLE;
        }

        /** Where the row is shown now: a result for another window or ticket is stale. */
        final class Place {
            final long ticket = request;
            final Object token;
            final View window;

            Place(View row) {
                token = row.getWindowToken();
                window = row.getRootView();
            }

            boolean holds() {
                View row = root.get();
                return currentPanel.get() == Panel.this
                        && ticket == request
                        && row != null
                        && open()
                        && row.isAttachedToWindow()
                        && row.getRootView() == window
                        && row.getWindowToken() == token;
            }
        }

        void ensureExpanded() {
            if (Looper.myLooper() != Looper.getMainLooper())
                throw new IllegalStateException("diagnostics_view_thread");
            LinearLayout box = root.get();
            if (expanded.get() != null || box == null) return;
            Context c = getContext();
            LinearLayout content = new LinearLayout(c);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setVisibility(View.GONE);
            TextView text = new TextView(c);
            text.setTag("ai_diagnostics_body");
            CaptionSettingsStyle.caption(text);
            text.setTextIsSelectable(true);
            text.setTypeface(Typeface.MONOSPACE);
            text.setPadding(
                    CaptionSettingsStyle.dp(c, 12),
                    CaptionSettingsStyle.dp(c, 10),
                    CaptionSettingsStyle.dp(c, 12),
                    CaptionSettingsStyle.dp(c, 10));
            ScrollView scroll =
                    new ScrollView(c) {
                        @Override
                        public boolean onInterceptTouchEvent(MotionEvent e) {
                            if (getParent() != null)
                                getParent().requestDisallowInterceptTouchEvent(true);
                            return super.onInterceptTouchEvent(e);
                        }
                    };
            scroll.setTag("ai_diagnostics_scroll");
            scroll.setBackground(CaptionSettingsStyle.surface(c, false));
            scroll.setFillViewport(false);
            scroll.setVerticalScrollBarEnabled(true);
            scroll.addView(text, new ScrollView.LayoutParams(-1, -2));
            body = new WeakReference<>(text);
            expanded = new WeakReference<>(content);

            Button refresh = button(c, "ai_diagnostics_refresh", "refresh", false);
            refresh.setOnClickListener(
                    v -> {
                        refresh();
                        scroll.scrollTo(0, 0);
                    });
            Button copy = button(c, "ai_diagnostics_copy", "copy", false);
            copy.setOnClickListener(v -> export(c, copy, false));
            Button save = button(c, "ai_diagnostics_save", "save_diagnostics", false);
            save.setOnClickListener(v -> export(c, save, true));
            Button clear = button(c, "ai_diagnostics_clear", "clear_diagnostics", true);
            clear.setOnClickListener(
                    v ->
                            CaptionSettingsDialogs.confirm(
                                    c,
                                    CaptionStrings.get(c, "clear_diagnostics"),
                                    CaptionStrings.get(c, "clear_diagnostics_confirm"),
                                    CaptionStrings.get(c, "clear_diagnostics"),
                                    () -> {
                                        cancel();
                                        CaptionDiagnostics.clear(c);
                                        complete = null;
                                        refresh();
                                    }));

            int gap = CaptionSettingsStyle.dp(c, 8);
            content.addView(
                    scroll, new LinearLayout.LayoutParams(-1, CaptionSettingsStyle.dp(c, 240)));
            content.addView(strip(c, gap, refresh, copy), new LinearLayout.LayoutParams(-1, -2));
            content.addView(strip(c, gap, save), new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-1, -2);
            clearParams.topMargin = gap;
            content.addView(clear, clearParams);
            box.addView(content);
            uiText(text, this::preview);
        }

        private Button button(Context c, String tag, String labelKey, boolean destructive) {
            Button button =
                    CaptionSettingsStyle.action(
                            c, CaptionStrings.get(c, labelKey), false, destructive, () -> {});
            button.setTag(tag);
            uiText(button, labelKey);
            return button;
        }

        private ProfileActionStrip strip(Context c, int gap, Button... buttons) {
            ProfileActionStrip strip = new ProfileActionStrip(c);
            for (Button button : buttons)
                strip.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
            strip.setPadding(0, gap, 0, 0);
            return strip;
        }

        String preview() {
            return complete != null && complete.key.current(getContext())
                    ? complete.preview
                    : CaptionStrings.get(getContext(), "diagnostics_loading");
        }

        void refresh() {
            View view = root.get();
            TextView text = body.get();
            if (view == null || text == null || !open()) return;
            complete = CaptionDiagnosticSnapshot.peek(getContext());
            String value = preview();
            if (!sameText(text.getText(), value)) text.setText(value);
            request++;
            Place place = new Place(view);
            lastLocale = CaptionTextResolver.locale(getContext()).toLanguageTag();
            callback =
                    snapshot -> {
                        TextView target = body.get();
                        if (target == null || !place.holds() || !snapshot.key.current(getContext()))
                            return;
                        complete = snapshot;
                        if (!sameText(target.getText(), snapshot.preview))
                            target.setText(snapshot.preview);
                    };
            CaptionDiagnosticSnapshot.request(getContext(), callback);
        }

        /** Saves the full report to Downloads, or copies it, off the main thread. */
        void export(Context c, Button button, boolean save) {
            View view = root.get();
            if (view == null) return;
            final Place place = new Place(view);
            final CaptionDiagnosticSnapshot.Key key = new CaptionDiagnosticSnapshot.Key(c);
            button.setEnabled(false);
            new Thread(
                            () -> {
                                String report = CaptionDiagnostics.fullText(c), name = null;
                                boolean failed = false;
                                if (save)
                                    try {
                                        name = writeReport(c, report);
                                    } catch (Exception error) {
                                        name = error.getClass().getSimpleName();
                                        failed = true;
                                    }
                                String result = name;
                                boolean failure = failed;
                                new Handler(Looper.getMainLooper())
                                        .post(
                                                () -> {
                                                    button.setEnabled(true);
                                                    if (!place.holds() || !key.current(c)) return;
                                                    if (failure) toast(c, "save_failed", result);
                                                    else if (save && result != null)
                                                        toast(c, "save_ok", result);
                                                    else copy(c, report);
                                                });
                            },
                            "caption-export")
                    .start();
        }

        void cancel() {
            request++;
            callback = null;
        }
    }

    private static void toast(Context c, String templateKey, Object... arguments) {
        Toast.makeText(
                        c,
                        String.format(Locale.ROOT, CaptionStrings.get(c, templateKey), arguments),
                        Toast.LENGTH_LONG)
                .show();
    }

    private static void copy(Context c, String report) {
        if (report.length() > COPY_PAGE_CHARS) {
            copyPages(c, report);
            return;
        }
        ClipboardManager manager = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(CaptionStrings.get(c, "diagnostics"), report));
        Toast.makeText(c, CaptionStrings.get(c, "message_896c4b51d7e9"), Toast.LENGTH_SHORT).show();
    }

    // ---- The row ----

    @Override
    public View getView(View convert, ViewGroup parent) {
        boolean ours =
                convert != null
                        && convert.getTag() instanceof Panel
                        && ((Panel) convert.getTag()).owner == this;
        return super.getView(ours ? convert : null, parent);
    }

    @Override
    protected void onBindView(View row) {
        if (row.getTag() instanceof Panel && ((Panel) row.getTag()).owner == this) {
            Panel panel = (Panel) row.getTag();
            Panel old = currentPanel.get();
            if (old != null && old != panel) old.cancel();
            currentPanel = new WeakReference<>(panel);
        }
        super.onBindView(row);
    }

    @Override
    protected void refreshDynamicText() {
        Panel panel = currentPanel.get();
        if (panel != null
                && panel.open()
                && !panel.lastLocale.equals(
                        CaptionTextResolver.locale(getContext()).toLanguageTag())) panel.refresh();
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        CaptionSettingsStyle.row(box);
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(c);
        uiText(title, "diagnostics");
        CaptionSettingsStyle.title(title);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button toggle = new Button(c, null, android.R.attr.borderlessButtonStyle);
        CaptionSettingsStyle.button(toggle);
        toggle.setTag("ai_diagnostics_toggle");
        header.addView(toggle);
        box.addView(header);
        TextView hint = new TextView(c);
        uiText(hint, "diagnostics_hint");
        CaptionSettingsStyle.caption(hint);
        box.addView(hint);

        Panel old = currentPanel.get();
        if (old != null) old.cancel();
        Panel panel = new Panel(box);
        currentPanel = new WeakReference<>(panel);
        box.setTag(panel);
        box.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View v) {
                        if (panel.open()) panel.refresh();
                    }

                    public void onViewDetachedFromWindow(View v) {
                        panel.cancel();
                    }
                });
        uiText(
                toggle,
                () -> {
                    String label = CaptionStrings.get(c, panel.open() ? "collapse" : "expand");
                    toggle.setContentDescription(label);
                    return label;
                });
        toggle.setOnClickListener(
                v -> {
                    boolean open = !panel.open();
                    if (open) panel.ensureExpanded();
                    View content = panel.expanded.get();
                    if (content != null) content.setVisibility(open ? View.VISIBLE : View.GONE);
                    if (open) panel.refresh();
                    else panel.cancel();
                    String label = CaptionStrings.get(c, open ? "collapse" : "expand");
                    toggle.setText(label);
                    toggle.setContentDescription(label);
                });
        return box;
    }

    // ---- Saving the report ----

    /** The message that tells where the report went, or null when this Android cannot save one. */
    static String saveReport(Context c, String report) throws IOException {
        String name = writeReport(c, report);
        return name == null
                ? null
                : String.format(Locale.ROOT, CaptionStrings.get(c, "save_ok"), name);
    }

    /** Publishes [report] in Downloads and returns its file name; null below Android 10. */
    private static String writeReport(Context c, String report) throws IOException {
        if (Build.VERSION.SDK_INT < 29) return null;
        String name =
                "caption-diagnostics-"
                        + BuildConfig.CAPTION_PATCH_VERSION
                        + "-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
                        + ".txt";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        ContentResolver resolver = c.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("No download destination");
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("No output stream");
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1)
                throw new IOException("Download was not published");
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw new IOException(e);
        }
        return name;
    }

    /**
     * Android 9 cannot publish a file through MediaStore, so the export is handed over in clipboard
     * chunks. Each label and toast is one complete authored template, never a concatenated
     * fragment.
     */
    private static void copyPages(Context c, String report) {
        int count = (report.length() + COPY_PAGE_CHARS - 1) / COPY_PAGE_CHARS;
        String[] labels = new String[count];
        for (int i = 0; i < count; i++)
            labels[i] =
                    String.format(
                            Locale.ROOT, CaptionStrings.get(c, "copy_part_label"), i + 1, count);
        AlertDialog dialog =
                new AlertDialog.Builder(c)
                        .setTitle(CaptionStrings.get(c, "copy_parts_title"))
                        .setNegativeButton(CaptionStrings.get(c, "cancel"), null)
                        .setItems(
                                labels,
                                (d, index) -> {
                                    ClipboardManager manager =
                                            (ClipboardManager)
                                                    c.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (manager != null)
                                        manager.setPrimaryClip(
                                                ClipData.newPlainText(
                                                        labels[index],
                                                        report.substring(
                                                                index * COPY_PAGE_CHARS,
                                                                Math.min(
                                                                        report.length(),
                                                                        (index + 1)
                                                                                * COPY_PAGE_CHARS))));
                                    toast(c, "copy_part_done", index + 1, count);
                                })
                        .create();
        dialog.show();
        CaptionTextResolver.direction(dialog.getWindow().getDecorView(), false);
        Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (cancel != null) CaptionTextResolver.direction(cancel, false);
    }
}
