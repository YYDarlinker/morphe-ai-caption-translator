package app.yydarlinker.deepseekcaptions;
import java.util.*;
/** Match a copied contiguous source phrase at the CURRENT cursor, never search elsewhere. */
final class SourcePhraseAlignment {
 static String canonical(String s){StringBuilder b=new StringBuilder();if(s!=null)s.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(b::appendCodePoint);return b.toString();}
 static int end(String phrase,List<SourceAtomTimeline.Atom> atoms,int from,int last){
  String target=canonical(phrase);StringBuilder actual=new StringBuilder();
  for(int i=from;i<=last;i++){
   actual.append(canonical(atoms.get(i).text));
   if(actual.toString().equals(target)){
    // Punctuation-only tails belong to the preceding phrase and must not become tiny events.
    while(i<last&&canonical(atoms.get(i+1).text).isEmpty())i++;return i;
   }
   if(actual.length()>target.length()||!target.startsWith(actual.toString()))break;
  }
  throw new IllegalArgumentException("source_phrase_mismatch;cursor="+from);
 }
}
