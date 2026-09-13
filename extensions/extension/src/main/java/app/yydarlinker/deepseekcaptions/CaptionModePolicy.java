package app.yydarlinker.deepseekcaptions;
final class CaptionModePolicy {
    static boolean mayCallApi(boolean sourceOnly,boolean terminalError,boolean cancelled) {
        return !sourceOnly && !terminalError && !cancelled;
    }
}
