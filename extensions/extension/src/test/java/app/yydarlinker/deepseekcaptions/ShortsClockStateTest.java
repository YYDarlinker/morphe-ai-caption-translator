package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import android.media.session.PlaybackState;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Captured in Shorts (diagnostics session 111): VideoState stayed PAUSED while the Short played, so
 * every ~85 ms player query looked like a paused seek and the caption was reset each time.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class ShortsClockStateTest {
  static final int PAUSED=PlaybackState.STATE_PAUSED,PLAYING=PlaybackState.STATE_PLAYING;
  @Before public void reset(){OfficialPlayerClockAdapter.resetForTests();}

  @Test public void aPausedStateWhoseTimeAdvancesAtThePlaybackRateIsPlaying() {
    assertEquals(PAUSED,OfficialPlayerClockAdapter.observedState("short",0,1000,1,PAUSED));
    assertEquals(PLAYING,OfficialPlayerClockAdapter.observedState("short",55,1060,1,PAUSED));
    assertEquals(PLAYING,OfficialPlayerClockAdapter.observedState("short",150,1145,1,PAUSED));
    // A coarse getter repeating the same time between updates is neither a pause nor a seek.
    assertEquals(PLAYING,OfficialPlayerClockAdapter.observedState("short",150,1160,1,PAUSED));
    assertEquals(PLAYING,OfficialPlayerClockAdapter.observedState("short",231,1230,1,PAUSED));
    RebuildClock clock=new RebuildClock();long serial=clock.seekSerial();
    clock.acceptNative("short",150,1145,PLAYING,1);clock.acceptNative("short",231,1230,PLAYING,1);
    assertEquals("no seek is reported while the Short plays",serial,clock.seekSerial());
  }

  @Test public void aRealPauseAndAPausedSeekStayPaused() {
    OfficialPlayerClockAdapter.observedState("v",10000,1000,1,PAUSED);
    assertEquals(PLAYING,OfficialPlayerClockAdapter.observedState("v",10100,1100,1,PAUSED));
    // The time stops: after half a second without movement the stale state is believed again.
    assertEquals(PLAYING,OfficialPlayerClockAdapter.observedState("v",10100,1400,1,PAUSED));
    assertEquals(PAUSED,OfficialPlayerClockAdapter.observedState("v",10100,1700,1,PAUSED));
    // A jump while paused is a seek, not playback.
    assertEquals(PAUSED,OfficialPlayerClockAdapter.observedState("v",40000,1800,1,PAUSED));
    // Another video never inherits the inference, and an actual PLAYING state passes through.
    assertEquals(PAUSED,OfficialPlayerClockAdapter.observedState("w",40100,1900,1,PAUSED));
    assertEquals(PLAYING,OfficialPlayerClockAdapter.observedState("w",40100,1950,1,PLAYING));
  }
}
