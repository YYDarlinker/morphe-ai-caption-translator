package app.yydarlinker.deepseekcaptions;

import android.net.Uri;
import java.util.Locale;

/** Per-Session observation, not a strategy decision. No UI-language or text-script inference. */
final class CaptionLanguageContext {
  final String sourceCode, targetCode, sourceProvenance;
  final CaptionLanguageProfile profile;
  final boolean canApplyEnglishToChinese, chineseFamily;
  private CaptionLanguageContext(String source, String target) {
    sourceCode=CaptionLanguageProfile.normalizeCode(source);
    targetCode=CaptionLanguageProfile.normalizeCode(target);
    sourceProvenance=sourceCode.equals("UNKNOWN") ? "UNKNOWN" : "url_lang";
    profile=CaptionLanguageProfile.fromCode(targetCode);
    chineseFamily=Locale.forLanguageTag(targetCode).getLanguage().equals("zh");
    canApplyEnglishToChinese=Locale.forLanguageTag(sourceCode).getLanguage().equals("en")
        && (profile.id.equals("zh-Hans") || profile.id.equals("zh-Hant"));
  }
  static CaptionLanguageContext observe(String url, String confirmedSessionTarget) {
    String source=null;
    try {
      Uri uri=Uri.parse(CaptionEngine.sourceCaptionUrl(url));
      // Ambiguous duplicate lang parameters cannot establish one actual source code.
      java.util.List<String> values=uri.getQueryParameters("lang");
      if(values.size()==1) source=values.get(0);
    } catch(Exception ignored) { /* Missing/invalid URL is unknown; never infer from tlang. */ }
    return new CaptionLanguageContext(source,confirmedSessionTarget);
  }
  String diagnosticFields() {
    return "source_code="+sourceCode+";target_code="+targetCode+";profile_id="+profile.id
        +";policy_version="+profile.policyVersion+";source_provenance="+sourceProvenance
        +";reading_counter="+profile.readingCounterId()+";line_counter="+profile.lineCounterId()
        +";direction="+profile.direction+";strategy=legacy_unchanged";
  }
}
