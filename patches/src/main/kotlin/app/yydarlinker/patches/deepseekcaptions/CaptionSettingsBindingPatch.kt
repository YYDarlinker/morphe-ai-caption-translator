package app.yydarlinker.patches.deepseekcaptions

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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
    for(name in listOf("initialize","onCreateView","onPreferenceTreeClick","lambda\$new\$4")) {
        val method=fragment.methods.singleOrNull { it.name==name }
            ?: throw PatchException("Caption UI: official settings lifecycle $name missing")
        val hook="invoke-static/range { p0 .. p0 }, $binding->onSettingsLoaded(Landroid/preference/PreferenceFragment;)V"
        if(name=="onCreateView" || name=="onPreferenceTreeClick")method.addInstruction(0,hook)
        else {
            val exits=method.implementation?.instructions?.mapIndexedNotNull { index, ins ->
                if(ins.opcode==Opcode.RETURN_VOID)index else null
            }?.toList() ?: throw PatchException("Caption UI: settings lifecycle has no body")
            exits.asReversed().forEach { method.addInstruction(it,hook) }
        }
    }
    val create=fragment.methods.single { it.name=="onCreateView" }
    val returns=create.implementation!!.instructions.mapIndexedNotNull { index,ins ->
        if(ins.opcode==Opcode.RETURN_OBJECT)index to (ins as OneRegisterInstruction).registerA else null
    }.toList()
    returns.asReversed().forEach { (index,register) ->
        create.addInstruction(index,"invoke-static/range { v$register .. v$register }, $binding->onSettingsView(Landroid/view/View;)V")
    }
}
