# ============================================================================
# TransactFlow - START ALL SERVICES
# Lance tous les services nécessaires pour la démonstration
# ============================================================================

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "🚀 TransactFlow - Démarrage complet" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# Configuration
$KAFKA_PATH = "C:\tools\kafka_2.13-3.9.0\bin\windows"
$PROJECT_PATH = "C:\BankingTransaction\Transaction"

# Vérifier les chemins
if (-not (Test-Path $KAFKA_PATH)) {
    Write-Host "❌ ERREUR: Kafka not found at $KAFKA_PATH" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $PROJECT_PATH)) {
    Write-Host "❌ ERREUR: Project not found at $PROJECT_PATH" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path "$PROJECT_PATH\target\banking-kafka-1.0.0-jar-with-dependencies.jar")) {
    Write-Host "❌ ERREUR: JAR file not found. Run 'mvn clean package' first" -ForegroundColor Red
    exit 1
}

# ============================================================================
# 1. Kafka Server
# ============================================================================
Write-Host "📦 Lancement de Kafka Server..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit -Command `"cd '$KAFKA_PATH'; .\kafka-server-start.bat ..\..\config\kraft\server.properties`"" -WindowStyle Normal
Write-Host "   ✓ Kafka en cours de démarrage..." -ForegroundColor Green
Start-Sleep -Seconds 5

# ============================================================================
# 2. Dashboard Server (HTTP + DashboardConsumer)
# ============================================================================
Write-Host "🌐 Lancement du Dashboard Server..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit -Command `"cd '$PROJECT_PATH'; java -jar target\banking-kafka-1.0.0-jar-with-dependencies.jar`"" -WindowStyle Normal
Write-Host "   ✓ Dashboard en cours de démarrage (port 8080)..." -ForegroundColor Green
Start-Sleep -Seconds 3

# ============================================================================
# 3. Notification Consumer (Email)
# ============================================================================
Write-Host "📧 Lancement du Notification Consumer (Emails)..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit -Command `"cd '$PROJECT_PATH'; java -cp 'target\banking-kafka-1.0.0-jar-with-dependencies.jar' Consumer.NotificationConsumer`"" -WindowStyle Normal
Write-Host "   ✓ Notification Consumer en cours de démarrage..." -ForegroundColor Green
Start-Sleep -Seconds 2

# ============================================================================
# 4. Fraud Detection Consumer
# ============================================================================
Write-Host "🚨 Lancement du Fraud Detection Consumer..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit -Command `"cd '$PROJECT_PATH'; java -cp 'target\banking-kafka-1.0.0-jar-with-dependencies.jar' Consumer.FraudDetectionConsumer`"" -WindowStyle Normal
Write-Host "   ✓ Fraud Detection en cours de démarrage..." -ForegroundColor Green
Start-Sleep -Seconds 2

# ============================================================================
# 5. Audit Log Consumer
# ============================================================================
Write-Host "📋 Lancement du Audit Log Consumer..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit -Command `"cd '$PROJECT_PATH'; java -cp 'target\banking-kafka-1.0.0-jar-with-dependencies.jar' Consumer.AuditLogConsumer`"" -WindowStyle Normal
Write-Host "   ✓ Audit Logger en cours de démarrage..." -ForegroundColor Green

# ============================================================================
# Afficher le statut
# ============================================================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "✅ TOUS LES SERVICES LANCÉS!" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "📊 Status des services:" -ForegroundColor Cyan
Write-Host "  ✓ Kafka Server................. Port 9092" -ForegroundColor Green
Write-Host "  ✓ Dashboard Server............ Port 8080" -ForegroundColor Green
Write-Host "  ✓ Notification Consumer....... Emails SMTP" -ForegroundColor Green
Write-Host "  ✓ Fraud Detection Consumer.... Alertes" -ForegroundColor Green
Write-Host "  ✓ Audit Log Consumer.......... Logging" -ForegroundColor Green
Write-Host ""
Write-Host "🌐 Accéder au dashboard:" -ForegroundColor Cyan
Write-Host "   http://localhost:8080" -ForegroundColor Yellow
Write-Host ""
Write-Host "📧 Email des notifications:" -ForegroundColor Cyan
Write-Host "   projetpfa26@gmail.com" -ForegroundColor Yellow
Write-Host ""
Write-Host "⏱️  Attendre 30-45 secondes pour que tout soit prêt..." -ForegroundColor Yellow
Write-Host ""
Write-Host "Pour arrêter tous les services: Fermer les fenêtres PowerShell" -ForegroundColor Magenta
Write-Host ""
