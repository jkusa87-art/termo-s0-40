"""Odczyt deskryptorow USB kamery na Windows bez dodatkowych sterownikow (IOCTL huba USB).

Przydatne, gdy inna kamera nie dziala w aplikacji: pokazuje formaty UVC i typ transferu.

1. Znajdz port i hub kamery (PowerShell):
     Get-PnpDevice -PresentOnly | ? InstanceId -like 'USB\\VID_1514*' |
       % { Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName DEVPKEY_Device_LocationInfo,DEVPKEY_Device_Parent }
   Dla urzadzenia glownego (bez MI_xx): LocationInfo = Port_#0005.Hub_#0001, Parent = USB\\ROOT_HUB30\\...
2. python usb_deskryptory.py "USB\\ROOT_HUB30\\4&xxxxxxx&0&0" 5
"""
import ctypes, struct, sys
from ctypes import wintypes as w

cfg = ctypes.WinDLL("cfgmgr32")
k32 = ctypes.WinDLL("kernel32", use_last_error=True)

class GUID(ctypes.Structure):
    _fields_ = [("a", ctypes.c_uint32), ("b", ctypes.c_uint16), ("c", ctypes.c_uint16), ("d", ctypes.c_ubyte * 8)]

GUID_DEVINTERFACE_USB_HUB = GUID(0xf18a0e88, 0xc30c, 0x11d0, (ctypes.c_ubyte * 8)(0x88, 0x15, 0x00, 0xa0, 0xc9, 0x06, 0xbe, 0xd8))
IOCTL_USB_GET_DESCRIPTOR_FROM_NODE_CONNECTION = 0x220410
XFER = {0: "control", 1: "IZOCHRONICZNY", 2: "BULK", 3: "interrupt"}
VS_SUBTYPES = {0x01: "VS_INPUT_HEADER", 0x04: "FORMAT_UNCOMPRESSED", 0x05: "FRAME_UNCOMPRESSED",
               0x06: "FORMAT_MJPEG", 0x07: "FRAME_MJPEG", 0x0d: "COLORFORMAT"}

def main():
    hub_instance, port = sys.argv[1], int(sys.argv[2])
    n = ctypes.c_ulong()
    cfg.CM_Get_Device_Interface_List_SizeW(ctypes.byref(n), ctypes.byref(GUID_DEVINTERFACE_USB_HUB), hub_instance, 0)
    buf = ctypes.create_unicode_buffer(max(n.value, 1))
    cfg.CM_Get_Device_Interface_ListW(ctypes.byref(GUID_DEVINTERFACE_USB_HUB), hub_instance, buf, n.value, 0)
    if not buf.value: sys.exit("Nie znaleziono huba - sprawdz identyfikator")
    k32.CreateFileW.restype = w.HANDLE
    h = k32.CreateFileW(buf.value, 0x40000000, 2, None, 3, 0, None)

    def get(wValue, length):
        req = struct.pack("<IBBHHH", port, 0x80, 6, wValue, 0, length) + b"\0" * length
        b = ctypes.create_string_buffer(req, len(req)); out = w.DWORD()
        if not k32.DeviceIoControl(h, IOCTL_USB_GET_DESCRIPTOR_FROM_NODE_CONNECTION, b, len(req), b, len(req), ctypes.byref(out), None):
            raise OSError(ctypes.get_last_error())
        return b.raw[12:out.value]

    dev = get(0x0100, 18)
    bcd, vid, pid = struct.unpack_from("<H", dev, 2)[0], *struct.unpack_from("<HH", dev, 8)
    print(f"USB {bcd >> 8}.{(bcd >> 4) & 0xf}  VID {vid:04x}  PID {pid:04x}")
    total = struct.unpack_from("<H", get(0x0200, 9), 2)[0]
    c = get(0x0200, total)
    i = 0; cur_class = (0, 0)
    while i < len(c) and c[i]:
        d = c[i:i + c[i]]; t = d[1]
        if t == 4:
            cur_class = (d[5], d[6])
            print(f"Interfejs {d[2]} alt {d[3]}  endpointy {d[4]}  klasa {d[5]}/{d[6]}")
        elif t == 5:
            mps = struct.unpack_from("<H", d, 4)[0]
            print(f"   EP 0x{d[2]:02x} {XFER[d[3] & 3]}  pakiet {mps & 0x7ff} B x{((mps >> 11) & 3) + 1}  interwal {d[6]}")
        elif t == 0x24 and cur_class == (14, 2):
            st = d[2]; name = VS_SUBTYPES.get(st, f"0x{st:02x}")
            if st == 0x04: print(f"   {name}: indeks {d[3]} GUID {d[5:21].hex()} bity/piksel {d[21]}")
            elif st == 0x05:
                wd, ht = struct.unpack_from("<HH", d, 5); iv = struct.unpack_from("<I", d, 21)[0]
                print(f"   {name}: indeks {d[3]} {wd}x{ht}  interwal {iv} ({1e7 / iv:.1f} fps)")
            else: print(f"   {name}")
        i += c[i]

if __name__ == "__main__":
    main()
