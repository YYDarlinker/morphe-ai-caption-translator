package app.yydarlinker.deepseekcaptions;

import android.content.Context;
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
public class DeepSeekTextPreference extends CaptionUiPreference implements ApiProfiles.Editor {
    static final String KEY_BASE_URL = "deepseek_caption_base_url";
    static final String KEY_API_KEY = "deepseek_caption_api_key";
    static final String KEY_PROMPT = "deepseek_caption_prompt";

    private static final long AUTO_SAVE_DELAY_MS = 850L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingSave;
    private EditText editor;
    private TextView state;
    private String lastCommitted = "";
    private String boundProfile="";
    private View boundView;
    private long boundRevision=-1;
    private String boundDefaultPrompt="";
    private boolean programmaticText, userEditedPrompt, justSavedState;
    private String errorState;

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
        ApiProfiles.register(this);
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    public View getView(View convertView, ViewGroup parent) {
        // Android groups rows of the same Preference subclass into one recycle pool. These rows
        // contain different editors (URL/key/prompt), so only reuse this exact field's view.
        String key = getKey();
        View safeView = boundView != null && (boundView.getParent()==null || boundView.getParent()==parent) && boundRevision==ApiProfiles.revision() && boundProfile.equals(ApiProfiles.active(getContext())) && key != null && (key+boundProfile).equals(boundView.getTag())
                ? boundView
                : null;
        View bound=super.getView(safeView,parent);
        if(editor!=null){editor.setEnabled(true);editor.setFocusable(true);editor.setFocusableInTouchMode(true);editor.setClickable(true);editor.setLongClickable(true);editor.setCursorVisible(true);}
        if(bound instanceof ViewGroup){((ViewGroup)bound).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);bound.setFocusable(false);}
        return bound;
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        ApiProfiles.register(this);
        // Commit before a scroll-induced recreation; the old debounce must not be discarded.
        flushProfile();
        cancelPendingSave();
        boundProfile=ApiProfiles.active(getContext());
        boundRevision=ApiProfiles.revision();
        boundDefaultPrompt=DeepSeekConfig.defaultPrompt(getContext());
        Context context = getContext();
        if (parent instanceof ListView) {
            ((ListView) parent).setItemsCanFocus(true);
            parent.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        LinearLayout root = new LinearLayout(context);
        boundView = root;
        root.setTag(getKey()+boundProfile);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        CaptionSettingsStyle.row(root);

        TextView title = new TextView(context);
        CaptionUiViewBindings.render(title,()->getTitle());
        CaptionSettingsStyle.title(title);
        title.setPadding(0,0,0,dp(8));
        root.addView(title, matchWrap());

        editor = new InlineCaptionEditor(context);
        editor.setId(android.R.id.edit);
        editor.setFocusableInTouchMode(true);
        CaptionSettingsStyle.editor(editor);
        configureEditor(editor);
        String initial = initialValue();
        if (!KEY_API_KEY.equals(getKey())) {
            editor.setText(initial);
            editor.setSelection(initial.length());
        }
        lastCommitted = KEY_API_KEY.equals(getKey()) ? "" : initial.trim();
        root.addView(editor, matchWrap());
        editor.setLongClickable(true);


        state = new TextView(context);
        CaptionSettingsStyle.caption(state);
        state.setPadding(0,dp(6),0,0);
        updateState(false, null);
        root.addView(state, matchWrap());

        final EditText createdEditor=editor;
        final String createdProfile=boundProfile;
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override public void afterTextChanged(Editable value) {
                if(!programmaticText&&createdEditor==editor&&createdProfile.equals(ApiProfiles.active(getContext()))){
                    if(KEY_PROMPT.equals(getKey()))userEditedPrompt=true;
                    scheduleSave(value == null ? "" : value.toString());
                }
            }
        });
        editor.setOnFocusChangeListener((view, hasFocus) -> {
            if(view!=editor||!createdProfile.equals(ApiProfiles.active(getContext())))return;
            if (!hasFocus) {
                String text=editor.getText().toString();commitNow(text,true);
                if(KEY_PROMPT.equals(getKey()) && text.trim().isEmpty()){
                    String defaults=DeepSeekConfig.defaultPrompt(getContext());
                    lastCommitted=defaults.trim();programmaticText=true;try{editor.setText(defaults);}finally{programmaticText=false;}cancelPendingSave();
                }
                // Do not clear text on transient focus loss from Android action mode / keyboard.
            }
        });
        editor.setOnEditorActionListener((view, actionId, event) -> {
            if(view!=editor||!createdProfile.equals(ApiProfiles.active(getContext())))return false;
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
            @Override public void onViewAttachedToWindow(View view) {if(view==editor)ApiProfiles.register(DeepSeekTextPreference.this);}

            @Override public void onViewDetachedFromWindow(View view) {
                if(view==editor)ApiProfiles.unregister(DeepSeekTextPreference.this);
                if(view!=editor||!createdProfile.equals(ApiProfiles.active(getContext())))return;
                commitNow(((EditText) view).getText().toString(), false);
                if(KEY_API_KEY.equals(getKey())){cancelPendingSave();((EditText)view).setText("");cancelPendingSave();}
            }
        });
        return root;
    }

    private void configureEditor(EditText value) {
        String key = getKey();
        if (KEY_API_KEY.equals(key)) {
            value.setSingleLine(true);
            value.setInputType(CaptionInputPolicy.keyInputType());
            ((InlineCaptionEditor)value).sensitive(true);
            value.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            boolean saved = SecureApiKey.hasSavedValue(getContext());
            CaptionUiViewBindings.hint(value,getContext(), saved ? "key_saved" : "enter_key");
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
        if(KEY_API_KEY.equals(getKey()))return "";
        DeepSeekConfig.Snapshot current = DeepSeekConfig.load(getContext());
        if (KEY_BASE_URL.equals(getKey())) return current.baseUrl;
        if (KEY_PROMPT.equals(getKey())) return "program_default".equals(current.preferenceProvenance)?DeepSeekConfig.defaultPrompt(getContext()):current.prompt;
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
        if(boundRevision!=ApiProfiles.revision() || !boundProfile.equals(ApiProfiles.active(getContext())))return;
        String value = raw == null ? "" : raw.trim();
        boolean explicitDefaultEdit=KEY_PROMPT.equals(getKey()) && userEditedPrompt &&
                "program_default".equals(DeepSeekConfig.load(getContext()).preferenceProvenance);
        if (value.equals(lastCommitted) && !explicitDefaultEdit) {userEditedPrompt=false;if(editor!=null && editor.getError()!=null){editor.setError(null);updateState(false,null);}return;}
        if (KEY_API_KEY.equals(getKey()) && value.isEmpty()) return;
        if(KEY_API_KEY.equals(getKey()) && (value.contains("\n") || value.contains("\r"))) {
            errorState="message_7da9039cc193";if(editor!=null)editor.setError(CaptionStrings.settings(getContext(),errorState));return;
        }

        try {
            saveValue(value);
            userEditedPrompt=false;
            lastCommitted = value;
            if (editor != null) editor.setError(null);
            updateState(true, null);
            if (KEY_BASE_URL.equals(getKey()) || KEY_API_KEY.equals(getKey())) {
                DeepSeekModelPreference.onCredentialsChanged(getContext());
            }
            if(!ApiProfiles.flushing())DynamicCaptionController.refreshConfiguration(getContext());
        } catch (Throwable error) {
            String detail = error.getMessage();
            if(KEY_BASE_URL.equals(getKey()) && error instanceof IllegalArgumentException && !"api_url_scheme".equals(detail))detail="api_address_invalid";
            if (detail == null || detail.trim().isEmpty()) detail = "message_bbbcd9c8bf80";
            // During ordinary typing an incomplete URL is expected. Keep the last valid
            // value and show a quiet inline hint; focus loss exposes the field error as well.
            updateState(false, detail);
            if (reportInvalid && editor != null) editor.setError(errorText(detail));
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
            throw new IllegalArgumentException("unknown_setting");
        }
    }

    private void updateState(boolean justSaved, String error) {
        justSavedState=justSaved;errorState=error;
        if (state == null) return;
        if (error != null) {
            CaptionUiViewBindings.render(state,()->errorText(error)+CaptionStrings.settings(getContext(),"keep_last_valid"));
            state.setAlpha(1f);
            return;
        }

        if (KEY_API_KEY.equals(getKey())) {
            CaptionUiViewBindings.text(state,getContext(), !SecureApiKey.hasSavedValue(getContext())
                    ? "message_10d1b374429d" : (justSaved ? "saved" : "key_saved"));
        } else {
            CharSequence summary = getSummary();
            // XML summaries are already localized. Re-translating their Chinese prefixes duplicates text.
            CaptionUiViewBindings.render(state,()->justSaved?CaptionStrings.settings(getContext(),"saved"):
                    (getSummary()==null||getSummary().length()==0?CaptionStrings.settings(getContext(),"autosave"):getSummary()));
        }
        state.setAlpha(1f);
    }

    private String errorText(String raw){
        String key="api_url_scheme".equals(raw)?"message_bafa7b1ca6cb":
                "unknown_setting".equals(raw)?"message_9a6606c64f5f":"api_key_empty".equals(raw)?"enter_key":raw;
        String known=CaptionStrings.settings(getContext(),key);return known.isEmpty()?raw:known;
    }
    @Override void rebindUi(){
        if(editor!=null && KEY_PROMPT.equals(getKey()) && pendingSave==null &&
                boundRevision==ApiProfiles.revision() && boundProfile.equals(ApiProfiles.active(getContext())) &&
                editor.getText().toString().trim().equals(lastCommitted) &&
                "program_default".equals(DeepSeekConfig.load(getContext()).preferenceProvenance)) {
            String display=DeepSeekConfig.defaultPrompt(getContext());
            if(!display.equals(editor.getText().toString())){
                programmaticText=true;try{editor.setText(display);}finally{programmaticText=false;}
                lastCommitted=display.trim();boundDefaultPrompt=display;
            }
        }
        if(editor!=null && editor.getError()!=null && errorState!=null)editor.setError(errorText(errorState));
        super.rebindUi();
    }

    @Override public boolean flushProfile(){
        if(editor==null||boundRevision!=ApiProfiles.revision()||!boundProfile.equals(ApiProfiles.active(getContext())))return true;
        String value=editor.getText().toString().trim();commitNow(value,true);
        return value.equals(lastCommitted) || (KEY_API_KEY.equals(getKey())&&value.isEmpty());
    }
    @Override public void profileChanged(){
        cancelPendingSave();
        userEditedPrompt=false;
        boundProfile="";boundView=null;lastCommitted="";
        if(editor!=null){programmaticText=true;try{editor.setText("");}finally{programmaticText=false;}editor.clearFocus();}
        notifyChanged();
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
