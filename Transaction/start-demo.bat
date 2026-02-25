@echo off
title Banking Kafka - Demarrage Complet
color 0A
echo.
echo  ============================================
echo   Banking Kafka - Demarrage Automatique
echo  ============================================
echo.

:: Ajouter Maven au PATH
set PATH=%PATH%;C:\tools\apache-maven-3.9.12\bin

:: Etape 1 : Compiler
echo [1/3] Compilation du projet...
cd /d C:\Users\MAHMOUD\Downloads\BankingTransaction\Transaction
call mvn clean package -DskipTests -q
if %ERRORLEVEL% NEQ 0 (
    echo ERREUR: La compilation a echoue!
    pause
    exit /b 1
)
echo       Compilation OK!
echo.

:: Etape 2 : Demarrer Kafka
echo [2/3] Demarrage de Kafka...
start "KAFKA SERVER" cmd /c "C:\tools\kafka_2.13-3.9.0\bin\windows\kafka-server-start.bat C:\tools\kafka_2.13-3.9.0\config\kraft\server.properties"
echo       Attente 10 secondes pour Kafka...
timeout /t 10 /nobreak >nul
echo       Kafka demarre!
echo.

:: Etape 3 : Demarrer le Dashboard
echo [3/3] Demarrage du Dashboard...
echo.
echo  ============================================
echo   Dashboard -> http://localhost:8080
echo  ============================================
echo.
echo   Ouvrez votre navigateur sur cette adresse!
echo   Appuyez sur Ctrl+C pour arreter.
echo.

:: Ouvrir le navigateur automatiquement
start http://localhost:8080

:: Lancer le dashboard (bloquant)
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" web.DashboardServer
pause
