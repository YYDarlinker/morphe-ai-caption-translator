package app.yydarlinker.patches.deepseekcaptions

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BRIDGE = "Lapp/yydarlinker/deepseekcaptions/NativeCaptionBridge;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.text() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Method.code() = implementation?.instructions?.toList() ?: emptyList()
private fun Method.hasText(s: String) = code().any { it.text()==s }
private fun FieldReference.id() = "$definingClass->$name:$type"
private fun MethodReference.id() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun <T> Iterable<T>.unique(role: String): T {
    val list=toList()
    if(list.size!=1) throw PatchException("AI captions: $role must match exactly once; found ${list.size}")
    return list.single()
}

/** Bind once after all bundles execute; no copied official extension or cross-bundle dependency. */
internal fun BytecodePatchContext.installNativeCaptionBridge() {
    val track=getAllClassesWithString("AUTO_TRANSLATE_CAPTIONS_OPTION").map { classDefBy(it.type) }
        .filter { "Landroid/os/Parcelable;" in it.interfaces }.unique("caption model")
    val sentinel=track.methods.filter { it.returnType=="Z" && it.hasText("AUTO_TRANSLATE_CAPTIONS_OPTION") }
        .unique("auto-translate sentinel")
    val language=sentinel.code().mapNotNull { it.field() }.filter { it.definingClass==track.type && it.type==STRING }
        .distinctBy { it.id() }.unique("language field")
    val vss=track.methods.filter { it.returnType=="Z" && it.hasText("t") }
        .flatMap { it.code().mapNotNull { ins -> ins.field() } }.filter { it.type==STRING }
        .distinctBy { it.id() }.unique("vss field")
    val display=track.methods.filter { it.name=="toString" }.flatMap { it.code().mapNotNull { ins -> ins.field() } }
        .filter { it.type=="Ljava/lang/CharSequence;" }.unique("display field")
    val listMethod=getAllClassesWithString("&tlang=").flatMap { classDefBy(it.type).methods.toList() }
        .filter { it.hasText("&tlang=") && it.parameterTypes.isEmpty() && it.returnType=="Ljava/util/List;" }
        .unique("translated track list")
    val listCode=listMethod.code()
    val url=listCode.mapIndexedNotNull { i,ins -> if(ins.text()=="&tlang=") listCode.getOrNull(i-2)?.field() else null }
        .filter { it.definingClass==track.type && it.type==STRING }.unique("signed source URL")
    val builderFactory=track.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty()
        && it.returnType.startsWith("L") && it.returnType!=track.type }.unique("track builder factory")
    val builder=classDefBy(builderFactory.returnType)
    val copy=builder.methods.filter { it.name=="<init>" && it.parameterTypes.toList()==listOf(track.type) }
        .unique("immutable track copy constructor")
    fun builderField(field: FieldReference): FieldReference {
        val c=copy.code()
        return c.mapIndexedNotNull { i, ins ->
            if(ins.field()?.id()==field.id()) c.getOrNull(i+1)?.field() else null
        }.filter { it.definingClass==builder.type }.unique("builder field ${field.name}")
    }
    fun setter(field: FieldReference): Method {
        val bf=builderField(field)
        return builder.methods.filter { it.parameterTypes.toList()==listOf(STRING) && it.returnType=="V" &&
            it.code().any { ins -> ins.opcode==Opcode.IPUT_OBJECT && ins.field()?.id()==bf.id() } }
            .unique("builder setter ${field.name}")
    }
    val languageSetter=setter(language); val urlSetter=setter(url); val vssSetter=setter(vss)
    val builderDisplay=builderField(display)
    val build=builder.methods.filter { it.parameterTypes.isEmpty() && it.returnType==track.type }.unique("build track")
    val selector=getAllClassesWithString("setSubtitleTrack name:%s languageCode:%s languageName:%s format:%d trackName:%s vssid:%s videoid:%s")
        .flatMap { classDefBy(it.type).methods.toList() }.filter { it.hasText("setSubtitleTrack name:%s languageCode:%s languageName:%s format:%d trackName:%s vssid:%s videoid:%s") }
        .unique("all-menu track selection")
    if(selector.parameterTypes.firstOrNull()!=track.type || AccessFlags.STATIC.isSet(selector.accessFlags))
        throw PatchException("AI captions: unexpected native track selector")
    for(m in listOf(copy,languageSetter,urlSetter,vssSetter,build))
        if(!AccessFlags.PUBLIC.isSet(m.accessFlags)) throw PatchException("AI captions: non-public builder")
    if(!AccessFlags.PUBLIC.isSet(builder.fields.first { it.name==builderDisplay.name }.accessFlags))
        throw PatchException("AI captions: private display field")
    fun mutable(m:Method)=mutableClassDefBy(m.definingClass).methods.filter { it.id()==m.id() }.unique("mutable method")
    val runtime=mutableClassDefBy(BRIDGE)
    fun bind(name:String, body:String) {
        val stub=runtime.methods.filter { it.name==name && it.parameterTypes.toList()==listOf(OBJECT) }.unique(name)
        val m=ImmutableMethod(BRIDGE,name,stub.parameters,stub.returnType,stub.accessFlags,stub.annotations,null,
            MutableMethodImplementation(4)).toMutable()
        m.addInstructionsWithLabels(0,body.trimIndent());runtime.methods.remove(stub);runtime.methods.add(m)
    }
    bind("language","check-cast p0, ${track.type}\niget-object v0, p0, ${language.id()}\nreturn-object v0")
    bind("url","check-cast p0, ${track.type}\niget-object v0, p0, ${url.id()}\nreturn-object v0")
    bind("cloneSimplified", """
        check-cast p0, ${track.type}
        new-instance v0, ${builder.type}
        invoke-direct {v0, p0}, ${copy.id()}
        const-string v1, "zh-Hans"
        invoke-virtual {v0, v1}, ${languageSetter.id()}
        const-string v1, "中文（简体）"
        iput-object v1, v0, ${builderDisplay.id()}
        iget-object v1, p0, ${url.id()}
        invoke-static {v1}, $BRIDGE->simplifiedUrl($STRING)$STRING
        move-result-object v1
        invoke-virtual {v0, v1}, ${urlSetter.id()}
        iget-object v1, p0, ${vss.id()}
        invoke-static {v1}, $BRIDGE->simplifiedVss($STRING)$STRING
        move-result-object v1
        invoke-virtual {v0, v1}, ${vssSetter.id()}
        invoke-virtual {v0}, ${build.id()}
        move-result-object v0
        return-object v0
    """)
    mutable(listMethod).apply {
        val returns=implementation!!.instructions.mapIndexedNotNull { i,ins ->
            if(ins.opcode==Opcode.RETURN_OBJECT) i to (ins as OneRegisterInstruction).registerA else null }
        for((i,r) in returns.reversed()) addInstructions(i,
            "invoke-static/range {v$r .. v$r}, $BRIDGE->augmentTranslations(Ljava/util/List;)Ljava/util/List;\nmove-result-object v$r")
    }
    mutable(selector).addInstructions(0,"invoke-static/range {p1 .. p1}, $BRIDGE->onSelection($OBJECT)V")
    val renderer=mutableClassDefBy("Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;")
    if(renderer.methods.any { it.name=="draw" && it.parameterTypes.toList()==listOf("Landroid/graphics/Canvas;") })
        throw PatchException("AI captions: native draw override already exists")
    val draw=ImmutableMethod(renderer.type,"draw",listOf(ImmutableMethodParameter("Landroid/graphics/Canvas;",null,null)),
        "V",AccessFlags.PUBLIC.value,null,null,MutableMethodImplementation(3)).toMutable()
    draw.addInstructionsWithLabels(0,"""
        invoke-static {}, $BRIDGE->suppressNativeDraw()Z
        move-result v0
        if-eqz v0, :original
        return-void
        :original
        invoke-super {p0, p1}, ${renderer.superclass}->draw(Landroid/graphics/Canvas;)V
        return-void
    """.trimIndent())
    renderer.methods.add(draw)
}
