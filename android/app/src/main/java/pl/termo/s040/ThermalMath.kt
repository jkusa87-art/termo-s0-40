package pl.termo.s040

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Przeliczenie surowych wartosci na °C - port Camera.info() + get_temp_table() z
 * ht301_hacklib (GPL-3) dla szerokosci 240/256/384/640 (sprawdzone tylko 256 - S0-40). Zweryfikowane w Pythonie: dlon 36.1°C vs QianLi 35.9-36.3°C.
 */
class ThermalFrame(
    val width: Int, val height: Int,
    val raw: IntArray,           // 14-bit, width*height
    val lut: FloatArray,         // 16384 -> °C
    val tShutter: Double, val tCore: Double, val tFpa: Double,
    val emissivity: Float, val distance: Int, val tAir: Float, val tRefl: Float,
    val rawMin: Int, val rawMax: Int, val idxMin: Int, val idxMax: Int,
    val source: ShortArray,      // cala surowa klatka z metadanymi (256*196 uint16), do zapisu
) {
    fun tempAt(x: Int, y: Int): Float = lut[raw[y * width + x]]
    val tMin get() = lut[rawMin]
    val tMax get() = lut[rawMax]
    val tCenter get() = tempAt(width / 2, height / 2)
    fun mean(): Float { var s = 0.0; for (v in raw) s += lut[v]; return (s / raw.size).toFloat() }
}

object ThermalMath {
    private const val ZEROC = 273.15

    /** Parametry zalezne od szerokosci matrycy - init_parameters() z ht301_hacklib. */
    private class Params(val fpaOff: Double, val fpaDiv: Double, val amountMul: Int,
                         val cal00Offset: Double = 390.0, val cal00FpaMul: Double = 7.05)

    private fun params(width: Int) = when (width) {
        640 -> Params(6867.0, 33.8, 3)
        384 -> Params(7800.0, 36.0, 3)
        256 -> Params(8617.0, 37.682, 1, cal00Offset = 170.0, cal00FpaMul = 0.0)   // sprawdzone (S0-40)
        240 -> Params(7800.0, 36.0, 1)
        else -> throw IllegalArgumentException("nieobslugiwana szerokosc matrycy: $width")
    }

    var correctionM = 1.0   // zakres wysoki: 1.17
    var correctionB = 0.0   // zakres wysoki: -40.9

    fun decode(u16: ShortArray, width: Int, height: Int): ThermalFrame {
        val p = params(width)
        val n = width * height                    // fourLinePara
        val amountPixels = width * p.amountMul
        val userArea = amountPixels + 127
        fun u(i: Int) = u16[i].toInt() and 0xFFFF
        fun f(i: Int) = Float.fromBits(u(i) or (u(i + 1) shl 16))

        val base = n + amountPixels
        val shut = u(base + 1) / 10.0 - ZEROC
        val core = u(base + 2) / 10.0 - ZEROC
        val cal00 = u(base).toDouble()
        val cal01 = f(base + 3).toDouble()
        val cal02 = f(base + 5).toDouble()
        val cal03 = f(base + 7).toDouble()
        val cal04 = f(base + 9).toDouble()
        val cal05 = f(base + 11).toDouble()

        val correction = f(n + userArea)
        val refl = f(n + userArea + 2)
        val air = f(n + userArea + 4)
        val humi = f(n + userArea + 6)
        val emiss = f(n + userArea + 8)
        val distance = u(n + userArea + 10)
        val fpaTmp = u(n + 1)
        val tfpa = 20.0 - (fpaTmp - p.fpaOff) / p.fpaDiv

        val d = (if (distance >= 20) 20.0 else distance.toDouble())
        val atm = atmt(humi.toDouble(), air.toDouble(), d)
        val numSub = (1.0 - emiss) * atm * (refl + ZEROC).pow(4) + (1.0 - atm) * (air + ZEROC).pow(4)
        val den = emiss * atm

        val calA = cal02 / (cal01 + cal01)
        val calB = cal02 * cal02 / (cal01 * cal01 * 4.0)
        val calC = cal01 * shut * shut + shut * cal02
        val calD = cal03 * tfpa * tfpa + cal04 * tfpa + cal05
        val corr = (p.cal00Offset - tfpa * p.cal00FpaMul).toInt()
        val tableOffset = cal00 - (if (corr > 0) corr else 0)

        val lut = FloatArray(16384)
        for (i in 0 until 16384) {
            var nn = sqrt(abs(((i - tableOffset) * calD + calC) / cal01 + calB))
            if (nn.isNaN()) nn = 0.0
            val wtot = (nn - calA + ZEROC).pow(4)
            val ttot = ((wtot - numSub) / den).pow(0.25) - ZEROC
            val t = ttot + (d * 0.85 - 1.125) * (ttot - air) / 100.0 + correction
            lut[i] = (correctionM * t + correctionB).toFloat()
        }

        val raw = IntArray(n)
        var mn = Int.MAX_VALUE; var mx = -1; var iMn = 0; var iMx = 0
        for (i in 0 until n) {
            val v = u(i) and 0x3FFF
            raw[i] = v
            if (v < mn) { mn = v; iMn = i }
            if (v > mx) { mx = v; iMx = i }
        }
        return ThermalFrame(width, height, raw, lut, shut, core, tfpa, emiss, distance, air, refl, mn, mx, iMn, iMx, u16.copyOf())
    }

    private fun wvc(h: Double, t: Double) =
        h * exp(1.5587 + 0.06939 * t - 2.7816e-4 * t * t + 6.8455e-7 * t * t * t)

    private fun atmt(h: Double, t: Double, d: Double): Double {
        val k = 1.9
        val nsqd = -sqrt(d)
        val sqw = sqrt(wvc(h, t))
        return k * exp(nsqd * (0.006569 - 0.002276 * sqw)) + (1.0 - k) * exp(nsqd * (0.01262 - 0.00667 * sqw))
    }
}
