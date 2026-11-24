import sys, os, time, json
d = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(d, "ht301_hacklib-master"))
import cv2, numpy as np, ht301_hacklib
log = open(os.path.join(d, "termo_log.txt"), "w", encoding="utf-8")
def p(*a):
    s=" ".join(str(x) for x in a); print(s); log.write(s+"\n"); log.flush()
idx = int(sys.argv[1]) if len(sys.argv) > 1 else 1  # indeks kamery (0 = zwykle wbudowana kamerka laptopa)
vc = cv2.VideoCapture(idx, cv2.CAP_MSMF)  # MSMF daje surowe 16-bit
vc.set(cv2.CAP_PROP_CONVERT_RGB, 0)
p("opened", vc.isOpened(), "WxH", vc.get(cv2.CAP_PROP_FRAME_WIDTH), vc.get(cv2.CAP_PROP_FRAME_HEIGHT))
cap = ht301_hacklib.Camera(vc)
p("size", cap.width, cap.height)
time.sleep(2)
for i in range(30): ret, frame = cap.read()
p("raw before LUT min/max", int(frame.min()), int(frame.max()))
if frame.max() >= 16384:
    p("UWAGA: wartosci >= 16384, maskuje do 14 bitow (& 0x3FFF)")
    frame = frame & 0x3FFF
info, lut = cap.info()
keys = ["cameraSoftVersion","sn","temp_core","temp_fpa","emissivity","distance","temp_air","temp_reflected","Tmin_C","Tmax_C","Tcenter_C","temp_average","Tmin_point","Tmax_point"]
for k in keys: p(k, "=", info.get(k))
T = lut[frame]
p("frame raw min/max", int(frame.min()), int(frame.max()), " T min/max/mean", round(float(T.min()),1), round(float(T.max()),1), round(float(T.mean()),1))
n = cv2.normalize(frame, None, 0, 255, cv2.NORM_MINMAX).astype('uint8')
img = cv2.resize(cv2.applyColorMap(n, cv2.COLORMAP_INFERNO), (768, 576), interpolation=cv2.INTER_CUBIC)
s = 3
for name, pt, col in [("max", info["Tmax_point"], (0,0,255)), ("min", info["Tmin_point"], (255,128,0)), ("c", info["Tcenter_point"], (255,255,255))]:
    x, y = int(pt[0])*s, int(pt[1])*s
    t = float(T[int(pt[1]), int(pt[0])]) if name=="c" else info["Tmax_C" if name=="max" else "Tmin_C"]
    cv2.drawMarker(img, (x,y), col, cv2.MARKER_CROSS, 20, 2)
    cv2.putText(img, f"{t:.1f}C", (x+8, y-8), cv2.FONT_HERSHEY_SIMPLEX, 0.7, col, 2)
cv2.imwrite(os.path.join(d, "termo_temperatury.png"), img)
np.save(os.path.join(d, "temp_C.npy"), T.astype(np.float32))
cap.release()
p("KONIEC")
