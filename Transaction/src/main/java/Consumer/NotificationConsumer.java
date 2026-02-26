package Consumer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.mail.*;
import jakarta.mail.internet.*;

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
 * Notification consumer — sends email notifications for each transaction.
 *
 * Input topics:
 * - bank.transactions.approved → email sender: transaction approved
 * - bank.transactions.rejected → email sender: transaction rejected
 * - bank.transactions.fraud → email compliance team: fraud alert
 *
 * Uses SMTP (Gmail) to send real emails for every transaction event.
 */
public class NotificationConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private static final String TOPIC_APPROVED = "bank.transactions.approved";
    private static final String TOPIC_REJECTED = "bank.transactions.rejected";
    private static final String TOPIC_FRAUD = "bank.transactions.fraud";

    // ═══════════════════════════════════════════════════════════════════
    // 📧 SMTP Configuration — Gmail
    // ═══════════════════════════════════════════════════════════════════
    // ⚠️ IMPORTANT: Use an "App Password" from Google, NOT your real password.
    // Go to: https://myaccount.google.com/apppasswords
    // Generate an app password for "Mail" and paste it below.
    // ═══════════════════════════════════════════════════════════════════
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final int SMTP_PORT = 587;
    private static final String EMAIL_FROM = "mahmoudelloumi@gmail.com"; // ← Remplacez par votre email Gmail
    private static final String EMAIL_PASSWORD = "nuqyghceytouqbpg"; // ← Remplacez par votre App Password
    private static final String EMAIL_TO = "projetpfa26@gmail.com"; // ← Email qui reçoit les notifications

    private final KafkaConsumer<String, BankTransaction> consumer;
    private final Session mailSession;
    private Transport smtpTransport;
    private final ExecutorService emailExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    // Stats
    private int emailsSent = 0;
    private int emailsFailed = 0;

    public NotificationConsumer(String bootstrapServers, String groupId) {
        // ─── Kafka Consumer config ───
        Properties cProps = new Properties();
        cProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BankTransactionDeserializer.class.getName());
        cProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        cProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cProps.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        cProps.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        this.consumer = new KafkaConsumer<>(cProps);

        // ─── SMTP Mail session config ───
        Properties mailProps = new Properties();
        mailProps.put("mail.smtp.auth", "true");
        mailProps.put("mail.smtp.starttls.enable", "true");
        mailProps.put("mail.smtp.host", SMTP_HOST);
        mailProps.put("mail.smtp.port", String.valueOf(SMTP_PORT));

        this.mailSession = Session.getInstance(mailProps, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EMAIL_FROM, EMAIL_PASSWORD);
            }
        });

        // ─── Pre-connect SMTP to avoid delay on first email ───
        try {
            smtpTransport = mailSession.getTransport("smtp");
            smtpTransport.connect(SMTP_HOST, SMTP_PORT, EMAIL_FROM, EMAIL_PASSWORD);
            log.info("📧 SMTP connection established to {}", SMTP_HOST);
        } catch (Exception e) {
            log.warn("📧 Could not pre-connect SMTP: {}", e.getMessage());
            smtpTransport = null;
        }
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(List.of(TOPIC_APPROVED, TOPIC_REJECTED, TOPIC_FRAUD));
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("📧 NotificationConsumer started — sending emails for:");
            log.info("   • {} → Success notification", TOPIC_APPROVED);
            log.info("   • {} → Rejection notification", TOPIC_REJECTED);
            log.info("   • {} → Fraud alert email", TOPIC_FRAUD);
            log.info("   📬 Sending from: {}", EMAIL_FROM);
            log.info("   📬 Sending to:   {}", EMAIL_TO);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            while (running) {
                ConsumerRecords<String, BankTransaction> records = consumer.poll(Duration.ofMillis(300));

                for (ConsumerRecord<String, BankTransaction> record : records) {
                    processAndNotify(record.topic(), record.value());
                }

                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            if (running)
                throw e;
        } finally {
            emailExecutor.shutdown();
            try {
                if (smtpTransport != null && smtpTransport.isConnected()) {
                    smtpTransport.close();
                }
            } catch (Exception ignored) {
            }
            consumer.close();
            log.info("📧 NotificationConsumer closed. Emails sent: {} | Failed: {}", emailsSent, emailsFailed);
        }
    }

    /**
     * Build the email content based on topic and send it.
     */
    private void processAndNotify(String topic, BankTransaction tx) {
        String subject;
        String body;

        switch (topic) {
            case TOPIC_APPROVED:
                subject = "✅ Transaction Approuvée — " + tx.getTransactionId();
                body = buildApprovedEmail(tx);
                break;

            case TOPIC_REJECTED:
                subject = "❌ Transaction Rejetée — " + tx.getTransactionId();
                body = buildRejectedEmail(tx);
                break;

            case TOPIC_FRAUD:
                subject = "🚨 ALERTE FRAUDE — " + tx.getTransactionId();
                body = buildFraudEmail(tx);
                break;

            default:
                log.warn("Unknown topic: {}", topic);
                return;
        }

        sendEmail(subject, body, tx.getTransactionId());
    }

    // ─── Email Templates ─────────────────────────────────────────────────────

    private String buildApprovedEmail(BankTransaction tx) {
        return String.format(
                "<html><body style='font-family: Arial, sans-serif;'>"
                        + "<h2 style='color: #27ae60;'>✅ Transaction Approuvée</h2>"
                        + "<hr>"
                        + "<table style='border-collapse: collapse; width: 100%%;'>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>ID Transaction</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Expéditeur</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Destinataire</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Montant</td><td style='padding: 8px;'><strong>%.2f %s</strong></td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Type</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Description</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Date</td><td style='padding: 8px;'>%s</td></tr>"
                        + "</table>"
                        + "<br><p style='color: #27ae60;'>Votre transaction a été validée et traitée avec succès.</p>"
                        + "<hr><p style='color: #999; font-size: 12px;'>Banking Kafka System — Notification automatique</p>"
                        + "</body></html>",
                tx.getTransactionId(),
                tx.getSenderId() != null ? tx.getSenderId() : "N/A",
                tx.getReceiverId() != null ? tx.getReceiverId() : "N/A",
                tx.getAmount(), tx.getCurrency(),
                tx.getType(),
                tx.getDescription() != null ? tx.getDescription() : "—",
                tx.getTimestamp() != null ? tx.getTimestamp().toString() : "N/A");
    }

    private String buildRejectedEmail(BankTransaction tx) {
        return String.format(
                "<html><body style='font-family: Arial, sans-serif;'>"
                        + "<h2 style='color: #e74c3c;'>❌ Transaction Rejetée</h2>"
                        + "<hr>"
                        + "<table style='border-collapse: collapse; width: 100%%;'>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>ID Transaction</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Expéditeur</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Destinataire</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Montant</td><td style='padding: 8px;'><strong>%.2f %s</strong></td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Type</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Description</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Date</td><td style='padding: 8px;'>%s</td></tr>"
                        + "</table>"
                        + "<br><p style='color: #e74c3c;'>Votre transaction a été rejetée. Veuillez vérifier les informations et réessayer.</p>"
                        + "<p>Raisons possibles : montant invalide, expéditeur manquant, ou dépassement de la limite autorisée.</p>"
                        + "<hr><p style='color: #999; font-size: 12px;'>Banking Kafka System — Notification automatique</p>"
                        + "</body></html>",
                tx.getTransactionId(),
                tx.getSenderId() != null ? tx.getSenderId() : "N/A",
                tx.getReceiverId() != null ? tx.getReceiverId() : "N/A",
                tx.getAmount(), tx.getCurrency(),
                tx.getType(),
                tx.getDescription() != null ? tx.getDescription() : "—",
                tx.getTimestamp() != null ? tx.getTimestamp().toString() : "N/A");
    }

    private String buildFraudEmail(BankTransaction tx) {
        return String.format(
                "<html><body style='font-family: Arial, sans-serif;'>"
                        + "<h2 style='color: #e74c3c;'>🚨 ALERTE FRAUDE — Action Immédiate Requise</h2>"
                        + "<hr>"
                        + "<div style='background: #ffeaea; padding: 15px; border-left: 4px solid #e74c3c; margin: 10px 0;'>"
                        + "<strong>Une transaction suspecte a été détectée et nécessite une vérification immédiate.</strong>"
                        + "</div>"
                        + "<table style='border-collapse: collapse; width: 100%%;'>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>ID Transaction</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Expéditeur</td><td style='padding: 8px;'><strong style='color:red;'>%s</strong></td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Destinataire</td><td style='padding: 8px;'><strong style='color:red;'>%s</strong></td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Montant</td><td style='padding: 8px;'><strong style='color:red; font-size: 18px;'>%.2f %s</strong></td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Type</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #f9f9f9;'><td style='padding: 8px; font-weight: bold;'>Description</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr><td style='padding: 8px; font-weight: bold;'>Date</td><td style='padding: 8px;'>%s</td></tr>"
                        + "<tr style='background: #ffeaea;'><td style='padding: 8px; font-weight: bold;'>Statut</td><td style='padding: 8px;'><strong style='color:red;'>🚨 FLAGGED</strong></td></tr>"
                        + "</table>"
                        + "<br><p style='color: #e74c3c; font-weight: bold;'>Cette transaction a été signalée automatiquement par le système de détection de fraude.</p>"
                        + "<hr><p style='color: #999; font-size: 12px;'>Banking Kafka System — Alerte de fraude automatique</p>"
                        + "</body></html>",
                tx.getTransactionId(),
                tx.getSenderId() != null ? tx.getSenderId() : "N/A",
                tx.getReceiverId() != null ? tx.getReceiverId() : "N/A",
                tx.getAmount(), tx.getCurrency(),
                tx.getType(),
                tx.getDescription() != null ? tx.getDescription() : "—",
                tx.getTimestamp() != null ? tx.getTimestamp().toString() : "N/A");
    }

    // ─── Email Sender ────────────────────────────────────────────────────────

    private void sendEmail(String subject, String htmlBody, String transactionId) {
        // Send email asynchronously to not block the Kafka consumer poll loop
        emailExecutor.submit(() -> {
            try {
                Message message = new MimeMessage(mailSession);
                message.setFrom(new InternetAddress(EMAIL_FROM));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(EMAIL_TO));
                message.setSubject(subject);
                message.setContent(htmlBody, "text/html; charset=utf-8");

                // Reuse persistent SMTP connection for speed
                if (smtpTransport != null && smtpTransport.isConnected()) {
                    smtpTransport.sendMessage(message, message.getAllRecipients());
                } else {
                    // Reconnect if connection was lost
                    smtpTransport = mailSession.getTransport("smtp");
                    smtpTransport.connect(SMTP_HOST, SMTP_PORT, EMAIL_FROM, EMAIL_PASSWORD);
                    smtpTransport.sendMessage(message, message.getAllRecipients());
                }

                emailsSent++;
                log.info("📧 Email sent ✅ | txId={} | to={} | subject={}", transactionId, EMAIL_TO, subject);

            } catch (MessagingException e) {
                emailsFailed++;
                log.error("📧 Email FAILED ❌ | txId={} | to={} | error={}", transactionId, EMAIL_TO, e.getMessage());
            }
        });
    }

    public void shutdown() {
        running = false;
        consumer.wakeup();
    }

    public int getEmailsSent() {
        return emailsSent;
    }

    public int getEmailsFailed() {
        return emailsFailed;
    }

    // ─── Demo ────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        NotificationConsumer notif = new NotificationConsumer("127.0.0.1:9092", "bank-notification-service");

        Runtime.getRuntime().addShutdownHook(new Thread(notif::shutdown));
        notif.run();
    }
}
