@echo off
REM ============================================================================
REM TransactFlow - START ALL SERVICES
REM Lance tous les services nécessaires pour la démonstration
REM ============================================================================

setlocal enabledelayedexpansion

echo.
echo ============================================
echo. 🚀 TransactFlow - Démarrage complet
echo ============================================
echo.

set KAFKA_PATH=C:\tools\kafka_2.13-3.9.0\bin\windows
set PROJECT_PATH=C:\BankingTransaction\Transaction

REM Vérifier les chemins
if not exist "%KAFKA_PATH%" (
    echo ❌ ERREUR: Kafka not found at %KAFKA_PATH%
    pause
    exit /b 1
)

if not exist "%PROJECT_PATH%" (
    echo ❌ ERREUR: Project not found at %PROJECT_PATH%
    pause
    exit /b 1
)

if not exist "%PROJECT_PATH%\target\banking-kafka-1.0.0-jar-with-dependencies.jar" (
    echo ❌ ERREUR: JAR file not found. Run 'mvn clean package' first
    pause
    exit /b 1
)

REM ============================================================================
REM 1. Kafka Server
REM ============================================================================
echo 📦 Lancement de Kafka Server...
start "Kafka Server" cmd /k "cd /d %KAFKA_PATH% && kafka-server-start.bat ..\..\config\kraft\server.properties"
echo ✓ Kafka en cours de démarrage...
timeout /t 5 /nobreak

REM ============================================================================
REM 2. Dashboard Server (HTTP + DashboardConsumer)
REM ============================================================================
echo 🌐 Lancement du Dashboard Server...
start "Dashboard Server" cmd /k "cd /d %PROJECT_PATH% && java -jar target\banking-kafka-1.0.0-jar-with-dependencies.jar"
echo ✓ Dashboard en cours de démarrage (port 8080)...
timeout /t 3 /nobreak

REM ============================================================================
REM 3. Notification Consumer (Email)
REM ============================================================================
echo 📧 Lancement du Notification Consumer (Emails)...
start "Notification Consumer" cmd /k "cd /d %PROJECT_PATH% && java -cp target\banking-kafka-1.0.0-jar-with-dependencies.jar Consumer.NotificationConsumer"
echo ✓ Notification Consumer en cours de démarrage...
timeout /t 2 /nobreak

REM ============================================================================
REM 4. Fraud Detection Consumer
REM ============================================================================
echo 🚨 Lancement du Fraud Detection Consumer...
start "Fraud Detection Consumer" cmd /k "cd /d %PROJECT_PATH% && java -cp target\banking-kafka-1.0.0-jar-with-dependencies.jar Consumer.FraudDetectionConsumer"
echo ✓ Fraud Detection en cours de démarrage...
timeout /t 2 /nobreak

REM ============================================================================
REM 5. Audit Log Consumer
REM ============================================================================
echo 📋 Lancement du Audit Log Consumer...
start "Audit Log Consumer" cmd /k "cd /d %PROJECT_PATH% && java -cp target\banking-kafka-1.0.0-jar-with-dependencies.jar Consumer.AuditLogConsumer"
echo ✓ Audit Logger en cours de démarrage...

REM ============================================================================
REM Afficher le statut
REM ============================================================================
echo.
echo ============================================
echo ✅ TOUS LES SERVICES LANCÉS!
echo ============================================
echo.
echo 📊 Status des services:
echo ✓ Kafka Server................. Port 9092
echo ✓ Dashboard Server............ Port 8080
echo ✓ Notification Consumer....... Emails SMTP
echo ✓ Fraud Detection Consumer.... Alertes
echo ✓ Audit Log Consumer.......... Logging
echo.
echo 🌐 Accéder au dashboard:
echo    http://localhost:8080
echo.
echo 📧 Email des notifications:
echo    projetpfa26@gmail.com
echo.
echo ⏱️  Attendre 30-45 secondes pour que tout soit prêt...
echo.
echo Pour arrêter tous les services: Fermer les fenêtres de commande
echo.
pause
