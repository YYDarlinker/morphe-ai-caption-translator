package app.yydarlinker.patches.deepseekcaptions

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Official 1.45 settings lifecycle only; the binder visits explicit addon keys. */
internal fun BytecodePatchContext.installCaptionSettingsBindings() {
    val binding="Lapp/yydarlinker/deepseekcaptions/CaptionPreferenceBindings;"
    val fragment=mutableClassDefBy("Lapp/morphe/extension/shared/settings/preference/AbstractPreferenceFragment;")
    val setting=classDefBy("Lapp/morphe/extension/shared/settings/BaseSettings;")
        .fields.singleOrNull { it.name=="MORPHE_LANGUAGE" && it.type=="Lapp/morphe/extension/shared/settings/EnumSetting;" }
        ?: throw PatchException("Caption UI: official MORPHE_LANGUAGE ABI missing")
    if(!AccessFlags.PUBLIC.isSet(setting.accessFlags) || !AccessFlags.STATIC.isSet(setting.accessFlags))
        throw PatchException("Caption UI: official language field inaccessible")
    for((type,name,result) in listOf(
        Triple("EnumSetting","get","Ljava/lang/Enum;"),
        Triple("AppLanguage","getLocale","Ljava/util/Locale;")
    )) {
        val method=classDefBy("Lapp/morphe/extension/shared/settings/$type;").methods.singleOrNull {
            it.name==name && it.parameterTypes.isEmpty() && it.returnType==result
        } ?: throw PatchException("Caption UI: official $type.$name ABI missing")
        if(!AccessFlags.PUBLIC.isSet(method.accessFlags))throw PatchException("Caption UI: language method inaccessible")
    }
    // At lifecycle entry p0 is still the Fragment receiver. Never read it at a return:
    // R8 reuses that physical register for a PreferenceScreen, boolean or exception.
    for(name in listOf("onCreateView","onPreferenceTreeClick")) {
        val method=fragment.methods.singleOrNull { it.name==name }
            ?: throw PatchException("Caption UI: official settings lifecycle $name missing")
        if(method.implementation==null || AccessFlags.STATIC.isSet(method.accessFlags))
            throw PatchException("Caption UI: settings lifecycle receiver unavailable: $name")
        method.addInstruction(0,"invoke-static/range { p0 .. p0 }, $binding->onSettingsLoaded(Landroid/preference/PreferenceFragment;)V")
    }
    // The language preference value reaches MORPHE_LANGUAGE inside updatePreference.
    // Rebind after that write, while the call's verified Fragment receiver is still live.
    val languageCallback=fragment.methods.singleOrNull { it.name=="lambda\$new\$4" }
        ?: throw PatchException("Caption UI: official language change callback missing")
    if(languageCallback.implementation==null || AccessFlags.STATIC.isSet(languageCallback.accessFlags))
        throw PatchException("Caption UI: language callback receiver unavailable")
    val callbackCode=languageCallback.implementation!!.instructions.toList()
    val updates=callbackCode.mapIndexedNotNull { index,ins ->
        val ref=(ins as? ReferenceInstruction)?.reference as? MethodReference
        if(ref?.name=="updatePreference" && ref.definingClass==fragment.type &&
            ref.parameterTypes.map { it.toString() }==listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;","Z","Z") &&
            ref.returnType=="V")index else null
    }
    if(updates.size!=1)throw PatchException("Caption UI: language preference update must match exactly once")
    val updateIndex=updates.single()
    val update=callbackCode[updateIndex] as? com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
        ?: throw PatchException("Caption UI: language update receiver register unavailable")
    val next=callbackCode.getOrNull(updateIndex+1)
    val nextCall=(next as? ReferenceInstruction)?.reference as? MethodReference
    val nextRegisters=next as? com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
    if(update.registerCount!=5 || nextCall?.name!="updateUIAvailability" ||
        nextCall.definingClass!=fragment.type || nextRegisters?.registerC!=update.registerC)
        throw PatchException("Caption UI: language update receiver lifetime is unsupported")
    languageCallback.addInstruction(updateIndex+1,
        "invoke-static/range { v${update.registerC} .. v${update.registerC} }, $binding->onSettingsLoaded(Landroid/preference/PreferenceFragment;)V")
    // Bind after the actual tree is loaded. The move-result register has a verified
    // PreferenceScreen type and is safe even when it aliases the original receiver register.
    val initialize=fragment.methods.singleOrNull { it.name=="initialize" && it.parameterTypes.isEmpty() }
        ?: throw PatchException("Caption UI: official settings initializer missing")
    val code=initialize.implementation?.instructions?.toList()
        ?: throw PatchException("Caption UI: settings initializer has no body")
    val screens=code.mapIndexedNotNull { index,ins ->
        val ref=(ins as? ReferenceInstruction)?.reference as? MethodReference
        if(ref?.name=="getPreferenceScreen" && ref.parameterTypes.isEmpty() &&
            ref.returnType=="Landroid/preference/PreferenceScreen;")index else null
    }
    if(screens.size!=1)throw PatchException("Caption UI: settings tree getter must match exactly once")
    val getter=screens.single()
    val result=code.getOrNull(getter+1)
    if(result?.opcode!=Opcode.MOVE_RESULT_OBJECT || result !is OneRegisterInstruction)
        throw PatchException("Caption UI: settings tree result register unavailable")
    val screenRegister=result.registerA
    initialize.addInstruction(getter+2,"invoke-static/range { v$screenRegister .. v$screenRegister }, $binding->rebind(Landroid/preference/PreferenceGroup;)V")
    val create=fragment.methods.single { it.name=="onCreateView" }
    val returns=create.implementation!!.instructions.mapIndexedNotNull { index,ins ->
        if(ins.opcode==Opcode.RETURN_OBJECT)index to (ins as OneRegisterInstruction).registerA else null
    }.toList()
    returns.asReversed().forEach { (index,register) ->
        create.addInstruction(index,"invoke-static/range { v$register .. v$register }, $binding->onSettingsView(Landroid/view/View;)V")
    }
}
