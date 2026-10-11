package app.yydarlinker.deepseekcaptions;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import app.yydarlinker.caption.contracts.CaptionContracts.*;
public class N38SourcePolicyTest {
 @Test public void bothSigningListsAndEncodedDuplicatesAreProtected(){
  for(String list:new String[]{"sparams","lsparams","sparams=id%2Cfmt&lsparams"}){
   String url="https://example.test/api?%66mt=srv3&fmt=vtt&xosf=1&"+list+"=id%2Cfmt%2Cxosf&sig=a%2Bb&&x=1#f";
   assertEquals(url,SourceFormatPolicy.json3(url));
   for(SourceFormatPolicy.Profile p:SourceFormatPolicy.referenceProfiles(url))assertEquals(url,p.url);
  }
 }
 @Test public void unsignedKnobsDoNotRewriteOtherRawBytes(){
  String url="https://example.test/api?id=x&xosf=1&&sig=a%2Bb&lsparams=id%2Csig&fmt=srv3#f";
  List<SourceFormatPolicy.Profile> p=SourceFormatPolicy.referenceProfiles(url);
  assertTrue(p.size()<=3);assertEquals("https://example.test/api?id=x&xosf=1&&sig=a%2Bb&lsparams=id%2Csig&fmt=json3#f",p.get(0).url);
  assertTrue(p.stream().anyMatch(x->x.name.equals("WORD_LAYOUT")&&!x.url.contains("xosf=")));
 }
 @Test public void aFewUnmatchedNativeWordsCannotBeatMatchedSegmentEvidence(){
  List<Token> words=new ArrayList<>(),reference=new ArrayList<>();
  for(int i=0;i<12;i++){words.add(new Token(i,0,"word"+i,i*100,i*100+90,Basis.ESTIMATED));reference.add(new Token(i,0,"word"+i,i*100,i*100+90,Basis.ESTIMATED));}
  reference.add(new Token(12,0,"unmatched",3000,3100,Basis.WORD));
  Source source=new Source("v","en",words);
  try{source.refineTiming(new Source("v","en",reference));fail("wrong source cannot supply matched fine timing");}catch(IllegalArgumentException expected){assertEquals("timing_content_mismatch",expected.getMessage());}
 }
 @Test public void descriptorLedgerNeverIncludesSignedOrSensitiveValues(){
  String url="https://example.test/api?v=video&%66mt=srv3&fmt=vtt&xosf=1&sparams=v%2Cfmt&lsparams=xosf%2Csig&sig=NEVER-LOG-THIS&key=PRIVATE-KEY#secret";
  SourceFormatPolicy.Evidence evidence=new SourceFormatPolicy.Evidence(url);
  assertEquals(Arrays.asList("v","fmt","xosf","sparams","lsparams","sig","key"),evidence.parameters);
  assertEquals(Arrays.asList("v","fmt","xosf","sig"),evidence.signedParameters);
  assertEquals(8,evidence.parameterCount);assertTrue(evidence.fragment);
  assertFalse(evidence.diagnostic().contains("NEVER-LOG-THIS"));assertFalse(evidence.diagnostic().contains("PRIVATE-KEY"));assertFalse(evidence.diagnostic().contains("secret"));
 }
 @Test public void threeNetworkBudgetDoesNotSpendAllSlotsOnFirstCoarseDescriptor(){
  String first="https://example.test/api?v=a&lang=en&xosf=1&sig=first",second="https://example.test/api?v=a&lang=en&xosf=1&sig=second";
  List<SourceFormatPolicy.Profile> scheduled=SourceFormatPolicy.referenceSchedule(Arrays.asList(first,second));
  assertEquals(SourceFormatPolicy.json3(first),scheduled.get(0).url);assertEquals(SourceFormatPolicy.json3(second),scheduled.get(1).url);
  assertEquals("WORD_LAYOUT",scheduled.get(2).name);assertEquals(scheduled.size(),scheduled.stream().map(p->p.url).distinct().count());
 }
}
