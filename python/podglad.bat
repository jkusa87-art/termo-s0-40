@echo off
rem Podglad na zywo. Opcjonalnie indeks kamery, np.: podglad.bat --index 0
cd /d "%~dp0"
py -3 termo_live.py %*
if errorlevel 1 pause
