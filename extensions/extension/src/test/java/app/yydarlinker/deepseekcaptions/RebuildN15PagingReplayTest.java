package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.*;
import java.util.function.Predicate;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Offline replay on fixed N13 word ownership and captured accepted Chinese events. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RebuildN15PagingReplayTest {
  static final int WIDTH=1121;
  static final float PREFERRED_PX=WIDTH/24f;
  static int lines(String value) {
    TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    paint.setTypeface(Typeface.DEFAULT);
    paint.setTextSize(PREFERRED_PX);
    return StaticLayout.Builder.obtain(value,0,value.length(),paint,WIDTH)
        .setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build().getLineCount();
  }
  @Test public void replayCapturedEvents() throws Exception {
    String output=System.getenv("MORPHE_N15_LAYOUT_EXPORT");

    String corpus;
    try(InputStream in=getClass().getResourceAsStream("/r29/captured-events.json")) {
      assertNotNull(in); corpus=new String(in.readAllBytes(),StandardCharsets.UTF_8);
    }
    JSONObject root=new JSONObject(corpus);
    JSONArray result=new JSONArray(),blocks=root.getJSONArray("blocks");
    JSONArray words=root.getJSONArray("source_word_times");
    assertEquals(9684,words.length());
    assertEquals(124,blocks.length());
    assertEquals("d841cf104e07cb2330f3143c538981979f8ba062dc6582c322f6dc8460ad0379",
        root.getString("prompt_sha256"));
    Predicate<String> fitsTwo=s->lines(s)<=2;
    Predicate<String> fitsOne=s->lines(s)<=1;
    int count=0, nextWord=0, chineseWords=0, fallbackWords=0;
    for(int i=0;i<blocks.length();i++) {
      JSONObject block=blocks.getJSONObject(i);
      assertEquals("contiguous N13 block ownership",nextWord,block.getInt("from"));
      JSONArray owned=block.getJSONArray("owned_tokens");
      assertEquals(block.getInt("to")-nextWord+1,owned.length());
      for(int k=0;k<owned.length();k++) {
        JSONArray token=owned.getJSONArray(k), word=words.getJSONArray(nextWord+k);
        assertEquals(nextWord+k,token.getInt(0));
        assertEquals(nextWord+k,word.getInt(0));
        assertEquals(token.getString(1),word.getString(1));
      }
      nextWord=block.getInt("to")+1;
      if(!"accepted".equals(block.getString("status"))) {
        assertEquals("documented original fallback", "final-fail",block.getString("status"));
        JSONArray fallback=block.getJSONArray("fallback_events");
        int cursor=block.getInt("from");
        for(int cue=0;cue<fallback.length();cue++) {
          JSONObject part=fallback.getJSONObject(cue);
          assertEquals("cue provenance covers each failed word once",cursor,part.getInt("from"));
          assertTrue(part.getInt("to")>=cursor && part.getInt("to")<=block.getInt("to"));
          assertEquals(words.getJSONArray(cursor).getInt(2),part.getLong("start"));
          assertEquals(words.getJSONArray(part.getInt("to")).getInt(3),part.getLong("end"));
          for(int word=cursor;word<=part.getInt("to");word++)
            assertEquals(part.getInt("source_cue_id"),words.getJSONArray(word).getInt(4));
          cursor=part.getInt("to")+1;
        }
        assertEquals(block.getInt("to")+1,cursor);
        fallbackWords+=owned.length();
        continue;
      }
      chineseWords+=owned.length();
      JSONArray events=block.getJSONArray("events");
      for(int j=0;j<events.length();j++) {
        JSONObject e=events.getJSONObject(j);
        String text=e.getString("text");
        if(text.isEmpty())continue;
        long start=e.getLong("start"),end=e.getLong("end");
        List<RebuildPageLayout.Page> pages=RebuildPageLayout.plan(text,start,end,fitsTwo,fitsOne);
        JSONObject row=new JSONObject().put("block",i).put("from",e.getInt("from"))
            .put("to",e.getInt("to")).put("start",start).put("end",end)
            .put("text",text).put("event_lines",lines(text));
        JSONArray outputPages=new JSONArray();
        for(RebuildPageLayout.Page page:pages)
          outputPages.put(new JSONObject().put("text",page.text).put("start",page.start)
              .put("end",page.end).put("lines",lines(page.text)));
        // Every translated page is bounded by its original event, even when a style goal loses.
        if (!pages.isEmpty()) {
          StringBuilder joined=new StringBuilder();
          long at=start;
          for(RebuildPageLayout.Page page:pages) {
            assertEquals(at,page.start);
            assertTrue(page.end>page.start);
            assertTrue(page.end-page.start>=RebuildPageLayout.MIN_PAGE_MS ||
                end-start<RebuildPageLayout.MIN_PAGE_MS && pages.size()==1);
            assertTrue(page.text.codePointCount(0,page.text.length())*1000L
                <=RebuildPageLayout.MAX_CPS*(page.end-page.start));
            assertTrue(lines(page.text)<=2);
            joined.append(page.text);
            at=page.end;
          }
          assertEquals(end,at);
          assertEquals(text,joined.toString());
          assertTrue(pages.size()*RebuildPageLayout.MIN_PAGE_MS<=end-start ||
              end-start<RebuildPageLayout.MIN_PAGE_MS && pages.size()==1);
        }
        row.put("pages",outputPages).put("fallback",pages.isEmpty());
        result.put(row);count++;
      }
    }
    if(output!=null) {
      Path file=Path.of(output);Files.createDirectories(file.getParent());
      Files.write(file,(new JSONObject().put("events",result)
        .put("event_count",count).put("test_lines_100_han",lines(String.join("",Collections.nCopies(100,"中"))))
        .put("viewport_width_px",WIDTH)
        .put("preferred_font_px",PREFERRED_PX)
        .put("measurement","Robolectric SDK28 StaticLayout, approx 24 CJK columns; not a phone measurement")
        .toString(2)+"\n").getBytes(StandardCharsets.UTF_8));
    }
    assertEquals(9684,nextWord);
    assertEquals(9576,chineseWords);
    assertEquals(108,fallbackWords);
    assertEquals("captured accepted Chinese events",540,count);
    assertEquals("StaticLayout native graphics must actually wrap",5,lines(String.join("",Collections.nCopies(100,"中"))));
  }
}
