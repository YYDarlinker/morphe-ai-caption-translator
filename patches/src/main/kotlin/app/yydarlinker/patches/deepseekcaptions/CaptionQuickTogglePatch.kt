package app.yydarlinker.patches.deepseekcaptions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/** Reuse the official menu inflater; never scan Activity windows or replace CC gestures. */
internal fun BytecodePatchContext.installCaptionQuickToggle(){
    val utils=mutableClassDefBy("Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils;")
    val filter=mutableClassDefBy("Lapp/morphe/extension/youtube/patches/components/PlayerFlyoutMenuComponentsFilter;")
    val runtime=mutableClassDefBy("Lapp/yydarlinker/deepseekcaptions/CaptionQuickToggle;")
    val objectType="Ljava/lang/Object;"
    val add=utils.methods.singleOrNull { it.name=="addFlyoutButton" && it.parameterTypes.map { t->t.toString() }==listOf(objectType,"Landroid/graphics/drawable/Drawable;","Ljava/lang/String;","Landroid/view/View\$OnClickListener;","I") }
        ?:throw PatchException("AI quick toggle: official flyout inflater signature unavailable")
    val entry=utils.methods.singleOrNull { it.name=="addFlyoutElements" && it.parameterTypes.map { t->t.toString() }==listOf(objectType) }
        ?:throw PatchException("AI quick toggle: official menu binding unavailable")
    // Expose only the existing checked inflater through a generated bridge in its own class.
    val bridge=ImmutableMethod(utils.type,"addonCaptionButton",add.parameters,add.returnType,AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,null,null,MutableMethodImplementation(6)).toMutable()
    bridge.addInstructions(0,"invoke-static/range {p0 .. p4}, ${utils.type}->${add.name}(Ljava/lang/Object;Landroid/graphics/drawable/Drawable;Ljava/lang/String;Landroid/view/View\$OnClickListener;I)I\nmove-result v0\nreturn v0")
    utils.methods.add(bridge)
    fun bind(name:String,body:String,registers:Int){
        val old=runtime.methods.single { it.name==name }
        val replacement=ImmutableMethod(runtime.type,name,old.parameters,old.returnType,old.accessFlags,old.annotations,null,MutableMethodImplementation(registers)).toMutable()
        replacement.addInstructions(0,body);runtime.methods.remove(old);runtime.methods.add(replacement)
    }
    bind("addNativeRow","invoke-static/range {p0 .. p4}, ${utils.type}->addonCaptionButton(Ljava/lang/Object;Landroid/graphics/drawable/Drawable;Ljava/lang/String;Landroid/view/View\$OnClickListener;I)I\nmove-result v0\nreturn v0",6)
    if(filter.methods.none { it.name=="getTopFlyoutMenuVisible" })throw PatchException("AI quick toggle: top-level menu signal missing")
    bind("topMenu","invoke-static {}, ${filter.type}->getTopFlyoutMenuVisible()Z\nmove-result v0\nreturn v0",1)
    val shorts=classDefBy("Lapp/morphe/extension/youtube/shared/ShortsPlayerState;")
    if(shorts.methods.none { it.name=="isOpen" && AccessFlags.STATIC.isSet(it.accessFlags) })throw PatchException("AI quick toggle: Shorts state unavailable")
    bind("shortsOpen","invoke-static {}, ${shorts.type}->isOpen()Z\nmove-result v0\nreturn v0",1)
    bind("dismissNative","invoke-static {}, ${utils.type}->dismissFlyout()V\nreturn-void",0)
    entry.addInstructions(0,"invoke-static/range {p0 .. p0}, ${runtime.type}->onMenu(Ljava/lang/Object;)V")
    val detector=filter.methods.single { it.name=="isFiltered" }
    val params=detector.parameterTypes.map { it.toString() }
    val bytes=params.indexOf("[B")
    if(bytes<1 || params[bytes-1]!="Ljava/lang/String;")throw PatchException("AI quick toggle: menu path signal unavailable")
    detector.addInstructions(0,"invoke-static/range {p$bytes .. p${bytes+1}}, ${runtime.type}->observeMenuPath(Ljava/lang/String;[B)V")
}
