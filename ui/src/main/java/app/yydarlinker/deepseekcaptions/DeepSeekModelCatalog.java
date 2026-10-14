package app.yydarlinker.deepseekcaptions;

import java.util.List;

final class DeepSeekModelCatalog {
    static List<String> fetch(String endpoint) throws Exception {
        return CaptionActions.models(endpoint);
    }
}
