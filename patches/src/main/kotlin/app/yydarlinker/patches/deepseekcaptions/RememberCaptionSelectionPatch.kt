package app.yydarlinker.patches.deepseekcaptions

import app.morphe.patcher.patch.bytecodePatch
import app.yydarlinker.patches.shared.Constants.YOUTUBE

@Suppress("unused")
val rememberCaptionSelectionPatch = bytecodePatch(
    name = "Remember caption selection",
    description = "Remembers caption language, source/translation mode and on/off selection across videos for this " +
        "app session, with or without AI.",
    default = true,
) {
    compatibleWith(YOUTUBE)
    dependsOn(captionSupportPatch)

    execute { CaptionFeatures.rememberSelection = true }
}
