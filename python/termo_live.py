"""Podglad na zywo z kamery QianLi Super Cam X (InfiRay S0-40, 256x192).

Klawisze:
  q / Esc  - wyjscie
  c        - kalibracja (migawka)
  p        - nastepna paleta
  s        - zapis zrzutu (PNG) + temperatur (NPY) do folderu zrzuty/
  r        - przelacz zakres: normalny (-20..120 C) / wysoki (-20..450 C)
  mysz     - temperatura w punkcie pod kursorem

Opcje: --frames N  (zakoncz po N klatkach i zapisz zrzut - do testow)
       --index I   (indeks kamery, domyslnie 1)
"""
import sys, os, time, argparse
d = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(d, "ht301_hacklib-master"))
import cv2, numpy as np, ht301_hacklib

SCALE = 3
PALETTES = [("INFERNO", cv2.COLORMAP_INFERNO), ("JET", cv2.COLORMAP_JET),
            ("HOT", cv2.COLORMAP_HOT), ("TURBO", cv2.COLORMAP_TURBO),
            ("BONE", cv2.COLORMAP_BONE)]
WIN = "Termo - QianLi S0-40 (q=wyjscie, c=kalibracja, p=paleta, s=zrzut, r=zakres)"

mouse = [None]
def on_mouse(ev, x, y, flags, param):
    mouse[0] = (x, y) if ev != cv2.EVENT_MOUSEWHEEL else mouse[0]

def label(img, text, pt, col):
    x, y = pt
    h, w = img.shape[:2]
    (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 2)
    tx = x + 10 if x + 10 + tw < w else x - 10 - tw
    ty = y - 10 if y - 10 - th > 0 else y + 10 + th
    cv2.putText(img, text, (tx, ty), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 0), 4)
    cv2.putText(img, text, (tx, ty), cv2.FONT_HERSHEY_SIMPLEX, 0.6, col, 2)

def marker(img, pt, col, text):
    cv2.drawMarker(img, pt, (0, 0, 0), cv2.MARKER_CROSS, 22, 4)
    cv2.drawMarker(img, pt, col, cv2.MARKER_CROSS, 20, 2)
    label(img, text, pt, col)

def colorbar(tmin, tmax, cmap, height):
    bar = np.linspace(255, 0, height).astype(np.uint8)[:, None].repeat(20, 1)
    bar = cv2.applyColorMap(bar, cmap)
    panel = np.zeros((height, 90, 3), np.uint8)
    panel[:, 5:25] = bar
    for frac in (0, 0.25, 0.5, 0.75, 1):
        y = int(frac * (height - 1))
        t = tmax - frac * (tmax - tmin)
        cv2.putText(panel, f"{t:.1f}", (30, min(max(y + 5, 12), height - 4)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)
    return panel

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", type=int, default=0)
    ap.add_argument("--index", type=int, default=1)
    a = ap.parse_args()

    vc = cv2.VideoCapture(a.index, cv2.CAP_MSMF)
    vc.set(cv2.CAP_PROP_CONVERT_RGB, 0)
    if not vc.isOpened():
        sys.exit(f"Nie mozna otworzyc kamery o indeksie {a.index}")
    cam = ht301_hacklib.Camera(vc)
    W, H = cam.width, cam.height

    cv2.namedWindow(WIN, cv2.WINDOW_AUTOSIZE)
    cv2.setMouseCallback(WIN, on_mouse)
    pal, high = 0, False
    shots = os.path.join(d, "zrzuty")
    n, t0, fps = 0, time.time(), 0.0

    def save(img, T):
        os.makedirs(shots, exist_ok=True)
        stamp = time.strftime("%Y%m%d_%H%M%S")
        cv2.imwrite(os.path.join(shots, f"termo_{stamp}.png"), img)
        np.save(os.path.join(shots, f"termo_{stamp}_C.npy"), T.astype(np.float32))
        print("zapisano", os.path.join(shots, f"termo_{stamp}.png"))

    while True:
        ret, frame = cam.read()
        if not ret:
            print("brak klatki"); time.sleep(0.1); continue
        frame = frame & 0x3FFF
        info, lut = cam.info()
        T = lut[frame]
        tmin, tmax = float(T.min()), float(T.max())

        n8 = cv2.normalize(frame, None, 0, 255, cv2.NORM_MINMAX).astype(np.uint8)
        img = cv2.applyColorMap(n8, PALETTES[pal][1])
        img = cv2.resize(img, (W * SCALE, H * SCALE), interpolation=cv2.INTER_CUBIC)

        ymin, xmin = np.unravel_index(np.argmin(T), T.shape)
        ymax, xmax = np.unravel_index(np.argmax(T), T.shape)
        cy, cx = H // 2, W // 2
        sc = lambda x, y: (int(x) * SCALE + SCALE // 2, int(y) * SCALE + SCALE // 2)
        marker(img, sc(xmax, ymax), (0, 0, 255), f"{tmax:.1f}C")
        marker(img, sc(xmin, ymin), (255, 160, 0), f"{tmin:.1f}C")
        marker(img, sc(cx, cy), (255, 255, 255), f"{T[cy, cx]:.1f}C")
        if mouse[0] is not None:
            mx, my = mouse[0][0] // SCALE, mouse[0][1] // SCALE
            if 0 <= mx < W and 0 <= my < H:
                marker(img, sc(mx, my), (0, 255, 0), f"{T[my, mx]:.1f}C")

        n += 1
        if n % 10 == 0:
            now = time.time(); fps = 10 / (now - t0); t0 = now
        status = (f"{PALETTES[pal][0]}  zakres {'WYSOKI' if high else 'normalny'}  "
                  f"min {tmin:.1f}  max {tmax:.1f}  sr {float(T.mean()):.1f}C  "
                  f"rdzen {info['temp_core']:.1f}C  {fps:.1f} fps")
        out = np.hstack([img, colorbar(tmin, tmax, PALETTES[pal][1], H * SCALE)])
        bar = np.full((28, out.shape[1], 3), 30, np.uint8)
        cv2.putText(bar, status, (8, 19), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA)
        out = np.vstack([out, bar])
        cv2.imshow(WIN, out)

        k = cv2.waitKey(1) & 0xFF
        if a.frames and n >= a.frames:
            save(out, T); print(status); break
        if k in (ord('q'), 27) or cv2.getWindowProperty(WIN, cv2.WND_PROP_VISIBLE) < 1:
            break
        elif k == ord('c'):
            cam.calibrate(); print("kalibracja")
        elif k == ord('p'):
            pal = (pal + 1) % len(PALETTES)
        elif k == ord('s'):
            save(out, T)
        elif k == ord('r'):
            high = not high
            cam.temperature_range_high() if high else cam.temperature_range_normal()
            print("zakres", "wysoki" if high else "normalny")

    cam.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
