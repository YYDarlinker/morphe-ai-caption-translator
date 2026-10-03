package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Supplemental async identity/lifecycle contracts; actual token checks remain covered by WMS. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=28, application=N32AsyncOwnershipTest.TrackingApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class N32AsyncOwnershipTest {
    public static class Host extends android.preference.PreferenceActivity {}
    public static class TrackingApplication extends Application {
        final List<ActivityLifecycleCallbacks> windowCallbacks=new ArrayList<>();
        @Override public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
            super.registerActivityLifecycleCallbacks(callback);
            if(callback.getClass().getEnclosingClass()==CaptionUiWindows.class)windowCallbacks.add(callback);
        }
    }
    /** Observe the listener release contract without changing Android attachment behavior. */
    static final class RequestView extends View {
        final Set<OnAttachStateChangeListener> listeners=Collections.newSetFromMap(new IdentityHashMap<>());
        RequestView(Context context){super(context);}
        @Override public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
            super.addOnAttachStateChangeListener(listener);listeners.add(listener);
        }
        @Override public void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
            super.removeOnAttachStateChangeListener(listener);listeners.remove(listener);
        }
    }
    final List<ActivityController<Host>> controllers=new ArrayList<>();
    ActivityController<Host> controller;
    Host activity;
    LinearLayout root;
    RequestView request;
    @Before public void setup() {
        controller=host();activity=controller.get();root=content(activity);
        request=new RequestView(activity);root.addView(request);
        assertTrue(request.isAttachedToWindow());
    }
    @After public void cleanup() {
        for(ActivityController<Host> owner:controllers)if(!owner.get().isDestroyed())owner.pause().stop().destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    ActivityController<Host> host() {
        ActivityController<Host> owner=Robolectric.buildActivity(Host.class).setup().visible();
        controllers.add(owner);return owner;
    }
    static LinearLayout content(Activity activity) {
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        android.widget.ListView list=new android.widget.ListView(activity);list.setId(android.R.id.list);root.addView(list);
        activity.setContentView(root);return root;
    }
    static PreferenceScreen tree(Host activity,Context context) {
        return activity.getPreferenceManager().createPreferenceScreen(context);
    }
    static Map<?,?> windowMap(String name) throws Exception {
        Field field=CaptionUiWindows.class.getDeclaredField(name);field.setAccessible(true);
        return (Map<?,?>)field.get(null);
    }
    static void deliver(CaptionUiWindows.Lease lease,Runnable result) {
        try {if(lease.current())result.run();}finally{lease.close();}
    }

    @Test public void detachedRequestCannotWriteAfterSameViewIsAttachedAgain() {
        CaptionUiWindows.Lease old=CaptionUiWindows.capture(request);assertNotNull(old);assertTrue(old.current());
        assertEquals(1,request.listeners.size());root.removeView(request);assertFalse(old.current());
        assertEquals(0,request.listeners.size());root.addView(request);
        assertTrue(CaptionUiWindows.isCurrent(request));assertFalse(old.current());
        int[] writes={0};deliver(old,()->writes[0]++);assertEquals(0,writes[0]);
        CaptionUiWindows.Lease fresh=CaptionUiWindows.capture(request);assertNotNull(fresh);
        deliver(fresh,()->writes[0]++);assertEquals(1,writes[0]);assertEquals(0,request.listeners.size());
        assertFalse(fresh.current());
    }

    @Test public void reboundResourceContextCannotRedirectTheOriginalRequestToANewSettingsUi() {
        Context shared=new ContextWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(activity.getResources().getConfiguration()));
        CaptionUiWindows.bind(tree(activity,shared),activity);
        RequestView original=new RequestView(shared);root.addView(original);
        CaptionUiWindows.Lease old=CaptionUiWindows.capture(original);assertNotNull(old);assertTrue(old.current());
        root.removeView(original);controller.pause().stop().destroy();
        ActivityController<Host> replacement=host();LinearLayout newRoot=content(replacement.get());
        CaptionUiWindows.bind(tree(replacement.get(),shared),replacement.get());
        RequestView freshView=new RequestView(shared);newRoot.addView(freshView);
        newRoot.addView(original);assertTrue(CaptionUiWindows.isCurrent(original));assertFalse(old.current());
        CaptionUiWindows.Lease fresh=CaptionUiWindows.capture(freshView);assertNotNull(fresh);
        int[] oldWrites={0},freshWrites={0};deliver(old,()->oldWrites[0]++);deliver(fresh,()->freshWrites[0]++);
        assertEquals(0,oldWrites[0]);assertEquals(1,freshWrites[0]);assertEquals(0,original.listeners.size());
    }

    @Test public void stoppedThenRestartedOwnerPermanentlyInvalidatesOldLeaseAndPendingSession() {
        CaptionUiWindows.Lease old=CaptionUiWindows.capture(request);assertNotNull(old);
        CaptionUiWindows.Session session=CaptionUiWindows.acquire(activity,"pending async result");assertNotNull(session);
        controller.pause().stop();assertFalse(old.current());
        controller.restart().start().resume();assertTrue(CaptionUiWindows.isCurrent(request));assertFalse(old.current());
        AlertDialog stale=new AlertDialog.Builder(session.context).setMessage("stale").create();
        assertFalse(CaptionUiWindows.show(session,stale));assertFalse(stale.isShowing());
        int[] writes={0};deliver(old,()->writes[0]++);assertEquals(0,writes[0]);
        CaptionUiWindows.Lease fresh=CaptionUiWindows.capture(request);assertNotNull(fresh);
        deliver(fresh,()->writes[0]++);assertEquals(1,writes[0]);
    }

    @Test public void sharedResourceContextIsRejectedWhileTwoSettingsOwnersAreAlive() {
        Context shared=new ContextWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(activity.getResources().getConfiguration()));
        PreferenceScreen firstTree=tree(activity,shared);CaptionUiWindows.bind(firstTree,activity);
        CaptionLanguagesPreference languages=new CaptionLanguagesPreference(shared);
        AlertDialog first=languages.showLanguages();assertNotNull(first);assertTrue(first.isShowing());
        ActivityController<Host> second=host();CaptionUiWindows.bind(tree(second.get(),shared),second.get());
        assertFalse(first.isShowing());assertFalse(CaptionUiWindows.valid(shared));assertNull(languages.showLanguages());
        second.pause().stop();assertFalse(CaptionUiWindows.valid(shared));assertNull(languages.showLanguages());
        second.restart().start().resume();second.get().finish();
        AlertDialog recovered=languages.showLanguages();assertNotNull(recovered);
        assertSame(activity,CaptionUiWindows.unwrap(recovered.getContext()));recovered.dismiss();
    }

    @Test public void destroyedOwnerCannotRecreateStateOrReviveAnOldRequest() throws Exception {
        Context shared=new ContextWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(activity.getResources().getConfiguration()));
        PreferenceScreen settings=tree(activity,shared);CaptionUiWindows.bind(settings,activity);
        CaptionUiWindows.Lease old=CaptionUiWindows.capture(request);assertNotNull(old);
        assertTrue(windowMap("states").containsKey(activity));assertTrue(windowMap("bindings").containsKey(shared));
        controller.pause().stop().destroy();assertFalse(old.current());
        assertFalse(windowMap("states").containsKey(activity));assertFalse(windowMap("bindings").containsKey(shared));
        CaptionUiWindows.bind(settings,activity);CaptionUiWindows.bind(new CaptionLanguagesPreference(shared),request);
        assertFalse(CaptionUiWindows.valid(shared));assertNull(CaptionUiWindows.capture(request));
        assertFalse(windowMap("states").containsKey(activity));assertFalse(windowMap("bindings").containsKey(shared));
        old.close();assertEquals(0,request.listeners.size());
    }

    @Test public void lifecycleCallbackIsRegisteredOnceAndReleasedOwnersLeaveNoState() throws Exception {
        CaptionUiWindows.Lease first=CaptionUiWindows.capture(request);assertNotNull(first);
        ActivityController<Host> second=host();LinearLayout otherRoot=content(second.get());
        RequestView other=new RequestView(second.get());otherRoot.addView(other);
        CaptionUiWindows.Lease secondRequest=CaptionUiWindows.capture(other);assertNotNull(secondRequest);
        assertNotNull(CaptionUiWindows.acquire(activity,"repeat owner"));assertNotNull(CaptionUiWindows.capture(request));
        TrackingApplication app=(TrackingApplication)RuntimeEnvironment.getApplication();
        assertEquals(1,app.windowCallbacks.size());
        for(Field field:app.windowCallbacks.get(0).getClass().getDeclaredFields()) {
            field.setAccessible(true);Object value=field.get(app.windowCallbacks.get(0));
            assertNotSame(activity,value);assertNotSame(second.get(),value);
        }
        controller.pause().stop().destroy();second.pause().stop().destroy();
        assertFalse(first.current());assertFalse(secondRequest.current());
        assertFalse(windowMap("states").containsKey(activity));assertFalse(windowMap("states").containsKey(second.get()));
        assertEquals(0,request.listeners.size());assertEquals(0,other.listeners.size());
        assertEquals(1,app.windowCallbacks.size());first.close();secondRequest.close();
    }

    @Test public void workerCannotCaptureOrValidateAWindowLease() throws Exception {
        CaptionUiWindows.Lease lease=CaptionUiWindows.capture(request);assertNotNull(lease);
        AtomicReference<CaptionUiWindows.Lease> captured=new AtomicReference<>();AtomicReference<Boolean> current=new AtomicReference<>();
        Thread worker=new Thread(()->{captured.set(CaptionUiWindows.capture(request));current.set(lease.current());});
        worker.start();worker.join();assertNull(captured.get());assertEquals(Boolean.FALSE,current.get());
        assertTrue(lease.current());lease.close();assertFalse(lease.current());assertEquals(0,request.listeners.size());
    }
}
