package app.yydarlinker.deepseekcaptions;
final class SubtitleStyleMetrics {
    static int alpha(int opacity){return Math.round(255f*Math.max(0,Math.min(100,opacity))/100f);}
    static float renderedSp(float configured,float videoWidthPx,float normalVideoWidthPx){
        if (normalVideoWidthPx <= 0 || videoWidthPx >= normalVideoWidthPx * .8f)
            return configured;
        // The measured video frame contracts while the same player remains active (comments).
        return Math.max(12f,configured * videoWidthPx / normalVideoWidthPx);
    }
    static float previewTextPx(float configured,float actualVideoWidthPx,float scaledDensity,float previewWidth){
        return configured*scaledDensity*previewWidth/Math.max(1,actualVideoWidthPx);
    }
}
