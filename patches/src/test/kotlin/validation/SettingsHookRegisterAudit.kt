package validation

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File

/** Validate actual injection register types and placement, rather than hook names alone. */
internal fun verifySettingsHookRegisters(classes:Map<String,ClassDef>) {
    val fragment=classes.getValue("Lapp/morphe/extension/shared/settings/preference/AbstractPreferenceFragment;")
    val binding="Lapp/yydarlinker/deepseekcaptions/CaptionPreferenceBindings;"
    fun Instruction.call()=(this as? ReferenceInstruction)?.reference as? MethodReference
    val initializer=fragment.methods.single { it.name=="initialize" }
    val code=initializer.implementation!!.instructions.toList()
    check(code.none { it.call()?.let { ref->ref.definingClass==binding && ref.name=="onSettingsLoaded" }==true }) {
        "SETTINGS_RECEIVER_REUSED: initializer tail must not pass its overwritten p0 as Fragment"
    }
    val hooks=code.indices.filter { code[it].call()?.let { ref->ref.definingClass==binding && ref.name=="rebind" }==true }
    check(hooks.size==1){"SETTINGS_TYPED_TREE_RESULT_MISSING"}
    val at=hooks.single()
    check(at>=2){"SETTINGS_TYPED_TREE_RESULT_INVALID"}
    val getter=code[at-2].call()
    val result=code[at-1] as? OneRegisterInstruction
    val invoke=code[at] as? RegisterRangeInstruction
    check(getter?.name=="getPreferenceScreen" && getter.parameterTypes.isEmpty() &&
        getter.returnType=="Landroid/preference/PreferenceScreen;" &&
        code[at-1].opcode==Opcode.MOVE_RESULT_OBJECT && result!=null && invoke!=null &&
        invoke.startRegister==result.registerA && invoke.registerCount==1 &&
        code[at].call()!!.parameterTypes.map { it.toString() }==listOf("Landroid/preference/PreferenceGroup;")) {
        "SETTINGS_TYPED_TREE_RESULT_INVALID: rebind must consume the getter's typed move-result"
    }
    for(name in listOf("onCreateView","onPreferenceTreeClick")) {
        val method=fragment.methods.single { it.name==name }
        check(!AccessFlags.STATIC.isSet(method.accessFlags)){"SETTINGS_NONINSTANCE_RECEIVER: $name"}
        val implementation=method.implementation!!
        val instructions=implementation.instructions.toList()
        val entries=instructions.indices.filter { instructions[it].call()?.let { ref->ref.definingClass==binding && ref.name=="onSettingsLoaded" }==true }
        check(entries==listOf(0)){"SETTINGS_RECEIVER_REUSED: $name must bind at entry, found $entries"}
        val parameters=method.parameterTypes.sumOf { if(it.toString() in listOf("J","D"))2 else 1 }
        val receiver=implementation.registerCount-parameters-1
        val entry=instructions[0] as? RegisterRangeInstruction
        check(entry!=null && entry.startRegister==receiver && entry.registerCount==1 &&
            instructions[0].call()!!.parameterTypes.map { it.toString() }==listOf("Landroid/preference/PreferenceFragment;")) {
            "SETTINGS_RECEIVER_REGISTER_INVALID: $name"
        }
    }
    val callback=fragment.methods.single { it.name=="lambda\$new\$4" }
    check(!AccessFlags.STATIC.isSet(callback.accessFlags)){"SETTINGS_NONINSTANCE_RECEIVER: language change"}
    val languageCode=callback.implementation!!.instructions.toList()
    val languageHooks=languageCode.indices.filter { languageCode[it].call()?.let { ref->ref.definingClass==binding && ref.name=="onSettingsLoaded" }==true }
    check(languageHooks.size==1){"SETTINGS_LANGUAGE_UPDATE_HOOK_MISSING"}
    val changeAt=languageHooks.single()
    check(changeAt>0){"SETTINGS_LANGUAGE_REBIND_BEFORE_WRITE"}
    val updateRef=languageCode[changeAt-1].call()
    val updateRegisters=languageCode[changeAt-1] as? FiveRegisterInstruction
    val bindRegisters=languageCode[changeAt] as? RegisterRangeInstruction
    val availabilityRef=languageCode.getOrNull(changeAt+1)?.call()
    val availabilityRegisters=languageCode.getOrNull(changeAt+1) as? FiveRegisterInstruction
    check(updateRef?.name=="updatePreference" && updateRef.definingClass==fragment.type &&
        updateRef.parameterTypes.map { it.toString() }==listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;","Z","Z") &&
        updateRegisters?.registerCount==5 && bindRegisters?.registerCount==1 &&
        bindRegisters.startRegister==updateRegisters.registerC &&
        availabilityRef?.name=="updateUIAvailability" && availabilityRef.definingClass==fragment.type &&
        availabilityRegisters?.registerC==updateRegisters.registerC) {
        "SETTINGS_LANGUAGE_REBIND_TIMING_OR_RECEIVER_INVALID"
    }
    val create=fragment.methods.single { it.name=="onCreateView" }.implementation!!.instructions.toList()
    val listEntries=create.indices.filter { create[it].call()?.let { ref->ref.definingClass==binding && ref.name=="onSettingsView" }==true }
    check(listEntries.size==1){"SETTINGS_VIEW_HOOK_MISSING"}
    val listAt=listEntries.single()
    val returned=create.getOrNull(listAt+1) as? OneRegisterInstruction
    val listInvoke=create[listAt] as? RegisterRangeInstruction
    check(create.getOrNull(listAt+1)?.opcode==Opcode.RETURN_OBJECT && returned!=null && listInvoke!=null &&
        listInvoke.startRegister==returned.registerA && listInvoke.registerCount==1){"SETTINGS_VIEW_REGISTER_INVALID"}
    println("SETTINGS_HOOK_REGISTER_TYPES_PASS typed_tree_result=1 receiver_entry_hooks=2 language_after_write=1 typed_view_return=1")
}

/** The same register predicate must reject the actual installed bad APK and accept the rebuilt APK. */
fun main(args:Array<String>) {
    fun load(path:String):Map<String,ClassDef> {
        val container=DexFileFactory.loadDexContainer(File(path),Opcodes.getDefault())
        val classes=mutableMapOf<String,ClassDef>()
        for(name in container.dexEntryNames)for(cls in container.getEntry(name)!!.dexFile.classes)
            check(classes.put(cls.type,cls)==null){"Duplicate class: "+cls.type}
        return classes
    }
    var rejected=false
    try { verifySettingsHookRegisters(load(args[0])) } catch(error:IllegalStateException) {
        check(error.message?.startsWith("SETTINGS_RECEIVER_REUSED")==true){"Unexpected before rejection: "+error.message}
        rejected=true
        println("SETTINGS_OLD_REGISTER_REUSE_REJECTED "+error.message)
    }
    check(rejected){"Faulty installed settings injection incorrectly passed"}
    if(args.size>2) {
        var timingRejected=false
        try { verifySettingsHookRegisters(load(args[2])) } catch(error:IllegalStateException) {
            check(error.message=="SETTINGS_LANGUAGE_REBIND_BEFORE_WRITE"){"Unexpected timing control rejection: "+error.message}
            timingRejected=true
            println("SETTINGS_PREWRITE_CALLBACK_REJECTED "+error.message)
        }
        check(timingRejected){"Prewrite callback candidate incorrectly passed"}
    }
    verifySettingsHookRegisters(load(args[1]))
    println("SETTINGS_REGISTER_REGRESSION_PASS")
}
