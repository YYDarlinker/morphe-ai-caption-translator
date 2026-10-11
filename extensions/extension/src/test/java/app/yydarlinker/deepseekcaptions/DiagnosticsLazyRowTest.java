package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import android.app.Activity;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk={28,35})
public class DiagnosticsLazyRowTest {
    static int nodes(View view){int count=1;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=nodes(((ViewGroup)view).getChildAt(i));return count;}
    @Test public void collapsedPreferenceOwnsOnlyHeaderAndHintAndReusesExpandedViews(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().visible().get();
        try {
            FrameLayout parent=new FrameLayout(activity);activity.setContentView(parent);
            DeepSeekDiagnosticsPreference preference=new DeepSeekDiagnosticsPreference(activity);
            View row=preference.getView(null,parent);parent.addView(row);
            assertEquals(5,nodes(row));assertNull(row.findViewWithTag("ai_diagnostics_body"));
            preference.refreshCaptionText();assertEquals(5,nodes(row));
            Button toggle=row.findViewWithTag("ai_diagnostics_toggle");toggle.performClick();
            TextView body=row.findViewWithTag("ai_diagnostics_body");assertNotNull(body);assertTrue(body.isTextSelectable());
            int expandedCount=nodes(row);toggle.performClick();toggle.performClick();
            assertSame(body,row.findViewWithTag("ai_diagnostics_body"));assertEquals(expandedCount,nodes(row));
            parent.removeView(row);preference.refreshCaptionText();
        } finally {activity.finish();}
    }
}
