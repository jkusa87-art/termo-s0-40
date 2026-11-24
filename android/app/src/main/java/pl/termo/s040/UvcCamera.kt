package pl.termo.s040

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimalna obsluga UVC dla InfiRay S0-40 (VID 0x1514 PID 0x0001).
 * Deskryptory odczytane na Windows (24.11.2026):
 *  - IF0 VideoControl, Camera Terminal id=1 z ZOOM_ABSOLUTE (tak biblioteka ht301 wysyla komendy),
 *  - IF1 VideoStreaming, format 1 = YUY2, ramka 1 = 256x196, interwal 400000 (25 fps),
 *  - IF1 alt1: EP 0x81 izochroniczny, 524 B.
 * Surowa klatka = 256*196*2 = 100352 B (uint16 LE): 192 linie obrazu + 4 linie metadanych.
 */
class UvcCamera(private val manager: UsbManager, val device: UsbDevice, private val log: (String) -> Unit) {

    companion object {
        const val VID = 0x1514
        const val PID = 0x0001
        const val WIDTH = 256
        const val HEIGHT_TOTAL = 196
        const val FRAME_BYTES = WIDTH * HEIGHT_TOTAL * 2

        // Komendy InfiRay wysylane przez CT_ZOOM_ABSOLUTE (jak CAP_PROP_ZOOM w ht301_hacklib)
        const val CMD_SHUTTER = 0x8000
        const val CMD_RAW16 = 0x8004
        const val CMD_RANGE_NORMAL = 0x8020
        const val CMD_RANGE_HIGH = 0x8021

        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val REQ_OUT = 0x21 // class | interface | host->device
        private const val REQ_IN = 0xA1
        private const val VS_PROBE = 0x01
        private const val VS_COMMIT = 0x02
        private const val CT_ZOOM_ABSOLUTE = 0x0B
        private const val CAMERA_TERMINAL_ID = 1
        private const val TIMEOUT = 1000
    }

    private var conn: UsbDeviceConnection? = null
    private var ctrlIf: UsbInterface? = null
    private var streamIf0: UsbInterface? = null
    private var streamIfAlt: UsbInterface? = null

    fun open(): Boolean {
        val c = manager.openDevice(device) ?: run { log("openDevice = null"); return false }
        conn = c
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_VIDEO) continue
            when {
                itf.interfaceSubclass == 1 && ctrlIf == null -> ctrlIf = itf
                itf.interfaceSubclass == 2 && itf.alternateSetting == 0 -> streamIf0 = itf
                itf.interfaceSubclass == 2 && itf.endpointCount > 0 &&
                    itf.getEndpoint(0).type == UsbConstants.USB_ENDPOINT_XFER_ISOC -> {
                    // wybierz alt z najwiekszym pakietem
                    val cur = streamIfAlt
                    if (cur == null || itf.getEndpoint(0).maxPacketSize > cur.getEndpoint(0).maxPacketSize) streamIfAlt = itf
                }
            }
        }
        val ci = ctrlIf; val s0 = streamIf0; val sa = streamIfAlt
        if (ci == null || s0 == null || sa == null) { log("brak interfejsow UVC (ctrl=$ci stream=$s0 alt=$sa)"); return false }
        if (!c.claimInterface(ci, true)) { log("claim IF${ci.id} nieudany"); return false }
        if (!c.claimInterface(s0, true)) { log("claim IF${s0.id} nieudany"); return false }
        log("otwarto: IF${ci.id}/IF${s0.id} alt${sa.alternateSetting} EP 0x%02x %d B".format(
            sa.getEndpoint(0).address, sa.getEndpoint(0).maxPacketSize))
        return true
    }

    private fun probeBlock(len: Int): ByteBuffer = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0, 1)              // bmHint: staly interwal
        put(2, 1)                   // bFormatIndex
        put(3, 1)                   // bFrameIndex
        putInt(4, 400000)           // dwFrameInterval (25 fps)
    }

    /** Negocjacja probe/commit i start strumienia izochronicznego. */
    fun startStreaming(): Boolean {
        val c = conn ?: return false
        val s0 = streamIf0!!; val sa = streamIfAlt!!
        c.setInterface(s0)
        var probe: ByteArray? = null
        for (len in intArrayOf(26, 34)) {   // UVC 1.0 = 26 B, 1.1 = 34 B
            val b = probeBlock(len).array()
            val w = c.controlTransfer(REQ_OUT, SET_CUR, VS_PROBE shl 8, s0.id, b, len, TIMEOUT)
            if (w < 0) { log("SET_CUR probe($len) = $w"); continue }
            val r = ByteArray(len)
            val n = c.controlTransfer(REQ_IN, GET_CUR, VS_PROBE shl 8, s0.id, r, len, TIMEOUT)
            if (n < 0) { log("GET_CUR probe($len) = $n"); continue }
            probe = r.copyOf(n); break
        }
        if (probe == null) { log("probe nieudany"); return false }
        val pb = ByteBuffer.wrap(probe).order(ByteOrder.LITTLE_ENDIAN)
        val maxFrame = if (probe.size >= 22) pb.getInt(18) else -1
        val maxPayload = if (probe.size >= 26) pb.getInt(22) else -1
        log("probe: fmt=${probe[2]} frame=${probe[3]} int=${pb.getInt(4)} maxFrame=$maxFrame maxPayload=$maxPayload")
        val w = c.controlTransfer(REQ_OUT, SET_CUR, VS_COMMIT shl 8, s0.id, probe, probe.size, TIMEOUT)
        if (w < 0) { log("commit = $w"); return false }
        if (!c.setInterface(sa)) { log("setInterface alt${sa.alternateSetting} nieudany"); return false }
        val ep = sa.getEndpoint(0)
        val ok = UvcIso.nativeStart(c.fileDescriptor, ep.address, ep.maxPacketSize, FRAME_BYTES)
        log("strumien: ${if (ok) "start" else "BLAD"}")
        return ok
    }

    /** Komenda InfiRay przez CT_ZOOM_ABSOLUTE (2 bajty LE). */
    fun command(value: Int): Boolean {
        val c = conn ?: return false
        val data = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        val r = c.controlTransfer(REQ_OUT, SET_CUR, CT_ZOOM_ABSOLUTE shl 8,
            (CAMERA_TERMINAL_ID shl 8) or ctrlIf!!.id, data, 2, TIMEOUT)
        log("komenda 0x%04x -> %d".format(value, r))
        return r >= 0
    }

    /**
     * Emisyjnosc: jak sendFloatCommand(SET_EMISSIVITY=16) w ht301_hacklib - 4 bajty floata,
     * kazdy jako komenda ((pozycja+i) shl 8) or bajt. Nie zapisujemy w pamieci kamery (0x80ff).
     */
    fun setEmissivity(e: Float): Boolean {
        val bits = java.lang.Float.floatToIntBits(e)
        var ok = true
        for (i in 0 until 4) ok = command(((16 + i) shl 8) or ((bits shr (8 * i)) and 0xFF)) && ok
        return ok
    }

    fun close() {
        UvcIso.nativeStop()
        conn?.let { c ->
            streamIf0?.let { runCatching { c.setInterface(it) }; c.releaseInterface(it) }
            ctrlIf?.let { c.releaseInterface(it) }
            c.close()
        }
        conn = null
    }
}
