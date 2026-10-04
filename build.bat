@echo off
setlocal
cd /d "%~dp0"

echo === Build du JAR AutoMod ===
rem Si Java 25 n'est pas le Java par defaut, decommente et adapte la ligne suivante :
rem set "JAVA_HOME=C:\Program Files\Java\jdk-25"

call gradlew.bat build
if errorlevel 1 (
    echo.
    echo [ERREUR] Le build a echoue. Lis les messages ci-dessus.
    pause
    exit /b 1
)

if not exist dist mkdir dist
copy /y "build\libs\automod-0.1.0.jar" "dist\automod-0.1.0.jar" >nul

echo.
echo [OK] JAR genere : %cd%\dist\automod-0.1.0.jar
echo Copie-le dans le dossier mods de Minecraft.
pause
