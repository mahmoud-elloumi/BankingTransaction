---
description: Comment lancer le projet Banking Kafka pour une démo
---

# 🎬 Guide de Démo — Banking Kafka System

## Prérequis

- Java 17+ installé
- Apache Kafka 3.9.0 dans `C:\tools\kafka_2.13-3.9.0`
- Projet compilé : `mvn clean package -DskipTests` dans `C:\BankingTransaction\Transaction`
- Email configuré dans `NotificationConsumer.java` (EMAIL_FROM, EMAIL_PASSWORD, EMAIL_TO)

---

## Étape 1 — Compiler le projet

// turbo
```bash
cd C:\BankingTransaction\Transaction
mvn clean package -q -DskipTests
```

> Attendez que le build soit terminé (BUILD SUCCESS).

---

## Étape 2 — Démarrer Kafka Server

Ouvrir un **Terminal 1** et lancer :

```bash
C:\tools\kafka_2.13-3.9.0\bin\windows\kafka-server-start.bat C:\tools\kafka_2.13-3.9.0\config\kraft\server.properties
```

> Attendez environ 10 secondes que Kafka soit prêt (vous verrez "Kafka Server started").

---

## Étape 3 — Démarrer le Dashboard Web

Ouvrir un **Terminal 2** et lancer :

// turbo
```bash
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" web.DashboardServer
```

> Attendez le message : `Dashboard started: http://localhost:8080`

---

## Étape 4 — Démarrer le TransactionConsumer (Étape Essentielle pour les Emails)
Ouvrir un **Terminal 3** et lancer :

// turbo
```bash
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.TransactionConsumer
```

> ⚠️ C'est LUI qui valide les transactions et les place dans le topic `approved`/`rejected` ! Sans lui, pas d'emails !

---

## Étape 5 — Démarrer le FraudDetectionConsumer

Ouvrir un **Terminal 4** et lancer :

// turbo
```bash
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.FraudDetectionConsumer
```

> ⚠️ C'est LUI qui détecte la fraude et place les transactions dans le topic `fraud` !

---

## Étape 6 — Démarrer le NotificationConsumer (Emails)

Ouvrir un **Terminal 5** et lancer :

// turbo
```bash
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.NotificationConsumer
```

---

## Étape 7 — Démarrer l'AuditLogConsumer (Journal CSV)

Ouvrir un **Terminal 6** et lancer :

// turbo
```bash
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.AuditLogConsumer
```

---

## Étape 6 — Ouvrir le Dashboard dans le navigateur

Ouvrir http://localhost:8080 dans le navigateur.

---

## Étape 7 — Envoyer des transactions de démo

Cliquer sur le bouton **"Demo"** dans le dashboard, ou envoyer via curl/PowerShell :

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/demo" -Method POST
```

---

## Étape 8 — Montrer les résultats

1. **Dashboard** : Rafraîchir la page http://localhost:8080 → voir les transactions traitées
2. **Emails** : Ouvrir la boîte email du destinataire → montrer les emails HTML reçus
3. **Audit CSV** : Ouvrir le fichier `audit-log.csv` dans le dossier du projet
4. **Logs** : Montrer les logs dans chaque terminal (validation, fraude, notifications, audit)

---

## Étape 9 — Montrer les Consumer Groups Kafka

```powershell
C:\tools\kafka_2.13-3.9.0\bin\windows\kafka-consumer-groups.bat --bootstrap-server 127.0.0.1:9092 --list
```

> Cela montre les 4 consumer groups : bank-transaction-validator, bank-fraud-detector, bank-notification-service, bank-audit-logger

---

## Étape 10 — Arrêter tout

Appuyer sur `Ctrl+C` dans chaque terminal dans l'ordre inverse :
1. AuditLogConsumer
2. NotificationConsumer
3. DashboardServer
4. Kafka Server
