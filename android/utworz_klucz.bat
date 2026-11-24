@echo off
rem Tworzy wlasny klucz do podpisywania APK (robi sie to RAZ). Haslo wpisujesz sam - nie zapisuj go w repozytorium.
rem Klucz trafia do folderu domowego, POZA projekt. Zrob jego kopie zapasowa - bez niego nie wydasz aktualizacji.
set KEYTOOL="C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
set KS=%USERPROFILE%\termo-release.jks
if exist "%KS%" (echo Klucz juz istnieje: %KS% & pause & exit /b 1)
%KEYTOOL% -genkeypair -v -keystore "%KS%" -alias termo -keyalg RSA -keysize 4096 -validity 10000
echo.
echo Gotowe: %KS%
echo Teraz skopiuj keystore.properties.przyklad jako keystore.properties i wpisz w nim swoje haslo.
pause
