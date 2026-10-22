package com.egouvc.phone.recording

/** Firmware v2: 14 fixed 18-byte samples, each DE C8 + big-endian counter + 6 int16 axes. */
object ImuPacketParser {
    const val PACKET_BYTES = 252
    const val RECORD_BYTES = 18
    const val SAMPLES_PER_PACKET = 14
    data class Sample(val counterRaw: Long, val ax: Int, val ay: Int, val az: Int,
                      val gx: Int, val gy: Int, val gz: Int)

    fun decode(bytes: ByteArray): List<Sample> {
        require(bytes.size == PACKET_BYTES) { "IMU packet size: ${bytes.size} != $PACKET_BYTES" }
        val out = ArrayList<Sample>(SAMPLES_PER_PACKET)
        for (i in 0 until SAMPLES_PER_PACKET) {
            val p = i * RECORD_BYTES
            require((bytes[p].toInt() and 255) == 0xde && (bytes[p + 1].toInt() and 255) == 0xc8) {
                "IMU header error at record $i"
            }
            val counter = ((u8(bytes, p + 2).toLong() shl 24) or
                (u8(bytes, p + 3).toLong() shl 16) or
                (u8(bytes, p + 4).toLong() shl 8) or u8(bytes, p + 5).toLong())
            out.add(Sample(counter, s16(bytes, p + 6), s16(bytes, p + 8), s16(bytes, p + 10),
                s16(bytes, p + 12), s16(bytes, p + 14), s16(bytes, p + 16)))
            if (i != 0) require(((counter - out[i - 1].counterRaw) and 0xffff_ffffL) == 32L) {
                "IMU counter progression invalid at record $i"
            }
        }
        return out
    }

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 255
    private fun s16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8 or u8(b, at + 1)).toShort().toInt()
}
