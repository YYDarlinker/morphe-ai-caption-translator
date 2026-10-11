package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/**
 * Diagnostics 00:09 (Shorts, 21.16): after a Short looped or was entered again the player type NONE
 * made the authority compact because the cached Shorts view was stale, and nothing ever looked again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w1280dp-h1000dp-mdpi",shadows={N39UiGeometryFixture.Geometry.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class ShortsSurfaceRecoveryTest {
  N39UiGeometryFixture h;

  @Before public void setup(){
    CaptionPlayerAuthority.resetForTests();h=new N39UiGeometryFixture();h.setup();
    CaptionPlayerAuthority.setOwner(h.a);Shadows.shadowOf(Looper.getMainLooper()).idle();
  }
  @After public void done(){h.done();CaptionPlayerAuthority.resetForTests();}

  void type(String value){CaptionPlayerAuthority.onNotification(value,false);Shadows.shadowOf(Looper.getMainLooper()).idle();}

  @Test public void aShortWhoseSurfaceWentStaleComesBackWhenItIsFoundAgain(){
    N39UiGeometryFixture.shorts=true;type("NONE");
    assertEquals(CaptionPlayerAuthority.REGULAR,CaptionPlayerAuthority.state());
    // The Short loops: YouTube repeats the type while the cached view is not visible for a moment.
    N39UiGeometryFixture.shorts=false;type("NONE");
    assertEquals(CaptionPlayerAuthority.COMPACT,CaptionPlayerAuthority.state());
    assertFalse("not found yet",CaptionPlayerAuthority.recoverShorts());
    N39UiGeometryFixture.shorts=true;
    assertTrue(CaptionPlayerAuthority.recoverShorts());
    assertEquals(CaptionPlayerAuthority.REGULAR,CaptionPlayerAuthority.state());
    assertTrue(CaptionPlayerAuthority.displayPermitted());
  }

  @Test public void aClosedMiniplayerWithoutAShortsSurfaceStaysCompact(){
    N39UiGeometryFixture.shorts=false;type("NONE");
    assertEquals(CaptionPlayerAuthority.COMPACT,CaptionPlayerAuthority.state());
    assertFalse(CaptionPlayerAuthority.recoverShorts());
    assertEquals(CaptionPlayerAuthority.COMPACT,CaptionPlayerAuthority.state());
  }
}
