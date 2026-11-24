package pl.termo.s040

/**
 * Minimalny parser deskryptorow UVC z UsbDeviceConnection.getRawDescriptors():
 * wersja UVC, Camera Terminal (do komend ZOOM) oraz formaty nieskompresowane i ich ramki.
 */
class UvcDescriptors(raw: ByteArray) {
    data class Frame(val formatIndex: Int, val frameIndex: Int, val width: Int, val height: Int,
                     val defaultInterval: Int, val fourcc: String)

    var bcdUvc = 0x0100; private set
    var cameraTerminalId = 1; private set
    var cameraTerminalControls = 0L; private set
    val frames = mutableListOf<Frame>()

    init {
        var i = 0
        var ifClass = -1; var ifSub = -1
        var fmtIndex = -1; var fourcc = ""
        fun u8(o: Int) = raw[o].toInt() and 0xFF
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        while (i + 1 < raw.size) {
            val len = u8(i); val type = u8(i + 1)
            if (len < 2 || i + len > raw.size) break
            when (type) {
                0x04 -> if (len >= 9) { ifClass = u8(i + 5); ifSub = u8(i + 6) }
                0x24 -> if (ifClass == 14 && len >= 3) {
                    val sub = u8(i + 2)
                    if (ifSub == 1) {
                        if (sub == 0x01 && len >= 5) bcdUvc = u16(i + 3)
                        if (sub == 0x02 && len >= 15 && u16(i + 4) == 0x0201) {   // ITT_CAMERA
                            cameraTerminalId = u8(i + 3)
                            val n = u8(i + 14); var c = 0L
                            for (k in 0 until n) if (i + 15 + k < i + len) c = c or (u8(i + 15 + k).toLong() shl (8 * k))
                            cameraTerminalControls = c
                        }
                    } else if (ifSub == 2) {
                        if (sub == 0x04 && len >= 21) {                // VS_FORMAT_UNCOMPRESSED
                            fmtIndex = u8(i + 3)
                            fourcc = String(ByteArray(4) { raw[i + 5 + it] }, Charsets.US_ASCII)
                        } else if (sub == 0x05 && len >= 26 && fmtIndex > 0) { // VS_FRAME_UNCOMPRESSED
                            frames += Frame(fmtIndex, u8(i + 3), u16(i + 5), u16(i + 7), u32(i + 21), fourcc)
                        } else if (sub != 0x05) {
                            if (sub == 0x06 || sub == 0x10) fmtIndex = -1  // MJPEG / frame-based - pomijamy
                        }
                    }
                }
            }
            i += len
        }
    }

    /** Wybiera ramke zgodna z rodzina Xtherm: YUY2, szerokosc z listy, wysokosc = obraz + 4 linie. */
    fun findXthermFrame(model: CameraModel?): Frame? {
        val yuy = frames.filter { it.fourcc == "YUY2" }
        model?.let { m -> yuy.firstOrNull { it.width == m.width && it.height == m.heightTotal }?.let { return it } }
        return yuy.firstOrNull { CameraModels.supportedSizes[it.width] == it.height - CameraModel.ROWS_META }
    }

    override fun toString() = "UVC %x.%02x, terminal %d, ramki: %s".format(
        bcdUvc shr 8, bcdUvc and 0xFF, cameraTerminalId,
        frames.joinToString { "${it.fourcc} ${it.width}x${it.height}#${it.formatIndex}/${it.frameIndex}" })
}
