package app.yydarlinker.deepseekcaptions;
import org.junit.Test;
public class LegacyRegressionTest {
 @Test public void contextualBatchApiClientHarness() throws Exception { ContextualBatchApiClientHarness.main(new String[0]); }
 @Test public void contextualCaptionTextPolicyHarness() throws Exception { ContextualCaptionTextPolicyHarness.main(new String[0]); }
 @Test public void contextualDisplayGroupPolicyHarness() throws Exception { ContextualDisplayGroupPolicyHarness.main(new String[0]); }
 @Test public void contextualUnitCorePolicyHarness() throws Exception { ContextualUnitCorePolicyHarness.main(new String[0]); }
 @Test public void contextualVideoOwnershipHarness() throws Exception { ContextualVideoOwnershipHarness.main(new String[0]); }
 @org.junit.Ignore("Retired target-length splitter: known baseline failure, see docs/ARCHITECTURE.md")
 @Test public void localDisplaySliceFallbackHarness() throws Exception { LocalDisplaySliceFallbackHarness.main(new String[0]); }
 @Test public void playbackClockEstimatorHarness() throws Exception { PlaybackClockEstimatorHarness.main(new String[0]); }
 @Test public void translationUnitTimelineHarness() throws Exception { TranslationUnitTimelineHarness.main(new String[0]); }
}
