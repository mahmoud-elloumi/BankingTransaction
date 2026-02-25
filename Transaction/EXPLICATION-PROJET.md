# 🏦 Banking Kafka — Système de Transactions Bancaires en Temps Réel

## 📌 Objectif du Projet

Ce projet implémente un **système de traitement de transactions bancaires en temps réel** utilisant **Apache Kafka** comme middleware de messagerie. Le système permet de :

- **Produire** des transactions bancaires (virements, paiements, retraits, dépôts)
- **Valider** automatiquement chaque transaction selon des règles métier
- **Détecter les fraudes** en temps réel grâce à des algorithmes de détection
- **Visualiser** le tout via un dashboard web interactif

---

## 🏗️ Architecture du Système

```
┌─────────────────┐        ┌──────────────────────────┐        ┌───────────────────────┐
│                 │        │                          │        │  TransactionConsumer  │
│   Producer      │──────▶│   Apache Kafka           │──────▶│  (Validation)         │
│   (Envoi TX)    │        │                          │        │  ✅ Approved          │
│                 │        │   Topic:                 │        │  ❌ Rejected          │
└─────────────────┘        │   bank.transactions      │        └───────────────────────┘
                           │   .pending               │
┌─────────────────┐        │                          │        ┌───────────────────────┐
│   Dashboard     │──────▶│   Partitions: 3          │──────▶│  FraudDetection       │
│   Web (API)     │        │   Replication Factor: 1  │        │  Consumer             │
│   Port 8080     │        │                          │        │  🚨 Fraud Alerts      │
└─────────────────┘        └──────────────────────────┘        └───────────────────────┘
```

---

## 🔧 Technologies Utilisées

| Technologie | Version | Rôle |
|------------|---------|------|
| **Java** | 17+ | Langage principal |
| **Apache Kafka** | 3.9.0 | Broker de messages (mode KRaft, sans ZooKeeper) |
| **Jackson** | 2.16.1 | Sérialisation/Désérialisation JSON des transactions |
| **SLF4J + Logback** | 2.0.12 | Logging |
| **Maven** | 3.9.12 | Gestion des dépendances et build |
| **Java HttpServer** | JDK intégré | API REST + Dashboard web |

---

## 📂 Structure du Projet

```
Transaction/
├── pom.xml                                    # Configuration Maven + dépendances
├── start-demo.bat                             # Script de lancement automatique
└── src/main/java/
    ├── model/
    │   └── BankTransaction.java               # Modèle de données (POJO)
    ├── serialization/
    │   ├── BankTransactionSerializer.java      # Sérialiseur Kafka (Java → JSON bytes)
    │   └── BankTransactionDeserializer.java    # Désérialiseur Kafka (JSON bytes → Java)
    ├── Producer/
    │   └── TransactionProducer.java            # Producteur Kafka
    ├── Consumer/
    │   ├── TransactionConsumer.java            # Consommateur — Validation
    │   └── FraudDetectionConsumer.java         # Consommateur — Détection de fraude
    └── web/
        └── DashboardServer.java                # Serveur HTTP + API REST + Dashboard
```

---

## 📦 Description des Composants

### 1. `BankTransaction.java` — Le Modèle de Données

C'est le **POJO** (Plain Old Java Object) qui représente une transaction bancaire.

**Attributs :**
| Champ | Type | Description |
|-------|------|-------------|
| `transactionId` | `String` | Identifiant unique (UUID auto-généré) |
| `senderId` | `String` | Compte expéditeur (ex: ACC-001) |
| `receiverId` | `String` | Compte destinataire |
| `amount` | `double` | Montant de la transaction |
| `currency` | `String` | Devise (EUR, USD...) |
| `type` | `enum` | DEPOSIT, WITHDRAWAL, TRANSFER, PAYMENT |
| `status` | `enum` | PENDING, APPROVED, REJECTED, FLAGGED |
| `timestamp` | `LocalDateTime` | Date/heure de création |
| `description` | `String` | Description de la transaction |

**Point technique** : L'annotation `@JsonFormat` est utilisée pour la sérialisation correcte de `LocalDateTime` par Jackson.

---

### 2. `BankTransactionSerializer.java` / `BankTransactionDeserializer.java` — Sérialisation Kafka

Kafka ne transporte que des **bytes**. Ces classes convertissent :
- **Serializer** : `BankTransaction` → `byte[]` (JSON) — utilisé par le Producer
- **Deserializer** : `byte[]` (JSON) → `BankTransaction` — utilisé par le Consumer

```java
// Sérialisation : Objet Java → JSON → bytes
objectMapper.writeValueAsBytes(transaction);

// Désérialisation : bytes → JSON → Objet Java  
objectMapper.readValue(data, BankTransaction.class);
```

**Point technique** : Le module `JavaTimeModule` est enregistré pour supporter la sérialisation de `LocalDateTime` (Java 8+ Date/Time API).

---

### 3. `TransactionProducer.java` — Le Producteur Kafka

Envoie les transactions au **topic Kafka** `bank.transactions.pending`.

**Configuration du Producer :**
| Paramètre | Valeur | Explication |
|-----------|--------|-------------|
| `ACKS_CONFIG` | `all` | Attend la confirmation de toutes les répliques |
| `RETRIES_CONFIG` | `3` | Réessaie 3 fois en cas d'échec |
| `ENABLE_IDEMPOTENCE_CONFIG` | `true` | Empêche les doublons (exactement une fois) |
| `LINGER_MS_CONFIG` | `5` | Attend 5ms pour regrouper les messages (batch) |
| `BATCH_SIZE_CONFIG` | `32768` | Taille max d'un batch (32 KB) |
| `COMPRESSION_TYPE_CONFIG` | `snappy` | Compression pour réduire le trafic réseau |

**Clé de partitionnement** : `senderId` → toutes les transactions d'un même compte vont dans la même partition (garantit l'ordre).

---

### 4. `TransactionConsumer.java` — Le Consommateur de Validation

Lit les transactions depuis Kafka et applique les **règles de validation métier** :

| Règle | Condition | Résultat |
|-------|-----------|----------|
| Montant positif | `amount <= 0` | ❌ REJECTED |
| Limite maximale | `amount > 50 000 EUR` | ❌ REJECTED |
| Expéditeur requis | `senderId == null` pour TRANSFER/PAYMENT/WITHDRAWAL | ❌ REJECTED |
| Transaction valide | Toutes les règles passent | ✅ APPROVED |

**Configuration du Consumer :**
| Paramètre | Valeur | Explication |
|-----------|--------|-------------|
| `GROUP_ID_CONFIG` | `bank-transaction-validator` | Groupe de consommateurs (load balancing) |
| `AUTO_OFFSET_RESET_CONFIG` | `earliest` | Lit depuis le début si pas de position sauvegardée |
| `ENABLE_AUTO_COMMIT_CONFIG` | `false` | Commit manuel pour garantir le traitement |
| `MAX_POLL_RECORDS_CONFIG` | `50` | Maximum 50 messages par poll |

---

### 5. `FraudDetectionConsumer.java` — Détection de Fraude en Temps Réel

Analyse chaque transaction avec **4 règles de détection** :

| Règle | Condition | Niveau de Risque |
|-------|-----------|-----------------|
| **Montant élevé** | `amount > 10 000 EUR` | 🔴 HIGH |
| **Vélocité** | `> 3 transactions en 60 secondes` depuis le même compte | 🟡 MEDIUM |
| **Montant rond suspect** | `amount >= 5000` et multiple de 1000 | 🟢 LOW |
| **Destinataire inconnu** | Transfer vers un compte non whitelisté | 🟢 LOW |

**Mécanisme de vélocité** : Utilise un `ConcurrentHashMap<String, Deque<LocalDateTime>>` pour tracker les timestamps des transactions par expéditeur dans une fenêtre glissante de 60 secondes.

---

### 6. `DashboardServer.java` — Dashboard Web + API REST

Serveur HTTP embarqué qui fournit :

| Endpoint | Méthode | Description |
|----------|---------|-------------|
| `/` | GET | Dashboard HTML interactif |
| `/api/send` | POST | Envoyer une transaction au topic Kafka |
| `/api/transactions` | GET | Liste des transactions traitées (JSON) |
| `/api/stats` | GET | Statistiques (envoyées, approuvées, rejetées, fraudes) |
| `/api/demo` | POST | Envoyer 5 transactions de démonstration |

Le dashboard intègre un **Consumer Kafka** qui lit le topic en temps réel et applique les mêmes règles de validation et de détection de fraude.

---

## 🔑 Concepts Kafka Utilisés

### 1. Topic
Un **topic** est un canal de messages nommé. Notre topic : `bank.transactions.pending`

### 2. Partitions
Le topic est divisé en **3 partitions** pour le parallélisme. Les transactions sont réparties selon la clé (`senderId`).

### 3. Producer
Le producteur **publie** des messages dans le topic. Chaque message a une **clé** (senderId) et une **valeur** (BankTransaction sérialisée en JSON).

### 4. Consumer Group
Les consommateurs avec le même `GROUP_ID` se **partagent** les partitions. Cela permet le **load balancing** (chaque partition est lue par un seul consommateur du groupe).

### 5. Offset
Chaque message dans une partition a un **offset** (position). Le consumer **commit** son offset après traitement pour ne pas retraiter les messages.

### 6. Sérialisation
Kafka transporte des `byte[]`. On utilise des **Serializer/Deserializer** personnalisés pour convertir nos objets Java en JSON et vice-versa.

### 7. Mode KRaft
Kafka fonctionne en mode **KRaft** (Kafka Raft) — sans ZooKeeper. C'est le nouveau mode recommandé depuis Kafka 3.3+.

### 8. Idempotence
Le Producer est configuré avec `enable.idempotence=true` : garantit que chaque message est écrit **exactement une fois**, même en cas de retry.

---

## 🎬 Scénario de Démonstration

### Transactions de test envoyées :

| # | Expéditeur | Destinataire | Montant | Type | Résultat Attendu |
|---|-----------|-------------|---------|------|-----------------|
| 1 | ACC-001 | ACC-002 | 1 500 € | TRANSFER | ✅ APPROVED |
| 2 | ACC-003 | ACC-001 | 250.75 € | PAYMENT | ✅ APPROVED |
| 3 | ACC-002 | — | 5 000 € | WITHDRAWAL | ✅ APPROVED + ⚠️ ROUND-LARGE-AMOUNT |
| 4 | — | ACC-004 | 3 000 € | DEPOSIT | ✅ APPROVED |
| 5 | ACC-005 | ACC-999 | 98 000 € | TRANSFER | 🚨 FLAGGED (HIGH-AMOUNT + UNKNOWN-RECEIVER + ROUND-LARGE-AMOUNT) |

La **transaction #5** est flaguée car :
- 98 000 € > 10 000 € (seuil de détection)
- ACC-999 n'est pas dans la liste des comptes connus
- 98 000 est un montant rond ≥ 5 000 et multiple de 1 000

---

## ▶️ Comment Exécuter

### Option 1 : Script automatique
```
Double-cliquer sur start-demo.bat
```

### Option 2 : Manuellement (3 terminaux)
```bash
# Terminal 1 — Kafka
C:\tools\kafka_2.13-3.9.0\bin\windows\kafka-server-start.bat C:\tools\kafka_2.13-3.9.0\config\kraft\server.properties

# Terminal 2 — Dashboard
cd C:\Users\MAHMOUD\Downloads\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" web.DashboardServer

# Navigateur → http://localhost:8080
```

---

## 👨‍💻 Auteur

Projet réalisé dans le cadre d'un TP sur Apache Kafka — Traitement de transactions bancaires en temps réel avec détection de fraude.
