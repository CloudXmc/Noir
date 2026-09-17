package moe.meowrealms.noir.network.data

import it.unimi.dsi.fastutil.objects.Object2FloatMap
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap
import moe.meowrealms.noir.utils.SimpleFriendlyByteBuf

/** YSM 协议包 15 的模型齿轮变量增量。 */
data class MolangFeedbackData(
    val modelHashId: Int,
    val entityId: Int,
    val variables: Object2FloatMap<String>
) {
    companion object {
        const val MAX_VARIABLE_COUNT = 64
        const val MAX_VARIABLE_NAME_LENGTH = 64

        fun read(buffer: SimpleFriendlyByteBuf): MolangFeedbackData {
            val modelHashId = buffer.readInt()
            val entityId = buffer.readVarInt()
            val count = buffer.readUnsignedByte().toInt()
            require(count <= MAX_VARIABLE_COUNT) { "Molang variable count exceeds $MAX_VARIABLE_COUNT: $count" }

            val variables = Object2FloatOpenHashMap<String>(count)
            repeat(count) {
                val name = buffer.readUtf(MAX_VARIABLE_NAME_LENGTH)
                require(name.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid Molang variable name: $name" }
                val value = buffer.readFloat()
                require(value.isFinite()) { "Invalid Molang variable value for $name" }
                variables.put(name, value)
            }
            return MolangFeedbackData(modelHashId, entityId, variables)
        }

        fun write(data: MolangFeedbackData, buffer: SimpleFriendlyByteBuf) {
            require(data.variables.size <= MAX_VARIABLE_COUNT)
            buffer.writeInt(data.modelHashId)
            buffer.writeVarInt(data.entityId)
            buffer.writeByte(data.variables.size)
            data.variables.object2FloatEntrySet().forEach { entry ->
                buffer.writeUtf(entry.key, MAX_VARIABLE_NAME_LENGTH)
                buffer.writeFloat(entry.floatValue)
            }
        }
    }
}
