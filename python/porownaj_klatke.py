"""Porownanie przeliczenia temperatur: aplikacja Android (Kotlin) vs ht301_hacklib (Python)
na tej samej surowej klatce.

Uzycie: porownaj_klatke.py <plik.raw>   (obok musi lezec <plik>_C.f32 z aplikacji)
Pliki z telefonu: adb pull /sdcard/Android/data/pl.termo.qianli/files/zrzuty/ .
"""
import sys, os
d = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(d, "ht301_hacklib-master"))
import numpy as np, ht301_hacklib

raw_path = sys.argv[1]
base = raw_path[:-4]
W, H_ALL = 256, 196
u16 = np.fromfile(raw_path, dtype="<u2")
assert u16.size == W * H_ALL, f"zly rozmiar: {u16.size}"
t_kotlin = np.fromfile(base + "_C.f32", dtype="<f4").reshape(H_ALL - 4, W)

npy = base + "_u16.npy"
np.save(npy, u16.reshape(H_ALL, W))
cam = ht301_hacklib.CameraEmulator(npy)
_, frame = cam.read()
info, lut = cam.info()
t_py = lut[frame & 0x3FFF].astype(np.float64)

diff = np.abs(t_py - t_kotlin)
print(f"surowe min/max: {int(frame.min())} {int(frame.max())}")
print(f"Python : min {t_py.min():.3f}  max {t_py.max():.3f}  srodek {t_py[96,128]:.3f}  srednia {t_py.mean():.3f}")
print(f"Kotlin : min {t_kotlin.min():.3f}  max {t_kotlin.max():.3f}  srodek {t_kotlin[96,128]:.3f}  srednia {t_kotlin.mean():.3f}")
print(f"roznica |Py-Kt|: max {diff.max():.4f}  srednia {diff.mean():.4f} °C")
print(f"metadane: rdzen {info['temp_core']:.2f}  fpa {info['temp_fpa']:.3f}  migawka {info['temp_shutter']:.2f}  "
      f"emis {info['emissivity']:.3f}  dist {info['distance']}  air {info['temp_air']:.2f}")
txt = base + ".txt"
if os.path.exists(txt): print("aplikacja:", open(txt, encoding="utf-8").read().strip())
print("WYNIK:", "ZGODNE" if diff.max() < 0.05 else "ROZNICA > 0.05 °C - sprawdzic")
