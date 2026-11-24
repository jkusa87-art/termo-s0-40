@echo off
rem Buduje TermoS040.exe (folder dist\TermoS040) i paczke ZIP do wydania.
rem Wymaga: Python 3.10+ z pakietami: pip install opencv-python numpy PySide6-Essentials pyinstaller
cd /d "%~dp0"
set LIB=..\python\ht301_hacklib-master
if not exist "%LIB%" set LIB=..\ht301_hacklib-master
py -3 -m PyInstaller --noconfirm --clean --windowed --onedir --name TermoS040 --icon termo.ico ^
  --add-data "termo.ico;." --paths "%LIB%" ^
  --exclude-module matplotlib --exclude-module tkinter ^
  --exclude-module PySide6.QtNetwork --exclude-module PySide6.QtQml --exclude-module PySide6.QtQuick ^
  termo_app.py || (pause & exit /b 1)
rem niepotrzebne: programowy OpenGL (zwykle okno) i ffmpeg (nagrywanie idzie przez Media Foundation)
del /q dist\TermoS040\_internal\PySide6\opengl32sw.dll 2>nul
del /q dist\TermoS040\_internal\cv2\opencv_videoio_ffmpeg*.dll 2>nul
for /f "tokens=3 delims= " %%v in ('findstr /c:"VERSION = " termo_app.py') do set VER=%%~v
powershell -NoProfile -Command "Compress-Archive -Force -Path dist\TermoS040 -DestinationPath dist\TermoS040-%VER%-windows.zip"
echo Gotowe: dist\TermoS040-%VER%-windows.zip
pause
