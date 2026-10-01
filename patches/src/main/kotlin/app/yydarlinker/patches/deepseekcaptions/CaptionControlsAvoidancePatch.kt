package app.yydarlinker.patches.deepseekcaptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CAPTION_HOOK_CLASS =
    "Lapp/yydarlinker/deepseekcaptions/DeepSeekCaptionHookV2;"

/**
 * N27: notify the caption overlay whenever the regular player's control visibility changes.
 *
 * <p>YouTube builds a `PlayerControlsVisibilityEntityModel` for every visibility transition. The
 * reader matched by [PlayerControlsVisibilityFingerprint] is the same anchor the official
 * player-controls patch uses, and the injection point is the same entity-model constructor the
 * official hook writes into. This patch never replaces or removes that hook: it only adds its own
 * observer, and it is added as an internal dependency of the AI caption root so no user has to
 * select an unrelated official patch.</p>
 *
 * <p>The state is carried by an already-initialised enum instance produced by the model's own
 * `(I)` factory, so nothing here guesses obfuscated field semantics: the holder field type, the
 * state field owner and the factory return type are cross-checked against each other before a
 * single instruction is written. Any mismatch throws a named [PatchException] instead of falling
 * back to a weaker match.</p>
 */
internal fun BytecodePatchContext.bindPlayerControlsVisibilityHook() {
    val matches = PlayerControlsVisibilityFingerprint.matchAll()
    if (matches.size != 1) {
        throw PatchException(
            "AI caption controls avoidance needs exactly one " +
                "getPlayerControlsVisibility(L) reader, found ${matches.size}"
        )
    }
    val method = PlayerControlsVisibilityFingerprint.method
    val matchIndex = PlayerControlsVisibilityFingerprint.instructionMatches.first().index
    val stateField = (
        method.getInstruction<ReferenceInstruction>(matchIndex).reference as? FieldReference
        ) ?: throw PatchException(
        "Player-controls visibility reader does not read an int state field at instruction $matchIndex"
    )
    if (stateField.type != "I") {
        throw PatchException(
            "Player-controls visibility state field ${stateField.definingClass}->${stateField.name} " +
                "is ${stateField.type}, not an int"
        )
    }
    val factory = (
        method.getInstruction<ReferenceInstruction>(matchIndex + 1).reference as? MethodReference
        ) ?: throw PatchException(
        "Player-controls visibility reader does not convert its state through a static factory"
    )
    if (factory.parameterTypes.map { it.toString() } != listOf("I") ||
        factory.returnType.toString() != method.returnType.toString()
    ) {
        throw PatchException(
            "Player-controls visibility factory $factory does not map int to ${method.returnType}"
        )
    }

    // The entity model stores the state holder in its own constructor; the getter reads the int
    // back out of that holder, which is why the holder field and the state field must agree.
    val model = PlayerControlsVisibilityFingerprint.classDef
    val constructor = model.methods.firstOrNull { it.name == "<init>" }
        ?: throw PatchException("Player-controls visibility model ${model.type} has no constructor")
    val implementation = constructor.implementation
        ?: throw PatchException("Player-controls visibility model constructor has no code")
    val instructions = implementation.instructions.toList()
    val stores = instructions.indices.filter {
        instructions[it].opcode == Opcode.IPUT_OBJECT &&
            ((instructions[it] as? ReferenceInstruction)?.reference as? FieldReference)
                ?.let { field -> field.type == stateField.definingClass } == true
    }
    if (stores.size != 1) {
        throw PatchException(
            "Player-controls visibility model constructor stores its state holder " +
                "${stores.size} times; exactly one store of ${stateField.definingClass} is required"
        )
    }
    val storeIndex = stores.single()
    val store = instructions[storeIndex] as TwoRegisterInstruction
    // N27r: the branch target is the real final RETURN_VOID, captured as the live
    // BuilderInstruction object before anything is inserted. The patcher relocates an
    // ExternalLabel by that object identity when the method is serialised, so the encoded
    // offset always lands on an opcode start no matter how wide the surrounding block becomes.
    val returnIndex = instructions.size - 1
    val originalReturn = instructions[returnIndex]
    if (originalReturn.opcode != Opcode.RETURN_VOID) {
        throw PatchException(
            "Player-controls visibility model constructor does not end in return-void"
        )
    }
    // Every path must still hold the state holder when the constructor finishes, so a path that
    // leaves before the store would need its own injection point and is rejected instead of guessed.
    if (instructions.subList(storeIndex + 1, instructions.size - 1)
            .any { it.opcode.name.startsWith("return") }
    ) {
        throw PatchException(
            "Player-controls visibility model constructor can return before its state holder is set"
        )
    }
    val valueRegister = store.registerA
    val ownerRegister = store.registerB
    val holderField = (store as ReferenceInstruction).reference as FieldReference

    // The observer is the last thing the constructor does. Reloading the holder keeps it correct
    // even when the official hook has already reused this register for its own enum result, so the
    // two injections can coexist in either order without the verifier seeing a mixed register type.
    // The null guard covers models built without a state holder, which YouTube's own getter tolerates.
    //
    // The fragment deliberately has no trailing label of its own: a bare label at the end of an
    // addInstructions fragment was serialised at the wrong code unit in N27
    // (`source_pc=0x10 target_pc=0x0d`, inside the official hook's operand words), and ART rejects
    // the whole class with VerifyError before any method body runs. `originalReturn` above is the
    // only target, and it is passed as an ExternalLabel so the offset is computed at serialisation.
    constructor.addInstructionsWithLabels(
        returnIndex,
        """
            iget-object v$valueRegister, v$ownerRegister, $holderField
            if-eqz v$valueRegister, :yydarlinker_caption_controls_return
            iget v$valueRegister, v$valueRegister, $stateField
            invoke-static { v$valueRegister }, $factory
            move-result-object v$valueRegister
            invoke-static { v$valueRegister }, $CAPTION_HOOK_CLASS->onPlayerControlsVisibility(Ljava/lang/Enum;)V
        """.trimIndent(),
        ExternalLabel("yydarlinker_caption_controls_return", originalReturn),
    )
}
