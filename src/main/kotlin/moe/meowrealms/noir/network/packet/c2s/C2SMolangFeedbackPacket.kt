package moe.meowrealms.noir.network.packet.c2s

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap
import moe.meowrealms.noir.network.EnumDirection
import moe.meowrealms.noir.network.data.MolangFeedbackData
import moe.meowrealms.noir.network.packet.Packet
import moe.meowrealms.noir.network.packet.PacketHandler
import moe.meowrealms.noir.utils.SimpleFriendlyByteBuf

class C2SMolangFeedbackPacket(
    var feedback: MolangFeedbackData = MolangFeedbackData(0, 0, Object2FloatOpenHashMap())
) : Packet {
    override fun direction(): EnumDirection = EnumDirection.C_T_S

    override fun handle(handler: PacketHandler) = handler.handleMolangFeedback(this)

    override fun read(buffer: SimpleFriendlyByteBuf) {
        this.feedback = MolangFeedbackData.read(buffer)
    }

    override fun write(buffer: SimpleFriendlyByteBuf) {
        MolangFeedbackData.write(this.feedback, buffer)
    }
}
