package app.yydarlinker.deepseekcaptions;
class CaptionPresentationBudget {
    final int width;final float minimumPx,preferredPx;final CaptionRenderSpec renderSpec;
    CaptionPresentationBudget(int width,float minimum,float preferred,CaptionRenderSpec spec){this.width=width;minimumPx=minimum;preferredPx=preferred;renderSpec=spec;}
    boolean fits(String text){return text.isEmpty()||renderSpec.fits(text,minimumPx,width,2);}
    boolean fitsPreferred(String text){return text.isEmpty()||renderSpec.fits(text,preferredPx,width,2);}
    int preferredColumns(){return Math.max(1,(int)(width/Math.max(1,preferredPx)));}
    int approximateColumns(){return Math.max(1,(int)(width/Math.max(1,minimumPx)));}
}
