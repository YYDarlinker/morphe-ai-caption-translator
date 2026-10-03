package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.settings.BaseSettings;
import java.util.Arrays;
import java.util.Locale;
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

/** Supplemental lifecycle/resource contracts; actual token rejection/display is tested on WMS. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="zh-rCN") @LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class N32WindowOwnershipTest {
    public static class Host extends android.preference.PreferenceActivity {}
    ActivityController<Host> controller;
    Host activity;
    @Before public void setup() {
        controller=Robolectric.buildActivity(Host.class).setup().visible();activity=controller.get();
        BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.OVERRIDE;AppLanguage.selected=Locale.JAPANESE;
    }
    @After public void done() {
        if(!activity.isDestroyed())controller.pause().stop().destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.DEFAULT;
    }
    @Test public void localizedWindowKeepsActivityBaseAndOriginalHostResources() {
        Context resources=CaptionUiLocale.context(activity);
        assertEquals(Locale.JAPANESE,resources.getResources().getConfiguration().getLocales().get(0));
        CaptionUiWindows.Session window=CaptionUiWindows.acquire(resources,"test");assertNotNull(window);
        assertSame(activity,window.owner);
        assertSame(activity,((ContextThemeWrapper)window.context).getBaseContext());
        assertSame(activity.getSystemService(Context.WINDOW_SERVICE),window.context.getSystemService(Context.WINDOW_SERVICE));
        assertEquals(Locale.JAPANESE,window.context.getResources().getConfiguration().getLocales().get(0));
        assertEquals(Locale.CHINA,activity.getResources().getConfiguration().getLocales().get(0));
        assertEquals(Locale.CHINA,RuntimeEnvironment.getApplication().getResources().getConfiguration().getLocales().get(0));
    }
    @Test public void resourceOnlyTreeMustBeBoundToItsActualSettingsActivity() {
        Context detached=new ContextWrapper(activity.createConfigurationContext(activity.getResources().getConfiguration()));
        CaptionLanguagesPreference languages=new CaptionLanguagesPreference(detached);
        assertNull(languages.showLanguages());
        PreferenceScreen tree=activity.getPreferenceManager().createPreferenceScreen(detached);
        tree.addPreference(languages);CaptionUiWindows.bind(tree,activity);
        AlertDialog dialog=languages.showLanguages();assertNotNull(dialog);assertTrue(dialog.isShowing());
        assertSame(activity,CaptionUiWindows.unwrap(dialog.getContext()));dialog.dismiss();
    }
    @Test public void missingTokenApplicationFinishingAndDestroyedOwnersDoNotChangeSelection() {
        CaptionLanguageSelection.save(activity,Arrays.asList("ja","fr"));Set<String> selected=CaptionLanguageSelection.read(activity);
        assertNull(new CaptionLanguagesPreference(RuntimeEnvironment.getApplication()).showLanguages());
        ActivityController<Activity> unopened=Robolectric.buildActivity(Activity.class).create().start().resume();
        assertNull(new CaptionLanguagesPreference(unopened.get()).showLanguages());unopened.pause().stop().destroy();
        CaptionLanguagesPreference languages=new CaptionLanguagesPreference(activity);
        AlertDialog dialog=languages.showLanguages();assertNotNull(dialog);
        activity.finish();assertNull(languages.showLanguages());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(selected,CaptionLanguageSelection.read(activity));
        controller.pause().stop().destroy();assertNull(languages.showLanguages());
        assertEquals(selected,CaptionLanguageSelection.read(activity));
    }
    @Test public void duplicateClicksMergeAndCancelThenReopenUsesOriginalSelection() {
        CaptionLanguagesPreference languages=new CaptionLanguagesPreference(activity);
        CaptionLanguageSelection.save(activity,Arrays.asList("ja"));
        AlertDialog first=languages.showLanguages();assertSame(first,languages.showLanguages());
        assertEquals(14,first.getListView().getAdapter().getCount());
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog second=languages.showLanguages();assertNotSame(first,second);
        assertEquals(1,CaptionLanguageSelection.read(activity).size());second.dismiss();
    }
    @Test public void stoppedOwnerDismissesAllSharedDialogsAndOldActionsCannotWrite() {
        int[] confirmed={0};
        Dialog confirm=CaptionSettingsDialogs.confirm(activity,"test","message","save",()->confirmed[0]++);
        Dialog duplicate=CaptionSettingsDialogs.confirm(activity,"test","message","save",()->confirmed[0]++);
        assertSame(confirm,duplicate);assertTrue(confirm.isShowing());
        Dialog profiles=CaptionSettingsDialogs.show(activity,"profiles",new LinearLayout(activity),"close");
        assertTrue(profiles.isShowing());
        controller.pause().stop();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(confirm.isShowing());assertFalse(profiles.isShowing());assertEquals(0,confirmed[0]);
        assertNull(CaptionSettingsDialogs.confirm(activity,"test","message","save",()->confirmed[0]++));
        controller.restart().start().resume();
        assertNotNull(CaptionSettingsDialogs.confirm(activity,"test","message","save",()->confirmed[0]++));
    }
    @Test public void wrappersCannotBorrowAnotherActivityAndWorkersCannotOpenWindows() throws Exception {
        ActivityController<Activity> other=Robolectric.buildActivity(Activity.class).setup().visible();
        CaptionLanguagesPreference languages=new CaptionLanguagesPreference(new ContextWrapper(activity));
        CaptionUiWindows.bind(languages,new LinearLayout(other.get()));
        AlertDialog dialog=languages.showLanguages();assertSame(activity,CaptionUiWindows.unwrap(dialog.getContext()));dialog.dismiss();
        AtomicReference<CaptionUiWindows.Session> result=new AtomicReference<>();
        Thread worker=new Thread(()->result.set(CaptionUiWindows.acquire(activity,"worker")));worker.start();worker.join();
        assertNull(result.get());other.pause().stop().destroy();
    }
}
