package Consumer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import model.BankTransaction;
import serialization.BankTransactionDeserializer;

/**
 * Audit log consumer — records every transaction outcome for compliance.
 *
 * Input topics:
 * - bank.transactions.approved → log approved transactions
 * - bank.transactions.rejected → log rejected transactions
 * - bank.transactions.fraud → log fraud-flagged transactions
 *
 * Output: Writes a structured audit trail to both console and a CSV file.
 * In production, this would write to a database or secure audit storage.
 */
public class AuditLogConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AuditLogConsumer.class);

    private static final String TOPIC_APPROVED = "bank.transactions.approved";
    private static final String TOPIC_REJECTED = "bank.transactions.rejected";
    private static final String TOPIC_FRAUD = "bank.transactions.fraud";

    private static final String AUDIT_FILE = "audit-log.csv";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KafkaConsumer<String, BankTransaction> consumer;
    private PrintWriter auditWriter;
    private volatile boolean running = true;

    // Stats
    private int totalAudited = 0;

    public AuditLogConsumer(String bootstrapServers, String groupId) {
        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BankTransactionDeserializer.class.getName());
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        this.consumer = new KafkaConsumer<>(props);

        // Initialize CSV audit file
        try {
            auditWriter = new PrintWriter(new FileWriter(AUDIT_FILE, true)); // append mode
            // Write header if file is new
            java.io.File file = new java.io.File(AUDIT_FILE);
            if (file.length() == 0) {
                auditWriter.println(
                        "AuditTimestamp,TransactionId,SenderId,ReceiverId,Amount,Currency,Type,Status,Topic,Description");
                auditWriter.flush();
            }
        } catch (IOException e) {
            log.error("Failed to open audit log file: {}", AUDIT_FILE, e);
        }
    }

    @Override
    public void run() {
        try {
            // Subscribe to ALL downstream topics
            consumer.subscribe(List.of(TOPIC_APPROVED, TOPIC_REJECTED, TOPIC_FRAUD));
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("📋 AuditLogConsumer started — recording audit trail from:");
            log.info("   • {}", TOPIC_APPROVED);
            log.info("   • {}", TOPIC_REJECTED);
            log.info("   • {}", TOPIC_FRAUD);
            log.info("   📁 Audit file: {}", AUDIT_FILE);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            while (running) {
                ConsumerRecords<String, BankTransaction> records = consumer.poll(Duration.ofMillis(300));

                for (ConsumerRecord<String, BankTransaction> record : records) {
                    auditTransaction(record.topic(), record.value(), record.partition(), record.offset());
                }

                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            if (running)
                throw e;
        } finally {
            if (auditWriter != null) {
                auditWriter.close();
            }
            consumer.close();
            log.info("📋 AuditLogConsumer closed. Total transactions audited: {}", totalAudited);
        }
    }

    private void auditTransaction(String topic, BankTransaction tx, int partition, long offset) {
        totalAudited++;
        String auditTimestamp = LocalDateTime.now().format(FORMATTER);

        // Determine the status label from the topic
        String statusLabel;
        switch (topic) {
            case TOPIC_APPROVED:
                statusLabel = "APPROVED";
                break;
            case TOPIC_REJECTED:
                statusLabel = "REJECTED";
                break;
            case TOPIC_FRAUD:
                statusLabel = "FLAGGED";
                break;
            default:
                statusLabel = "UNKNOWN";
        }

        // Console log
        log.info(
                "📋 AUDIT #{} | {} | txId={} | sender={} → receiver={} | {} {} | type={} | topic={} | partition={} offset={}",
                totalAudited,
                statusLabel,
                tx.getTransactionId(),
                tx.getSenderId() != null ? tx.getSenderId() : "(null)",
                tx.getReceiverId() != null ? tx.getReceiverId() : "(null)",
                tx.getAmount(),
                tx.getCurrency(),
                tx.getType(),
                topic,
                partition,
                offset);

        // Write to CSV file
        if (auditWriter != null) {
            String csvLine = String.join(",",
                    auditTimestamp,
                    tx.getTransactionId(),
                    tx.getSenderId() != null ? tx.getSenderId() : "",
                    tx.getReceiverId() != null ? tx.getReceiverId() : "",
                    String.valueOf(tx.getAmount()),
                    tx.getCurrency(),
                    tx.getType() != null ? tx.getType().name() : "",
                    statusLabel,
                    topic,
                    tx.getDescription() != null ? "\"" + tx.getDescription().replace("\"", "\"\"") + "\"" : "");
            auditWriter.println(csvLine);
            auditWriter.flush();
        }
    }

    public void shutdown() {
        running = false;
        consumer.wakeup();
    }

    public int getTotalAudited() {
        return totalAudited;
    }

    // ─── Demo ────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        AuditLogConsumer audit = new AuditLogConsumer("127.0.0.1:9092", "bank-audit-logger");

        Runtime.getRuntime().addShutdownHook(new Thread(audit::shutdown));
        audit.run();
    }
}
