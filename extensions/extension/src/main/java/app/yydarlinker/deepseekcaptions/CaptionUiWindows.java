package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.PopupWindow;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Settings window ownership is independent of the context used to read localized resources. */
@SuppressWarnings("deprecation")
public final class CaptionUiWindows {
    private CaptionUiWindows() {}
    private static final Map<Context, Binding> bindings = new WeakHashMap<>();
    private static final Map<Activity, State> states = new WeakHashMap<>();
    private static final Set<Application> applications = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Context> reported = Collections.newSetFromMap(new WeakHashMap<>());
    private static final class Binding {
        final WeakReference<Activity> owner;
        final WeakReference<View> decor;
        WeakReference<Activity> competing = new WeakReference<>(null);
        Binding(Activity activity) { owner=new WeakReference<>(activity);decor=new WeakReference<>(activity.getWindow().getDecorView()); }
    }
    /** Captures the initiating view/window, so a rebound context cannot redirect an async result. */
    static final class Lease {
        private final WeakReference<View> view;
        private final WeakReference<Activity> activity;
        private final IBinder token;
        private final long epoch;
        private boolean detached;
        private final View.OnAttachStateChangeListener listener;
        Lease(View view,Activity activity) {
            this.view=new WeakReference<>(view);this.activity=new WeakReference<>(activity);
            token=view.getApplicationWindowToken();epoch=observe(activity).epoch;
            listener=new View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(View attached){}
                @Override public void onViewDetachedFromWindow(View detachedView){close();}
            };
            view.addOnAttachStateChangeListener(listener);
        }
        boolean current() {
            if(Looper.myLooper()!=Looper.getMainLooper())return false;
            View original=view.get();Activity owner=activity.get();
            State state=states.get(owner);
            return !detached && state!=null && state.epoch==epoch && isCurrent(original) && owner(original.getContext())==owner
                    && tokenEquals(original.getApplicationWindowToken(),token);
        }
        void close(){detached=true;View original=view.get();if(original!=null)original.removeOnAttachStateChangeListener(listener);}
    }
    static Lease capture(View view) { return isCurrent(view)?new Lease(view,owner(view.getContext())):null; }
    private static final class State {
        boolean stopped;
        long epoch;
        final Map<String, WeakReference<Dialog>> dialogs = new HashMap<>();
        final Set<PopupWindow> popups = Collections.newSetFromMap(new WeakHashMap<>());
    }
    static final class Session {
        final Activity owner;
        final IBinder token;
        final String operation;
        final Context context;
        final long epoch;
        private WeakReference<Dialog> dialog = new WeakReference<>(null);
        Session(Activity owner, String operation, Context source) {
            this.owner = owner;
            this.token = owner.getWindow().getDecorView().getWindowToken();
            this.operation = operation;
            this.epoch = observe(owner).epoch;
            Configuration configuration = new Configuration(source.getResources().getConfiguration());
            configuration.setLocale(CaptionUiLocale.snapshot(source).locale);
            // Activity remains the base, hence WINDOW_SERVICE retains its parent window/token.
            ContextThemeWrapper localized = new ContextThemeWrapper(owner, 0);
            localized.applyOverrideConfiguration(configuration);
            localized.getTheme().setTo(owner.getTheme());
            context = localized;
        }
        boolean current() {
            Dialog shown = dialog.get();
            return usable(owner) && observe(owner).epoch==epoch && owner.getWindow().getDecorView().getWindowToken() == token
                    && shown != null && shown.isShowing();
        }
        Runnable guard(Runnable action) { return () -> { if (current()) action.run(); }; }
    }

    static Activity unwrap(Context supplied) {
        Set<Context> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Context current = supplied;
        while (current != null && seen.add(current)) {
            if (current instanceof Activity) return (Activity) current;
            if (current instanceof CaptionUiLocale.ResourceContext) {
                current = ((CaptionUiLocale.ResourceContext) current).origin.get();
            } else if (current instanceof ContextWrapper) {
                current = ((ContextWrapper) current).getBaseContext();
            } else return null;
        }
        return null;
    }
    private static Activity owner(Context context) {
        Activity direct = unwrap(context);
        if (direct != null) return direct;
        Binding bound = bindings.get(context);
        if(bound==null)return null;
        Activity activity=bound.owner.get(),competing=bound.competing.get();
        if(competing!=null && !competing.isFinishing() && !competing.isDestroyed())return null;
        if(activity==null || activity.getWindow()==null || bound.decor.get()!=activity.getWindow().getDecorView())return null;
        return activity;
    }
    private static State observe(Activity activity) {
        State state = states.get(activity);
        if (state == null) { state = new State(); states.put(activity, state); }
        Application app = activity.getApplication();
        if (applications.add(app)) app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) { State s=states.get(a);if(s!=null)s.stopped=false; }
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) { release(a, false); }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) { release(a, true); }
        });
        return state;
    }
    private static boolean usable(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity == null
                || activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) return false;
        State state = observe(activity);
        View decor = activity.getWindow().getDecorView();
        return !state.stopped && decor.isAttachedToWindow() && decor.getWindowToken() != null;
    }
    private static void bindContext(Context context, Activity activity) {
        if(activity==null || activity.isFinishing() || activity.isDestroyed())return;
        Activity direct = unwrap(context);
        if (context == null || (direct != null && direct != activity)) return;
        Binding previous = bindings.get(context);
        Activity old = previous == null ? null : previous.owner.get();
        Activity competing = previous == null ? null : previous.competing.get();
        if(competing!=null && !competing.isFinishing() && !competing.isDestroyed())return;
        if(old!=null && old!=activity && !old.isFinishing() && !old.isDestroyed()) {
            previous.competing=new WeakReference<>(activity);
            closeWindows(old);reject(context,"ambiguous settings trees");return;
        }
        bindings.put(context, new Binding(activity));
        observe(activity);
    }
    public static void bind(PreferenceGroup tree, Activity activity) {
        if (tree == null || activity == null || Looper.myLooper() != Looper.getMainLooper()) return;
        bindContext(tree.getContext(), activity);
        for (int i=0;i<tree.getPreferenceCount();i++) {
            Preference preference = tree.getPreference(i);
            bindContext(preference.getContext(), activity);
            if (preference instanceof PreferenceGroup) bind((PreferenceGroup)preference, activity);
        }
    }
    public static void bind(Preference preference, View view) {
        if (view == null || preference == null || Looper.myLooper() != Looper.getMainLooper()) return;
        Activity activity = owner(view.getContext());
        if (activity != null) bindContext(preference.getContext(), activity);
    }
    static void bind(PreferenceGroup tree) { if(tree!=null)bind(tree, unwrap(tree.getContext())); }
    static boolean valid(Context context) { return Looper.myLooper()==Looper.getMainLooper() && usable(owner(context)); }
    public static boolean isCurrent(View view) {
        if(Looper.myLooper()!=Looper.getMainLooper() || view==null || !view.isAttachedToWindow() || view.getWindowToken()==null)return false;
        Activity activity=owner(view.getContext());
        return usable(activity) && tokenEquals(view.getApplicationWindowToken(),activity.getWindow().getDecorView().getApplicationWindowToken());
    }
    private static boolean tokenEquals(IBinder left, IBinder right) { return left != null && right != null && (left == right || left.equals(right)); }
    private static void reject(Context context, String operation) {
        boolean first;
        synchronized(reported){first=context!=null && reported.add(context);}
        if (first)
            Log.w("CaptionUiWindows", "Settings window unavailable: no live owner/token for " + operation);
    }
    static Session acquire(Context source, String operation) {
        if(Looper.myLooper()!=Looper.getMainLooper()){reject(source,operation);return null;}
        Activity activity = owner(source);
        if (!usable(activity)) { reject(source, operation); return null; }
        return new Session(activity, operation, source);
    }
    static Dialog find(Context source, String operation) {
        if(Looper.myLooper()!=Looper.getMainLooper())return null;
        Activity activity = owner(source);
        if (!usable(activity)) return null;
        WeakReference<Dialog> reference = observe(activity).dialogs.get(operation);
        Dialog dialog = reference == null ? null : reference.get();
        return dialog != null && dialog.isShowing() ? dialog : null;
    }
    static boolean show(Session session, Dialog dialog) {
        if (session == null || !usable(session.owner) || observe(session.owner).epoch!=session.epoch
                || session.owner.getWindow().getDecorView().getWindowToken() != session.token) return false;
        State state = observe(session.owner);
        state.dialogs.put(session.operation, new WeakReference<>(dialog));
        session.dialog = new WeakReference<>(dialog);
        View decor = dialog.getWindow().getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                WeakReference<Dialog> reference = state.dialogs.get(session.operation);
                if (reference != null && reference.get() == dialog) state.dialogs.remove(session.operation);
                view.removeOnAttachStateChangeListener(this);
                dialog.setOnShowListener(null);
            }
        });
        dialog.show();
        CaptionUiLocale.direction(decor, session.context);
        return true;
    }
    static boolean popup(View anchor, PopupWindow popup, Runnable dismissed) {
        if (!isCurrent(anchor)) return false;
        Activity activity = owner(anchor.getContext());
        State state=observe(activity);state.popups.add(popup);
        View.OnAttachStateChangeListener listener=new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                popup.dismiss();view.removeOnAttachStateChangeListener(this);
            }
        };
        anchor.addOnAttachStateChangeListener(listener);
        popup.setOnDismissListener(()->{state.popups.remove(popup);anchor.removeOnAttachStateChangeListener(listener);dismissed.run();});
        popup.showAsDropDown(anchor);
        return true;
    }
    private static void closeWindows(Activity activity) {
        State state = states.get(activity);
        if (state == null) return;
        state.epoch++;
        for (WeakReference<Dialog> reference : new java.util.ArrayList<>(state.dialogs.values())) {
            Dialog dialog = reference.get();
            if (dialog != null) {
                dialog.dismiss();
                new android.os.Handler(Looper.getMainLooper()).post(() -> {
                    dialog.setOnShowListener(null);dialog.setOnCancelListener(null);
                    dialog.setOnDismissListener(null);dialog.setOnKeyListener(null);
                });
            }
        }
        state.dialogs.clear();
        for (PopupWindow popup : new java.util.ArrayList<>(state.popups)) popup.dismiss();
        state.popups.clear();
    }
    private static void release(Activity activity, boolean destroyed) {
        State state=states.get(activity);if(state==null)return;
        state.stopped=true;closeWindows(activity);
        if (destroyed) {
            states.remove(activity);
            bindings.entrySet().removeIf(e -> e.getValue().owner.get() == null || e.getValue().owner.get() == activity);
        }
    }
}
