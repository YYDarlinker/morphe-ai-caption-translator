package app.yydarlinker.deepseekcaptions;
/** Translate only the demanded startup unit; requestForIndices still supplies both context sides. */
final class StartupCaptionPolicy {
    static int targetLimit(boolean firstReady,int usual){return firstReady ? Math.max(1,usual) : 1;}
}
