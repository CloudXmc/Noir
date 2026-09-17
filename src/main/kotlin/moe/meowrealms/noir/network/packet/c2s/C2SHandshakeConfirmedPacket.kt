package moe.meowrealms.noir.network.packet.c2s

import moe.meowrealms.noir.NoirConstants
import moe.meowrealms.noir.network.ClientConnectionManager
import moe.meowrealms.noir.network.EnumDirection
import moe.meowrealms.noir.network.packet.Packet
import moe.meowrealms.noir.network.packet.PacketHandler
import moe.meowrealms.noir.utils.SimpleFriendlyByteBuf

class C2SHandshakeConfirmedPacket(
    var version: String = ClientConnectionManager.VERSION,
    /**
     * 客户端构建标识。配套客户端在版本检查包末尾追加这一段，旧客户端不写。
     * 读不到时保持空串，交给上层拒绝握手并给玩家提示，而不是在解包阶段抛异常断线。
     */
    var clientBrand: String = NoirConstants.ClientRequirements.REQUIRED_CLIENT_BRAND
): Packet {
    override fun direction(): EnumDirection {
        return EnumDirection.C_T_S
    }

    override fun handle(handler: PacketHandler) {
        handler.handleHandshakeConfirmed(this)
    }

    override fun read(buffer: SimpleFriendlyByteBuf) {
        this.version = buffer.readUtf()
        // 旧客户端的包体到此为止，剩余字节为 0，此时必须容错读成空标识。
        this.clientBrand = if (buffer.readableBytes() > 0) buffer.readUtf() else ""
    }

    override fun write(buffer: SimpleFriendlyByteBuf) {
        buffer.writeUtf(this.version)
        buffer.writeUtf(this.clientBrand)
    }
}