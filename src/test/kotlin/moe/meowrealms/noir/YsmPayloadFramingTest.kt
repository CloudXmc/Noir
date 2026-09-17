package moe.meowrealms.noir

import moe.meowrealms.noir.network.YsmPayloadFraming
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class YsmPayloadFramingTest {
    @Test
    fun `encode adds fabric payload length and decode restores packet`() {
        val packet = byteArrayOf(51, 5, '2'.code.toByte(), '.'.code.toByte(), '6'.code.toByte(), '.'.code.toByte(), '0'.code.toByte())

        val framed = YsmPayloadFraming.encode(packet)

        assertContentEquals(byteArrayOf(packet.size.toByte()) + packet, framed)
        assertContentEquals(packet, YsmPayloadFraming.decode(framed))
    }

    @Test
    fun `decode rejects mismatched payload length`() {
        assertFailsWith<Throwable> {
            YsmPayloadFraming.decode(byteArrayOf(2, 51))
        }
    }
}
