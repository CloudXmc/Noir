package moe.meowrealms.noir.network

import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import io.netty.util.ReferenceCountUtil
import moe.meowrealms.noir.utils.SimpleFriendlyByteBuf

/**
 * Fabric YSM payload 的外层编码：先写 payload 长度，再写 Noir 包内容。
 * Bukkit Plugin Message 不会替我们执行这个自定义 payload codec，因此必须在桥接层补上。
 */
object YsmPayloadFraming {
    private const val MAX_PAYLOAD_SIZE = 2 * 1024 * 1024

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "YSM payload is too large: ${payload.size} bytes"
        }

        val buffer = SimpleFriendlyByteBuf(Unpooled.buffer(SimpleFriendlyByteBuf.getVarIntSize(payload.size) + payload.size))
        return try {
            buffer.writeVarInt(payload.size)
            buffer.writeBytes(payload)
            buffer.bytes
        } finally {
            ReferenceCountUtil.safeRelease(buffer)
        }
    }

    fun decode(message: ByteArray): ByteArray {
        val buffer = SimpleFriendlyByteBuf(Unpooled.wrappedBuffer(message))
        return try {
            val payloadSize = buffer.readVarInt()
            if (payloadSize < 0 || payloadSize > MAX_PAYLOAD_SIZE) {
                throw DecoderException("Invalid YSM payload size: $payloadSize")
            }
            if (buffer.readableBytes() != payloadSize) {
                throw DecoderException(
                    "YSM payload length mismatch: declared=$payloadSize, actual=${buffer.readableBytes()}"
                )
            }
            ByteArray(payloadSize).also(buffer::readBytes)
        } finally {
            ReferenceCountUtil.safeRelease(buffer)
        }
    }
}
