package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The official player-type names mix a compact state with a regular-player animation state. The
 * sliding names contain "MINIMIZED" but are not compact: treating them as compact retracts the
 * caption in the middle of YouTube's detail &lt;-&gt; fullscreen drag and leaves it hidden when the
 * gesture is cancelled.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1280dp-h1000dp-mdpi")
public class CaptionPlayerTypeContractTest {
    private Activity activity;

    @Before
    public void setUp() {
        CaptionPlayerAuthority.resetForTests();
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        CaptionPlayerAuthority.setOwner(activity);
        CaptionPlayerAuthority.onNotification("WATCH_WHILE_MAXIMIZED");
        assertEquals(CaptionPlayerAuthority.REGULAR, CaptionPlayerAuthority.state());
    }

    @After
    public void tearDown() {
        activity.finish();
        CaptionPlayerAuthority.resetForTests();
    }

    private void type(String value) {
        CaptionPlayerAuthority.onNotification(value);
    }

    @Test
    public void regularPlayerSlidingBetweenSizesKeepsDisplayPermission() {
        type("WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED");
        assertEquals(CaptionPlayerAuthority.REGULAR, CaptionPlayerAuthority.state());
        type("WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN");
        assertEquals(CaptionPlayerAuthority.REGULAR, CaptionPlayerAuthority.state());
        type("WATCH_WHILE_FULLSCREEN");
        assertEquals(CaptionPlayerAuthority.REGULAR, CaptionPlayerAuthority.state());
    }

    @Test
    public void onlyExactNonPlayingStatesAreCompact() {
        type("WATCH_WHILE_MINIMIZED");
        assertEquals(CaptionPlayerAuthority.COMPACT, CaptionPlayerAuthority.state());
        type("WATCH_WHILE_MAXIMIZED");
        type("WATCH_WHILE_PICTURE_IN_PICTURE");
        assertEquals(CaptionPlayerAuthority.COMPACT, CaptionPlayerAuthority.state());
        type("WATCH_WHILE_MAXIMIZED");
        type("WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED");
        assertEquals(CaptionPlayerAuthority.COMPACT, CaptionPlayerAuthority.state());
        assertTrue(CaptionPlayerAuthority.isCompactType("HIDDEN"));
        assertTrue(CaptionPlayerAuthority.isCompactType("NONE"));
        assertFalse(CaptionPlayerAuthority.isCompactType("WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED"));
        assertFalse(CaptionPlayerAuthority.isCompactType("WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN"));
    }
}
