package app.yydarlinker.deepseekcaptions;

/** Optional upstream display join, copied at the application boundary. */
final class CaptionDisplayCandidate {
    final String text;
    final long start,end;
    CaptionDisplayCandidate(String text,long start,long end){this.text=text;this.start=start;this.end=end;}
}
