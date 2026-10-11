package app.yydarlinker.deepseekcaptions;

/** Explicit display-only newline and its reversible canonical range mapping. */
final class CaptionLinePlan {
    enum CutClass { MODEL_PHRASE, LANGUAGE_BOUNDARY, LONG_CLAUSE, UNICODE, EMERGENCY }
    final int fromUtf16,toUtf16,lineCutUtf16;
    final String displayText;
    final float line1Px,line2Px;
    final double line1Units,line2Units;
    final CutClass cutClass;
    final boolean generatedNewline;
    CaptionLinePlan(String canonical,int from,int to,int cut,float first,float second,CutClass type){
        this(canonical,from,to,cut,first,second,type,0,0);
    }
    CaptionLinePlan(String canonical,int from,int to,int cut,float first,float second,CutClass type,double firstUnits,double secondUnits){
        fromUtf16=from;toUtf16=to;lineCutUtf16=cut;line1Px=first;line2Px=second;cutClass=type;
        line1Units=firstUnits;line2Units=secondUnits;
        generatedNewline=cut>from&&cut<to&&canonical.charAt(cut-1)!='\n'&&canonical.charAt(cut)!='\n';
        displayText=generatedNewline?canonical.substring(from,cut)+"\n"+canonical.substring(cut,to):canonical.substring(from,to);
    }
    int canonicalOffset(int displayOffset){
        int newline=lineCutUtf16-fromUtf16;
        return fromUtf16+displayOffset-(generatedNewline&&displayOffset>newline?1:0);
    }
    String canonicalText(){return generatedNewline?displayText.substring(0,lineCutUtf16-fromUtf16)+displayText.substring(lineCutUtf16-fromUtf16+1):displayText;}
    float fill(int width){return Math.max(line1Px,line2Px)/Math.max(1,width);}
}
