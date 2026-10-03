package app.yydarlinker.patches.deepseekcaptions

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Official 1.45 ABI: refresh at typed load/write/view seams, never during row dispatch. */
internal fun BytecodePatchContext.installCaptionSettingsBindings() {
    val binding="Lapp/yydarlinker/deepseekcaptions/CaptionPreferenceBindings;"
    val fragment=mutableClassDefBy("Lapp/morphe/extension/shared/settings/preference/AbstractPreferenceFragment;")
    if(fragment.accessFlags != (AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value) ||
        fragment.superclass!="Landroid/preference/PreferenceFragment;")
        throw PatchException("Caption UI: unsupported official settings fragment ABI")
    fun method(name:String,parameters:List<String>,result:String,flags:Int) = fragment.methods.singleOrNull {
        it.name==name && it.parameterTypes.map { p->p.toString() }==parameters && it.returnType==result
    }?.also {
        if(it.accessFlags!=flags || it.implementation==null)
            throw PatchException("Caption UI: unsupported official settings method access/body: $name")
    } ?: throw PatchException("Caption UI: official settings descriptor missing/nonunique: $name$parameters$result")
    fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.call()=
        (this as? ReferenceInstruction)?.reference as? MethodReference

    val settingClass=classDefBy("Lapp/morphe/extension/shared/settings/BaseSettings;")
    val language=settingClass.fields.singleOrNull {
        it.name=="MORPHE_LANGUAGE" && it.type=="Lapp/morphe/extension/shared/settings/EnumSetting;"
    } ?: throw PatchException("Caption UI: official MORPHE_LANGUAGE ABI missing")
    if(settingClass.accessFlags!=AccessFlags.PUBLIC.value || language.accessFlags!=
        (AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value))
        throw PatchException("Caption UI: official language field inaccessible")
    for((type,name,result) in listOf(Triple("EnumSetting","get","Ljava/lang/Enum;"),
        Triple("AppLanguage","getLocale","Ljava/util/Locale;"))) {
        val owner=classDefBy("Lapp/morphe/extension/shared/settings/$type;")
        val target=owner.methods.singleOrNull {
            it.name==name && it.parameterTypes.isEmpty() && it.returnType==result
        } ?: throw PatchException("Caption UI: official $type.$name ABI missing")
        if(!AccessFlags.PUBLIC.isSet(owner.accessFlags) || target.accessFlags!=AccessFlags.PUBLIC.value)
            throw PatchException("Caption UI: language method inaccessible or unsupported")
    }
    val initialize=method("initialize",emptyList(),"V",AccessFlags.PUBLIC.value)
    val create=method("onCreateView",listOf("Landroid/view/LayoutInflater;","Landroid/view/ViewGroup;","Landroid/os/Bundle;"),
        "Landroid/view/View;",AccessFlags.PUBLIC.value)
    // Validate dispatch ABI, but leave its root, adapter, listeners and route untouched.
    method("onPreferenceTreeClick",listOf("Landroid/preference/PreferenceScreen;","Landroid/preference/Preference;"),
        "Z",AccessFlags.PUBLIC.value)
    val callback=method("lambda\$new\$4",listOf("Landroid/content/SharedPreferences;","Ljava/lang/String;"),
        "V",AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value)
    val confirmed=method("lambda\$showSettingUserDialogConfirmation\$5",
        listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;","Landroid/content/Context;"),
        "V",AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value)
    val updateParameters=listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;","Z","Z")
    val updateMethod=method("updatePreference",updateParameters,"V",AccessFlags.PRIVATE.value)
    val syncParameters=listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;","Z")
    val sync=method("syncSettingWithPreference",syncParameters,"V",AccessFlags.PUBLIC.value)
    method("updateUIAvailability",emptyList(),"V",AccessFlags.PUBLIC.value)
    val writeOwner=classDefBy("Lapp/morphe/extension/shared/settings/Setting;")
    val writeMethod=writeOwner.methods.singleOrNull {
        it.name=="privateSetValueFromString" && it.parameterTypes.map { p->p.toString() }==
            listOf(writeOwner.type,"Ljava/lang/String;") && it.returnType=="V"
    } ?: throw PatchException("Caption UI: official setting write ABI missing/nonunique")
    if(!AccessFlags.PUBLIC.isSet(writeOwner.accessFlags) || writeMethod.accessFlags!=
        (AccessFlags.PUBLIC.value or AccessFlags.STATIC.value))
        throw PatchException("Caption UI: official setting write inaccessible")
    val syncCalls=updateMethod.implementation!!.instructions.filter { it.call()?.let { ref->
        ref.definingClass==fragment.type && ref.name==sync.name && ref.parameterTypes.map { p->p.toString() }==syncParameters && ref.returnType=="V"
    }==true }.toList()
    if(syncCalls.size!=1)throw PatchException("Caption UI: official preference write delegation unsupported")
    val syncCode=sync.implementation!!.instructions.toList()
    val valueWrites=syncCode.indices.filter { i->syncCode[i].call()?.let { ref->
        ref.definingClass=="Lapp/morphe/extension/shared/settings/Setting;" && ref.name=="privateSetValueFromString" &&
            ref.parameterTypes.map { p->p.toString() }==listOf("Lapp/morphe/extension/shared/settings/Setting;","Ljava/lang/String;") && ref.returnType=="V"
    }==true && syncCode.getOrNull(i-2)?.call()?.let { ref->
        ref.definingClass=="Landroid/preference/ListPreference;" && ref.name=="getValue" && ref.parameterTypes.isEmpty() && ref.returnType=="Ljava/lang/String;"
    }==true && syncCode.getOrNull(i-1)?.opcode==Opcode.MOVE_RESULT_OBJECT }
    if(valueWrites.size!=1)throw PatchException("Caption UI: official list language write seam missing/nonunique")

    // A nondefault language with userDialogMessage returns from lambda$new$4 before writing.
    // Confirm writes in lambda$showSettingUserDialogConfirmation$5; both paths refresh only
    // after updatePreference has completed while its actual Fragment receiver is still live.
    for ((writeCallback, label) in listOf(callback to "language", confirmed to "confirmation")) {
        val callbackCode=writeCallback.implementation!!.instructions.toList()
        val updates=callbackCode.indices.filter { i->callbackCode[i].call()?.let { ref->
            ref.definingClass==fragment.type && ref.name==updateMethod.name &&
                ref.parameterTypes.map { p->p.toString() }==updateParameters && ref.returnType=="V"
        }==true }
        if(updates.size!=1)throw PatchException("Caption UI: $label preference update must match exactly once")
        val at=updates.single()
        val invocation=callbackCode[at] as? FiveRegisterInstruction
            ?: throw PatchException("Caption UI: unsupported $label write invocation form")
        val next=callbackCode.getOrNull(at+1)
        val nextRegisters=next as? FiveRegisterInstruction
        if(callbackCode[at].opcode!=Opcode.INVOKE_DIRECT || invocation.registerCount!=5 ||
            next?.opcode!=Opcode.INVOKE_VIRTUAL ||
            next?.call()?.let { ref->ref.definingClass==fragment.type && ref.name=="updateUIAvailability" &&
                ref.parameterTypes.isEmpty() && ref.returnType=="V" }!=true ||
            nextRegisters?.registerCount!=1 || nextRegisters.registerC!=invocation.registerC)
            throw PatchException("Caption UI: $label write receiver lifetime is unsupported")
        writeCallback.addInstruction(at+1,"invoke-static/range { v${invocation.registerC} .. v${invocation.registerC} }, $binding->onSettingsLoaded(Landroid/preference/PreferenceFragment;)V")
    }

    // R8 replaces initialize's receiver with the screen. Consume that typed result directly.
    val code=initialize.implementation!!.instructions.toList()
    val getters=code.indices.filter { i->code[i].call()?.let { ref->
        ref.definingClass=="Landroid/preference/PreferenceFragment;" && ref.name=="getPreferenceScreen" &&
            ref.parameterTypes.isEmpty() && ref.returnType=="Landroid/preference/PreferenceScreen;"
    }==true }
    if(getters.size!=1)throw PatchException("Caption UI: settings tree getter missing/nonunique")
    val getter=getters.single()
    val result=code.getOrNull(getter+1) as? OneRegisterInstruction
    if(code.getOrNull(getter+1)?.opcode!=Opcode.MOVE_RESULT_OBJECT || result==null)
        throw PatchException("Caption UI: settings tree typed result unavailable")
    initialize.addInstruction(getter+2,"invoke-static/range { v${result.registerA} .. v${result.registerA} }, $binding->rebind(Landroid/preference/PreferenceGroup;)V")
    // p0 is known to be Fragment only at method entry. No return-site p0 assumptions.
    create.addInstruction(0,"invoke-static/range { p0 .. p0 }, $binding->onSettingsLoaded(Landroid/preference/PreferenceFragment;)V")
    val returns=create.implementation!!.instructions.mapIndexedNotNull { i,ins->
        if(ins.opcode==Opcode.RETURN_OBJECT)i to (ins as OneRegisterInstruction).registerA else null
    }.toList()
    if(returns.size!=1)throw PatchException("Caption UI: unsupported settings view return count")
    val (returnAt,viewRegister)=returns.single()
    create.addInstruction(returnAt,"invoke-static/range { v$viewRegister .. v$viewRegister }, $binding->onSettingsView(Landroid/view/View;)V")
}
