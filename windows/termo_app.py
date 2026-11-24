"""Termo S0-40 dla Windows - podgląd z kamer termowizyjnych z rodziny Xtherm/InfiRay (S0-40 i podobne).

Te same funkcje co aplikacja na Androida: temperatury min/max/środek/pod kursorem, obszar, obrót,
palety, emisyjność, migawka, zakres, zrzut (PNG + surowa klatka), nagrywanie MP4.
Obraz surowy daje tylko backend Media Foundation (MSMF) z CONVERT_RGB=0.
Wzory temperatur: ht301_hacklib (stawel, GPL-3). Licencja: GPL-3.0.

Opcje: --index N (wymuś kamerę), --test N (N klatek, zrzut, wyjście - do testów).
"""
import sys, os, time, queue, argparse, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (os.path.join(HERE, "ht301_hacklib-master"), os.path.join(HERE, "..", "ht301_hacklib-master"),
           os.path.join(HERE, "..", "python", "ht301_hacklib-master")):
    if os.path.isdir(_p):
        sys.path.insert(0, _p)
        break

import numpy as np
import cv2
import ht301_hacklib
from PySide6 import QtCore, QtGui, QtWidgets

APP_NAME = "Termo S0-40"
VERSION = "0.4"
ROWS_META = 4
# rozmiar ramki (szer, wys z liniami danych) -> rozmiary, dla których ht301_hacklib ma parametry
FRAME_SIZES = {(240, 184), (256, 196), (384, 292), (640, 516)}
# nazwa produktu USB -> (szer, wys ramki, sprawdzona) - lista z aplikacji QianLi IR (devType 1)
KNOWN = {"S0-40": (256, 196, True), "T2": (256, 196, False), "T2L-A4L": (256, 196, False),
         "T3C": (256, 196, False), "T2-Search": (256, 196, False), "Xtherm-T3S": (384, 292, False)}
PALETTES = [("INFERNO", cv2.COLORMAP_INFERNO), ("JET", cv2.COLORMAP_JET), ("TURBO", cv2.COLORMAP_TURBO),
            ("HOT", cv2.COLORMAP_HOT), ("BONE", cv2.COLORMAP_BONE)]
EMISSIVITY = [("Skóra 0.98", 0.98), ("Matowa farba, papier 0.95", 0.95), ("Plastik, guma 0.93", 0.93),
              ("Beton, cegła, tynk 0.92", 0.92), ("Drewno 0.90", 0.90), ("Woda, lód 0.96", 0.96),
              ("Metal utleniony / malowany 0.80", 0.80), ("Metal matowy 0.50", 0.50),
              ("Metal błyszczący 0.20 (pomiar niepewny)", 0.20)]


def camera_names():
    """Nazwy kamer w Windows (do rozpoznania modelu po nazwie USB)."""
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-PnpDevice -Class Camera,Image -PresentOnly | ForEach-Object { $_.FriendlyName }"],
            capture_output=True, text=True, timeout=10, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return [x.strip() for x in out.stdout.splitlines() if x.strip()]
    except Exception:
        return []


# ---------------------------------------------------------------- wątek kamery
class CameraWorker(QtCore.QThread):
    frame = QtCore.Signal(object)
    status = QtCore.Signal(str)
    log = QtCore.Signal(str)

    def __init__(self, force_index=None):
        super().__init__()
        self.force_index = force_index
        self.cmds = queue.Queue()
        self.running = True
        self.cam = None
        self.model = "?"
        self.verified = False
        self.emissivity = 0.98
        self.emissivity_sent = False

    def command(self, name, *args):
        self.cmds.put((name, args))

    def stop(self):
        self.running = False
        self.wait(3000)

    def _scan(self):
        names = camera_names()
        known = [n for n in names if n in KNOWN]
        self.log.emit("kamery w systemie: " + (", ".join(names) or "brak"))
        indices = [self.force_index] if self.force_index is not None else range(8)
        for idx in indices:
            vc = cv2.VideoCapture(idx, cv2.CAP_MSMF)
            if not vc.isOpened():
                vc.release()
                continue
            vc.set(cv2.CAP_PROP_CONVERT_RGB, 0)
            w, h = int(vc.get(cv2.CAP_PROP_FRAME_WIDTH)), int(vc.get(cv2.CAP_PROP_FRAME_HEIGHT))
            self.log.emit(f"kamera {idx}: {w}x{h}")
            if (w, h) not in FRAME_SIZES:
                vc.release()
                continue
            try:
                cam = ht301_hacklib.Camera(vc)
            except Exception as e:
                self.log.emit(f"kamera {idx}: {e}")
                vc.release()
                continue
            match = [n for n in known if KNOWN[n][:2] == (w, h)]
            self.model = match[0] if match else f"kamera {w}x{h - ROWS_META}"
            self.verified = bool(match) and KNOWN[match[0]][2]
            self.log.emit(f"wybrano kamerę {idx}: {self.model} ({'sprawdzona' if self.verified else 'eksperymentalna'})")
            self.cam = cam
            self.emissivity_sent = False
            return True
        return False

    def _release(self):
        if self.cam is not None:
            try:
                self.cam.release()
            except Exception:
                pass
        self.cam = None

    def _do_commands(self):
        while not self.cmds.empty():
            name, args = self.cmds.get()
            if self.cam is None:
                continue
            try:
                if name == "shutter":
                    self.cam.calibrate()
                elif name == "range_high":
                    self.cam.temperature_range_high()
                elif name == "range_normal":
                    self.cam.temperature_range_normal()
                elif name == "emissivity":
                    self.emissivity = args[0]
                    self.emissivity_sent = False
                self.log.emit(f"komenda: {name} {args or ''}")
            except Exception as e:
                self.log.emit(f"komenda {name}: {e}")

    def run(self):
        fails = 0
        while self.running:
            if self.cam is None:
                self.status.emit("Szukam kamery termowizyjnej…")
                if not self._scan():
                    self.status.emit("Brak kamery - podłącz kamerę termowizyjną do USB")
                    for _ in range(20):
                        if not self.running:
                            return
                        time.sleep(0.1)
                    continue
                skip = 10        # pierwsze klatki mogą być jeszcze w trybie nie-surowym
            self._do_commands()
            try:
                ret, raw = self.cam.read()
                if not ret or raw is None:
                    raise RuntimeError("brak klatki")
                fails = 0
            except Exception as e:
                fails += 1
                if fails > 10:
                    self.log.emit(f"kamera przestała odpowiadać ({e}) - szukam ponownie")
                    self._release()
                    fails = 0
                time.sleep(0.05)
                continue
            if skip > 0:
                skip -= 1
                continue
            raw = raw & 0x3FFF
            try:
                info, lut = self.cam.info()
            except Exception as e:
                self.log.emit(f"info: {e}")
                continue
            if not self.emissivity_sent:
                self.emissivity_sent = True
                if abs(float(info["emissivity"]) - self.emissivity) > 0.004:
                    self.log.emit(f"emisyjność w kamerze {float(info['emissivity']):.3f} -> {self.emissivity:.2f}")
                    self.cam.set_emissivity(self.emissivity)
            T = lut[raw].astype(np.float32)
            self.frame.emit({"raw": raw, "T": T, "info": info, "source": self.cam.frame_raw_u16.copy(),
                             "w": self.cam.width, "h": self.cam.height, "model": self.model,
                             "verified": self.verified})
        self._release()


# ---------------------------------------------------------------- widok obrazu
class ThermalView(QtWidgets.QWidget):
    def __init__(self):
        super().__init__()
        self.setMouseTracking(True)
        self.setMinimumSize(480, 360)
        self.data = None
        self.palette = 0
        self.rotation = 0          # 0..3, co 90° w prawo
        self.area_mode = False
        self.area = None           # (x0, y0, x1, y1) we współrzędnych obrazu po obrocie
        self.drag = None
        self.hover = None
        self.dst = QtCore.QRectF()
        self.status_text = ""
        self.on_drawn = None

    # dane po obrocie
    def rotated(self):
        d = self.data
        k = -self.rotation
        return np.rot90(d["T"], k), np.rot90(d["raw"], k)

    def set_frame(self, d):
        self.data = d
        self.update()

    def _image(self, raw):
        lo, hi = int(raw.min()), int(raw.max())
        n8 = ((raw.astype(np.int32) - lo) * 255 // max(hi - lo, 1)).astype(np.uint8)
        rgb = cv2.cvtColor(cv2.applyColorMap(n8, PALETTES[self.palette][1]), cv2.COLOR_BGR2RGB)
        rgb = np.ascontiguousarray(rgb)
        return QtGui.QImage(rgb.data, rgb.shape[1], rgb.shape[0], rgb.strides[0], QtGui.QImage.Format_RGB888).copy()

    def _to_img(self, pos, clamp):
        if self.data is None or self.dst.width() <= 0:
            return None
        T, _ = self.rotated()
        h, w = T.shape
        x = int((pos.x() - self.dst.left()) / self.dst.width() * w)
        y = int((pos.y() - self.dst.top()) / self.dst.height() * h)
        if clamp:
            return min(max(x, 0), w - 1), min(max(y, 0), h - 1)
        return (x, y) if 0 <= x < w and 0 <= y < h else None

    def mousePressEvent(self, e):
        if self.area_mode:
            self.drag = self._to_img(e.position(), True)
            self.area = None

    def mouseMoveEvent(self, e):
        if self.area_mode and self.drag is not None and e.buttons():
            p = self._to_img(e.position(), True)
            self.area = (min(self.drag[0], p[0]), min(self.drag[1], p[1]), max(self.drag[0], p[0]), max(self.drag[1], p[1]))
        self.hover = self._to_img(e.position(), False)
        self.update()

    def mouseReleaseEvent(self, e):
        if self.area_mode and self.area is not None:
            a = self.area
            if a[2] - a[0] < 2 and a[3] - a[1] < 2:
                self.area = None
        self.drag = None
        self.update()

    def leaveEvent(self, e):
        self.hover = None
        self.update()

    def paintEvent(self, e):
        p = QtGui.QPainter(self)
        p.fillRect(self.rect(), QtCore.Qt.black)
        if self.data is None:
            p.setPen(QtCore.Qt.white)
            p.drawText(self.rect(), QtCore.Qt.AlignCenter, self.status_text or "Czekam na obraz…")
            return
        self.dst = self.render_to(p, self.width(), self.height(), 1.0, False, hover=True)
        p.end()
        if self.on_drawn:
            self.on_drawn()

    def render_to(self, p, W, H, k, show_status, hover=False):
        """Rysuje obraz z markerami na dowolnym QPainter (okno, zrzut, film). Zwraca prostokąt obrazu."""
        T, raw = self.rotated()
        h, w = T.shape
        p.setRenderHint(QtGui.QPainter.SmoothPixmapTransform)
        p.setRenderHint(QtGui.QPainter.Antialiasing)
        status_h = 26 * k if show_status else 0
        bar_w = 70 * k
        aw, ah = W - bar_w, H - status_h
        s = min(aw / w, ah / h)
        dst = QtCore.QRectF((aw - w * s) / 2, (ah - h * s) / 2, w * s, h * s)
        p.drawImage(dst, self._image(raw))

        def scr(x, y, c=0.5):
            return QtCore.QPointF(dst.left() + (x + c) * dst.width() / w, dst.top() + (y + c) * dst.height() / h)

        font = QtGui.QFont("Segoe UI", max(7, int(11 * k)))
        font.setBold(True)
        p.setFont(font)

        def label(pt, text, color):
            fm = p.fontMetrics()
            tw = fm.horizontalAdvance(text)
            x, y = pt.x() + 10 * k, pt.y() - 10 * k
            if x + tw > dst.right():
                x = pt.x() - 10 * k - tw
            if y - fm.ascent() < dst.top():
                y = pt.y() + 10 * k + fm.ascent()
            path = QtGui.QPainterPath()
            path.addText(x, y, font, text)
            p.strokePath(path, QtGui.QPen(QtCore.Qt.black, 4 * k))
            p.fillPath(path, QtGui.QColor(color))

        def marker(x, y, color, t):
            c = scr(x, y)
            r = 9 * k
            for pen in (QtGui.QPen(QtCore.Qt.black, 5 * k), QtGui.QPen(QtGui.QColor(color), 2.5 * k)):
                p.setPen(pen)
                p.drawLine(QtCore.QPointF(c.x() - r, c.y()), QtCore.QPointF(c.x() + r, c.y()))
                p.drawLine(QtCore.QPointF(c.x(), c.y() - r), QtCore.QPointF(c.x(), c.y() + r))
            label(c, f"{t:.1f}°C", color)

        if self.area is not None:
            x0, y0, x1, y1 = self.area
            sub = T[y0:y1 + 1, x0:x1 + 1]
            a, b = scr(x0, y0, 0), scr(x1 + 1, y1 + 1, 0)
            rect = QtCore.QRectF(a, b)
            p.setBrush(QtCore.Qt.NoBrush)
            p.setPen(QtGui.QPen(QtCore.Qt.black, 4 * k)); p.drawRect(rect)
            p.setPen(QtGui.QPen(QtGui.QColor("#00ff00"), 2 * k)); p.drawRect(rect)
            label(QtCore.QPointF(rect.left() - 10 * k, rect.top() + 4 * k),
                  f"max {sub.max():.1f}  min {sub.min():.1f}  śr {sub.mean():.1f}°C", "#00ff00")

        iy, ix = np.unravel_index(np.argmax(T), T.shape)
        marker(ix, iy, "#ff3030", float(T[iy, ix]))
        iy, ix = np.unravel_index(np.argmin(T), T.shape)
        marker(ix, iy, "#00a0ff", float(T[iy, ix]))
        marker(w // 2, h // 2, "#ffffff", float(T[h // 2, w // 2]))
        if hover and self.hover is not None and not self.area_mode:
            hx, hy = self.hover
            marker(hx, hy, "#00ff00", float(T[hy, hx]))

        # pasek kolorów
        tmin, tmax = float(T.min()), float(T.max())
        bar = cv2.applyColorMap(np.linspace(255, 0, 256).astype(np.uint8).reshape(-1, 1), PALETTES[self.palette][1])
        bar = np.ascontiguousarray(cv2.cvtColor(bar, cv2.COLOR_BGR2RGB))
        qbar = QtGui.QImage(bar.data, 1, 256, bar.strides[0], QtGui.QImage.Format_RGB888).copy()
        bx = W - bar_w + 8 * k
        p.drawImage(QtCore.QRectF(bx, dst.top(), 16 * k, dst.height()), qbar)
        small = QtGui.QFont("Segoe UI", max(6, int(9 * k)))
        p.setFont(small)
        p.setPen(QtCore.Qt.white)
        for i in range(5):
            f = i / 4
            y = dst.top() + f * dst.height()
            y = min(max(y + 4 * k, dst.top() + 10 * k), dst.bottom())
            p.drawText(QtCore.QPointF(bx + 20 * k, y), f"{tmax - f * (tmax - tmin):.1f}")
        if show_status:
            p.setFont(QtGui.QFont("Segoe UI", max(7, int(10 * k))))
            p.drawText(QtCore.QPointF(8 * k, H - 8 * k), self.status_text)
        return dst

    def render_image(self, W, H, show_status=True):
        img = QtGui.QImage(W, H, QtGui.QImage.Format_RGB888)
        img.fill(QtCore.Qt.black)
        p = QtGui.QPainter(img)
        k = H / max(self.height(), 1)
        self.render_to(p, W, H, k, show_status)
        p.end()
        return img


# ---------------------------------------------------------------- okno główne
class MainWindow(QtWidgets.QMainWindow):
    def __init__(self, args):
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {VERSION} – kamera termowizyjna")
        self.settings = QtCore.QSettings("TermoS040", "TermoS040")
        self.view = ThermalView()
        self.view.palette = int(self.settings.value("palette", 0)) % len(PALETTES)
        self.view.rotation = int(self.settings.value("rotation", 0)) % 4
        self.high_range = False
        self.writer = None
        self.rec_name = ""
        self.fps = 0.0
        self._n = 0
        self._t0 = time.time()
        self.test_frames = args.test
        self.frames_seen = 0
        self.warned = False

        pics = QtCore.QStandardPaths.writableLocation(QtCore.QStandardPaths.PicturesLocation)
        vids = QtCore.QStandardPaths.writableLocation(QtCore.QStandardPaths.MoviesLocation)
        self.pic_dir = os.path.normpath(os.path.join(pics, "Termo"))
        self.vid_dir = os.path.normpath(os.path.join(vids, "Termo"))

        side = QtWidgets.QWidget()
        grid = QtWidgets.QGridLayout(side)
        def btn(text, fn, r, c):
            b = QtWidgets.QPushButton(text)
            b.setMinimumHeight(38)
            b.clicked.connect(fn)
            grid.addWidget(b, r, c)
            return b
        btn("Migawka", lambda: self.worker.command("shutter"), 0, 0)
        btn("Obrót", self.rotate, 0, 1)
        btn("Paleta", self.next_palette, 1, 0)
        btn("Emisyjność", self.choose_emissivity, 1, 1)
        self.btn_area = btn("Obszar", self.toggle_area, 2, 0)
        self.btn_rec = btn("● Nagraj", self.toggle_rec, 2, 1)
        btn("Zrzut", self.snapshot, 3, 0)
        self.btn_range = btn("Zakres", self.toggle_range, 3, 1)
        btn("Log", self.toggle_log, 4, 0)
        btn("Folder", lambda: os.startfile(self.pic_dir) if os.path.isdir(self.pic_dir) else None, 4, 1)
        grid.setRowStretch(5, 1)
        hint = QtWidgets.QLabel("Mysz: temperatura w punkcie.\nObszar: przeciągnij myszą.\nZrzuty: Obrazy\\Termo\nFilmy: Wideo\\Termo")
        hint.setStyleSheet("color: #aaa")
        grid.addWidget(hint, 6, 0, 1, 2)

        central = QtWidgets.QWidget()
        lay = QtWidgets.QHBoxLayout(central)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(self.view, 1)
        lay.addWidget(side)
        self.setCentralWidget(central)

        self.logbox = QtWidgets.QPlainTextEdit(readOnly=True)
        self.logbox.setMaximumBlockCount(300)
        self.logdock = QtWidgets.QDockWidget("Log", self)
        self.logdock.setWidget(self.logbox)
        self.addDockWidget(QtCore.Qt.BottomDockWidgetArea, self.logdock)
        self.logdock.hide()

        self.status = QtWidgets.QLabel("Start…")
        self.statusBar().addWidget(self.status, 1)
        self.resize(1200, 760)

        self.worker = CameraWorker(args.index)
        self.worker.emissivity = float(self.settings.value("emissivity", 0.98))
        self.worker.frame.connect(self.on_frame)
        self.worker.status.connect(self.on_status)
        self.worker.log.connect(self.on_log)
        self.worker.start()

    # ---- zdarzenia z kamery
    def on_log(self, s):
        self.logbox.appendPlainText(time.strftime("%H:%M:%S ") + s)

    def on_status(self, s):
        self.status.setText(s)
        if self.view.data is None:
            self.view.status_text = s
            self.view.update()

    def on_frame(self, d):
        self.view.set_frame(d)
        self.record_frame()
        self._n += 1
        now = time.time()
        if now - self._t0 >= 1:
            self.fps = self._n / (now - self._t0)
            self._n, self._t0 = 0, now
        T, info = d["T"], d["info"]
        label = d["model"] + ("" if d["verified"] else " (eksp.)")
        txt = (f"{label}   min {T.min():.1f}  max {T.max():.1f}  śr {T.mean():.1f}  środek {T[d['h'] // 2, d['w'] // 2]:.1f} °C"
               f"   ε {float(info['emissivity']):.2f}   zakres {'WYSOKI' if self.high_range else 'normalny'}"
               f"   rdzeń {float(info['temp_core']):.1f}   {self.fps:.0f} fps" + ("   ● REC" if self.writer else ""))
        self.status.setText(txt)
        self.view.status_text = txt
        if not d["verified"] and not self.warned:
            self.warned = True
            self.statusBar().showMessage(f"{d['model']}: obsługa eksperymentalna – sprawdź temperatury i zgłoś wynik na GitHubie", 10000)
        self.frames_seen += 1
        if self.test_frames and self.frames_seen >= self.test_frames:
            self.snapshot()
            self.grab().save(os.path.join(QtCore.QDir.tempPath(), "termo_okno.png"))
            if self.writer is None:
                self.toggle_rec()
                QtCore.QTimer.singleShot(3000, lambda: (self.toggle_rec(), self.close()))
            self.test_frames = 0

    # ---- przyciski
    def rotate(self):
        self.view.rotation = (self.view.rotation + 1) % 4
        self.view.area = None
        self.settings.setValue("rotation", self.view.rotation)
        self.statusBar().showMessage(f"Obrót {self.view.rotation * 90}°", 2000)

    def next_palette(self):
        self.view.palette = (self.view.palette + 1) % len(PALETTES)
        self.settings.setValue("palette", self.view.palette)
        self.statusBar().showMessage(PALETTES[self.view.palette][0], 2000)
        self.view.update()

    def toggle_area(self):
        self.view.area_mode = not self.view.area_mode
        if not self.view.area_mode:
            self.view.area = None
        self.btn_area.setText("Obszar ✓" if self.view.area_mode else "Obszar")
        self.view.update()

    def choose_emissivity(self):
        labels = [l for l, _ in EMISSIVITY]
        cur = next((i for i, (_, v) in enumerate(EMISSIVITY) if abs(v - self.worker.emissivity) < 0.005), 0)
        item, ok = QtWidgets.QInputDialog.getItem(self, "Emisyjność", f"Materiał (teraz {self.worker.emissivity:.2f}):", labels, cur, False)
        if ok:
            v = dict(EMISSIVITY)[item]
            self.settings.setValue("emissivity", v)
            self.worker.command("emissivity", v)

    def toggle_range(self):
        self.high_range = not self.high_range
        self.worker.command("range_high" if self.high_range else "range_normal")
        self.btn_range.setText("Zakres: wysoki" if self.high_range else "Zakres")

    def toggle_log(self):
        self.logdock.setVisible(not self.logdock.isVisible())

    def stamp(self):
        return "termo_" + time.strftime("%Y%m%d_%H%M%S")

    def snapshot(self):
        d = self.view.data
        if d is None:
            return
        os.makedirs(os.path.join(self.pic_dir, "dane"), exist_ok=True)
        name = self.stamp()
        png = os.path.join(self.pic_dir, name + ".png")
        self.view.render_image(1600, 900).save(png)
        base = os.path.join(self.pic_dir, "dane", name)
        d["source"].astype("<u2").tofile(base + ".raw")
        d["T"].astype("<f4").tofile(base + "_C.f32")
        info, T = d["info"], d["T"]
        with open(base + ".txt", "w", encoding="utf-8") as f:
            f.write(f"kamera={d['model']} szer={d['w']} wys={d['h'] + ROWS_META} min={T.min():.3f} max={T.max():.3f} "
                    f"srodek={T[d['h'] // 2, d['w'] // 2]:.3f} srednia={T.mean():.3f} rdzen={float(info['temp_core']):.2f} "
                    f"emis={float(info['emissivity']):.3f} obrot={self.view.rotation * 90}\n")
        self.on_log(f"zrzut: {png}")
        self.statusBar().showMessage(f"Zapisano {png}", 5000)

    def toggle_rec(self):
        if self.writer is not None:
            self.writer.release()
            self.writer = None
            self.btn_rec.setText("● Nagraj")
            self.btn_rec.setStyleSheet("")
            self.on_log(f"nagranie zapisane: {self.rec_name}")
            self.statusBar().showMessage(f"Zapisano {self.rec_name}", 5000)
            return
        if self.view.data is None:
            return
        os.makedirs(self.vid_dir, exist_ok=True)
        self.rec_name = os.path.join(self.vid_dir, self.stamp() + ".mp4")
        # H.264 przez Media Foundation (wbudowane w Windows); zapasowo mp4v przez ffmpeg, jeśli jest
        self.writer = cv2.VideoWriter(self.rec_name, cv2.CAP_MSMF, cv2.VideoWriter_fourcc(*"H264"), 25, (1280, 720))
        if not self.writer.isOpened():
            self.writer = cv2.VideoWriter(self.rec_name, cv2.VideoWriter_fourcc(*"mp4v"), 25, (1280, 720))
        if not self.writer.isOpened():
            self.writer = None
            QtWidgets.QMessageBox.warning(self, APP_NAME, "Nie udało się rozpocząć nagrywania.")
            return
        self.btn_rec.setText("■ Stop")
        self.btn_rec.setStyleSheet("color: red")
        self.on_log(f"nagrywanie: {self.rec_name}")

    def record_frame(self):
        if self.writer is None or self.view.data is None:
            return
        img = self.view.render_image(1280, 720)
        ptr = img.constBits()
        arr = np.frombuffer(ptr, np.uint8, count=img.bytesPerLine() * img.height()).reshape(img.height(), img.bytesPerLine())
        rgb = arr[:, :img.width() * 3].reshape(img.height(), img.width(), 3)
        self.writer.write(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))

    def closeEvent(self, e):
        if self.writer is not None:
            self.toggle_rec()
        self.worker.stop()
        super().closeEvent(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", type=int, default=None)
    ap.add_argument("--test", type=int, default=0)
    args = ap.parse_args()
    app = QtWidgets.QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setStyle("Fusion")
    pal = QtGui.QPalette()
    for role, col in [(QtGui.QPalette.Window, "#202020"), (QtGui.QPalette.WindowText, "#eeeeee"),
                      (QtGui.QPalette.Base, "#151515"), (QtGui.QPalette.Text, "#eeeeee"),
                      (QtGui.QPalette.Button, "#3a3a3a"), (QtGui.QPalette.ButtonText, "#eeeeee"),
                      (QtGui.QPalette.Highlight, "#f98e09")]:
        pal.setColor(role, QtGui.QColor(col))
    app.setPalette(pal)
    ico = os.path.join(HERE, "termo.ico")
    if os.path.exists(ico):
        app.setWindowIcon(QtGui.QIcon(ico))
    w = MainWindow(args)
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
