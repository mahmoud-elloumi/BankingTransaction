---
description: Comment lancer le projet Banking Kafka pour une démo
---

# 🚀 Lancer le Projet Banking Kafka — Démo

## Prérequis installés
- Java 25 : `C:\Program Files\Java\jdk-25`
- Maven 3.9.12 : `C:\tools\apache-maven-3.9.12`
- Kafka 3.9.0 : `C:\tools\kafka_2.13-3.9.0`

---

## Étapes (3 terminaux PowerShell)

### Étape 1 — Compiler le projet
// turbo
```powershell
cd C:\Users\MAHMOUD\Downloads\BankingTransaction\Transaction
$env:Path = "$env:Path;C:\tools\apache-maven-3.9.12\bin"
mvn clean package -DskipTests -q
```

### Étape 2 — Démarrer Kafka (Terminal 1)
```powershell
C:\tools\kafka_2.13-3.9.0\bin\windows\kafka-server-start.bat C:\tools\kafka_2.13-3.9.0\config\kraft\server.properties
```
> ⚠️ Garder ce terminal ouvert ! Kafka doit tourner en permanence.

### Étape 3 — Lancer le Dashboard (Terminal 2)
```powershell
cd C:\Users\MAHMOUD\Downloads\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" web.DashboardServer
```
> Le dashboard sera accessible sur **http://localhost:8080**

### Étape 4 — Ouvrir le navigateur
Ouvrir **http://localhost:8080** dans le navigateur.

### Étape 5 — Démonstration
1. Cliquer sur **"🎬 Lancer Démo (5 transactions)"** pour envoyer 5 transactions de test
2. Observer les résultats en temps réel dans le feed
3. Créer des transactions personnalisées via le formulaire

---

## Pour arrêter tout
- `Ctrl+C` dans chaque terminal
