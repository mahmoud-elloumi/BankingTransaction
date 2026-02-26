package Consumer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import model.BankTransaction;
import model.BankTransaction.TransactionStatus;
import serialization.BankTransactionDeserializer;
import serialization.BankTransactionSerializer;

/**
 * Validates incoming transactions and routes them to different topics:
 * 
 * Input: bank.transactions.pending → reads new transactions
 * Output: bank.transactions.approved → validated transactions
 * bank.transactions.rejected → rejected transactions
 *
 * Business rules:
 * • Amount must be > 0
 * • Amount must not exceed 50,000 EUR (hard limit)
 * • Sender must not be null for TRANSFER / PAYMENT / WITHDRAWAL
 */
public class TransactionConsumer implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

	private static final String TOPIC_PENDING = "bank.transactions.pending";
	private static final String TOPIC_APPROVED = "bank.transactions.approved";
	private static final String TOPIC_REJECTED = "bank.transactions.rejected";
	private static final double MAX_AMOUNT = 50_000.0;

	private final KafkaConsumer<String, BankTransaction> consumer;
	private final KafkaProducer<String, BankTransaction> producer;
	private volatile boolean running = true;

	public TransactionConsumer(String bootstrapServers, String groupId) {
		// ─── Consumer config ───
		Properties cProps = new Properties();
		cProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		cProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		cProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BankTransactionDeserializer.class.getName());
		cProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		cProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		// Manual offset commit for reliability
		cProps.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		cProps.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
		this.consumer = new KafkaConsumer<>(cProps);

		// ─── Producer config (to forward to approved/rejected topics) ───
		Properties pProps = new Properties();
		pProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		pProps.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		pProps.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, BankTransactionSerializer.class.getName());
		pProps.setProperty(ProducerConfig.ACKS_CONFIG, "all");
		pProps.setProperty(ProducerConfig.RETRIES_CONFIG, "3");
		pProps.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
		this.producer = new KafkaProducer<>(pProps);
	}

	@Override
	public void run() {
		try {
			consumer.subscribe(List.of(TOPIC_PENDING));
			log.info("TransactionConsumer started — listening on {}", TOPIC_PENDING);

			while (running) {
				ConsumerRecords<String, BankTransaction> records = consumer.poll(Duration.ofMillis(300));

				for (ConsumerRecord<String, BankTransaction> record : records) {
					process(record.value());
				}

				// Commit after batch processing
				if (!records.isEmpty()) {
					consumer.commitSync();
				}
			}
		} catch (WakeupException e) {
			if (running)
				throw e;
		} finally {
			producer.close();
			consumer.close();
			log.info("TransactionConsumer closed.");
		}
	}

	private void process(BankTransaction tx) {
		String reason = validate(tx);

		if (reason == null) {
			tx.setStatus(TransactionStatus.APPROVED);
			log.info("✅ APPROVED  | {}", tx);

			// ── Publish to approved topic ──
			producer.send(new ProducerRecord<>(TOPIC_APPROVED, tx.getSenderId(), tx), (metadata, ex) -> {
				if (ex == null) {
					log.info("  → Routed to {} | partition={} offset={}", TOPIC_APPROVED, metadata.partition(),
							metadata.offset());
				} else {
					log.error("  → Failed to route to {}", TOPIC_APPROVED, ex);
				}
			});
		} else {
			tx.setStatus(TransactionStatus.REJECTED);
			log.warn("❌ REJECTED  | {} | Reason: {}", tx, reason);

			// ── Publish to rejected topic ──
			producer.send(new ProducerRecord<>(TOPIC_REJECTED, tx.getSenderId(), tx), (metadata, ex) -> {
				if (ex == null) {
					log.info("  → Routed to {} | partition={} offset={}", TOPIC_REJECTED, metadata.partition(),
							metadata.offset());
				} else {
					log.error("  → Failed to route to {}", TOPIC_REJECTED, ex);
				}
			});
		}

		producer.flush();
	}

	/**
	 * Returns null if valid, or a rejection reason string.
	 */
	private String validate(BankTransaction tx) {
		if (tx.getAmount() <= 0) {
			return "Amount must be positive";
		}
		if (tx.getAmount() > MAX_AMOUNT) {
			return "Amount exceeds hard limit of " + MAX_AMOUNT;
		}
		if (tx.getType() != BankTransaction.TransactionType.DEPOSIT && tx.getSenderId() == null) {
			return "Sender account is required for " + tx.getType();
		}
		return null;
	}

	public void shutdown() {
		running = false;
		consumer.wakeup();
	}

	// ─── Demo ────────────────────────────────────────────────────────────────

	public static void main(String[] args) {
		TransactionConsumer consumer = new TransactionConsumer("127.0.0.1:9092", "bank-transaction-validator");

		// Graceful shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(consumer::shutdown));

		consumer.run();
	}
}