@echo off
rem Buduje APK do publikacji i kopiuje go do folderu wydanie\
cd /d "%~dp0"
if not exist keystore.properties echo UWAGA: brak keystore.properties - APK bedzie podpisany kluczem testowym (debug).
if "%ANDROID_HOME%"=="" if not exist local.properties set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call gradlew.bat assembleRelease || (pause & exit /b 1)
if not exist wydanie mkdir wydanie
for /f "tokens=3 delims= " %%v in ('findstr /c:"versionName = " app\build.gradle.kts') do set VER=%%~v
copy /y app\build\outputs\apk\release\app-release.apk "wydanie\TermoS040-%VER%.apk"
echo Gotowe: wydanie\TermoS040-%VER%.apk
pause
