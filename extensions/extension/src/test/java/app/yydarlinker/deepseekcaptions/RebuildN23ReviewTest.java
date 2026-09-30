package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;

public class RebuildN23ReviewTest {
  private List<RebuildReview.Issue> issues(String english, String chinese) {
    RebuildSource source = RebuildContractTest.source(english, 323);
    RebuildProtocol.Event event = new RebuildProtocol.Event(0, source.words.size()-1, 0, 8000, chinese);
    return RebuildReview.inspect(source, RebuildContractTest.block(source), Collections.singletonList(event));
  }

  @Test public void wordingDriftStillRepairsAndWithholdsSubjectAttachment() {
    String source="foreign investment and the explosive economic growth that would follow deng reduced the share of gdp that was focused on the pla";
    List<RebuildReview.Issue> risks=issues(source,"外资以及随之而来的经济爆发式增长，降低了国内生产总值中用于解放军的份额");
    assertTrue(risks.stream().anyMatch(i->i.code.equals("possible_subject_attachment")&&i.repair));
    RebuildProtocol.Event event=new RebuildProtocol.Event(0,21,0,8000,"外资以及随之而来的经济爆发式增长，降低了国内生产总值中用于解放军的份额");
    RebuildProtocol.Plan plan=new RebuildProtocol.Plan(Collections.singletonList(event),"{}",risks);
    assertTrue(RebuildReview.shouldRepair(plan,1,0,0,8000));
    assertTrue(RebuildReview.semanticBlocked(plan,event));
    assertFalse(RebuildReview.shouldRepair(plan,3,2,0,8000));
  }

  @Test public void licensedCopiesMustRetainTheHeadNoun() {
    String source="were either licensed or unlicensed copies or derivatives of soviet designs whether";
    assertTrue(issues(source,"无论是经授权还是未经授权，").stream().anyMatch(i->i.code.equals("possible_omission")&&i.repair));
    assertFalse(issues(source,"要么是授权或未经授权仿制苏联设计的复制品或衍生型号").stream().anyMatch(i->i.code.equals("possible_omission")));
    assertFalse(issues("whether these systems were authorized","无论是经授权还是未经授权，").stream().anyMatch(i->i.code.equals("possible_omission")));
  }

  @Test public void replayExactly540MirrorEventsThroughProductionReview() throws Exception {
    JSONObject corpus;
    try(java.io.InputStream in=getClass().getResourceAsStream("/r29/captured-events.json")) {
      corpus=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
    }
    List<RebuildSource.Word> words=new ArrayList<>();
    JSONArray raw=corpus.getJSONArray("source_word_times");
    for(int i=0;i<raw.length();i++) {
      JSONArray w=raw.getJSONArray(i);
      words.add(new RebuildSource.Word(w.getString(1),w.getLong(2),w.getLong(3),w.getInt(4),RebuildSource.Precision.valueOf(w.getString(5))));
    }
    RebuildSource source=new RebuildSource(words);
    Path root=Path.of("").toAbsolutePath();
    while(!Files.exists(root.resolve(".verification/n20-20260930/offline-layout-mirror.json"))) root=root.getParent();
    JSONArray events=new JSONObject(new String(Files.readAllBytes(root.resolve(".verification/n20-20260930/offline-layout-mirror.json")),StandardCharsets.UTF_8)).getJSONArray("events");
    assertEquals(540,events.length());
    Map<Integer,RebuildPlanner.Block> blocks=new HashMap<>();
    JSONArray bs=corpus.getJSONArray("blocks");
    for(int i=0;i<bs.length();i++) {JSONObject b=bs.getJSONObject(i);blocks.put(b.getInt("index"),new RebuildPlanner.Block(b.getInt("index"),b.getInt("from"),b.getInt("to"),source));}
    JSONObject report=new JSONObject();
    for(String code:new String[]{"possible_subject_attachment","possible_omission"}) {
      JSONArray hits=new JSONArray();
      for(int i=0;i<events.length();i++) {
        JSONObject e=events.getJSONObject(i);
        RebuildProtocol.Event event=new RebuildProtocol.Event(e.getInt("from"),e.getInt("to"),e.getLong("start"),e.getLong("end"),e.getString("text"));
        for(RebuildReview.Issue issue:RebuildReview.inspect(source,blocks.get(e.getInt("block")),Collections.singletonList(event)))
          if(issue.code.equals(code)) hits.put(new JSONObject().put("event_index",i).put("block",e.getInt("block")).put("from",event.from).put("to",event.to).put("source",source.text(event.from,event.to)).put("text",event.text));
      }
      report.put(code,new JSONObject().put("count",hits.length()).put("events",hits));
      System.out.println("N23_REVIEW "+code+" hits="+hits.length()+" events="+hits);
      assertTrue("N23 stop line: more than five hits",hits.length()<=5);
    }
    Files.createDirectories(root.resolve(".verification/n23"));
    Files.write(root.resolve(".verification/n23/review-hits.json"),report.toString(2).getBytes(StandardCharsets.UTF_8));
  }
}
