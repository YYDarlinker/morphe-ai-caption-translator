package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.util.AttributeSet;

/** Separate recycling type; same native inline editor, never a disabled saved-value placeholder. */
public final class ApiKeyPreference extends DeepSeekTextPreference {
    public ApiKeyPreference(Context c) {
        super(c);
    }

    public ApiKeyPreference(Context c, AttributeSet a) {
        super(c, a);
    }
}
