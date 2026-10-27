package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmaCodecTest {

    /**
     * The ITU G.711 reference A-law encoder (Sun's g711.c linear2alaw), kept
     * here so the codec is checked against something other than itself.
     */
    private fun referenceEncode(pcm: Int): Int {
        val segAend = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)
        var v = pcm shr 3
        val mask: Int
        if (v >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            v = -v - 1
        }
        val seg = segAend.indexOfFirst { v <= it }
        if (seg < 0) return 0x7F xor mask
        val mantissa = if (seg < 2) (v shr 1) and 0x0F else (v shr seg) and 0x0F
        return ((seg shl 4) or mantissa) xor mask
    }

    @Test
    fun encoderMatchesItuReferenceForEverySample() {
        for (s in Short.MIN_VALUE..Short.MAX_VALUE) {
            val got = PcmaCodec.encodeSample(s.toShort()).toInt() and 0xFF
            assertEquals("sample $s", referenceEncode(s), got)
        }
    }

    /** 150 used to encode as a loud sample that decoded to 2240. */
    @Test
    fun quietSamplesStayQuiet() {
        for (s in 16..255) {
            val back = PcmaCodec.decodeSample(PcmaCodec.encodeSample(s.toShort())).toInt()
            assertTrue("sample $s decoded to $back", abs(back - s) <= 8)
        }
        assertEquals(152, PcmaCodec.decodeSample(PcmaCodec.encodeSample(150)).toInt())
    }

    @Test
    fun roundTripErrorIsWithinHalfAStepEverywhere() {
        // Segment 7 has a step of 1024, so the error must never exceed 512.
        for (s in Short.MIN_VALUE..Short.MAX_VALUE) {
            val back = PcmaCodec.decodeSample(PcmaCodec.encodeSample(s.toShort())).toInt()
            assertTrue("sample $s decoded to $back", abs(back - s) <= 512)
        }
    }
}
