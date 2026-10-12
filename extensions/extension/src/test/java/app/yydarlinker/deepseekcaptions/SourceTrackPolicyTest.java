package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.app.Activity;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

/** Which of a video's own tracks is translated: the speech's original, human-made text first. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class SourceTrackPolicyTest {
    static final String VIDEO = "abcdefghijk";
    Activity activity;

    @Before public void before() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        SourceCatalog.clear();
        SourceTrackPolicy.reset();
    }

    @After public void after() { activity.finish(); }

    static String url(String lang, boolean asr, String name) {
        return "https://www.youtube.com/api/timedtext?v=" + VIDEO + "&lang=" + lang + (asr ? "&kind=asr" : "")
                + (name.isEmpty() ? "" : "&name=" + name) + "&sig=KEEP" + lang;
    }

    static SourceSelection track(String lang, boolean asr) { return track(lang, asr, ""); }

    static SourceSelection track(String lang, boolean asr, String name) {
        return new SourceSelection(VIDEO, (asr ? "a." : ".") + lang + "/" + name, lang, url(lang, asr, name),
                asr ? SourceSelection.Kind.ASR : SourceSelection.Kind.MANUAL, true, 0, "");
    }

    String chosenWithDefault(String target, String defaultLanguage, String requested, SourceSelection... tracks) {
        SourceCatalog.register(VIDEO, Arrays.asList(tracks), true);
        if (!defaultLanguage.isEmpty()) SourceCatalog.defaultLanguage(VIDEO, defaultLanguage);
        String out = SourceTrackPolicy.apply(activity, requested + "&tlang=" + target);
        return TargetLanguage.fromUrl(out) == null ? "no-target" : SourceTrackPolicy.descriptor(out);
    }

    String run(String target, String defaultLanguage, String requestedLang, SourceSelection... tracks) {
        return chosenWithDefault(target, defaultLanguage, url(requestedLang, false, ""), tracks);
    }

    @Test public void humanTrackInTheSpokenLanguageBeatsEverythingElse() {
        assertEquals("en||", run("zh-Hans", "zh-Hans", "zh-Hans",
                track("zh-Hans", false), track("en", true), track("en", false), track("ja", false)));
    }

    @Test public void automaticSpeechTrackIsUsedWhenNoHumanTrackExistsInThatLanguage() {
        assertEquals("en|asr|", run("zh-Hans", "zh-Hans", "zh-Hans", track("zh-Hans", false), track("en", true)));
    }

    @Test public void humanEnglishIsThePivotAheadOfAnAutomaticTrackInAnotherSpokenLanguage() {
        assertEquals("en||", run("zh-Hans", "zh-Hans", "ja", track("ja", true), track("en", false), track("zh-Hans", false)));
    }

    @Test public void originalLanguageHumanTrackBeatsEnglishPivot() {
        assertEquals("ja||", run("zh-Hans", "zh-Hans", "en", track("ja", true), track("ja", false), track("en", false)));
    }

    @Test public void theTargetLanguageTrackIsTheLastResortUnlessTheSpeechIsInThatLanguage() {
        assertEquals("fr||", run("zh-Hans", "zh-Hans", "zh-Hans", track("zh-Hans", false), track("fr", false)));
        assertEquals("zh-Hans||", run("zh-Hans", "zh-Hans", "en", track("zh-Hans", true), track("zh-Hans", false), track("en", false)));
    }

    @Test public void withoutAnAutomaticTrackEnglishHumanIsPreferredThenYouTubesOwnChoice() {
        assertEquals("en||", run("zh-Hans", "ja", "ja", track("ja", false), track("en", false), track("de", false)));
        assertEquals("ja||", run("zh-Hans", "ja", "de", track("de", false), track("ja", false), track("ko", false)));
    }

    @Test public void aDubbedTargetLanguageAutomaticTrackDoesNotHideTheOriginalSpeech() {
        assertEquals("en||", run("zh-Hans", "zh-Hans", "zh-Hans",
                track("zh-Hans", true), track("en", true), track("en", false), track("zh-Hans", false)));
    }

    @Test public void theRequestedTrackIsReturnedUntouchedWhenItIsAlreadyTheBest() {
        SourceCatalog.register(VIDEO, Arrays.asList(track("en", false), track("zh-Hans", false)), true);
        String requested = url("en", false, "") + "&tlang=zh-Hans&pot=extra";
        assertEquals(requested, SourceTrackPolicy.apply(activity, requested));
    }

    @Test public void aTrackAlreadyInTheTargetLanguageIsShownWithoutTranslation() {
        SourceCatalog.register(VIDEO, Arrays.asList(track("en", false), track("zh-Hans", true), track("zh-Hans", false)), true);
        SourceSelection own = SourceTrackPolicy.ownTargetTrack(activity, url("en", false, "") + "&tlang=zh-Hans");
        assertNotNull(own);
        assertEquals("zh-Hans", own.language);
        assertEquals(SourceSelection.Kind.MANUAL, own.kind);
        assertFalse(own.url.contains("tlang"));
        assertTrue(CaptionDiagnostics.fullText(activity).contains("translation_request=false"));
        // A track whose language is only the same family but another script is still translated.
        SourceCatalog.register(VIDEO, Arrays.asList(track("en", false), track("zh-Hant", false)), true);
        assertNull(SourceTrackPolicy.ownTargetTrack(activity, url("en", false, "") + "&tlang=zh-Hans"));
        // Automatic captions in the target language count when nothing human-made exists.
        SourceCatalog.register(VIDEO, Arrays.asList(track("en", false), track("zh-CN", true)), true);
        assertEquals(SourceSelection.Kind.ASR, SourceTrackPolicy.ownTargetTrack(activity, url("en", false, "") + "&tlang=zh-Hans").kind);
    }

    @Test public void aSingleTargetLanguageTrackNeedsNoTranslationEither() {
        SourceCatalog.register(VIDEO, Arrays.asList(track("zh-Hans", false)), true);
        assertNotNull(SourceTrackPolicy.ownTargetTrack(activity, url("zh-Hans", false, "") + "&tlang=zh-Hans"));
        SourceCatalog.clear();
        assertNull(SourceTrackPolicy.ownTargetTrack(activity, url("zh-Hans", false, "") + "&tlang=zh-Hans"));
    }

    @Test public void singleTrackAndUnknownCatalogueAreLeftAlone() {
        String requested = url("zh-Hans", false, "") + "&tlang=zh-Hans";
        assertEquals(requested, SourceTrackPolicy.apply(activity, requested));
        SourceCatalog.register(VIDEO, Arrays.asList(track("zh-Hans", false)), true);
        assertEquals(requested, SourceTrackPolicy.apply(activity, requested));
    }

    @Test public void theChoiceStaysTheSameWhileTheCatalogueDoesNot_Change() {
        SourceCatalog.register(VIDEO, Arrays.asList(track("zh-Hans", false), track("en", false), track("ja", false)), true);
        String requested = url("zh-Hans", false, "") + "&tlang=zh-Hans";
        String first = SourceTrackPolicy.apply(activity, requested);
        SourceCatalog.defaultLanguage(VIDEO, "ja");
        assertEquals(first, SourceTrackPolicy.apply(activity, requested));
        assertTrue(first.contains("lang=en") && first.contains("tlang=zh-Hans"));
        assertTrue(CaptionDiagnostics.fullText(activity).contains("SOURCE_TRACK_POLICY"));
    }
}
