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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Inline model editor plus automatic OpenAI-compatible model discovery. */
@SuppressWarnings("deprecation")
public final class DeepSeekModelPreference extends android.preference.Preference {
    static final String KEY_MODEL = "deepseek_caption_model";

    private static final long AUTO_SAVE_DELAY_MS = 850L;
    private static final long RETRY_AUTO_FETCH_AFTER_MS = 30_000L;
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final ExecutorService NETWORK = Executors.newCachedThreadPool(
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(
                            runnable,
                            "DeepSeekModelCatalog-" + THREAD_IDS.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );
    private static final Object CACHE_LOCK = new Object();
    private static volatile WeakReference<DeepSeekModelPreference> active =
            new WeakReference<>(null);
    private static String cachedFingerprint = "";
    private static List<String> cachedModels = Collections.emptyList();
    private static String lastAttemptFingerprint = "";
    private static long lastAttemptAtMs;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingSave;
    private Runnable pendingCredentialRefresh;
    private EditText editor;
    private TextView state;
    private Button refresh;
    private Spinner choices;
    private String lastCommitted = "";
    private volatile int fetchGeneration;
    private boolean populatingChoices;

    public DeepSeekModelPreference(Context context) {
        super(context);
        initialize();
    }

    public DeepSeekModelPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public DeepSeekModelPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public DeepSeekModelPreference(
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

    static void onCredentialsChanged(Context context) {
        synchronized (CACHE_LOCK) {
            cachedFingerprint = "";
            cachedModels = Collections.emptyList();
            lastAttemptFingerprint = "";
        }
        DeepSeekModelPreference preference = active.get();
        if (preference == null) return;
        preference.scheduleCredentialRefresh();
    }

    @Override
    public View getView(View convertView, ViewGroup parent) {
        View safe = convertView != null && KEY_MODEL.equals(convertView.getTag())
                ? convertView
                : null;
        return super.getView(safe, parent);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        cancelPendingSave();
        active = new WeakReference<>(this);
        Context context = getContext();
        if (parent instanceof ListView) {
            ((ListView) parent).setItemsCanFocus(true);
            parent.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }

        LinearLayout root = new LinearLayout(context);
        root.setTag(KEY_MODEL);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.setPadding(dp(16), dp(9), dp(16), dp(8));

        TextView title = new TextView(context);
        title.setText(getTitle());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(title, matchWrap());

        editor = new EditText(context);
        editor.setSingleLine(true);
        editor.setFocusableInTouchMode(true);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editor.setHint("可从下方选择，也可手动输入模型 ID");
        String initial = DeepSeekConfig.load(context).model;
        editor.setText(initial);
        editor.setSelection(initial.length());
        lastCommitted = initial;
        root.addView(editor, matchWrap());

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);

        refresh = new Button(context);
        refresh.setText("获取模型");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(view -> fetchModels(true));
        controls.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        state = new TextView(context);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        state.setAlpha(0.72f);
        state.setPadding(dp(10), 0, 0, 0);
        controls.addView(state, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        root.addView(controls, matchWrap());

        choices = new Spinner(context);
        choices.setVisibility(View.GONE);
        choices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(
                    AdapterView<?> parentView,
                    View view,
                    int position,
                    long id
            ) {
                if (populatingChoices || position <= 0) return;
                Object selected = parentView.getItemAtPosition(position);
                if (!(selected instanceof String)) return;
                String model = ((String) selected).trim();
                if (model.isEmpty()) return;
                editor.setText(model);
                editor.setSelection(model.length());
                commitNow(model, true);
            }

            @Override public void onNothingSelected(AdapterView<?> parentView) {}
        });
        root.addView(choices, matchWrap());

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
            if (!done) return false;
            commitNow(editor.getText().toString(), true);
            editor.clearFocus();
            return true;
        });
        editor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                active = new WeakReference<>(DeepSeekModelPreference.this);
                if (refresh != null) refresh.setEnabled(true);
                showCachedOrFetch();
            }

            @Override public void onViewDetachedFromWindow(View view) {
                commitNow(((EditText) view).getText().toString(), false);
                fetchGeneration++;
                if (active.get() == DeepSeekModelPreference.this) {
                    active = new WeakReference<>(null);
                }
            }
        });

        return root;
    }

    private void showCachedOrFetch() {
        DeepSeekConfig.Snapshot config = DeepSeekConfig.load(getContext());
        if (config.apiKey.isEmpty()) {
            setState("填写 API 地址和 API Key 后会自动获取；仍可手动输入", false);
            return;
        }
        String fingerprint = credentialFingerprint(config);
        synchronized (CACHE_LOCK) {
            if (fingerprint.equals(cachedFingerprint) && !cachedModels.isEmpty()) {
                showModels(new ArrayList<>(cachedModels), false);
                return;
            }
            long age = SystemClockCompat.elapsedRealtime() - lastAttemptAtMs;
            if (fingerprint.equals(lastAttemptFingerprint) && age < RETRY_AUTO_FETCH_AFTER_MS) {
                setState("可点“获取模型”重试，或直接手动输入", false);
                return;
            }
        }
        fetchModels(false);
    }

    private void fetchModels(boolean userInitiated) {
        DeepSeekConfig.Snapshot config = DeepSeekConfig.load(getContext());
        if (config.apiKey.isEmpty()) {
            setState("请先填写 API Key；模型也可手动输入", true);
            return;
        }
        final String fingerprint = credentialFingerprint(config);
        final int generation = ++fetchGeneration;
        synchronized (CACHE_LOCK) {
            lastAttemptFingerprint = fingerprint;
            lastAttemptAtMs = SystemClockCompat.elapsedRealtime();
        }
        if (refresh != null) refresh.setEnabled(false);
        setState(userInitiated ? "正在重新获取模型列表…" : "正在自动获取模型列表…", false);

        NETWORK.execute(() -> {
            try {
                List<String> models = DeepSeekModelCatalog.fetch(config.baseUrl, config.apiKey);
                if (generation != fetchGeneration) return;
                synchronized (CACHE_LOCK) {
                    cachedFingerprint = fingerprint;
                    cachedModels = new ArrayList<>(models);
                }
                main.post(() -> {
                    if (generation != fetchGeneration) return;
                    if (refresh != null) {
                        refresh.setEnabled(true);
                        refresh.setText("重新获取");
                    }
                    showModels(models, true);
                });
            } catch (Throwable error) {
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                final String detail = message;
                main.post(() -> {
                    if (generation != fetchGeneration) return;
                    if (refresh != null) refresh.setEnabled(true);
                    setState("获取失败，可手动输入：" + detail, true);
                });
            }
        });
    }

    private void showModels(List<String> models, boolean newlyFetched) {
        if (choices == null) return;
        List<String> entries = new ArrayList<>(models.size() + 1);
        entries.add("选择接口返回的模型（" + models.size() + " 个）");
        entries.addAll(models);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                android.R.layout.simple_spinner_item,
                entries
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        populatingChoices = true;
        choices.setAdapter(adapter);
        choices.setSelection(0, false);
        choices.setVisibility(View.VISIBLE);
        populatingChoices = false;
        setState(newlyFetched ? "模型列表已更新；选择后立即保存" : "选择后立即保存", false);
    }

    private void scheduleCredentialRefresh() {
        if (pendingCredentialRefresh != null) main.removeCallbacks(pendingCredentialRefresh);
        pendingCredentialRefresh = () -> {
            pendingCredentialRefresh = null;
            fetchModels(false);
        };
        main.postDelayed(pendingCredentialRefresh, 500L);
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
        try {
            DeepSeekConfig.saveModel(getContext(), value);
            lastCommitted = value;
            if (editor != null) editor.setError(null);
            setState("模型已自动保存", false);
            DynamicCaptionController.refreshConfiguration(getContext());
        } catch (Throwable error) {
            String detail = error.getMessage();
            if (detail == null || detail.trim().isEmpty()) detail = "模型自动保存失败";
            setState(detail + "；保留上次有效值", true);
            if (reportInvalid && editor != null) editor.setError(detail);
        }
    }

    private void setState(String text, boolean important) {
        if (state == null) return;
        state.setText(text);
        state.setAlpha(important ? 1f : 0.72f);
    }

    private void cancelPendingSave() {
        if (pendingSave != null) main.removeCallbacks(pendingSave);
        pendingSave = null;
    }

    private static String credentialFingerprint(DeepSeekConfig.Snapshot config) {
        return config.baseUrl + '|' + Integer.toHexString(config.apiKey.hashCode());
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

    /** Isolated wrapper makes the clock replaceable in plain JVM tests. */
    private static final class SystemClockCompat {
        static long elapsedRealtime() {
            return android.os.SystemClock.elapsedRealtime();
        }
    }
}
