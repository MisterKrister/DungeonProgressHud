package dev.krister.dungeonprogresshud

import dev.krister.dungeonprogresshud.mixin.ClientPacketListenerMixin
import java.lang.classfile.ClassFile
import java.lang.classfile.instruction.InvokeInstruction
import org.spongepowered.asm.mixin.injection.Inject
import kotlin.test.Test
import kotlin.test.assertContains

class PacketHookTest {
    @Test fun `chat hook points to an actual invocation in the Minecraft packet handler`() {
        val mixin = ClientPacketListenerMixin::class.java
        val hook = mixin.declaredMethods.single { it.name == "dungeonprogresshud\$chat" }.getAnnotation(Inject::class.java)
        val bytes = mixin.classLoader.getResourceAsStream("net/minecraft/client/multiplayer/ClientPacketListener.class")!!
            .use { it.readBytes() }
        val minecraft = ClassFile.of().parse(bytes)
        val handler = minecraft.methods().single { it.methodName().stringValue() == hook.method.single() }
        val calls = handler.code().orElseThrow().filterIsInstance<InvokeInstruction>().map {
            "L${it.owner().asInternalName()};${it.name().stringValue()}${it.type().stringValue()}"
        }
        assertContains(calls, hook.at.single().target, "The chat hook would crash during Mixin startup")
    }
}
