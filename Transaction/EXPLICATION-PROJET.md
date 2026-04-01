# 🏦 TransactFlow — Système de Transactions Bancaires en Temps Réel

## 📌 Objectif du Projet

Ce projet implémente un **système complet de surveillance des transactions bancaires en temps réel** utilisant **Apache Kafka** comme middleware de messagerie. Le système utilise **4 consumers Kafka** indépendants pour démontrer les concepts de traitement distribué et événementiel.

### Fonctionnalités Principales:

- ✅ **Authentification sécurisée** avec gestion de sessions et tokens UUID
- 📤 **Produire** des transactions bancaires (virements, paiements, retraits, dépôts)
- ✔️ **Valider** automatiquement chaque transaction selon des règles métier
- 🚨 **Détecter les fraudes** en temps réel avec 4 règles de scoring
- 📧 **Notifier par email** chaque transaction (approuvée, rejetée, fraude) via SMTP Gmail
- 📋 **Auditer** toutes les transactions dans un fichier CSV pour la traçabilité
- 🎨 **Visualiser** en temps réel via un dashboard web moderne (Dark Theme TransactFlow)
- 📊 **Statistiques en direct** (transactions envoyées, approuvées, rejetées, fraudes)

---

## 🏗️ Architecture du Système

Le système utilise **4 topics Kafka** et **4 consumers** indépendants pour séparer les différentes étapes du traitement :

```
┌─────────────────┐        ┌──────────────────────────┐        ┌───────────────────────┐
│                 │        │                          │        │  TransactionConsumer  │
│   Producer      │──────▶│   Apache Kafka           │──────▶│  (Validation)         │
│   (Envoi TX)    │        │                          │        │  ✅ Approved          │
│                 │        │   Topic:                 │        │  ❌ Rejected          │
└─────────────────┘        │   bank.transactions      │        └──────────┬────────────┘
                           │   .pending               │                   │
┌─────────────────┐        │                          │        ┌──────────▼────────────┐
│   Dashboard     │──────▶│   Partitions: 3          │──────▶│  FraudDetection       │
│   Web (API)     │        │   Replication Factor: 1  │        │  Consumer             │
│   Port 8080     │        │                          │        │  🚨 Fraud Alerts      │
└─────────────────┘        └──────────────────────────┘        └──────────┬────────────┘
                                                                         │
                           ┌─────────────────────────────────────────────┘
                           │  Topics downstream:
                           │  • bank.transactions.approved
                           │  • bank.transactions.rejected
                           │  • bank.transactions.fraud
                           │
                    ┌──────▼──────────────┐     ┌──────────────────────┐
                    │ NotificationConsumer │     │  AuditLogConsumer    │
                    │ 📧 Envoi d'emails   │     │  📋 Journal d'audit  │
                    │ HTML via Gmail SMTP │     │  Fichier CSV         │
                    └─────────────────────┘     └──────────────────────┘
```

---

## 🔧 Technologies Utilisées

| Technologie | Version | Rôle |
|------------|---------|------|
| **Java** | 17+ | Langage principal |
| **Apache Kafka** | 3.9.0 | Broker de messages (mode KRaft, sans ZooKeeper) |
| **Jackson** | 2.16.1 | Sérialisation/Désérialisation JSON des transactions |
| **Jakarta Mail** | 2.0.1 | Envoi d'emails SMTP (notifications par email) |
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
    │   ├── FraudDetectionConsumer.java         # Consommateur — Détection de fraude
    │   ├── NotificationConsumer.java           # Consommateur — Notifications par email
    │   └── AuditLogConsumer.java               # Consommateur — Journal d'audit CSV
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

### 6. `NotificationConsumer.java` — Notifications par Email

Envoie un **email HTML** pour chaque transaction traitée via SMTP Gmail.

| Topic écouté | Type d'email | Destinataire |
|---|---|---|
| `bank.transactions.approved` | ✅ Confirmation de succès | Client (sender) |
| `bank.transactions.rejected` | ❌ Notification de rejet | Client (sender) |
| `bank.transactions.fraud` | 🚨 Alerte fraude urgente | Équipe compliance |

**Configuration SMTP (dans le code) :**
| Paramètre | Valeur | Explication |
|-----------|--------|-------------|
| `SMTP_HOST` | `smtp.gmail.com` | Serveur SMTP de Gmail |
| `SMTP_PORT` | `587` | Port TLS de Gmail |
| `EMAIL_FROM` | `votre.email@gmail.com` | Adresse d'envoi (avec App Password) |
| `EMAIL_TO` | `destinataire@gmail.com` | Adresse de réception des notifications |

**Point technique** : Utilise **Jakarta Mail** (anciennement JavaMail) avec authentification SMTP et STARTTLS. Les emails sont en **HTML** avec des templates visuels différents pour chaque type de transaction.

⚠️ **Important** : Il faut utiliser un **App Password** Google (pas le mot de passe normal). Générez-en un sur : https://myaccount.google.com/apppasswords

---

### 7. `AuditLogConsumer.java` — Journal d'Audit

Enregistre **toutes les transactions** traitées dans un fichier **CSV** (`audit-log.csv`) pour la traçabilité.

| Topic écouté | Données enregistrées |
|---|---|
| `bank.transactions.approved` | Timestamp, ID, montant, statut APPROVED |
| `bank.transactions.rejected` | Timestamp, ID, montant, statut REJECTED |
| `bank.transactions.fraud` | Timestamp, ID, montant, statut FLAGGED |

**Format du fichier CSV :**
```
AuditTimestamp,TransactionId,SenderId,ReceiverId,Amount,Currency,Type,Status,Topic,Description
```

**Configuration du Consumer :**
| Paramètre | Valeur | Explication |
|-----------|--------|-------------|
| `GROUP_ID_CONFIG` | `bank-audit-logger` | Groupe dédié au journal d'audit |
| `AUTO_OFFSET_RESET_CONFIG` | `earliest` | Lit depuis le début pour ne rien manquer |

---

### 8. `DashboardServer.java` — Dashboard Web + API REST

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
Un **topic** est un canal de messages nommé. Notre système utilise **4 topics** : `pending`, `approved`, `rejected`, `fraud`.

### 2. Partitions
Chaque topic est divisé en **3 partitions** pour le parallélisme. Les transactions sont réparties selon la clé (`senderId`).

### 3. Producer
Le producteur **publie** des messages dans le topic. Chaque message a une **clé** (senderId) et une **valeur** (BankTransaction sérialisée en JSON).

### 4. Consumer Group
Les consommateurs avec le même `GROUP_ID` se **partagent** les partitions. Cela permet le **load balancing** (chaque partition est lue par un seul consommateur du groupe).

Notre système utilise **4 consumer groups distincts** :

| Consumer Group | Consumer | Topics lus | Rôle |
|---|---|---|---|
| `bank-transaction-validator` | TransactionConsumer | `pending` | Validation métier |
| `bank-fraud-detector` | FraudDetectionConsumer | `pending` | Détection de fraude |
| `bank-notification-service` | NotificationConsumer | `approved`, `rejected`, `fraud` | Envoi d'emails |
| `bank-audit-logger` | AuditLogConsumer | `approved`, `rejected`, `fraud` | Journal d'audit CSV |

> **Point clé** : Le topic `pending` est lu par **2 consumer groups différents** (validator + fraud), donc chaque transaction est traitée par les deux. Les topics downstream (`approved`, `rejected`, `fraud`) sont aussi lus par **2 consumer groups** (notifications + audit), ce qui illustre le concept de **fan-out** Kafka.

### 5. Offset
Chaque message dans une partition a un **offset** (position). Le consumer **commit** son offset après traitement pour ne pas retraiter les messages.

### 6. Sérialisation
Kafka transporte des `byte[]`. On utilise des **Serializer/Deserializer** personnalisés pour convertir nos objets Java en JSON et vice-versa.

### 7. Mode KRaft
Kafka fonctionne en mode **KRaft** (Kafka Raft) — sans ZooKeeper. C'est le nouveau mode recommandé depuis Kafka 3.3+.

### 8. Idempotence
Le Producer est configuré avec `enable.idempotence=true` : garantit que chaque message est écrit **exactement une fois**, même en cas de retry.

### 9. Fan-Out
Un même message peut être lu par **plusieurs consumer groups**. Dans notre système, chaque transaction passe par au minimum **3 consumers** différents (validation + fraude + dashboard), illustrant le pattern **fan-out** de Kafka.

---

## 📡 Explication des 4 Topics — En Code

Le système utilise **4 topics Kafka** pour séparer les étapes du traitement. Voici comment chaque topic est implémenté dans le code :

### 📥 Topic 1 : `bank.transactions.pending`

> **Rôle** : C'est le point d'entrée. Toutes les nouvelles transactions arrivent ici avant d'être traitées.

**Déclaration** (dans `TransactionProducer.java`) :
```java
public static final String TOPIC_PENDING  = "bank.transactions.pending";
public static final String TOPIC_APPROVED = "bank.transactions.approved";
public static final String TOPIC_REJECTED = "bank.transactions.rejected";
public static final String TOPIC_FRAUD    = "bank.transactions.fraud";
```

**Production** — Le Producer envoie les transactions vers ce topic (dans `TransactionProducer.java`) :
```java
public void send(BankTransaction transaction) {
    ProducerRecord<String, BankTransaction> record = 
        new ProducerRecord<>(TOPIC_PENDING, transaction.getSenderId(), transaction);
    
    producer.send(record, (metadata, exception) -> {
        if (exception == null) {
            log.info("Transaction sent ▶ id={} | topic={} partition={} offset={}",
                    transaction.getTransactionId(),
                    metadata.topic(), metadata.partition(), metadata.offset());
        }
    });
}
```
> La **clé** du message est `senderId` → Kafka envoie toutes les transactions du même compte dans la même partition (garantit l'ordre).

**Consommation** — Le TransactionConsumer lit depuis ce topic (dans `TransactionConsumer.java`) :
```java
consumer.subscribe(List.of(TOPIC_PENDING));
log.info("TransactionConsumer started — listening on {}", TOPIC_PENDING);

while (running) {
    ConsumerRecords<String, BankTransaction> records = consumer.poll(Duration.ofMillis(300));
    for (ConsumerRecord<String, BankTransaction> record : records) {
        process(record.value());  // valide puis route vers approved ou rejected
    }
    if (!records.isEmpty()) {
        consumer.commitSync();  // commit manuel des offsets
    }
}
```

---

### ✅ Topic 2 : `bank.transactions.approved`

> **Rôle** : Reçoit les transactions qui ont passé toutes les règles de validation avec succès.

**Production** — Le TransactionConsumer publie vers ce topic après validation réussie (dans `TransactionConsumer.java`) :
```java
private void process(BankTransaction tx) {
    String reason = validate(tx);

    if (reason == null) {
        tx.setStatus(TransactionStatus.APPROVED);
        log.info("✅ APPROVED  | {}", tx);

        // Publier vers le topic approved
        producer.send(new ProducerRecord<>(TOPIC_APPROVED, tx.getSenderId(), tx), (metadata, ex) -> {
            if (ex == null) {
                log.info("  → Routed to {} | partition={} offset={}", 
                    TOPIC_APPROVED, metadata.partition(), metadata.offset());
            }
        });
    }
}
```
> Quand la validation passe → le statut devient `APPROVED` et la transaction est publiée vers `bank.transactions.approved`.

---

### ❌ Topic 3 : `bank.transactions.rejected`

> **Rôle** : Reçoit les transactions qui ont échoué la validation (montant invalide, expéditeur manquant, etc.).

**Production** — Le TransactionConsumer publie vers ce topic après rejet (dans `TransactionConsumer.java`) :
```java
private void process(BankTransaction tx) {
    String reason = validate(tx);

    // ... (cas approved ci-dessus)

    if (reason != null) {
        tx.setStatus(TransactionStatus.REJECTED);
        log.warn("❌ REJECTED  | {} | Reason: {}", tx, reason);

        // Publier vers le topic rejected
        producer.send(new ProducerRecord<>(TOPIC_REJECTED, tx.getSenderId(), tx), (metadata, ex) -> {
            if (ex == null) {
                log.info("  → Routed to {} | partition={} offset={}", 
                    TOPIC_REJECTED, metadata.partition(), metadata.offset());
            }
        });
    }
}
```

**Règles de validation** qui déclenchent un rejet :
```java
private String validate(BankTransaction tx) {
    if (tx.getAmount() <= 0) {
        return "Amount must be positive";              // Montant négatif ou zéro
    }
    if (tx.getAmount() > MAX_AMOUNT) {                 // MAX_AMOUNT = 50 000 EUR
        return "Amount exceeds hard limit of " + MAX_AMOUNT;
    }
    if (tx.getType() != TransactionType.DEPOSIT && tx.getSenderId() == null) {
        return "Sender account is required for " + tx.getType();
    }
    return null;  // null = pas d'erreur = APPROVED
}
```

---

### 🚨 Topic 4 : `bank.transactions.fraud`

> **Rôle** : Reçoit les transactions suspectes détectées par le module anti-fraude.

**Consommation** — Le FraudDetectionConsumer lit aussi depuis `pending` (dans `FraudDetectionConsumer.java`) :
```java
consumer.subscribe(List.of(TOPIC_PENDING));
log.info("FraudDetectionConsumer started — listening on {}", TOPIC_PENDING);
```
> Le même topic `pending` est lu par **deux consumers différents** (TransactionConsumer et FraudDetectionConsumer), chacun dans un **group_id différent**, donc chacun reçoit **tous** les messages.

**Analyse de fraude** — 4 règles sont appliquées (dans `FraudDetectionConsumer.java`) :
```java
private void analyze(BankTransaction tx) {
    List<String> alerts = new ArrayList<>();

    // Règle 1 — Montant élevé (> 10 000 EUR → HIGH risk)
    if (tx.getAmount() > HIGH_AMOUNT_LIMIT) {
        alerts.add("HIGH-AMOUNT");
    }

    // Règle 2 — Vélocité (> 3 transactions en 60s → MEDIUM risk)
    if (tx.getSenderId() != null && checkVelocity(tx.getSenderId())) {
        alerts.add("VELOCITY-EXCEEDED");
    }

    // Règle 3 — Montant rond suspect (>= 5000 et multiple de 1000 → LOW risk)
    if (isRoundLargeAmount(tx.getAmount())) {
        alerts.add("ROUND-LARGE-AMOUNT");
    }

    // Règle 4 — Destinataire inconnu (→ LOW risk)
    if (tx.getType() == TransactionType.TRANSFER && !knownReceivers.contains(tx.getReceiverId())) {
        alerts.add("UNKNOWN-RECEIVER");
    }
```

**Production** — Si au moins une alerte est déclenchée, la transaction est publiée vers `fraud` :
```java
    if (!alerts.isEmpty()) {
        tx.setStatus(TransactionStatus.FLAGGED);
        log.warn("🚨 FRAUD ALERT | txId={} | flags={}", tx.getTransactionId(), alerts);

        // Publier vers le topic fraud
        fraudProducer.send(new ProducerRecord<>(TOPIC_FRAUD, tx.getSenderId(), tx), (metadata, ex) -> {
            if (ex == null) {
                log.info("  → Published to {} | partition={} offset={}", 
                    TOPIC_FRAUD, metadata.partition(), metadata.offset());
            }
        });
        fraudProducer.flush();
    }
}
```

---

### 🔄 Résumé du Flux Multi-Topics

```
Producer/Dashboard
       │
       ▼ (produce)
bank.transactions.pending ─────┬──────────────────────────────────┐
       │                       │                                  │
       ▼ (consume)             ▼ (consume)                       ▼ (consume)
TransactionConsumer      FraudDetectionConsumer             DashboardServer
       │                       │
       ├─ ✅ → bank.transactions.approved ──┬──────────────────────┐
       ├─ ❌ → bank.transactions.rejected ──┤                      │
       │                       │            │                      │
       │                       └─ 🚨 → bank.transactions.fraud ──┤
       │                                    │                      │
                                            ▼                      ▼
                                   NotificationConsumer     AuditLogConsumer
                                   📧 Envoie des emails     📋 Écrit dans
                                   HTML par SMTP Gmail      audit-log.csv
```

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

### Prérequis
1. ✅ **Java 17+** installé
2. ✅ **Apache Kafka 3.9.0** installé dans `C:\tools\kafka_2.13-3.9.0`
3. ✅ **Maven** installé et compilé : `mvn clean package -DskipTests`
4. ✅ **Email configuré** : Modifier `EMAIL_FROM`, `EMAIL_PASSWORD` et `EMAIL_TO` dans `NotificationConsumer.java`

### Option 1 ⭐ : LANCER TOUT AUTOMATIQUEMENT (Recommandé)

#### Méthode A - Script Batch (Windows)
```bash
Double-cliquer sur: C:\BankingTransaction\START_ALL.bat
```

#### Méthode B - Script PowerShell
```powershell
Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope CurrentUser
C:\BankingTransaction\START_ALL.ps1
```

✅ **Résultat :** 5 fenêtres s'ouvrent automatiquement:
- Terminal 1: Kafka Server
- Terminal 2: Dashboard Server (port 8080)
- Terminal 3: Notification Consumer (📧 Emails)
- Terminal 4: Fraud Detection (🚨 Alertes)
- Terminal 5: Audit Log (📋 Logging)

### Option 2 : Manuellement (5 terminaux)

#### Terminal 1 — Kafka Server
```bash
cd C:\tools\kafka_2.13-3.9.0\bin\windows
.\kafka-server-start.bat ..\..\config\kraft\server.properties
```
⏳ **Attendre 30-45 secondes** jusqu'à voir `[ControllerServer id=1]`

#### Terminal 2 — Dashboard Server
```bash
cd C:\BankingTransaction\Transaction
java -jar target\banking-kafka-1.0.0-jar-with-dependencies.jar
```
✅ **Vous verrez :** `🌐 HTTP Dashboard Server started on port 8080`

#### Terminal 3 — Notification Consumer (Emails)
```bash
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.NotificationConsumer
```
✅ **Vous verrez :** `📧 NotificationConsumer started`

#### Terminal 4 — Fraud Detection Consumer
```bash
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.FraudDetectionConsumer
```
✅ **Vous verrez :** `🚨 FraudDetectionConsumer started`

#### Terminal 5 — Audit Log Consumer
```bash
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.AuditLogConsumer
```
✅ **Vous verrez :** `📋 AuditLogConsumer started`

### ✅ Accéder au Dashboard

Après 45 secondes, ouvrir votre navigateur:
```
http://localhost:8080
```

**Identifiants de test :**
| Utilisateur | Mot de passe | Rôle |
|-----------|-------------|------|
| `admin` | `admin123` | Administrateur |
| `mahmoud` | `kafka2026` | Utilisateur |
| `operateur` | `bank@2026` | Opérateur |

### 🎯 Vérifier le Fonctionnement

1. **Dashboard** : http://localhost:8080
   - ✅ Voir les statistiques en temps réel
   - ✅ Voir le logo TransactFlow animé
   - ✅ Voir le badge "Kafka Connected — 127.0.0.1:9092"

2. **Envoyer une transaction :**
   - Remplir le formulaire "Nouvelle transaction"
   - Cliquer "Envoyer"
   - ✅ Transaction apparaît immédiatement dans le feed
   - ✅ Email envoyé (visible dans Terminal 3)

3. **Tester la fraude :**
   - Envoyer transaction avec montant > 50,000
   - ✅ Transaction marquée "FRAUD" dans le dashboard
   - ✅ Alerte dans Terminal 4
   - ✅ Email d'alerte reçu

4. **Consulter l'audit :**
   - Fichier `audit-log.csv` généré dans `C:\BankingTransaction\Transaction\`
   - ✅ Tous les événements enregistrés avec timestamps

### 🛑 Pour Arrêter

Dans chaque terminal, appuyer sur:
```
Ctrl + C
```

Kafka mettra ~10 secondes à s'arrêter (normal)

---

## 🎨 Design & Interface Utilisateur

### TransactFlow Branding
Le projet utilise un **design professionnel moderne** avec le thème **TransactFlow** :

**Palette de couleurs :**
- 🔵 Primaire: `#0a0e1a` (Dark Navy) — Fond principal
- 🔷 Accent: `#00b4d8` (Cyan Bright) — Boutons et highlights
- 🟦 Secondaire: `#0077b6` (Navy Blue) — Textes importants
- ⚪ Texte: `#ffffff` (White) — Lisibilité optimale

**Caractéristiques :**
- ✨ **Dark Theme** moderne et élégant
- 🔮 **Glassmorphism** avec backdrop-filter blur
- ✏️ **Fonts Premium** : Outfit (headings), DM Sans (subtitles), Inter (body)
- 🎬 **Animations fluides** avec CSS Keyframes
- 📱 **Responsive Design** (mobile-friendly)
- ♿ **Accessibilité** améliorée (contrast ratios optimaux)
- 🎯 **Logo SVG animé** TransactFlow

### Pages du Dashboard

#### 1. Page de Connexion (`login.html`)
- Formulaire d'authentification sécurisé
- Toggle password visibility
- Messages d'erreur animés
- Spinner loading state
- Support de 3 comptes utilisateurs

#### 2. Dashboard Principal (`dashboard.html`)
- **Stats Grid** : 4 statistiques en temps réel
- **Formulaire d'envoi** : Interface intuitive pour créer transactions
- **Feed en temps réel** : Affichage live avec auto-refresh 2 secondes
- **Filtrage des transactions** : Par statut (Approuvée, Rejetée, Fraude)
- **Détection fraude** : Visualisation des alertes
- **Header personnalisé** : Logo animé + badge Kafka + utilisateur connecté

---

## ✅ Améliorations & Corrections Appliquées

| # | Problème | Solution | Résultat |
|---|----------|----------|---------|
| 1 | JAR non exécutable | Ajout `<mainClass>` dans pom.xml | ✅ Commande `java -jar` fonctionne |
| 2 | Input focus invisible | Background bleu transparent au focus | ✅ Texte lisible au focus |
| 3 | Navbar blanc au lieu foncé | Changement background → `rgba(10,14,26,0.95)` | ✅ Design cohérent |
| 4 | Username presque invisible | Texte blanc sur navbar foncée | ✅ Excellent contraste |
| 5 | Design inconsistant | Application uniforme du thème TransactFlow | ✅ Branding cohérent partout |

---

## 📊 Technologies Mises à Jour

| Composant | Avant | Après | Amélioration |
|-----------|-------|-------|-------------|
| Frontend | Generic | TransactFlow Dark Theme | +100% UI/UX |
| Navbar | White | Dark (#0a0e1a) | Cohérence design |
| Exécution | JAR sans main class | JAR auto-exécutable | Simplicité déploiement |
| Scripts | Aucun | START_ALL.bat + START_ALL.ps1 | Lancement ultra-simple |

---

## 🎓 Concepts Démontrés

Ce projet couvre les **concepts avancés** suivants :

1. **Event-Driven Architecture** — Découpling total
2. **Apache Kafka** — Production, consumption, topics, partitions
3. **Sérialisation JSON** — Custom Serializers/Deserializers
4. **Consumer Groups** — Fan-out pattern avec 4 groups différents
5. **Mode KRaft** — Kafka sans ZooKeeper
6. **HTTP REST API** — Endpoints JSON
7. **Session Management** — Tokens et authentification
8. **Real-time Updates** — Polling client-side
9. **Fraud Detection** — Algorithmes de scoring
10. **Email SMTP** — Integration Gmail avec authentification
11. **Logging & Audit** — Traçabilité complète
12. **UI/UX Modern** — Design responsive et accessible
13. **Maven Build** — Multi-module, fat JAR
14. **Deployment** — Scripts d'automatisation

---

## 👨‍💻 Auteur & Statut

**Projet :** TransactFlow v1.0.0
**Status :** ✅ **Production Ready** — Prêt pour présentation
**Réalisé dans le cadre :** TP Apache Kafka — Système complet de transactions bancaires en temps réel

**Capabilities:**
- ✅ Traitement distribué avec 4 consumers Kafka
- ✅ Validation métier automatique
- ✅ Détection de fraude en temps réel
- ✅ Notifications email SMTP
- ✅ Audit et traçabilité complète
- ✅ Dashboard web moderne et interactif
- ✅ Lancement automatisé (batch script)
- ✅ Documentation complète

---

## 📝 Fichiers Importants

| Fichier | Location | Purpose |
|---------|----------|---------|
| `START_ALL.bat` | `C:\BankingTransaction\` | 🚀 Lance tous les services automatiquement |
| `START_ALL.ps1` | `C:\BankingTransaction\` | 🚀 Alternative PowerShell |
| `GUIDE_LANCEMENT_RAPIDE.txt` | `C:\BankingTransaction\` | 📖 Guide de lancement complet |
| `RAPPORT_TRANSACTFLOW.txt` | `C:\BankingTransaction\` | 📊 Rapport détaillé du projet |
| `EXPLICATION-PROJET.md` | `Transaction/` | 📚 Cette documentation |
| `audit-log.csv` | `Transaction/` | 📋 Journal d'audit généré |
| `pom.xml` | `Transaction/` | ⚙️ Configuration Maven |
| `target/*.jar` | `Transaction/target/` | 📦 JAR exécutable fat-JAR |

---

**Dernière mise à jour :** 29 Mars 2026
**Version :** 1.0.0 (Stable)
**Ready for Demo :** ✅ YES
