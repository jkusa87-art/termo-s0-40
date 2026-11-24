package pl.termo.s040

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimalna obsluga UVC dla kamer z rodziny Xtherm/InfiRay (S0-40, T2, T2L, T3C, T3S...).
 * Format i ramka sa czytane z deskryptorow UVC (YUY2, obraz + 4 linie metadanych), strumien
 * izochroniczny albo bulk. Sprawdzone na S0-40 (VID 0x1514 PID 0x0001): IF0 VideoControl,
 * Camera Terminal id=1, IF1 VideoStreaming YUY2 256x196 @25 fps, alt1 EP 0x81 izochroniczny 524 B.
 * Surowa klatka = szer * wys * 2 B (uint16 LE).
 */
class UvcCamera(private val manager: UsbManager, val device: UsbDevice, private val log: (String) -> Unit) {

    companion object {
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
        private const val TIMEOUT = 1000

        /** wMaxPacketSize z mnoznikiem high-bandwidth (bity 11-12). */
        fun effectivePacket(ep: UsbEndpoint): Int {
            val m = ep.maxPacketSize
            return (m and 0x7FF) * (1 + ((m shr 11) and 3))
        }
    }

    private var conn: UsbDeviceConnection? = null
    private var ctrlIf: UsbInterface? = null
    private var streamIf0: UsbInterface? = null
    private var streamIfAlt: UsbInterface? = null      // alt z endpointem izochronicznym
    private var bulkEp: UsbEndpoint? = null            // albo endpoint bulk w alt 0
    private var desc: UvcDescriptors? = null

    /** Rozpoznany model (null = nieznana nazwa, ale ramka zgodna z rodzina Xtherm). */
    var model: CameraModel? = null; private set
    lateinit var frame: UvcDescriptors.Frame; private set
    val width get() = frame.width
    val heightTotal get() = frame.height
    val frameBytes get() = frame.width * frame.height * 2
    val displayName get() = model?.name ?: (device.productName?.trim()?.ifEmpty { null } ?: "kamera UVC")
    val verified get() = model?.verified == true

    /** Otwiera urzadzenie i sprawdza, czy jest zgodne. Zwraca null albo klucz bledu (I18n: err_*). */
    fun open(): String? {
        val c = manager.openDevice(device) ?: return "err_open"
        conn = c
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_VIDEO) continue
            when {
                itf.interfaceSubclass == 1 && ctrlIf == null -> ctrlIf = itf
                itf.interfaceSubclass == 2 && itf.alternateSetting == 0 && streamIf0 == null -> {
                    streamIf0 = itf
                    for (e in 0 until itf.endpointCount) {
                        val ep = itf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) bulkEp = ep
                    }
                }
                itf.interfaceSubclass == 2 && itf.id == streamIf0?.id && itf.endpointCount > 0 &&
                    itf.getEndpoint(0).type == UsbConstants.USB_ENDPOINT_XFER_ISOC -> {
                    val cur = streamIfAlt
                    if (cur == null || effectivePacket(itf.getEndpoint(0)) > effectivePacket(cur.getEndpoint(0))) streamIfAlt = itf
                }
            }
        }
        val ci = ctrlIf; val s0 = streamIf0
        if (ci == null || s0 == null) return "err_not_uvc"
        if (streamIfAlt == null && bulkEp == null) return "err_no_stream"

        val d = UvcDescriptors(c.rawDescriptors ?: ByteArray(0))
        desc = d
        log("deskryptory: $d")
        model = CameraModels.byName(device.productName)
        val f = d.findXthermFrame(model) ?: return "err_unsupported"
        frame = f

        if (!c.claimInterface(ci, true)) return "err_claim"
        if (!c.claimInterface(s0, true)) return "err_claim"
        val sa = streamIfAlt
        log("otwarto: ${device.productName} [%04x:%04x] model=%s ramka %dx%d #%d/%d, %s".format(
            device.vendorId, device.productId, model?.name ?: "nieznany", f.width, f.height, f.formatIndex, f.frameIndex,
            if (sa != null) "iso alt${sa.alternateSetting} EP 0x%02x %d B".format(sa.getEndpoint(0).address, effectivePacket(sa.getEndpoint(0)))
            else "bulk EP 0x%02x".format(bulkEp!!.address)))
        return null
    }

    private fun probeBlock(len: Int): ByteBuffer = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0, 1)                                  // bmHint: staly interwal
        put(2, frame.formatIndex.toByte())
        put(3, frame.frameIndex.toByte())
        putInt(4, if (frame.defaultInterval > 0) frame.defaultInterval else 400000)
    }

    /** Negocjacja probe/commit i start strumienia. */
    fun startStreaming(): Boolean {
        val c = conn ?: return false
        val s0 = streamIf0!!
        c.setInterface(s0)
        var probe: ByteArray? = null
        val lens = if ((desc?.bcdUvc ?: 0x100) >= 0x110) intArrayOf(34, 26) else intArrayOf(26, 34)
        for (len in lens) {
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
        if (maxFrame > 0 && maxFrame != frameBytes) log("UWAGA: kamera podaje rozmiar klatki $maxFrame, oczekiwano $frameBytes")
        val w = c.controlTransfer(REQ_OUT, SET_CUR, VS_COMMIT shl 8, s0.id, probe, probe.size, TIMEOUT)
        if (w < 0) { log("commit = $w"); return false }

        val sa = streamIfAlt
        val ok = if (sa != null) {
            if (!c.setInterface(sa)) { log("setInterface alt${sa.alternateSetting} nieudany"); return false }
            val ep = sa.getEndpoint(0)
            UvcIso.nativeStart(c.fileDescriptor, ep.address, effectivePacket(ep), frameBytes, false)
        } else {
            val ep = bulkEp!!
            val size = if (maxPayload in 512..(4 shl 20)) maxPayload else 16384
            UvcIso.nativeStart(c.fileDescriptor, ep.address, size, frameBytes, true)
        }
        log("strumien: ${if (ok) "start" else "BLAD"}")
        return ok
    }

    /** Komenda InfiRay przez CT_ZOOM_ABSOLUTE (2 bajty LE). */
    fun command(value: Int): Boolean {
        val c = conn ?: return false
        val data = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        val r = c.controlTransfer(REQ_OUT, SET_CUR, CT_ZOOM_ABSOLUTE shl 8,
            ((desc?.cameraTerminalId ?: 1) shl 8) or ctrlIf!!.id, data, 2, TIMEOUT)
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
