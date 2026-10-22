package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Rect;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The caption layer contract: the overlay belongs to the view that owns the rendered video surface,
 * never to the activity content. The comment sheet is drawn after the player branch, so anything
 * inside that branch is below it by construction. Transforms on a player transition must not move
 * the caption off the video either.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1000dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class CaptionLayerContractTest {
    private Activity activity;

    @Before
    public void setUp() {
        CaptionSurface.invalidateGeometry();
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
    }

    @After
    public void tearDown() {
        CaptionSurface.invalidateGeometry();
        activity.finish();
    }

    private static void layout(View root, int width, int height) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    @Test
    public void theVideoPlaneIsTheCaptionHostNotTheActivityContent() {
        FrameLayout content = new FrameLayout(activity);
        activity.setContentView(content);
        FrameLayout player = new FrameLayout(activity);
        content.addView(player, new FrameLayout.LayoutParams(1000, 600));
        FrameLayout plane = new FrameLayout(activity);
        player.addView(plane, new FrameLayout.LayoutParams(1000, 562));
        TextureView video = new TextureView(activity);
        plane.addView(video, new FrameLayout.LayoutParams(1000, 562));
        layout(content, 1000, 600);

        assertSame(plane, CaptionSurface.captionHost(player));
        assertNotSame(content, CaptionSurface.captionHost(player));
    }

    @Test
    public void aVideoDirectlyInsideThePlayerUsesThePlayerItself() {
        FrameLayout content = new FrameLayout(activity);
        activity.setContentView(content);
        FrameLayout player = new FrameLayout(activity);
        content.addView(player, new FrameLayout.LayoutParams(1000, 600));
        TextureView video = new TextureView(activity);
        player.addView(video, new FrameLayout.LayoutParams(1000, 600));
        layout(content, 1000, 600);

        assertSame(player, CaptionSurface.captionHost(player));
    }

    @Test
    public void aPlayerWithoutASurfaceStillHasItsOwnLayer() {
        FrameLayout content = new FrameLayout(activity);
        activity.setContentView(content);
        FrameLayout player = new FrameLayout(activity);
        content.addView(player, new FrameLayout.LayoutParams(1000, 600));
        layout(content, 1000, 600);

        assertSame(player, CaptionSurface.captionHost(player));
    }

    @Test
    public void aPlayerThatCannotHoldOverlayParamsHasNoLayer() {
        FrameLayout content = new FrameLayout(activity);
        activity.setContentView(content);
        LinearLayout player = new LinearLayout(activity);
        content.addView(player, new FrameLayout.LayoutParams(1000, 600));
        layout(content, 1000, 600);

        assertNull(CaptionSurface.captionHost(player));
    }

    @Test
    public void aScaledPlayerKeepsTheVideoRectInTheHostsOwnCoordinates() {
        FrameLayout content = new FrameLayout(activity);
        activity.setContentView(content);
        FrameLayout player = new FrameLayout(activity);
        content.addView(player, new FrameLayout.LayoutParams(1000, 600));
        FrameLayout plane = new FrameLayout(activity);
        player.addView(plane, new FrameLayout.LayoutParams(1000, 600));
        TextureView video = new TextureView(activity);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(400, 200);
        videoParams.leftMargin = 100;
        videoParams.topMargin = 50;
        plane.addView(video, videoParams);
        layout(content, 1000, 600);
        player.setPivotX(0);
        player.setPivotY(0);
        player.setScaleX(.5f);
        player.setScaleY(.5f);

        Rect bounds = CaptionSurface.renderedBounds(player, plane);
        assertNotNull(bounds);
        assertEquals(100, bounds.left);
        assertEquals(50, bounds.top);
        assertEquals(400, bounds.width());
        assertEquals(200, bounds.height());
    }

    @Test
    public void aHiddenSurfaceLeavesTheLastGeometryInsteadOfFallingBackToThePlayer() {
        FrameLayout content = new FrameLayout(activity);
        activity.setContentView(content);
        FrameLayout player = new FrameLayout(activity);
        content.addView(player, new FrameLayout.LayoutParams(1000, 600));
        FrameLayout plane = new FrameLayout(activity);
        player.addView(plane, new FrameLayout.LayoutParams(1000, 562));
        TextureView video = new TextureView(activity);
        plane.addView(video, new FrameLayout.LayoutParams(1000, 562));
        layout(content, 1000, 600);
        assertNotNull(CaptionSurface.renderedBounds(player, plane));

        video.setVisibility(android.view.View.GONE);
        assertNull(
                "a transiently hidden surface must hold the last rectangle, not jump to the player",
                CaptionSurface.currentVideoBounds(plane));
    }
}
