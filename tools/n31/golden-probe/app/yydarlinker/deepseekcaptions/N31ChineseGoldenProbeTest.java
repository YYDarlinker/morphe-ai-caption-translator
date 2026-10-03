package app.yydarlinker.deepseekcaptions;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
/** Chinese baseline replay invokes the original golden methods with unchanged inputs/assertions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="zh-rCN",shadows={RebuildIntegrationTest.Keys.class,RebuildLayoutTest.Geometry.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class N31ChineseGoldenProbeTest {
    @Test public void originalEighteenGoldenMethodsInChineseConfiguration()throws Exception {
        new N28BLegacyGoldenTest().actualEnglishRegionsAndChineseTargetSpellingsRetainFullGoldenEvidence();
    }
    @Test public void originalActivateMethodInChineseConfiguration()throws Exception {
        new N28ALegacyEvidenceTest().actualActivateRequestCacheTokensAndPresentationEvidence();
    }
}
