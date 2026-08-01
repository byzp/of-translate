package com.of.colorpicker

/**
 * JNI bridge to native C++ code.
 *
 * The native library handles:
 * - Protocol framing (PacketHead + Snappy decompression + protobuf parsing)
 * - Swirl-noise color generation
 * - CIE76 uvy scan search
 * - Per-connection TCP stream framing
 */
class NativeLib {

    // Fields written by JNI after search() completes
    @Suppress("unused")
    var lastMatchedHex: String = ""
    @Suppress("unused")
    var lastTargetHex: String = ""

    companion object {
        init {
            System.loadLibrary("ofcolorpicker")
        }
    }

    /**
     * Load a PNG texture from bytes (read from assets).
     * @param textureIndex 1..4
     * @return true if loaded successfully
     */
    external fun loadTexture(textureIndex: Int, data: ByteArray): Boolean

    /**
     * Run the uvy scan search for the closest dye color match.
     *
     * @param targetHex target color as "#rrggbb"
     * @param pictureId texture index (1..4)
     * @param params 64 doubles (16x4 swirl params)
     * @param textureIndex which texture to use (usually same as pictureId)
     * @return double array [sim, uvy, slot, r0,g0,b0,a0, r1,g1,b1,a1, ...] or null
     *         After return, lastMatchedHex and lastTargetHex are set.
     */
    external fun search(
        targetHex: String,
        pictureId: Int,
        params: DoubleArray,
        textureIndex: Int
    ): DoubleArray?

    /** Feed one downstream TCP payload into the framer for a SOCKS connection. */
    external fun feedTcpPayload(flowId: Int, payload: ByteArray): DoubleArray?

    /** Release framing state after a SOCKS connection closes. */
    external fun closeTcpFlow(flowId: Int)
}
