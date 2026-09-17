package moe.meowrealms.noir.network.packet

import moe.meowrealms.noir.network.EnumDirection
import moe.meowrealms.noir.network.packet.c2s.C2SAnimationRequestPacket
import moe.meowrealms.noir.network.packet.c2s.C2SHandshakeConfirmedPacket
import moe.meowrealms.noir.network.packet.c2s.C2SModelDataPayload
import moe.meowrealms.noir.network.packet.c2s.C2SModelSwitchRequestPacket
import moe.meowrealms.noir.network.packet.c2s.C2SMolangExecuteRequestPacket
import moe.meowrealms.noir.network.packet.c2s.C2SMolangExpressionValueSyncPacket
import moe.meowrealms.noir.network.packet.c2s.C2SMolangFeedbackPacket
import moe.meowrealms.noir.network.packet.c2s.C2SStarModelPacket

interface PacketHandler {
    fun receivingDirection(): EnumDirection

    fun sendingDirection(): EnumDirection

    fun send(packet: Packet)

    fun handleClientModelSyncPayload(packet: C2SModelDataPayload)

    fun handleHandshakeConfirmed(packet: C2SHandshakeConfirmedPacket)

    fun handleModelSwitchRequest(packet: C2SModelSwitchRequestPacket)

    fun handleAnimationRequest(packet: C2SAnimationRequestPacket)

    fun handleStarModel(packet: C2SStarModelPacket)

    fun handleMolangExecuteRequest(packet: C2SMolangExecuteRequestPacket)

    fun handleMolangExpressionValueSyncPacket(packet: C2SMolangExpressionValueSyncPacket)

    fun handleMolangFeedback(packet: C2SMolangFeedbackPacket)
}
