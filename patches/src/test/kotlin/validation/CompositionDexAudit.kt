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
        for(name in listOf("addNativeRow","topMenu","shortsOpen","dismissNative"))check(menu.methods.single { it.name==name }.implementation!!.instructions.filterIsInstance<ReferenceInstruction>().any())
        println("QUICK_MENU_BOUND=true")
    }
    println("DEX_AUDIT_PASS classes=${classes.size}")
}
