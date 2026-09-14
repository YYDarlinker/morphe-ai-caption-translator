package app.yydarlinker.deepseekcaptions;
final class StartupCaptionPolicy {static int targetLimit(boolean firstReady,int usual){return firstReady?Math.max(1,usual):1;}}
