package app.yydarlinker.deepseekcaptions;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.Locale;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="zh-rCN")
public class N31BeforeLocaleProbeTest {
    @After public void reset(){BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.DEFAULT;ResourceUtils.activity=null;}
    @Test public void n30ReadsChineseDespiteExplicitJapaneseOverride() throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        ResourceUtils.activity=a;BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.OVERRIDE;AppLanguage.selected=Locale.JAPANESE;
        Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.setLocale(Locale.JAPANESE);
        Context expected=a.createConfigurationContext(cfg);
        String ja=expected.getString(expected.getResources().getIdentifier("cap_preview_hint","string",a.getPackageName()));
        String before=ResourceUtils.getString("cap_preview_hint");
        assertEquals(Locale.CHINA,a.getResources().getConfiguration().getLocales().get(0));
        assertEquals(ResourceUtils.getString("cap_preview_hint"),before);
        assertNotEquals(ja,before);
        assertEquals(ja,CaptionStrings.settings(a,"preview_hint"));
        N28CGeometryTest.export("n31-before-override.json",new org.json.JSONObject().put("activity","zh-CN").put("override","ja").put("n30",before).put("authored_ja",ja));
        a.finish();
    }
}
