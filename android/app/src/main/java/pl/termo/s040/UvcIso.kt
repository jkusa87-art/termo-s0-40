package pl.termo.s040

import java.nio.ByteBuffer

/** Natywny odbior izochroniczny (app/src/main/cpp/uvc_iso.c). */
object UvcIso {
    init { System.loadLibrary("uvciso") }

    @JvmStatic external fun nativeStart(fd: Int, endpoint: Int, packetSize: Int, frameSize: Int): Boolean
    /** Czeka na klatke nowsza niz [lastSeq]; zwraca jej numer albo -1 (timeout/stop). */
    @JvmStatic external fun nativeReadFrame(buf: ByteBuffer, lastSeq: Int, timeoutMs: Int): Int
    @JvmStatic external fun nativeStats(): String
    @JvmStatic external fun nativeStop()
}
