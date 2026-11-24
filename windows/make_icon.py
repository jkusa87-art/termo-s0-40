"""Tworzy termo.ico (termometr na tle w kolorach INFERNO) - ta sama ikona co w aplikacji na Androida."""
import os, sys
from PySide6 import QtCore, QtGui

app = QtGui.QGuiApplication(sys.argv)
S = 256
img = QtGui.QImage(S, S, QtGui.QImage.Format_ARGB32)
img.fill(QtCore.Qt.transparent)
p = QtGui.QPainter(img)
p.setRenderHint(QtGui.QPainter.Antialiasing)
k = S / 108.0
g = QtGui.QLinearGradient(0, S, S, 0)
for off, col in [(0, "#000004"), (0.35, "#57106E"), (0.6, "#BC3754"), (0.85, "#F98E09"), (1, "#FCFFA4")]:
    g.setColorAt(off, QtGui.QColor(col))
p.setBrush(g); p.setPen(QtCore.Qt.NoPen)
p.drawRoundedRect(QtCore.QRectF(0, 0, S, S), 40, 40)
p.scale(k, k)
def tube(r_tube, r_bulb, color, top=34):
    path = QtGui.QPainterPath()
    path.addRoundedRect(QtCore.QRectF(54 - r_tube, top - r_tube, 2 * r_tube, 66.5 - top + r_tube), r_tube, r_tube)
    path.addEllipse(QtCore.QPointF(54, 72), r_bulb, r_bulb)
    p.setBrush(QtGui.QColor(color)); p.drawPath(path.simplified())
tube(8, 13, "#FFFFFF", 26 + 8)
tube(4, 9, "#1B0C41", 30 + 4)
p.setBrush(QtGui.QColor("#F98E09")); p.drawRect(QtCore.QRectF(52, 46, 4, 22)); p.drawEllipse(QtCore.QPointF(54, 72), 6.2, 6.2)
p.setBrush(QtGui.QColor("#FCFFA4")); p.drawEllipse(QtCore.QPointF(54, 72), 3.2, 3.2)
p.setPen(QtGui.QPen(QtGui.QColor("#FFFFFF"), 2, QtCore.Qt.SolidLine, QtCore.Qt.RoundCap))
for y, l in [(36, 5), (43, 3.5), (50, 5), (57, 3.5)]:
    p.drawLine(QtCore.QPointF(66, y), QtCore.QPointF(66 + l, y))
p.end()
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "termo.ico")
print("ico:", img.save(out), out)
img.save(out.replace(".ico", ".png"))
