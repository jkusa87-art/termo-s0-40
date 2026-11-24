package pl.termo.s040

import android.content.SharedPreferences
import java.util.Locale

/** Tłumaczenia interfejsu: polski, angielski, chiński (uproszczony). Logi diagnostyczne zostają po polsku. */
object I18n {
    enum class Lang(val code: String) { PL("pl"), EN("en"), ZH("zh") }

    var lang = Lang.EN
        private set

    fun init(prefs: SharedPreferences) {
        val saved = prefs.getString("lang", null)
        lang = Lang.entries.firstOrNull { it.code == saved } ?: when (Locale.getDefault().language) {
            "pl" -> Lang.PL
            "zh" -> Lang.ZH
            else -> Lang.EN
        }
    }

    fun set(l: Lang, prefs: SharedPreferences) {
        lang = l
        prefs.edit().putString("lang", l.code).apply()
    }

    fun t(key: String): String = S[key]?.get(lang.ordinal) ?: key
    fun f(key: String, vararg args: Any?): String = t(key).format(*args)

    private val S: Map<String, Array<String>> = mapOf(
        // przyciski
        "shutter" to arrayOf("Migawka", "Shutter", "快门"),
        "rotate" to arrayOf("Obrót", "Rotate", "旋转"),
        "palette" to arrayOf("Paleta", "Palette", "调色板"),
        "emissivity" to arrayOf("Emisyjność", "Emissivity", "发射率"),
        "area" to arrayOf("Obszar", "Area", "区域"),
        "record" to arrayOf("● Nagraj", "● Record", "● 录像"),
        "stop" to arrayOf("■ Stop", "■ Stop", "■ 停止"),
        "snapshot" to arrayOf("Zrzut", "Snapshot", "截图"),
        "range" to arrayOf("Zakres", "Range", "量程"),
        "log" to arrayOf("Log", "Log", "日志"),
        "cancel" to arrayOf("Anuluj", "Cancel", "取消"),
        // komunikaty
        "shutter_toast" to arrayOf("Migawka (kalibracja)", "Shutter (calibration)", "快门校准"),
        "rotate_toast" to arrayOf("Obrót %d°", "Rotation %d°", "旋转 %d°"),
        "area_hint" to arrayOf("Przeciągnij palcem po obrazie, aby zaznaczyć obszar",
            "Drag your finger over the image to select an area", "在图像上拖动手指以选择区域"),
        "emis_title" to arrayOf("Emisyjność (teraz %.2f)", "Emissivity (now %.2f)", "发射率（当前 %.2f）"),
        "emis_toast" to arrayOf("Emisyjność %.2f", "Emissivity %.2f", "发射率 %.2f"),
        "range_high_toast" to arrayOf("Zakres wysoki (-20…450°C)", "High range (-20…450°C)", "高温档（-20…450°C）"),
        "range_normal_toast" to arrayOf("Zakres normalny (-20…120°C)", "Normal range (-20…120°C)", "常温档（-20…120°C）"),
        "range_high" to arrayOf("zakres WYSOKI", "HIGH range", "高温档"),
        "range_normal" to arrayOf("zakres normalny", "normal range", "常温档"),
        "no_image" to arrayOf("Brak obrazu z kamery", "No image from the camera", "没有相机图像"),
        "recording" to arrayOf("Nagrywanie…", "Recording…", "正在录像…"),
        "rec_fail" to arrayOf("Nie udało się rozpocząć nagrywania", "Could not start recording", "无法开始录像"),
        "rec_empty" to arrayOf("Nagranie puste", "Recording is empty", "录像为空"),
        "saved" to arrayOf("Zapisano %s", "Saved %s", "已保存 %s"),
        "snap_saved" to arrayOf("Zapisano %s (max %.1f°C)", "Saved %s (max %.1f°C)", "已保存 %s（最高 %.1f°C）"),
        "no_camera" to arrayOf("Brak kamery – podłącz kamerę termowizyjną do USB",
            "No camera – connect a thermal camera to USB", "未检测到相机 – 请将热像仪连接到 USB"),
        "no_cam_perm" to arrayOf("Bez uprawnienia „Aparat” Android nie da dostępu do kamery USB (UVC)",
            "Without the Camera permission Android does not allow access to the USB camera (UVC)",
            "没有“相机”权限，Android 不允许访问 USB 相机（UVC）"),
        "stream_fail" to arrayOf("Błąd uruchomienia obrazu – patrz Log", "Could not start the video – see Log", "无法启动图像 – 请查看日志"),
        "experimental" to arrayOf("%s: obsługa eksperymentalna – sprawdź temperatury i zgłoś wynik na GitHubie",
            "%s: experimental support – check the temperatures and report the result on GitHub",
            "%s：实验性支持 – 请核对温度并在 GitHub 上反馈结果"),
        "exp_short" to arrayOf(" (eksp.)", " (exp.)", "（实验）"),
        "camera" to arrayOf("Kamera", "Camera", "相机"),
        // pasek stanu / etykiety
        "min" to arrayOf("min", "min", "最低"),
        "max" to arrayOf("max", "max", "最高"),
        "avg" to arrayOf("śr", "avg", "平均"),
        "center" to arrayOf("środek", "center", "中心"),
        "core" to arrayOf("rdzeń", "core", "机芯"),
        // błędy kamery
        "err_open" to arrayOf("nie można otworzyć urządzenia USB", "cannot open the USB device", "无法打开 USB 设备"),
        "err_not_uvc" to arrayOf("to nie jest kamera UVC", "this is not a UVC camera", "这不是 UVC 相机"),
        "err_no_stream" to arrayOf("brak strumienia obrazu (iso/bulk)", "no video stream endpoint (iso/bulk)", "没有视频流端点（iso/bulk）"),
        "err_unsupported" to arrayOf("ta kamera nie jest obsługiwana (brak formatu Xtherm: obraz + 4 linie danych)",
            "this camera is not supported (no Xtherm format: image + 4 data lines)",
            "不支持此相机（没有 Xtherm 格式：图像 + 4 行数据）"),
        "err_claim" to arrayOf("nie można przejąć interfejsu USB", "cannot claim the USB interface", "无法占用 USB 接口"),
        // materiały (emisyjność)
        "m_skin" to arrayOf("Skóra", "Skin", "皮肤"),
        "m_paint" to arrayOf("Matowa farba, papier", "Matte paint, paper", "哑光漆、纸"),
        "m_plastic" to arrayOf("Plastik, guma", "Plastic, rubber", "塑料、橡胶"),
        "m_concrete" to arrayOf("Beton, cegła, tynk", "Concrete, brick, plaster", "混凝土、砖、灰泥"),
        "m_wood" to arrayOf("Drewno", "Wood", "木材"),
        "m_water" to arrayOf("Woda, lód", "Water, ice", "水、冰"),
        "m_metal_ox" to arrayOf("Metal utleniony / malowany", "Oxidized / painted metal", "氧化或涂漆金属"),
        "m_metal_matte" to arrayOf("Metal matowy", "Matte metal", "哑光金属"),
        "m_metal_shiny" to arrayOf("Metal błyszczący (pomiar niepewny)", "Shiny metal (unreliable)", "光亮金属（测量不可靠）"),
    )
}
