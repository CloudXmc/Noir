package moe.meowrealms.noir

import io.netty.buffer.Unpooled
import moe.meowrealms.noir.network.data.DispatchServerDrivenProperty
import moe.meowrealms.noir.network.data.MolangFeedbackData
import moe.meowrealms.noir.network.packet.c2s.C2SHandshakeConfirmedPacket
import moe.meowrealms.noir.network.packet.c2s.C2SMolangFeedbackPacket
import moe.meowrealms.noir.network.packet.s2c.S2CEntityModelSelectionDataPacket
import moe.meowrealms.noir.network.packet.s2c.S2CHandshakeRequestPacket
import moe.meowrealms.noir.utils.SimpleFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap

class ProtocolPacketTest {
    @Test
    fun `handshake packet keeps YSM protocol version`() {
        val original = S2CHandshakeRequestPacket("2.6.0")
        val buffer = SimpleFriendlyByteBuf(Unpooled.buffer())
        try {
            original.write(buffer)
            val decoded = S2CHandshakeRequestPacket()
            decoded.read(buffer)
            assertEquals("2.6.0", decoded.version)
        } finally {
            buffer.release()
        }
    }

    @Test
    fun `selection packet round trips empty animation state`() {
        val original = S2CEntityModelSelectionDataPacket(
            42,
            "builtin/example",
            "default",
            false,
            DispatchServerDrivenProperty(42)
        )
        val buffer = SimpleFriendlyByteBuf(Unpooled.buffer())
        try {
            original.write(buffer)
            val decoded = S2CEntityModelSelectionDataPacket(
                0,
                "",
                "",
                true,
                DispatchServerDrivenProperty(0)
            )
            decoded.read(buffer)
            assertEquals(42, decoded.entityId)
            assertEquals("builtin/example", decoded.modelId)
            assertEquals("default", decoded.textureId)
            assertEquals(false, decoded.disabled)
            assertEquals(0, decoded.modelAnimationData.variant.toInt())
        } finally {
            buffer.release()
        }
    }

    @Test
    fun `molang feedback packet preserves per model gear variables`() {
        val variables = Object2FloatOpenHashMap<String>().apply {
            put("hat_visible", 1.0f)
            put("face_type", 2.0f)
        }
        val original = C2SMolangFeedbackPacket(MolangFeedbackData(123456, 42, variables))
        val buffer = SimpleFriendlyByteBuf(Unpooled.buffer())
        try {
            original.write(buffer)
            val decoded = C2SMolangFeedbackPacket()
            decoded.read(buffer)

            assertEquals(123456, decoded.feedback.modelHashId)
            assertEquals(42, decoded.feedback.entityId)
            assertEquals(1.0f, decoded.feedback.variables.getFloat("hat_visible"))
            assertEquals(2.0f, decoded.feedback.variables.getFloat("face_type"))
        } finally {
            buffer.release()
        }
    }

    /**
     * 空的齿轮变量表同样必须真正写进数据包：客户端靠这个全量包建立 RoamingStruct，
     * 少发这一个包，玩家之后的齿轮改动就永远传不回服务端。
     */
    @Test
    fun `empty molang seed is still written as a full sync`() {
        val property = DispatchServerDrivenProperty(42)
        property.setFull()
        property.molangVars(123456, Object2FloatOpenHashMap())

        assertTrue(property.isFull())
        assertNotEquals(0, property.variant.toInt() and (1 shl 12))

        val buffer = SimpleFriendlyByteBuf(Unpooled.buffer())
        try {
            DispatchServerDrivenProperty.encode(property, buffer)

            assertEquals(42, buffer.readVarInt())
            assertEquals(property.variant, buffer.readShort())
            assertEquals(123456, buffer.readInt())
            assertEquals(0, buffer.readVarInt())
            assertEquals(0, buffer.readableBytes())
        } finally {
            buffer.release()
        }
    }

    /**
     * 新客户端在握手回包里追加一段客户端标识，服务端据此拒绝旧客户端。
     */
    @Test
    fun `handshake confirm packet carries client build brand`() {
        val original = C2SHandshakeConfirmedPacket("2.6.0", NoirConstants.ClientRequirements.REQUIRED_CLIENT_BRAND)
        val buffer = SimpleFriendlyByteBuf(Unpooled.buffer())
        try {
            original.write(buffer)
            val decoded = C2SHandshakeConfirmedPacket("", "")
            decoded.read(buffer)
            assertEquals("2.6.0", decoded.version)
            assertEquals(NoirConstants.ClientRequirements.REQUIRED_CLIENT_BRAND, decoded.clientBrand)
        } finally {
            buffer.release()
        }
    }

    /**
     * 旧客户端只写协议版本，没有标识字段；解码必须得到空标识而不是抛异常，
     * 否则旧客户端会在解包阶段就断线，玩家看不到任何提示。
     */
    @Test
    fun `handshake confirm packet without brand decodes as legacy client`() {
        val buffer = SimpleFriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeUtf("2.6.0")
            val decoded = C2SHandshakeConfirmedPacket("", "placeholder")
            decoded.read(buffer)
            assertEquals("2.6.0", decoded.version)
            assertEquals("", decoded.clientBrand)
            assertFalse(NoirConstants.ClientRequirements.isAcceptedClient(decoded.clientBrand))
        } finally {
            buffer.release()
        }
    }

    /** 只有完全匹配当前发布的客户端标识才允许握手。 */
    @Test
    fun `only the shipped client brand is accepted`() {
        assertTrue(NoirConstants.ClientRequirements.isAcceptedClient(NoirConstants.ClientRequirements.REQUIRED_CLIENT_BRAND))
        assertFalse(NoirConstants.ClientRequirements.isAcceptedClient("openysm:2.6.5.21"))
        assertFalse(NoirConstants.ClientRequirements.isAcceptedClient(""))
        assertFalse(NoirConstants.ClientRequirements.isAcceptedClient("  "))
    }

    /**
     * 本次发布配套的客户端是 2.6.5.23（修复了进入他人视野时 requestModelSwitch 的空指针崩溃）。
     * 2.6.5.22 仍带着那个崩溃，必须被服务端挡在握手之外，否则玩家会继续崩客户端。
     */
    @Test
    fun `client brand is pinned to the crash-fixed build`() {
        assertEquals("openysm:2.6.5.23", NoirConstants.ClientRequirements.REQUIRED_CLIENT_BRAND)
        assertFalse(NoirConstants.ClientRequirements.isAcceptedClient("openysm:2.6.5.22"))
    }

    @Test
    fun `molang feedback rejects excessive variable count`() {
        val buffer = SimpleFriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeInt(123456)
            buffer.writeVarInt(42)
            buffer.writeByte(MolangFeedbackData.MAX_VARIABLE_COUNT + 1)
            assertThrows(IllegalArgumentException::class.java) {
                C2SMolangFeedbackPacket().read(buffer)
            }
        } finally {
            buffer.release()
        }
    }
}
