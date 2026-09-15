package app.yydarlinker.deepseekcaptions;
import android.app.Activity;import android.app.AlertDialog;import android.content.res.Configuration;import android.os.LocaleList;import android.graphics.*;import android.view.View;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import org.robolectric.shadows.ShadowAlertDialog;
import java.util.*;import java.io.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class QuickCaptionDialogTest {
    @Test public void nativeDialogUsesLocalizedChoicesWithoutChangingEngineOnOpen()throws Exception {
        for(String tag:Arrays.asList("en","zh-CN","ar")){
            Activity a=Robolectric.buildActivity(Activity.class).setup().visible().get();Configuration c=new Configuration(a.getResources().getConfiguration());c.setLocales(new LocaleList(Locale.forLanguageTag(tag)));a.getResources().updateConfiguration(c,a.getResources().getDisplayMetrics());a.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
            DeepSeekConfig.saveEnabled(a,false);CaptionQuickToggle.show(a);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();dialog.getListView().setScrollIndicators(0);
            assertTrue(dialog.isShowing());assertEquals(2,dialog.getListView().getCount());assertEquals(CaptionStrings.get(a,"use_ai"),dialog.getListView().getItemAtPosition(1));assertFalse(DeepSeekConfig.enabled(a));
            View decor=dialog.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(440,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.AT_MOST));decor.layout(0,0,440,decor.getMeasuredHeight());
            String output=System.getenv("CAPTION_UI_PREVIEW_OUTPUT");if(output!=null){Bitmap image=Bitmap.createBitmap(440,decor.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);decor.draw(canvas);File f=new File(output,"engine-dialog-"+tag+".png");f.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(f)){image.compress(Bitmap.CompressFormat.PNG,100,stream);}}
            dialog.dismiss();assertFalse(DeepSeekConfig.enabled(a));a.finish();
        }
    }
}
