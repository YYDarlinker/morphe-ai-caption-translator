package validation

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

internal const val SETTINGS_FRAGMENT="Lapp/morphe/extension/shared/settings/preference/AbstractPreferenceFragment;"
internal const val SETTINGS_BINDING="Lapp/yydarlinker/deepseekcaptions/CaptionPreferenceBindings;"
internal fun Instruction.settingsCall()=(this as? ReferenceInstruction)?.reference as? MethodReference
private fun MethodReference.signature()=definingClass+"->"+name+"("+parameterTypes.joinToString("")+")"+returnType
private fun Instruction.arguments():List<Int> = when(this) {
    is RegisterRangeInstruction -> (startRegister until startRegister+registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC,registerD,registerE,registerF,registerG).take(registerCount)
    else -> error("SETTINGS_INVOKE_FORMAT_INVALID")
}

/** Final serialized ABI, control-flow and register provenance checks shared by all compositions. */
internal fun auditSettingsComposition(classes:Map<String,ClassDef>) {
    val fragment=classes.getValue(SETTINGS_FRAGMENT)
    check(fragment.accessFlags==(AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value) &&
        fragment.superclass=="Landroid/preference/PreferenceFragment;"){"SETTINGS_FRAGMENT_ABI_INVALID"}
    fun method(name:String,params:List<String>,result:String,flags:Int):Method = fragment.methods.singleOrNull {
        it.name==name && it.parameterTypes.map { p->p.toString() }==params && it.returnType==result
    }?.also { check(it.accessFlags==flags && it.implementation!=null){"SETTINGS_METHOD_ABI_INVALID: $it"} }
        ?: error("SETTINGS_METHOD_DESCRIPTOR_MISSING: $name$params$result")
    val initialize=method("initialize",emptyList(),"V",1)
    val create=method("onCreateView",listOf("Landroid/view/LayoutInflater;","Landroid/view/ViewGroup;","Landroid/os/Bundle;"),"Landroid/view/View;",1)
    val click=method("onPreferenceTreeClick",listOf("Landroid/preference/PreferenceScreen;","Landroid/preference/Preference;"),"Z",1)
    val callback=method("lambda\$new\$4",listOf("Landroid/content/SharedPreferences;","Ljava/lang/String;"),"V",4098)
    val confirmed=method("lambda\$showSettingUserDialogConfirmation\$5",
        listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;","Landroid/content/Context;"),"V",4098)
    val cancelled=method("lambda\$showSettingUserDialogConfirmation\$6",
        listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;"),"V",4098)
    // Verify every binder argument before checking placement: this rejects the original
    // N31 register reuse through all normal and exception paths, without special cases.
    listOf(initialize,create,click,callback,confirmed,cancelled).forEach { auditSettingsRegisterFlow(it,classes) }
    fun Method.hooks(name:String)=implementation!!.instructions.toList().indices.filter { i->
        implementation!!.instructions.toList()[i].settingsCall()?.let { ref->ref.definingClass==SETTINGS_BINDING && ref.name==name }==true
    }
    val initCode=initialize.implementation!!.instructions.toList()
    check(initialize.hooks("onSettingsLoaded").isEmpty()){ "SETTINGS_INITIALIZER_FRAGMENT_HOOK_FORBIDDEN" }
    val trees=initialize.hooks("rebind")
    check(trees.size==1){"SETTINGS_TYPED_TREE_RESULT_MISSING"}
    val at=trees.single()
    check(at>=2 && initCode[at-2].settingsCall()?.signature()==
        "Landroid/preference/PreferenceFragment;->getPreferenceScreen()Landroid/preference/PreferenceScreen;" &&
        initCode[at-1].opcode==Opcode.MOVE_RESULT_OBJECT &&
        initCode[at].arguments()==listOf((initCode[at-1] as OneRegisterInstruction).registerA)){
        "SETTINGS_TYPED_TREE_RESULT_INVALID"
    }
    check(create.hooks("onSettingsLoaded")==listOf(0)){"SETTINGS_ENTRY_HOOK_INVALID"}
    val receiver=create.implementation!!.registerCount-create.parameterTypes.sumOf { if(it.toString() in listOf("J","D"))2 else 1 }-1
    check(create.implementation!!.instructions.first().arguments()==listOf(receiver)){"SETTINGS_ENTRY_RECEIVER_INVALID"}
    check(click.implementation!!.instructions.none { it.settingsCall()?.definingClass==SETTINGS_BINDING }){
        "SETTINGS_CLICK_WHOLE_TREE_REBIND_FORBIDDEN"
    }
    val views=create.hooks("onSettingsView")
    val viewCode=create.implementation!!.instructions.toList()
    check(views.size==1 && views.single()+1<viewCode.size && viewCode[views.single()+1].opcode==Opcode.RETURN_OBJECT &&
        viewCode[views.single()].arguments()==listOf((viewCode[views.single()+1] as OneRegisterInstruction).registerA)){
        "SETTINGS_VIEW_RETURN_INVALID"
    }
    val updateDescriptor="$SETTINGS_FRAGMENT->updatePreference(Landroid/preference/Preference;Lapp/morphe/extension/shared/settings/Setting;ZZ)V"
    for ((writeCallback, label) in listOf(callback to "LANGUAGE", confirmed to "CONFIRMATION")) {
        val languageCode=writeCallback.implementation!!.instructions.toList()
        val languageHooks=writeCallback.hooks("onSettingsLoaded")
        check(languageHooks.size==1){"SETTINGS_${label}_HOOK_COUNT_INVALID"}
        val languageAt=languageHooks.single()
        val updates=languageCode.indices.filter { languageCode[it].settingsCall()?.signature()==updateDescriptor }
        check(updates.size==1 && languageAt==updates.single()+1){"SETTINGS_${label}_REBIND_BEFORE_WRITE"}
        val update=languageCode[languageAt-1] as? FiveRegisterInstruction
        val availability=languageCode.getOrNull(languageAt+1)
        check(languageCode[languageAt-1].opcode==Opcode.INVOKE_DIRECT && update?.registerCount==5 &&
            languageCode[languageAt].arguments()==listOf(update.registerC) &&
            availability?.opcode==Opcode.INVOKE_VIRTUAL &&
            availability?.settingsCall()?.signature()=="$SETTINGS_FRAGMENT->updateUIAvailability()V" &&
            availability.arguments()==listOf(update.registerC)){"SETTINGS_${label}_RECEIVER_INVALID"}
    }
    check(cancelled.implementation!!.instructions.none { it.settingsCall()?.definingClass==SETTINGS_BINDING }) {
        "SETTINGS_CANCEL_REBIND_FORBIDDEN"
    }

    val updateMethod=method("updatePreference",listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;","Z","Z"),"V",2)
    val sync=method("syncSettingWithPreference",listOf("Landroid/preference/Preference;","Lapp/morphe/extension/shared/settings/Setting;","Z"),"V",1)
    method("updateUIAvailability",emptyList(),"V",1)
    val delegated=updateMethod.implementation!!.instructions.filter { it.settingsCall()?.signature()==
        "$SETTINGS_FRAGMENT->syncSettingWithPreference(Landroid/preference/Preference;Lapp/morphe/extension/shared/settings/Setting;Z)V" }.toList()
    check(delegated.size==1 && delegated.single().arguments()==listOf(0,1,2,4)){
        "SETTINGS_LANGUAGE_WRITE_DELEGATION_INVALID"
    }
    val syncCode=sync.implementation!!.instructions.toList()
    val writes=syncCode.indices.filter { i->syncCode[i].settingsCall()?.signature()==
        "Lapp/morphe/extension/shared/settings/Setting;->privateSetValueFromString(Lapp/morphe/extension/shared/settings/Setting;Ljava/lang/String;)V" &&
        syncCode.getOrNull(i-2)?.settingsCall()?.signature()=="Landroid/preference/ListPreference;->getValue()Ljava/lang/String;" &&
        syncCode.getOrNull(i-1)?.opcode==Opcode.MOVE_RESULT_OBJECT }
    check(writes.size==1){"SETTINGS_LANGUAGE_ACTUAL_WRITE_MISSING"}
    val write=writes.single()
    check(syncCode[write].arguments()==listOf(3,(syncCode[write-1] as OneRegisterInstruction).registerA) &&
        syncCode[write-2].arguments()==listOf(2)){
        "SETTINGS_LANGUAGE_WRITTEN_VALUE_INVALID"
    }
    val writer=classes.getValue("Lapp/morphe/extension/shared/settings/Setting;")
    check(AccessFlags.PUBLIC.isSet(writer.accessFlags) && writer.methods.single { it.name=="privateSetValueFromString" &&
        it.parameterTypes.map { p->p.toString() }==listOf(writer.type,"Ljava/lang/String;") && it.returnType=="V" }.accessFlags==9){
        "SETTINGS_LANGUAGE_WRITE_ACCESS_INVALID"
    }
    // All host binder calls are exactly these five typed seams.
    val total=fragment.methods.sumOf { m->m.implementation?.instructions?.count { it.settingsCall()?.definingClass==SETTINGS_BINDING }?:0 }
    check(total==5){"SETTINGS_UNEXPECTED_BINDER_HOOK: $total"}
    println("N32_SETTINGS_COMPOSITION_PASS typed_tree=1 fragment_entry=1 language_after_actual_write=1 confirmation_after_actual_write=1 cancel_rebind=0 view_return=1 click_rebind=0 cfg_register_types=true")
}

/** Small reference-type verifier for the actual host settings methods, including catch edges. */
private fun auditSettingsRegisterFlow(method:Method,classes:Map<String,ClassDef>) {
    val implementation=method.implementation!!
    val code=implementation.instructions.toList()
    val pcs=IntArray(code.size);var end=0
    code.forEachIndexed { i,ins->pcs[i]=end;end+=ins.codeUnits }
    val indices=pcs.withIndex().associate { it.value to it.index }
    fun index(pc:Int)=indices[pc]?:error("SETTINGS_BRANCH_NOT_INSTRUCTION_BOUNDARY: $method pc=$pc")
    code.forEachIndexed { i,ins->if(ins is OffsetInstruction)index(pcs[i]+ins.codeOffset) }
    implementation.tryBlocks.forEach { block->
        index(block.startCodeAddress)
        check(block.startCodeAddress+block.codeUnitCount==end || indices.containsKey(block.startCodeAddress+block.codeUnitCount)){
            "SETTINGS_TRY_NOT_INSTRUCTION_BOUNDARY"
        }
        block.exceptionHandlers.forEach { index(it.handlerCodeAddress) }
    }
    val androidParents=mapOf("Landroid/preference/PreferenceFragment;" to "Landroid/app/Fragment;",
        "Landroid/preference/PreferenceScreen;" to "Landroid/preference/PreferenceGroup;",
        "Landroid/preference/PreferenceGroup;" to "Landroid/preference/Preference;",
        "Landroid/widget/ListView;" to "Landroid/widget/AbsListView;",
        "Landroid/widget/AbsListView;" to "Landroid/widget/AdapterView;",
        "Landroid/widget/AdapterView;" to "Landroid/view/ViewGroup;",
        "Landroid/view/ViewGroup;" to "Landroid/view/View;")
    fun assignable(actual:String,expected:String):Boolean {
        var type:String?=actual;val visited=mutableSetOf<String>()
        while(type!=null && visited.add(type)) {
            if(type==expected)return true
            type=classes[type]?.superclass?:androidParents[type]
        }
        return false
    }
    // Keep sets of possible reaching types; unlike a generic Object merge this retains
    // the invalid boolean, exception or synthetic-lambda path at an injected call.
    val initial=Array(implementation.registerCount){setOf("unknown")}
    var parameter=implementation.registerCount-(if(AccessFlags.STATIC.isSet(method.accessFlags))0 else 1)-
        method.parameterTypes.sumOf { if(it.toString() in listOf("J","D"))2 else 1 }
    if(!AccessFlags.STATIC.isSet(method.accessFlags))initial[parameter++]=setOf(method.definingClass)
    method.parameterTypes.forEach { type->initial[parameter++]=setOf(type.toString());if(type.toString() in listOf("J","D"))initial[parameter++]=setOf("wide-tail") }
    val states=arrayOfNulls<Array<Set<String>>>(code.size)
    val queue=ArrayDeque<Int>()
    fun merge(i:Int,state:Array<Set<String>>) {
        val old=states[i]
        if(old==null){states[i]=state.map { it.toSet() }.toTypedArray();queue.add(i);return}
        var changed=false
        val joined=Array(state.size){r->(old[r]+state[r]).also { if(it!=old[r])changed=true }}
        if(changed){states[i]=joined;queue.add(i)}
    }
    merge(0,initial)
    while(queue.isNotEmpty()) {
        val i=queue.removeFirst();val ins=code[i];val before=states[i]!!;val after=before.copyOf()
        val op=ins.opcode.toString()
        val a=(ins as? OneRegisterInstruction)?.registerA
        val b=(ins as? TwoRegisterInstruction)?.registerB
        val ref=(ins as? ReferenceInstruction)?.reference
        if(ins.settingsCall()?.definingClass==SETTINGS_BINDING) {
            val call=ins.settingsCall()!!
            check(ins.opcode==Opcode.INVOKE_STATIC_RANGE && call.returnType=="V" && call.parameterTypes.size==1){"SETTINGS_BINDER_DESCRIPTOR_INVALID"}
            val expected=when(call.name){"onSettingsLoaded"->"Landroid/preference/PreferenceFragment;";"rebind"->"Landroid/preference/PreferenceGroup;";"onSettingsView"->"Landroid/view/View;";else->error("SETTINGS_BINDER_METHOD_UNKNOWN")}
            check(call.parameterTypes.single().toString()==expected){"SETTINGS_BINDER_DESCRIPTOR_INVALID"}
            val arguments=ins.arguments()
            check(arguments.size==1 && arguments.single() in before.indices){"SETTINGS_BINDER_REGISTER_INVALID"}
            val types=before[arguments.single()]
            check(types.all { assignable(it,expected) }){"SETTINGS_REGISTER_TYPE_INVALID: $method pc=${pcs[i]} v${arguments.single()} types=$types expected=$expected"}
            val owner=classes.getValue(call.definingClass)
            val target=owner.methods.singleOrNull { it.name==call.name && it.parameterTypes==call.parameterTypes && it.returnType==call.returnType }
            check(AccessFlags.PUBLIC.isSet(owner.accessFlags) && target!=null && AccessFlags.PUBLIC.isSet(target.accessFlags) && AccessFlags.STATIC.isSet(target.accessFlags)){
                "SETTINGS_BINDER_ACCESS_INVALID"
            }
        }
        if(a!=null)when {
            op.startsWith("MOVE_OBJECT") -> after[a]=before[b!!]
            op=="MOVE_RESULT_OBJECT" -> after[a]=setOf(code.getOrNull(i-1)?.settingsCall()?.returnType?:error("SETTINGS_RESULT_WITHOUT_CALL"))
            op.startsWith("MOVE_RESULT") || op.startsWith("MOVE") && op!="MOVE_EXCEPTION" -> after[a]=if(b!=null)before[b] else setOf("primitive")
            op=="MOVE_EXCEPTION" -> after[a]=setOf("Ljava/lang/Throwable;")
            op=="NEW_INSTANCE" || op=="CHECK_CAST" -> after[a]=setOf((ref as TypeReference).type)
            op.startsWith("CONST_STRING") -> after[a]=setOf("Ljava/lang/String;")
            op=="CONST_CLASS" -> after[a]=setOf("Ljava/lang/Class;")
            op.startsWith("CONST") -> after[a]=setOf("primitive")
            op=="SGET_OBJECT" || op=="IGET_OBJECT" -> after[a]=setOf((ref as FieldReference).type)
            op.startsWith("SGET") || op.startsWith("IGET") || op=="INSTANCE_OF" -> after[a]=setOf("primitive")
            else -> if(ins.opcode.setsRegister())after[a]=setOf("unknown")
        }
        val branch=ins as? OffsetInstruction
        when {
            op.startsWith("GOTO") -> merge(index(pcs[i]+branch!!.codeOffset),after)
            op.startsWith("IF_") -> {merge(index(pcs[i]+branch!!.codeOffset),after);if(i+1<code.size)merge(i+1,after)}
            op.startsWith("RETURN") || op=="THROW" -> {}
            else -> if(i+1<code.size)merge(i+1,after)
        }
        if(ins.opcode.canThrow())implementation.tryBlocks.filter { pcs[i]>=it.startCodeAddress && pcs[i]<it.startCodeAddress+it.codeUnitCount }
            .flatMap { it.exceptionHandlers }.forEach { merge(index(it.handlerCodeAddress),before) }
    }
    check(code.indices.filter { code[it].settingsCall()?.definingClass==SETTINGS_BINDING }.all { states[it]!=null }){
        "SETTINGS_UNREACHABLE_BINDER_HOOK"
    }
}
