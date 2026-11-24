package pl.termo.s040

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    companion object {
        private const val TAG = "Termo"
        private const val ACTION_PERM = "pl.termo.s040.USB_PERMISSION"
        private val EMISSIVITY = listOf(
            "Skóra 0.98" to 0.98f, "Matowa farba, papier 0.95" to 0.95f, "Plastik, guma 0.93" to 0.93f,
            "Beton, cegła, tynk 0.92" to 0.92f, "Drewno 0.90" to 0.90f, "Woda, lód 0.96" to 0.96f,
            "Metal utleniony / malowany 0.80" to 0.80f, "Metal matowy 0.50" to 0.50f,
            "Metal błyszczący 0.20 (pomiar niepewny)" to 0.20f,
        )
    }

    private lateinit var usb: UsbManager
    private lateinit var prefs: SharedPreferences
    private lateinit var view: ThermalView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var btnArea: Button
    private lateinit var btnRec: Button
    private val logLines = ArrayDeque<String>()

    private var camera: UvcCamera? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    private var highRange = false
    private var permissionPending = false
    @Volatile private var emissivity = 0.98f
    @Volatile private var emissivitySent = false
    private lateinit var recorder: Recorder

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val dev = i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (i.action) {
                ACTION_PERM -> {
                    permissionPending = false
                    val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log("uprawnienie USB: $ok")
                    if (ok && dev != null) start(dev)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (dev != null && dev.deviceName == camera?.device?.deviceName) { log("kamera odlaczona"); stop() }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(USB_SERVICE) as UsbManager
        prefs = getSharedPreferences("termo", MODE_PRIVATE)
        emissivity = prefs.getFloat("emissivity", 0.98f)
        recorder = Recorder(this, ::log)
        buildUi()
        view.palette = prefs.getInt("palette", 0).coerceIn(0, Palettes.tables.size - 1)
        view.rotation90 = prefs.getInt("rotation", 0)
        val f = IntentFilter(ACTION_PERM).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        log("Termo S0-40 ${packageManager.getPackageInfo(packageName, 0).versionName}")
    }

    override fun onResume() { super.onResume(); if (camera == null) findAndOpen() }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); if (camera == null) findAndOpen() }
    override fun onPause() { super.onPause(); stopRecording(); stop() }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }

    // ---------------- UI ----------------
    private fun buildUi() {
        view = ThermalView(this)
        view.onFrameDrawn = { if (recorder.active) recordFrame() }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(16, 4, 16, 4) }
        logView = TextView(this).apply {
            setTextColor(Color.rgb(180, 255, 180)); textSize = 11f; setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }

        val dp = resources.displayMetrics.density
        fun btn(t: String, on: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { on() }
        }
        btnArea = btn("Obszar") { toggleArea() }
        btnRec = btn("● Nagraj") { toggleRecording() }
        val all = listOf(
            btn("Migawka") { camera?.command(UvcCamera.CMD_SHUTTER); toast("Migawka (kalibracja)") },
            btn("Obrót") { rotate() },
            btn("Paleta") { nextPalette() },
            btn("Emisyjność") { chooseEmissivity() },
            btnArea, btnRec,
            btn("Zrzut") { saveSnapshot() },
            btn("Zakres") { toggleRange() },
            btn("Log") { logView.visibility = if (logView.visibility == TextView.VISIBLE) TextView.GONE else TextView.VISIBLE },
        )
        val grid = GridLayout(this).apply {
            columnCount = 2
            for (b in all) addView(b, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
            })
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(grid, LinearLayout.LayoutParams((250 * dp).toInt(), WRAP_CONTENT))
        }
        val left = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(logView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(side, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(main, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // targetSdk 36 = edge-to-edge: odsun od paskow systemowych i wyciecia aparatu
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }
        setContentView(root)
    }

    private fun rotate() {
        view.rotation90 = view.rotation90 + 1
        prefs.edit().putInt("rotation", view.rotation90).apply()
        toast("Obrót ${view.rotation90 * 90}°")
    }

    private fun nextPalette() {
        view.palette = (view.palette + 1) % Palettes.tables.size
        prefs.edit().putInt("palette", view.palette).apply()
        toast(Palettes.names[view.palette])
    }

    private fun toggleArea() {
        view.areaMode = !view.areaMode
        btnArea.text = if (view.areaMode) "Obszar ✓" else "Obszar"
        if (view.areaMode) toast("Przeciągnij palcem po obrazie, aby zaznaczyć obszar")
    }

    private fun chooseEmissivity() {
        val labels = EMISSIVITY.map { it.first }.toTypedArray()
        val cur = EMISSIVITY.indexOfFirst { abs(it.second - emissivity) < 0.005f }
        AlertDialog.Builder(this)
            .setTitle("Emisyjność (teraz %.2f)".format(emissivity))
            .setSingleChoiceItems(labels, cur) { d, which ->
                emissivity = EMISSIVITY[which].second
                prefs.edit().putFloat("emissivity", emissivity).apply()
                emissivitySent = false        // watek kamery wysle ja przy nastepnej klatce
                toast("Emisyjność %.2f".format(emissivity))
                d.dismiss()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    // ---------------- kamera ----------------
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val ok = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
        log("uprawnienie CAMERA: $ok")
        if (!ok) status.text = "Bez uprawnienia Aparat Android nie da dostępu do kamery USB (UVC)"
    }

    private fun findAndOpen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log("prosze o uprawnienie CAMERA (wymagane dla kamer UVC)")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        // najpierw znane modele (po nazwie USB), potem VID InfiRay, na koncu dowolna kamera UVC
        val dev = usb.deviceList.values.filter { CameraModels.candidateScore(it) > 0 }
            .maxByOrNull { CameraModels.candidateScore(it) }
        if (dev == null) { log("nie znaleziono kamery termowizyjnej USB - podlacz ja"); status.text = "Brak kamery"; return }
        log("znaleziono: ${dev.productName} [%04x:%04x] ${dev.deviceName}".format(dev.vendorId, dev.productId))
        if (usb.hasPermission(dev)) start(dev)
        else if (!permissionPending) {
            permissionPending = true
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(dev, pi)
        }
    }

    private fun start(dev: UsbDevice) {
        if (camera != null) return
        val cam = UvcCamera(usb, dev, ::log)
        val err = cam.open()
        if (err != null) { log("${dev.productName}: $err"); cam.close(); status.text = "${dev.productName ?: "Kamera"}: $err"; return }
        if (!cam.startStreaming()) { cam.close(); status.text = "Blad uruchomienia obrazu - patrz Log"; return }
        if (!cam.verified) toast("${cam.displayName}: obsługa eksperymentalna - sprawdź temperatury i zgłoś wynik na GitHubie")
        camera = cam
        running = true
        emissivitySent = false
        worker = Thread({ loop(cam) }, "thermal").also { it.start() }
    }

    private fun loop(cam: UvcCamera) {
        val buf = ByteBuffer.allocateDirect(cam.frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val u16 = ShortArray(cam.frameBytes / 2)
        val label = cam.displayName + if (cam.verified) "" else " (eksp.)"
        var seq = 0; var frames = 0; var t0 = System.nanoTime(); var fps = 0f
        var configured = false; var noFrame = 0
        while (running) {
            val s = UvcIso.nativeReadFrame(buf, seq, 1000)
            if (s < 0) {
                noFrame++
                log("brak klatek (${UvcIso.nativeStats()})")
                if (noFrame >= 5) { log("zatrzymano: brak danych"); break }
                continue
            }
            noFrame = 0; seq = s
            if (!configured) {
                // jak ht301_hacklib: tryb raw 16-bit, potem migawka
                log("pierwsza klatka (${UvcIso.nativeStats()})")
                cam.command(UvcCamera.CMD_RAW16)
                Thread.sleep(300)
                cam.command(UvcCamera.CMD_SHUTTER)
                configured = true
                runOnUiThread { logView.visibility = TextView.GONE }   // przycisk "Log" go pokaze
                continue
            }
            buf.rewind(); buf.asShortBuffer().get(u16)
            val f = try { ThermalMath.decode(u16, cam.width, cam.heightTotal - CameraModel.ROWS_META) }
                    catch (e: Exception) { log("decode: $e"); continue }
            if (!emissivitySent) {
                emissivitySent = true
                if (abs(f.emissivity - emissivity) > 0.004f) {
                    log("emisyjnosc w kamerze %.3f -> ustawiam %.2f".format(f.emissivity, emissivity))
                    cam.setEmissivity(emissivity)
                }
            }
            view.setFrame(f)
            frames++
            val now = System.nanoTime()
            if (now - t0 > 1_000_000_000L) { fps = frames * 1e9f / (now - t0); frames = 0; t0 = now }
            if (frames % 5 == 0) {
                val txt = "%s   min %.1f  max %.1f  śr %.1f  środek %.1f °C   ε %.2f  %s  rdzeń %.1f  %.0f fps%s".format(
                    label, f.tMin, f.tMax, f.mean(), f.tCenter, f.emissivity,
                    if (highRange) "zakres WYSOKI" else "zakres normalny", f.tCore, fps,
                    if (recorder.active) "   ● REC" else "")
                view.statusText = txt
                runOnUiThread { status.text = txt }
            }
        }
    }

    private fun stop() {
        running = false
        worker?.join(1500); worker = null
        camera?.close(); camera = null
    }

    private fun toggleRange() {
        val cam = camera ?: return
        highRange = !highRange
        cam.command(if (highRange) UvcCamera.CMD_RANGE_HIGH else UvcCamera.CMD_RANGE_NORMAL)
        if (highRange) { ThermalMath.correctionM = 1.17; ThermalMath.correctionB = -40.9 }
        else { ThermalMath.correctionM = 1.0; ThermalMath.correctionB = 0.0 }
        toast(if (highRange) "Zakres wysoki (-20..450°C)" else "Zakres normalny (-20..120°C)")
    }

    // ---------------- nagrywanie ----------------
    private fun toggleRecording() {
        if (recorder.active) { stopRecording(); return }
        if (view.current() == null) { toast("Brak obrazu z kamery"); return }
        if (recorder.start(stamp())) { btnRec.text = "■ Stop"; btnRec.setTextColor(Color.RED); toast("Nagrywanie…") }
        else toast("Nie udało się rozpocząć nagrywania")
    }

    private fun stopRecording() {
        if (!recorder.active) return
        val ok = recorder.stop()
        btnRec.text = "● Nagraj"; btnRec.setTextColor(Color.WHITE)
        toast(if (ok) "Zapisano Movies/Termo/${recorder.name}.mp4" else "Nagranie puste")
    }

    private fun recordFrame() {
        val k = Recorder.H.toFloat() / view.height.coerceAtLeast(1)
        recorder.frame { c ->
            c.drawColor(Color.BLACK)
            view.drawContent(c, Recorder.W, Recorder.H, k, showStatus = true)
        }
    }

    // ---------------- zrzut ----------------
    private fun stamp() = "termo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveSnapshot() {
        val f = view.current() ?: run { toast("Brak obrazu z kamery"); return }
        val name = stamp()
        val w = 1600; val h = 900
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.BLACK); view.drawContent(this, w, h, h.toFloat() / view.height.coerceAtLeast(1), showStatus = true) }
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Termo")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
        contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        saveRaw(name, f)
        toast("Zapisano Pictures/Termo/$name.png (max %.1f°C)".format(f.tMax))
    }

    /** Surowa klatka (uint16 LE, 256x196) + temperatury z Kotlina (float32 LE, 256x192) do porownania z Pythonem. */
    private fun saveRaw(name: String, f: ThermalFrame) {
        val dir = getExternalFilesDir("zrzuty") ?: return
        val raw = ByteBuffer.allocate(f.source.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        raw.asShortBuffer().put(f.source)
        java.io.File(dir, "$name.raw").writeBytes(raw.array())
        val t = ByteBuffer.allocate(f.raw.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        val fb = t.asFloatBuffer(); for (v in f.raw) fb.put(f.lut[v])
        java.io.File(dir, "${name}_C.f32").writeBytes(t.array())
        java.io.File(dir, "$name.txt").writeText(
            "kamera=%s szer=%d wys=%d min=%.3f max=%.3f srodek=%.3f srednia=%.3f rdzen=%.2f fpa=%.3f migawka=%.2f emis=%.3f dist=%d air=%.2f refl=%.2f zakresM=%.3f zakresB=%.3f obrot=%d\n".format(
                Locale.US, camera?.displayName ?: "?", f.width, f.height + CameraModel.ROWS_META, f.tMin, f.tMax, f.tCenter, f.mean(), f.tCore, f.tFpa, f.tShutter, f.emissivity, f.distance,
                f.tAir, f.tRefl, ThermalMath.correctionM, ThermalMath.correctionB, view.rotation90 * 90))
        log("zapisano surowa klatke: ${dir.absolutePath}/$name.raw")
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun log(s: String) {
        Log.i(TAG, s)
        runOnUiThread {
            logLines.addLast(s); while (logLines.size > 12) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
        }
    }
}
