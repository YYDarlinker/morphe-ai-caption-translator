package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.yydarlinker.caption.contracts.CaptionContracts.*;

import static org.junit.Assert.*;

/**
 * The reconciler is the only place that applies the session intent: (video, player epoch, revision)
 * is applied once, a teardown never writes the intent, and a native observation can never turn into
 * a user command.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
        sdk = 28,
        manifest = Config.NONE,
        shadows = {
            CaptionSessionReconcilerTest.Core.class,
            CaptionSessionReconcilerTest.Epoch.class,
            CaptionSessionReconcilerTest.Tracks.class
        })
public class CaptionSessionReconcilerTest {
    private static final String VIDEO = "abcdefghijk";
    private static final String SOURCE =
            "https://www.youtube.com/api/timedtext?v=" + VIDEO + "&lang=en";
    private static final String TRANSLATED = SOURCE + "&tlang=zh-Hans";

    enum Origin {
        PREFERRED_TRACK,
        DEFAULT
    }

    static final List<String> calls = new ArrayList<>();
    static String currentVideo = VIDEO;
    static boolean visible;
    static long epoch = 7L;

    static final class Track {
        final String language, url, vss;

        Track(String language, String url, String vss) {
            this.language = language;
            this.url = url;
            this.vss = vss;
        }
    }

    @Implements(CaptionActions.class)
    public static class Core {
        @Implementation
        public static void activate(Context context, String url) {
            calls.add("start:" + url);
            visible = true;
        }

        @Implementation
        public static void activateSource(Context context, String url) {
            calls.add("start:" + url);
            visible = true;
        }

        @Implementation
        public static String currentVideoIdSnapshot() {
            return currentVideo;
        }

        @Implementation
        public static boolean isVisibleActive() {
            return visible;
        }

        @Implementation
        public static void deactivateByUser() {
            calls.add("stop:user_off");
            visible = false;
        }

        @Implementation
        public static void deactivateAfterNativeReset() {
            calls.add("stop:native_reset");
            visible = false;
        }
    }

    @Implements(CaptionPlayerAuthority.class)
    public static class Epoch {
        @Implementation
        public static long ownerEpoch() {
            return epoch;
        }
    }

    @Implements(NativeCaptionBridge.class)
    public static class Tracks {
        @Implementation
        public static String language(Object track) {
            return ((Track) track).language;
        }

        @Implementation
        public static String url(Object track) {
            return ((Track) track).url;
        }

        @Implementation
        public static String vss(Object track) {
            return ((Track) track).vss;
        }

        @Implementation
        public static String nativeModelVideo(Object manager) {
            return VIDEO;
        }
    }

    private Activity a;

    @Before
    public void before() {
        a = Robolectric.buildActivity(Activity.class).setup().get();
        CaptionAddonSupport.initialize(a);
        CaptionActions.install(
                new CaptionPort() {
                    public void command(Action action, String value) {}

                    public boolean active() {
                        return false;
                    }

                    public boolean ownsNativeTrack() {
                        return false;
                    }

                    public String restoreTarget(String url) {
                        return url;
                    }

                    public String videoId() {
                        return currentVideo;
                    }

                    public void playback(long time) {}
                },
                new CaptionActions.SettingsServices() {
                    public String testApi() {
                        return "fake";
                    }

                    public List<String> models(String endpoint) {
                        return Collections.emptyList();
                    }
                });
        CaptionNativeProjection.resetForTests();
        CaptionSessionReconciler.resetForTests();
        CaptionSessionIntent.resetForTests(a);
        SourceCatalog.clear();
        SourceTrackPolicy.reset();
        DeepSeekConfig.saveEnabled(a, true);
        calls.clear();
        currentVideo = VIDEO;
        visible = false;
        epoch = 7L;
        // The video's own signed source track: enough to materialise a translation target without
        // any native track callback, which is what a re-entry after a teardown must be able to do.
        SourceCatalog.register(
                VIDEO,
                Collections.singletonList(
                        new SourceSelection(
                                VIDEO,
                                "en",
                                "en",
                                SOURCE,
                                SourceSelection.Kind.MANUAL,
                                true,
                                0,
                                "")),
                true);
    }

    @After
    public void after() {
        CaptionSessionReconciler.resetForTests();
        CaptionNativeProjection.resetForTests();
        CaptionSessionIntent.resetForTests(a);
        SourceCatalog.clear();
        a.finish();
    }

    private void userChoseTarget() {
        CaptionSessionIntent.userToggled(a, true);
        CaptionSessionIntent.userSelected(a, "zh-Hans", true);
    }

    private static int starts() {
        int count = 0;
        for (String call : calls) if (call.startsWith("start:")) count++;
        return count;
    }

    private static int stops(String reason) {
        int count = 0;
        for (String call : calls) if (call.equals("stop:" + reason)) count++;
        return count;
    }

    // 1
    @Test
    public void teardownNeverWritesTheIntent() {
        userChoseTarget();
        CaptionSessionReconciler.onVideo(a, VIDEO);
        assertEquals(1, starts());

        CaptionSessionIntent before = CaptionSessionIntent.load(a);
        CaptionSessionReconciler.onTeardown(a);
        CaptionSessionIntent after = CaptionSessionIntent.load(a);

        assertEquals("a teardown must not advance the revision", before.revision, after.revision);
        assertEquals(before.aiEnabled, after.aiEnabled);
        assertEquals(before.targetLanguage, after.targetLanguage);
        assertEquals(before.translateTarget, after.translateTarget);
        assertEquals("", CaptionSessionReconciler.sessionVideo());
    }

    // 2
    @Test
    public void tenNativeOffProjectionsChangeNoIntentAndTheNextVideoStillApplies() {
        userChoseTarget();
        CaptionSessionReconciler.onVideo(a, VIDEO);
        assertEquals(1, starts());
        CaptionSessionIntent before = CaptionSessionIntent.load(a);

        Track off = null;
        for (int i = 0; i < 10; i++)
            NativeCaptionBridge.observe(
                    new Object(),
                    NativeCaptionBridge.Event.selection(off, Origin.DEFAULT, 0, null),
                    epoch);

        CaptionSessionIntent after = CaptionSessionIntent.load(a);
        assertEquals("an observed native Off is not a user act", before.revision, after.revision);
        assertEquals(before.aiEnabled, after.aiEnabled);
        assertEquals(before.targetLanguage, after.targetLanguage);
        assertTrue("the projection follows the native model", CaptionNativeProjection.snapshot().off);
        assertTrue(
                "a native reset may retire the application, but only as a recoverable stop",
                stops("native_reset") > 0);
        assertEquals("no user act wrote the switch back off", 0, stops("user_off"));

        CaptionSessionReconciler.onTeardown(a);
        CaptionSessionReconciler.onVideo(a, VIDEO);
        assertEquals("the next video applies the unchanged intent again", 2, starts());
    }

    // 3
    @Test
    public void theSameAppliedKeyIsAppliedOnce() {
        userChoseTarget();
        CaptionSessionReconciler.onVideo(a, VIDEO);
        String key = CaptionSessionReconciler.appliedKey();
        assertEquals(1, starts());

        for (int i = 0; i < 3; i++) CaptionSessionReconciler.onPlayerRegular(a);
        CaptionSessionReconciler.onVideo(a, VIDEO);

        assertEquals("the same key must not restart the engine", 1, starts());
        assertEquals(1, CaptionSessionReconciler.applications());
        assertEquals(key, CaptionSessionReconciler.appliedKey());
    }

    // 4
    @Test
    public void reenteringTheSameVideoAppliesTheSameIntentAgain() {
        userChoseTarget();
        CaptionSessionReconciler.onVideo(a, VIDEO);
        assertEquals(1, starts());
        String first = CaptionSessionReconciler.appliedKey();

        CaptionSessionReconciler.onTeardown(a);
        assertEquals("the key is retired with its session", "", CaptionSessionReconciler.appliedKey());
        CaptionSessionReconciler.onVideo(a, VIDEO);
        CaptionSessionReconciler.onPlayerRegular(a);

        assertEquals(2, starts());
        assertEquals(first, CaptionSessionReconciler.appliedKey());
        assertEquals(2, CaptionSessionReconciler.applications());
    }

    // 5
    @Test
    public void aUserOffIsNotAppliedUntilTheUserSwitchesOnAgain() {
        userChoseTarget();
        CaptionSessionReconciler.onVideo(a, VIDEO);
        assertEquals(1, starts());

        CaptionSessionReconciler.onUserOff(a, "cc_button");
        assertFalse(CaptionSessionIntent.load(a).aiEnabled);
        assertEquals("the reconciler writes no stop of its own", 0, stops("user_off"));
        CaptionSessionReconciler.onPlayerRegular(a);
        CaptionSessionReconciler.onTeardown(a);
        CaptionSessionReconciler.onVideo(a, VIDEO);
        CaptionSessionReconciler.onPlayerRegular(a);
        assertEquals("a user off stays authoritative", 1, starts());

        assertTrue(CaptionSessionReconciler.onUserSwitched(a, true));
        assertEquals(2, starts());
        assertTrue(
                "the stored target is what starts",
                calls.get(calls.size() - 1).contains("tlang=zh-Hans"));
    }

    // 6
    @Test
    public void anOldEpochTrackListChangesNothing() {
        userChoseTarget();
        CaptionSessionReconciler.onVideo(a, VIDEO);
        assertEquals(1, starts());
        String key = CaptionSessionReconciler.appliedKey();
        int stale = staleObservations();

        List<Track> tracks =
                Collections.singletonList(new Track("en", SOURCE, ".en"));
        NativeCaptionBridge.observe(
                null, NativeCaptionBridge.Event.trackList(tracks), epoch - 1);

        assertEquals(1, starts());
        assertEquals(1, CaptionSessionReconciler.applications());
        assertEquals(key, CaptionSessionReconciler.appliedKey());
        assertEquals(
                "the old epoch is dropped before it can touch the session",
                stale + 1,
                staleObservations());

        NativeCaptionBridge.observe(null, NativeCaptionBridge.Event.trackList(tracks), epoch);
        assertEquals("the current epoch is not stale", stale + 1, staleObservations());
    }

    private static int staleObservations() {
        String history = CaptionDiagnostics.history(target());
        int count = 0, at = 0;
        while ((at = history.indexOf("NATIVE_OBSERVATION_STALE", at)) >= 0) {
            count++;
            at += "NATIVE_OBSERVATION_STALE".length();
        }
        return count;
    }

    private static Context target() {
        return CaptionAddonSupport.context();
    }
}
