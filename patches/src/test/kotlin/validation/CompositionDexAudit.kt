package validation
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File

/** Read-only verification of generated DEX, including the new native reselect invocation's access. */
fun main(args:Array<String>){
    val file=File(args[0]);val container=DexFileFactory.loadDexContainer(file,Opcodes.getDefault())
    val classes=mutableMapOf<String,ClassDef>()
    container.dexEntryNames.forEach { name->container.getEntry(name)!!.dexFile.classes.forEach { cls->check(classes.put(cls.type,cls)==null){"Duplicate class ${cls.type}"} } }
    val support=classes.getValue("Lapp/yydarlinker/deepseekcaptions/CaptionAddonSupport;")
    val flags=support.methods.filter { it.name.endsWith("Installed") }.associate { method -> method.name to method.implementation!!.instructions.filterIsInstance<WideLiteralInstruction>().single().wideLiteral }
    println("FEATURES=$flags")
    if(flags["aiInstalled"]==1L){
        val bridge=classes.getValue("Lapp/yydarlinker/deepseekcaptions/NativeCaptionBridge;")
        val reselect=bridge.methods.single { it.name=="selectNative" }
        val call=reselect.implementation!!.instructions.single { (it as? ReferenceInstruction)?.reference is MethodReference }
        val target=reselect.implementation!!.instructions.filterIsInstance<ReferenceInstruction>().mapNotNull { it.reference as? MethodReference }.single()
        val method=classes.getValue(target.definingClass).methods.single { it.name==target.name && it.parameterTypes==target.parameterTypes }
        val actualWords=when(call){
            is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction->call.registerCount
            is com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction->call.registerCount
            else->error("Unexpected invocation form")
        }
        val expectedWords=1+target.parameterTypes.sumOf { if(it.toString() in listOf("J","D"))2 else 1 }
        check(actualWords==expectedWords){"Native selector argument mismatch: actual=$actualWords expected=$expectedWords"}
        check(AccessFlags.PUBLIC.isSet(classes.getValue(target.definingClass).accessFlags)){"Native selector owner not public"}
        check(AccessFlags.PUBLIC.isSet(method.accessFlags)){"Native selector is not public: $target flags=${method.accessFlags}"}
        println("NATIVE_RESELECT_PUBLIC=$target")
        val menu=classes.getValue("Lapp/yydarlinker/deepseekcaptions/CaptionQuickToggle;")
        for(name in listOf("addNativeRow","topMenu","shortsOpen","dismissNative","nativeContainer"))check(menu.methods.single { it.name==name }.implementation!!.instructions.filterIsInstance<ReferenceInstruction>().any())
        for(ins in menu.methods.single { it.name=="nativeContainer" }.implementation!!.instructions){
            val ref=(ins as? ReferenceInstruction)?.reference as? MethodReference?:continue
            val owner=classes.getValue(ref.definingClass)
            val targetMethod=owner.methods.single { it.name==ref.name&&it.parameterTypes==ref.parameterTypes&&it.returnType==ref.returnType }
            check(AccessFlags.PUBLIC.isSet(owner.accessFlags)&&AccessFlags.PUBLIC.isSet(targetMethod.accessFlags)){"Inaccessible native menu-container bridge: $ref"}
        }
        val containerCode=menu.methods.single { it.name=="nativeContainer" }.implementation!!.instructions.toList()
        val returns=containerCode.indices.filter { containerCode[it].opcode==com.android.tools.smali.dexlib2.Opcode.RETURN_OBJECT }
        check(returns.size==2){"Container bridge must have independent null and LinearLayout returns (ART type safety)"}
        val nullValue=containerCode[returns[0]-1] as? WideLiteralInstruction
        check(nullValue?.wideLiteral==0L){"Null return must explicitly clear the reference register"}
        check(containerCode[returns[1]-1].opcode==com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT_OBJECT)
        val accessor=(containerCode[returns[1]-2] as? ReferenceInstruction)?.reference as? MethodReference
        check(accessor?.name=="menuContainer"&&accessor.returnType=="Landroid/widget/LinearLayout;")
        println("NATIVE_CONTAINER_TYPED_RETURNS=true")
        val utils=classes.getValue("Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils;")
        check(AccessFlags.PUBLIC.isSet(utils.methods.single { it.name=="getFlyoutMenuInfo" }.accessFlags))
        val instructions=utils.methods.single { it.name=="addFlyoutElements" }.implementation!!.instructions.toList()
        fun called(i:Int)=((instructions[i] as? ReferenceInstruction)?.reference as? MethodReference)?.name
        val hook=instructions.indices.single { called(it)=="onMenu" }
        val divider=instructions.indices.single { called(it)=="addDivider" }
        val reset=instructions.indices.single { called(it)=="resetTopFlyoutMenuVisible" }
        check(hook<divider&&divider<reset){"AI toggle must precede shared divider and signal reset"}
        check(instructions[hook+1].opcode==com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT)
        check(instructions[hook+2].opcode==com.android.tools.smali.dexlib2.Opcode.IF_LEZ)
        val positions=IntArray(instructions.size);var position=0
        instructions.forEachIndexed { i,ins->positions[i]=position;position+=ins.codeUnits }
        for(i in 0 until hook){
            val offset=instructions[i] as? com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction?:continue
            val targetPosition=positions[i]+offset.codeOffset
            check(targetPosition<=positions[hook]){"Branch bypasses toggle before divider: $i -> $targetPosition"}
        }
        check(menu.methods.none { it.name=="show" }){"Obsolete second-level engine dialog remains"}
        println("QUICK_MENU_SHARED_DIVIDER=true; ALL_INCOMING_BRANCHES_REACH_TOGGLE=true; DIRECT_TOGGLE=true")
        println("QUICK_MENU_BOUND=true")
    }
    println("DEX_AUDIT_PASS classes=${classes.size}")
}
