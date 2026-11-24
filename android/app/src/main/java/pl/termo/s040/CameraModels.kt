package pl.termo.s040

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * Kamery z rodziny Xtherm/InfiRay (ramka = obraz + 4 linie metadanych, komendy przez CT_ZOOM_ABSOLUTE,
 * wzor temperatur z ht301_hacklib). Lista nazw wg wbudowanej listy aplikacji QianLi IR (devType 1),
 * dopasowanie - jak tam - po nazwie produktu USB. Tylko S0-40 zostala sprawdzona na prawdziwym sprzecie.
 */
data class CameraModel(val name: String, val width: Int, val heightTotal: Int, val verified: Boolean) {
    val height get() = heightTotal - ROWS_META
    companion object { const val ROWS_META = 4 }
}

object CameraModels {
    val known = listOf(
        CameraModel("S0-40", 256, 196, verified = true),
        CameraModel("T2", 256, 196, verified = false),
        CameraModel("T2L-A4L", 256, 196, verified = false),
        CameraModel("T3C", 256, 196, verified = false),
        CameraModel("T2-Search", 256, 196, verified = false),
        CameraModel("Xtherm-T3S", 384, 292, verified = false),
    )

    /** Matryce (szerokosc -> wysokosc obrazu), dla ktorych ht301_hacklib ma parametry przeliczenia. */
    val supportedSizes = mapOf(240 to 180, 256 to 192, 384 to 288, 640 to 512)

    fun byName(productName: String?): CameraModel? {
        val n = productName?.trim() ?: return null
        return known.firstOrNull { it.name.equals(n, ignoreCase = true) }
    }

    /** Czy warto probowac otworzyc to urzadzenie (znana nazwa, VID InfiRay albo dowolna kamera UVC). */
    fun candidateScore(d: UsbDevice): Int = when {
        byName(d.productName) != null -> 3
        d.vendorId == 0x1514 -> 2
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO } -> 1
        else -> 0
    }
}
