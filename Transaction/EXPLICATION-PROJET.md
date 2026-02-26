# 🏦 Banking Kafka — Système de Transactions Bancaires en Temps Réel

## 📌 Objectif du Projet

Ce projet implémente un **système de traitement de transactions bancaires en temps réel** utilisant **Apache Kafka** comme middleware de messagerie. Le système utilise **4 consumers Kafka** indépendants pour démontrer les concepts de traitement distribué. Le système permet de :

- **Produire** des transactions bancaires (virements, paiements, retraits, dépôts)
- **Valider** automatiquement chaque transaction selon des règles métier
- **Détecter les fraudes** en temps réel grâce à des algorithmes de détection
- **Notifier par email** chaque transaction (approuvée, rejetée, fraude) via SMTP Gmail
- **Auditer** toutes les transactions dans un fichier CSV pour la traçabilité
- **Visualiser** le tout via un dashboard web interactif

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
1. **Java 17+** installé
2. **Apache Kafka 3.9.0** installé dans `C:\tools\kafka_2.13-3.9.0`
3. **Maven** installé
4. **Compiler le projet** : `mvn clean package -DskipTests`
5. **Configurer l'email** : Modifier `EMAIL_FROM`, `EMAIL_PASSWORD` et `EMAIL_TO` dans `NotificationConsumer.java`

### Option 1 : Script automatique
```
Double-cliquer sur start-demo.bat
```

### Option 2 : Manuellement (5 terminaux)
```bash
# Terminal 1 — Kafka Server
C:\tools\kafka_2.13-3.9.0\bin\windows\kafka-server-start.bat C:\tools\kafka_2.13-3.9.0\config\kraft\server.properties

# Terminal 2 — Dashboard (Producer + Consumer + Web)
cd C:\BankingTransaction\Transaction
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" web.DashboardServer
# → Ouvrir http://localhost:8080

# Terminal 3 — TransactionConsumer (Validation)
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.TransactionConsumer

# Terminal 4 — NotificationConsumer (Envoi d'emails)
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.NotificationConsumer

# Terminal 5 — AuditLogConsumer (Journal CSV)
java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.AuditLogConsumer
```

> **Note** : Le `FraudDetectionConsumer` est intégré dans le `DashboardServer`, mais peut aussi être lancé séparément :
> ```bash
> java -cp "target\banking-kafka-1.0.0-jar-with-dependencies.jar" Consumer.FraudDetectionConsumer
> ```

### Vérifier le fonctionnement

1. **Dashboard** : Ouvrir http://localhost:8080 et cliquer sur "Demo" pour envoyer 5 transactions
2. **Emails** : Vérifier la boîte de réception de l'adresse `EMAIL_TO` configurée
3. **Audit** : Consulter le fichier `audit-log.csv` généré dans le dossier du projet
4. **Logs** : Observer les logs dans chaque terminal pour voir le traitement en temps réel

---

## 👨‍💻 Auteur

Projet réalisé dans le cadre d'un TP sur Apache Kafka — Traitement de transactions bancaires en temps réel avec 4 consumers (validation, détection de fraude, notifications email, audit).
