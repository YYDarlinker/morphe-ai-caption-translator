package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Locale;

/**
 * An inline text field of the active API profile, on the Morphe second-level screen.
 *
 * <p>The value is committed shortly after typing stops and again when focus leaves the field. This
 * keeps every setting on the page itself: there is no editor dialog and no page-level Save button.
 * Whatever the user typed is also committed when the field is recreated, detached or the profile is
 * switched; an edit made for another profile is never written into this one.
 */
@SuppressWarnings("deprecation")
abstract class ProfileEditorPreference extends CaptionSettingPreference
        implements ApiProfiles.Editor {
    private static final long AUTO_SAVE_DELAY_MS = 850L;

    final Handler main = new Handler(Looper.getMainLooper());
    EditText editor;
    TextView state;
    private Runnable pendingSave;
    private String lastCommitted = "";
    private String boundProfile = "";
    private View boundView;
    private long boundRevision = -1;

    ProfileEditorPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        ApiProfiles.register(this);
        setPersistent(false);
        setSelectable(false);
    }

    // ---- What a field adds ----

    /** Single-line input type, IME action and hint of the editor. */
    abstract void configureEditor(EditText editor);

    /** The text the editor starts with. */
    abstract String initialValue();

    /** Everything below the editor. */
    abstract void addDetails(Context context, LinearLayout root);

    /** Writes a changed, non-empty value. */
    abstract void save(String value) throws Exception;

    /** The value was saved. */
    abstract void saved();

    /** The message shown when saving failed without any detail. */
    abstract String defaultFailure();

    /** A value that must not be saved: return false, after reporting it. */
    boolean accepts(String value) {
        return true;
    }

    void attached(boolean currentProfile) {
        ApiProfiles.register(this);
    }

    void detached() {
        commitNow(editor.getText().toString(), false);
    }

    void profileSwitched() {}

    /** Was [value], as flushed, written (or is it a value that needs no write)? */
    boolean flushed(String value) {
        return value.equals(lastCommitted);
    }

    // ---- Binding ----

    /** The view and values this field was bound with are still those of the active profile. */
    final boolean bound() {
        return boundRevision == ApiProfiles.revision()
                && boundProfile.equals(ApiProfiles.active(getContext()));
    }

    private boolean forProfile(String profile) {
        return profile.equals(ApiProfiles.active(getContext()));
    }

    @Override
    public View getView(View convertView, ViewGroup parent) {
        // Android groups rows of the same Preference subclass into one recycle pool. These rows
        // contain different editors, so only this exact field's view is reused.
        String key = getKey();
        boolean reusable =
                boundView != null
                        && (boundView.getParent() == null || boundView.getParent() == parent)
                        && bound()
                        && key != null
                        && (key + boundProfile).equals(boundView.getTag());
        View row = super.getView(reusable ? boundView : null, parent);
        if (editor != null) {
            editor.setEnabled(true);
            editor.setFocusable(true);
            editor.setFocusableInTouchMode(true);
            editor.setClickable(true);
            editor.setLongClickable(true);
            editor.setCursorVisible(true);
        }
        if (row instanceof ViewGroup) {
            ((ViewGroup) row).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            row.setFocusable(false);
        }
        return row;
    }

    @Override
    protected final View onCreateView(ViewGroup parent) {
        ApiProfiles.register(this);
        // Commit before a scroll-induced recreation; the old debounce must not be discarded.
        flushProfile();
        cancelPendingSave();
        boundProfile = ApiProfiles.active(getContext());
        boundRevision = ApiProfiles.revision();
        Context context = getContext();
        if (parent instanceof ListView) {
            ((ListView) parent).setItemsCanFocus(true);
            parent.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }

        LinearLayout root = new LinearLayout(context);
        boundView = root;
        root.setTag(getKey() + boundProfile);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        CaptionSettingsStyle.row(root);

        TextView title = new TextView(context);
        uiText(title, () -> String.valueOf(getTitle()));
        CaptionSettingsStyle.title(title);
        title.setPadding(0, 0, 0, CaptionSettingsStyle.dp(context, 8));
        root.addView(title, wide());

        editor = new InlineCaptionEditor(context);
        editor.setId(CaptionEditorIds.forKey(getKey()));
        editor.setFocusableInTouchMode(true);
        CaptionSettingsStyle.editor(editor);
        configureEditor(editor);
        String initial = initialValue();
        editor.setText(initial);
        editor.setSelection(initial.length());
        lastCommitted = initial.trim();
        root.addView(editor, wide());

        addDetails(context, root);
        wire(editor, boundProfile);
        return root;
    }

    /** The listeners of [created]: they act only while it is still this field's editor. */
    private void wire(EditText created, String createdProfile) {
        created.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable value) {
                        if (created != editor || !forProfile(createdProfile)) return;
                        scheduleSave(value == null ? "" : value.toString());
                        edited();
                    }
                });
        created.setOnFocusChangeListener(
                (view, hasFocus) -> {
                    // Transient focus loss from an action mode or the keyboard clears nothing.
                    if (view == editor && forProfile(createdProfile) && !hasFocus)
                        commitNow(editor.getText().toString(), true);
                });
        created.setOnEditorActionListener(
                (view, actionId, event) -> {
                    if (view != editor || !forProfile(createdProfile)) return false;
                    boolean done =
                            actionId == EditorInfo.IME_ACTION_DONE
                                    || (event != null
                                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                            && event.getAction() == KeyEvent.ACTION_DOWN);
                    if (!done) return false;
                    commitNow(editor.getText().toString(), true);
                    editor.clearFocus();
                    return true;
                });
        created.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                        if (view == editor) attached(forProfile(createdProfile));
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        if (view != editor) return;
                        ApiProfiles.unregister(ProfileEditorPreference.this);
                        if (forProfile(createdProfile)) detached();
                    }
                });
    }

    /** The text changed (after the save was scheduled). */
    void edited() {}

    // ---- Saving ----

    private void scheduleSave(String value) {
        cancelPendingSave();
        pendingSave = () -> commit(value, false);
        main.postDelayed(pendingSave, AUTO_SAVE_DELAY_MS);
    }

    final void commitNow(String value, boolean reportInvalid) {
        cancelPendingSave();
        commit(value, reportInvalid);
    }

    final void cancelPendingSave() {
        if (pendingSave != null) main.removeCallbacks(pendingSave);
        pendingSave = null;
    }

    private void commit(String raw, boolean reportInvalid) {
        if (!bound()) return;
        String value = raw == null ? "" : raw.trim();
        if (value.equals(lastCommitted) || !accepts(value)) return;
        try {
            save(value);
            lastCommitted = value;
            if (editor != null) editor.setError(null);
            saved();
            if (!ApiProfiles.flushing()) CaptionActions.refreshConfiguration(getContext());
        } catch (Throwable error) {
            String detail = error.getMessage();
            if (detail == null || detail.trim().isEmpty()) detail = defaultFailure();
            // During ordinary typing an incomplete value is expected: keep the last valid one and
            // show a quiet inline hint; focus loss exposes the field error as well.
            retained(detail);
            if (reportInvalid && editor != null)
                editor.setError(CaptionStrings.localize(getContext(), detail));
        }
    }

    /** Tells that the value in the field was refused and the previous one is still in force. */
    final void retained(String detail) {
        uiText(
                state,
                () ->
                        String.format(
                                Locale.ROOT,
                                CaptionStrings.get(getContext(), "value_retained"),
                                CaptionStrings.localize(getContext(), detail)));
        state.setAlpha(1f);
    }

    @Override
    public final boolean flushProfile() {
        if (editor == null || !bound()) return true;
        String value = editor.getText().toString().trim();
        commitNow(value, true);
        return flushed(value);
    }

    @Override
    public final void profileChanged() {
        cancelPendingSave();
        profileSwitched();
        boundProfile = "";
        boundView = null;
        lastCommitted = "";
        if (editor != null) {
            editor.setText("");
            editor.clearFocus();
        }
        notifyChanged();
    }

    static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
