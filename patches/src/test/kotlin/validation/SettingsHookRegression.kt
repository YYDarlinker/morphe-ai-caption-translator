package validation

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File

internal fun loadSettingsClasses(file:File):Map<String,ClassDef> {
    val container=DexFileFactory.loadDexContainer(file,Opcodes.getDefault())
    val classes=mutableMapOf<String,ClassDef>()
    container.dexEntryNames.forEach { name->container.getEntry(name)!!.dexFile.classes.forEach {
        check(classes.put(it.type,it)==null){"Duplicate class ${it.type}"}
    } }
    return classes
}

/** Read-only standalone gate: invalid old/corrupted APKs or DEX exit nonzero. */
object SettingsHookAudit {
    @JvmStatic fun main(args:Array<String>) {
        require(args.isNotEmpty()){ "Usage: SettingsHookAudit <apk-or-dex> [base-apk]" }
        val classes=if(args.size>1)loadSettingsClasses(File(args[1]))+loadSettingsClasses(File(args[0]))
            else loadSettingsClasses(File(args[0]))
        auditSettingsComposition(classes)
    }
}

/** Corrupt the final method, serialize it, reload it, and use the identical final gate. */
object SettingsHookRegression {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==3){ "Usage: SettingsHookRegression <old-before-apk> <final-apk> <negative-output-dir>" }
        val classes=loadSettingsClasses(File(args[1]))
        auditSettingsComposition(classes)
        fun reject(name:String,input:Map<String,ClassDef>,reason:String) {
            val failure=runCatching { auditSettingsComposition(input) }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message?.startsWith(reason)==true){
                "Negative $name incorrectly accepted or rejected for another reason: $failure"
            }
            println("N32_SETTINGS_NEGATIVE_REJECTED $name ${failure.message}")
        }
        reject("original-n31-installed",loadSettingsClasses(File(args[0])),"SETTINGS_REGISTER_TYPE_INVALID")
        val output=File(args[2]);output.mkdirs()
        val fragment=classes.getValue(SETTINGS_FRAGMENT)
        val callback=fragment.methods.single { it.name=="lambda\$new\$4" && it.parameterTypes.map { p->p.toString() }==listOf("Landroid/content/SharedPreferences;","Ljava/lang/String;") && it.returnType=="V" }
        val code=callback.implementation!!.instructions.toList()
        val hook=code.indices.single { code[it].settingsCall()?.let { ref->ref.definingClass==SETTINGS_BINDING && ref.name=="onSettingsLoaded" }==true }
        val update=code[hook-1] as FiveRegisterInstruction
        val ref=code[hook].settingsCall()!!
        fun serializeClass(name:String,replacement:ClassDef):Map<String,ClassDef> {
            val file=File(output,"$name.dex")
            DexPool.writeTo(file.absolutePath,ImmutableDexFile(Opcodes.getDefault(),listOf(replacement)))
            val reread=loadSettingsClasses(file)
            check(reread.getValue(replacement.type).methods.count()==replacement.methods.count())
            return classes+reread
        }
        fun serialized(name:String,source:Method,edit:(MutableMethodImplementation)->Unit):Map<String,ClassDef> {
            val body=MutableMethodImplementation(source.implementation)
            edit(body)
            val changed=ImmutableMethod(source.definingClass,source.name,source.parameters,source.returnType,
                source.accessFlags,source.annotations,source.hiddenApiRestrictions,body)
            val replacement=ImmutableClassDef(fragment.type,fragment.accessFlags,fragment.superclass,fragment.interfaces,
                fragment.sourceFile,fragment.annotations,fragment.fields,fragment.methods.map { if(it==source)changed else it })
            return serializeClass(name,replacement)
        }
        reject("bad-receiver",serialized("bad-receiver",callback) { body->
            // Preference is live here, but cannot be passed as a PreferenceFragment.
            body.replaceInstruction(hook,BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,update.registerD,1,ref))
        },"SETTINGS_REGISTER_TYPE_INVALID")
        reject("before-language-write",serialized("before-language-write",callback) { body->
            body.removeInstruction(hook)
            body.addInstruction(hook-1,BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,update.registerC,1,ref))
        },"SETTINGS_LANGUAGE_REBIND_BEFORE_WRITE")
        val confirmed=fragment.methods.single { it.name=="lambda\$showSettingUserDialogConfirmation\$5" &&
            it.parameterTypes.map { p->p.toString() }==listOf("Landroid/preference/Preference;",
                "Lapp/morphe/extension/shared/settings/Setting;","Landroid/content/Context;") && it.returnType=="V" }
        val confirmationCode=confirmed.implementation!!.instructions.toList()
        val confirmationHook=confirmationCode.indices.single { confirmationCode[it].settingsCall()?.let { call->
            call.definingClass==SETTINGS_BINDING && call.name=="onSettingsLoaded" }==true }
        val confirmationUpdate=confirmationCode[confirmationHook-1] as FiveRegisterInstruction
        val confirmationRef=confirmationCode[confirmationHook].settingsCall()!!
        reject("bad-confirmation-receiver",serialized("bad-confirmation-receiver",confirmed) { body->
            body.replaceInstruction(confirmationHook,BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                confirmationUpdate.registerD,1,confirmationRef))
        },"SETTINGS_REGISTER_TYPE_INVALID")
        reject("before-confirmation-write",serialized("before-confirmation-write",confirmed) { body->
            body.removeInstruction(confirmationHook)
            body.addInstruction(confirmationHook-1,BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                confirmationUpdate.registerC,1,confirmationRef))
        },"SETTINGS_CONFIRMATION_REBIND_BEFORE_WRITE")
        reject("missing-confirmation-hook",serialized("missing-confirmation-hook",confirmed) { body->
            body.removeInstruction(confirmationHook)
        },"SETTINGS_CONFIRMATION_HOOK_COUNT_INVALID")
        reject("unknown-binder-descriptor",serialized("unknown-binder-descriptor",confirmed) { body->
            val unknown=ImmutableMethodReference(SETTINGS_BINDING,"onSettingsLoaded",listOf("Landroid/content/Context;"),"V")
            body.replaceInstruction(confirmationHook,BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                confirmationUpdate.registerC,1,unknown))
        },"SETTINGS_BINDER_DESCRIPTOR_INVALID")
        val helper=classes.getValue(SETTINGS_BINDING)
        val target=helper.methods.single { it.name=="onSettingsLoaded" &&
            it.parameterTypes.map { p->p.toString() }==listOf("Landroid/preference/PreferenceFragment;") && it.returnType=="V" }
        check(target.accessFlags==9){"Final helper access must be public static"}
        val inaccessible=ImmutableMethod(target.definingClass,target.name,target.parameters,target.returnType,
            10,target.annotations,target.hiddenApiRestrictions,target.implementation)
        reject("inaccessible-helper",serializeClass("inaccessible-helper",ImmutableClassDef(helper.type,helper.accessFlags,
            helper.superclass,helper.interfaces,helper.sourceFile,helper.annotations,helper.fields,
            helper.methods.map { if(it==target)inaccessible else it })),"SETTINGS_BINDER_ACCESS_INVALID")
        val unsupported=ImmutableMethod(confirmed.definingClass,confirmed.name,confirmed.parameters,confirmed.returnType,
            1,confirmed.annotations,confirmed.hiddenApiRestrictions,confirmed.implementation)
        reject("unsupported-confirmation-access",serializeClass("unsupported-confirmation-access",
            ImmutableClassDef(fragment.type,fragment.accessFlags,fragment.superclass,fragment.interfaces,
                fragment.sourceFile,fragment.annotations,fragment.fields,
                fragment.methods.map { if(it==confirmed)unsupported else it })),"SETTINGS_METHOD_ABI_INVALID")
        println("N32_SETTINGS_SERIALIZED_REGRESSION_PASS negatives=9 same_final_audit=true")
    }
}
