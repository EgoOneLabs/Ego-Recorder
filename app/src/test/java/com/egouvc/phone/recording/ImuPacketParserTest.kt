package com.egouvc.phone.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImuPacketParserTest {
    @Test fun valid14SamplesWithBigEndianSignedAxes() {
        val packet = fakePacket()
        val samples = ImuPacketParser.decode(packet)
        assertEquals(14, samples.size)
        assertEquals(1234L, samples[0].counterRaw)
        assertEquals(1650L, samples[13].counterRaw)
        assertEquals(-100, samples[0].ax)
        assertEquals(200, samples[0].ay)
    }

    @Test fun rejectsBadHeaderAndCounter() {
        val bytes = fakePacket()
        bytes[18] = 0
        assertThrows(IllegalArgumentException::class.java) { ImuPacketParser.decode(bytes) }
        bytes[18] = 0xde.toByte()
        bytes[22] = 0
        assertThrows(IllegalArgumentException::class.java) { ImuPacketParser.decode(bytes) }
    }

    @Test fun rejectsTruncatedPacket() {
        assertThrows(IllegalArgumentException::class.java) { ImuPacketParser.decode(ByteArray(250)) }
    }

    private fun fakePacket(): ByteArray {
        val bytes = ByteArray(252)
        for (i in 0 until 14) {
            val p = i * 18
            bytes[p] = 0xde.toByte(); bytes[p + 1] = 0xc8.toByte()
            val c = 1234 + i * 32
            for (k in 0..3) bytes[p + 2 + k] = (c ushr (24 - k * 8)).toByte()
            val axes = intArrayOf(-100, 200, -300, 400, -500, 600)
            for (j in 0..5) {
                bytes[p + 6 + j * 2] = (axes[j] shr 8).toByte()
                bytes[p + 7 + j * 2] = axes[j].toByte()
            }
        }
        return bytes
    }
}
