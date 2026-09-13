package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.widget.Button;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * Inline text field for the Morphe second-level screen.
 *
 * <p>The value is committed shortly after typing stops and again when focus leaves the field. This
 * keeps every setting on the page itself: there is no editor dialog and no page-level Save button.</p>
 */
@SuppressWarnings("deprecation")
public final class DeepSeekTextPreference extends android.preference.Preference {
    static final String KEY_BASE_URL = "deepseek_caption_base_url";
    static final String KEY_API_KEY = "deepseek_caption_api_key";
    static final String KEY_PROMPT = "deepseek_caption_prompt";

    private static final long AUTO_SAVE_DELAY_MS = 850L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingSave;
    private EditText editor;
    private TextView state;
    private String lastCommitted = "";

    public DeepSeekTextPreference(Context context) {
        super(context);
        initialize();
    }

    public DeepSeekTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public DeepSeekTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public DeepSeekTextPreference(
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
        // Android groups rows of the same Preference subclass into one recycle pool. These rows
        // contain different editors (URL/key/prompt), so only reuse this exact field's view.
        String key = getKey();
        View safeView = convertView != null && key != null && key.equals(convertView.getTag())
                ? convertView
                : null;
        return super.getView(safeView, parent);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        cancelPendingSave();
        Context context = getContext();
        if (parent instanceof ListView) {
            ((ListView) parent).setItemsCanFocus(true);
            parent.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        LinearLayout root = new LinearLayout(context);
        root.setTag(getKey());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.setPadding(dp(16), dp(9), dp(16), dp(7));

        TextView title = new TextView(context);
        title.setText(getTitle());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(title, matchWrap());

        editor = new EditText(context);
        editor.setFocusableInTouchMode(true);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        editor.setPadding(0, dp(3), 0, dp(3));
        configureEditor(editor);
        String initial = initialValue();
        if (!KEY_API_KEY.equals(getKey())) {
            editor.setText(initial);
            editor.setSelection(initial.length());
        }
        lastCommitted = KEY_API_KEY.equals(getKey()) ? "" : initial.trim();
        root.addView(editor, matchWrap());
        if(KEY_API_KEY.equals(getKey())) {
            // A dialog isolates Android text gestures from the host ListView long-press handler.
            editor.setFocusable(false);
            editor.setOnClickListener(v -> editApiKey());
            editor.setOnLongClickListener(v -> { editApiKey(); return true; });
            Button edit=new Button(context);
            edit.setText("填写 / 粘贴 API Key");
            edit.setOnClickListener(v -> editApiKey());
            root.addView(edit,matchWrap());
        }

        state = new TextView(context);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        state.setAlpha(0.72f);
        updateState(false, null);
        root.addView(state, matchWrap());

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override public void afterTextChanged(Editable value) {
                scheduleSave(value == null ? "" : value.toString());
            }
        });
        editor.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) commitNow(editor.getText().toString(), true);
        });
        editor.setOnEditorActionListener((view, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                            event.getAction() == KeyEvent.ACTION_DOWN);
            if (done && !KEY_PROMPT.equals(getKey())) {
                commitNow(editor.getText().toString(), true);
                editor.clearFocus();
                return true;
            }
            return false;
        });
        editor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}

            @Override public void onViewDetachedFromWindow(View view) {
                commitNow(((EditText) view).getText().toString(), false);
            }
        });
        return root;
    }

    private void editApiKey() {
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("输入或粘贴 API Key");
        input.setLongClickable(true);
        input.setSelectAllOnFocus(false);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        LinearLayout box=new LinearLayout(getContext());box.setPadding(dp(20),dp(8),dp(20),dp(8));box.addView(input,matchWrap());
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle("API Key")
            .setView(box).setNegativeButton("取消",null).setPositiveButton("加密保存",null)
            .setNeutralButton("从剪贴板粘贴",null).create();
        dialog.setOnShowListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button -> {
                ClipboardManager clipboard=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip=clipboard==null ? null : clipboard.getPrimaryClip();
                if(clip!=null && clip.getItemCount()>0) {
                    CharSequence text=clip.getItemAt(0).getText();
                    if(text!=null) { input.setText(text);input.setSelection(input.length()); }
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String key=input.getText().toString().trim();
                if(key.isEmpty() || key.indexOf('\n')>=0 || key.indexOf('\r')>=0) {
                    input.setError("请输入完整的单行 API Key");return;
                }
                try { SecureApiKey.save(getContext(),key);updateState(true,null);
                    DeepSeekModelPreference.onCredentialsChanged(getContext());
                    DynamicCaptionController.refreshConfiguration(getContext());
                    input.setText("");dialog.dismiss();
                } catch(Exception failed) { input.setError("加密保存失败"); }
            });
        });
        dialog.setOnDismissListener(v -> input.setText(""));
        dialog.show();
    }

    private void configureEditor(EditText value) {
        String key = getKey();
        if (KEY_API_KEY.equals(key)) {
            value.setSingleLine(true);
            value.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PASSWORD |
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            boolean saved = !DeepSeekConfig.load(getContext()).apiKey.isEmpty();
            value.setHint(saved ? "已加密保存；输入可替换" : "请输入 API Key");
            value.setImeOptions(EditorInfo.IME_ACTION_DONE);
        } else if (KEY_PROMPT.equals(key)) {
            value.setSingleLine(false);
            value.setMinLines(3);
            value.setMaxLines(7);
            value.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            value.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            value.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        } else if (KEY_BASE_URL.equals(key)) {
            value.setSingleLine(true);
            value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            value.setImeOptions(EditorInfo.IME_ACTION_DONE);
        } else {
            value.setSingleLine(true);
            value.setInputType(InputType.TYPE_CLASS_TEXT);
            value.setImeOptions(EditorInfo.IME_ACTION_DONE);
        }
    }

    private String initialValue() {
        DeepSeekConfig.Snapshot current = DeepSeekConfig.load(getContext());
        if (KEY_BASE_URL.equals(getKey())) return current.baseUrl;
        if (KEY_PROMPT.equals(getKey())) return current.prompt;
        return "";
    }

    private void scheduleSave(String value) {
        cancelPendingSave();
        pendingSave = () -> commit(value, false);
        main.postDelayed(pendingSave, AUTO_SAVE_DELAY_MS);
    }

    private void commitNow(String value, boolean reportInvalid) {
        cancelPendingSave();
        commit(value, reportInvalid);
    }

    private void commit(String raw, boolean reportInvalid) {
        String value = raw == null ? "" : raw.trim();
        if (value.equals(lastCommitted)) return;
        if (KEY_API_KEY.equals(getKey()) && value.isEmpty()) return;

        try {
            saveValue(value);
            lastCommitted = value;
            if (editor != null) editor.setError(null);
            updateState(true, null);
            if (KEY_BASE_URL.equals(getKey()) || KEY_API_KEY.equals(getKey())) {
                DeepSeekModelPreference.onCredentialsChanged(getContext());
            }
            DynamicCaptionController.refreshConfiguration(getContext());
        } catch (Throwable error) {
            String detail = error.getMessage();
            if (detail == null || detail.trim().isEmpty()) detail = "自动保存失败";
            // During ordinary typing an incomplete URL is expected. Keep the last valid
            // value and show a quiet inline hint; focus loss exposes the field error as well.
            updateState(false, detail);
            if (reportInvalid && editor != null) editor.setError(detail);
        }
    }

    private void saveValue(String value) throws Exception {
        String key = getKey();
        if (KEY_BASE_URL.equals(key)) {
            DeepSeekConfig.saveBaseUrl(getContext(), value);
        } else if (KEY_API_KEY.equals(key)) {
            SecureApiKey.save(getContext(), value);
        } else if (KEY_PROMPT.equals(key)) {
            DeepSeekConfig.savePrompt(getContext(), value);
        } else {
            throw new IllegalArgumentException("未知设置项");
        }
    }

    private void updateState(boolean justSaved, String error) {
        if (state == null) return;
        if (error != null) {
            state.setText(error + "；保留上次有效值");
            state.setAlpha(1f);
            return;
        }

        DeepSeekConfig.Snapshot current = DeepSeekConfig.load(getContext());
        if (KEY_API_KEY.equals(getKey())) {
            state.setText(current.apiKey.isEmpty()
                    ? "填写后自动加密保存"
                    : (justSaved ? "已自动加密保存" : "已使用 Android Keystore 加密保存"));
        } else {
            CharSequence summary = getSummary();
            state.setText(justSaved ? "已自动保存" :
                    (summary == null || summary.length() == 0 ? "修改后自动保存" : summary));
        }
        state.setAlpha(0.72f);
    }

    private void cancelPendingSave() {
        if (pendingSave != null) main.removeCallbacks(pendingSave);
        pendingSave = null;
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
