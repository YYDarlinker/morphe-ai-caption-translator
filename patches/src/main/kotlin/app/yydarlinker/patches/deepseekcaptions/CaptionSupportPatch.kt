package app.yydarlinker.patches.deepseekcaptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch

/**
 * What the selected patches ask of the native caption flow. Each patch sets its flag in `execute`; the hooks are
 * installed once in `finalize`, when every selected patch has executed.
 */
internal object CaptionFeatures {
    var translation = false
    var rememberSelection = false
}

/** Adds the strings of the add-on, in every language that it was translated to, to the app's resources. */
private val captionStringsPatch = resourcePatch {
    execute {
        val loader = CaptionFeatures.javaClass.classLoader
        fun read(path: String) = loader.getResourceAsStream("captionlocales/$path")?.bufferedReader()?.use { it.readText() }
            ?: error("Caption locale resource missing: $path")

        for (folder in read("index.txt").lineSequence().filter { it.isNotBlank() }) {
            val destination = get("res/$folder/strings.xml")
            destination.parentFile.mkdirs()
            if (!destination.exists()) destination.writeText("<resources/>")
            document("res/$folder/strings.xml").use { target ->
                document(read("$folder/caption_addon_strings.xml").byteInputStream()).use { source ->
                    val strings = source.getElementsByTagName("string")
                    for (index in 0 until strings.length) {
                        target.documentElement.appendChild(target.importNode(strings.item(index), true))
                    }
                }
            }
        }
    }
}

/**
 * The part that every caption patch of the add-on needs: the extension itself, and the call that hands YouTube's main
 * activity to it. The native caption hooks are installed here, once, for whatever was selected.
 */
internal val captionSupportPatch = bytecodePatch {
    dependsOn(captionStringsPatch)
    extendWith("extensions/extension.mpe")

    execute {
        CaptionFeatures.translation = false
        CaptionFeatures.rememberSelection = false
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, ${EXTENSION}CaptionAddonSupport;->initialize(Landroid/app/Activity;)V",
        )
    }

    finalize {
        installNativeCaptionBridge(CaptionFeatures.translation, CaptionFeatures.rememberSelection)
    }
}
