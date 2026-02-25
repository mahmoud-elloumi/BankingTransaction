package Producer;

import model.BankTransaction;
import model.BankTransaction.TransactionType;
import serialization.BankTransactionSerializer;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Produces bank transactions to Kafka topics.
 *
 * Topics used:
 * - bank.transactions.pending → all new transactions (key = senderId)
 */
public class TransactionProducer {

    private static final Logger log = LoggerFactory.getLogger(TransactionProducer.class);

    public static final String TOPIC_PENDING = "bank.transactions.pending";

    private final KafkaProducer<String, BankTransaction> producer;

    public TransactionProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                BankTransactionSerializer.class.getName());

        // Reliability settings
        props.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        props.setProperty(ProducerConfig.RETRIES_CONFIG, "3");
        props.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        // Performance settings
        props.setProperty(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, "32768"); // 32 KB
        props.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        this.producer = new KafkaProducer<>(props);
    }

    /**
     * Sends a transaction and returns immediately (async callback).
     */
    public void send(BankTransaction transaction) {
        ProducerRecord<String, BankTransaction> record = new ProducerRecord<>(TOPIC_PENDING, transaction.getSenderId(),
                transaction);

        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                log.info("Transaction sent ▶ id={} | topic={} partition={} offset={}",
                        transaction.getTransactionId(),
                        metadata.topic(), metadata.partition(), metadata.offset());
            } else {
                log.error("Failed to send transaction id={}", transaction.getTransactionId(), exception);
            }
        });
    }

    public void flush() {
        producer.flush();
    }

    public void close() {
        producer.close();
    }

    // ─── Demo ────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws InterruptedException {
        TransactionProducer producer = new TransactionProducer("127.0.0.1:9092");

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 1: Normal transactions (different senders)
        // ═══════════════════════════════════════════════════════════════════
        Object[][] normalData = {
                { "ACC-003", "ACC-001", 250.75, "EUR", TransactionType.PAYMENT, "Online shopping" },
                { null, "ACC-004", 3000.00, "EUR", TransactionType.DEPOSIT, "Salary deposit" },
        };

        for (Object[] d : normalData) {
            BankTransaction tx = new BankTransaction(
                    (String) d[0], (String) d[1],
                    (double) d[2], (String) d[3],
                    (TransactionType) d[4], (String) d[5]);
            producer.send(tx);
            log.info("Queued (normal): {}", tx);
            Thread.sleep(200);
        }

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 2: VELOCITY ATTACK — 5 rapid transactions from ACC-001
        // > 3 transactions in 60s → MEDIUM RISK (VELOCITY-EXCEEDED)
        // Transactions 1-3: clean ✅
        // Transaction 4+: 🚨 FLAGGED (VELOCITY-EXCEEDED)
        // ═══════════════════════════════════════════════════════════════════
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔴 VELOCITY ATTACK SIMULATION — Sending 5 rapid txns from ACC-001");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        for (int i = 1; i <= 5; i++) {
            BankTransaction tx = new BankTransaction(
                    "ACC-001", // same sender every time!
                    "ACC-002",
                    100.00 + (i * 50), // varying small amounts
                    "EUR",
                    TransactionType.TRANSFER,
                    "Rapid transfer #" + i);
            producer.send(tx);
            log.info("Queued (velocity #{}/5): {} — amount={} EUR", i, tx.getTransactionId(), tx.getAmount());
            Thread.sleep(500); // 500ms between each → all 5 within ~2.5 seconds (< 60s window)
        }

        // ═══════════════════════════════════════════════════════════════════
        // SCENARIO 3: Suspicious large transfer (HIGH risk)
        // ═══════════════════════════════════════════════════════════════════
        BankTransaction bigTx = new BankTransaction(
                "ACC-005", "ACC-999", 98000.00, "EUR",
                TransactionType.TRANSFER, "Suspicious large transfer");
        producer.send(bigTx);
        log.info("Queued (suspicious): {}", bigTx);

        producer.flush();
        producer.close();
        log.info("✅ All transactions sent — check FraudDetectionConsumer logs for alerts!");
    }
}