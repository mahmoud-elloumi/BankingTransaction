package Consumer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
import model.BankTransaction.TransactionType;
import serialization.BankTransactionDeserializer;
import serialization.BankTransactionSerializer;

/**
 * Real-time fraud detection consumer.
 *
 * Input: bank.transactions.pending → reads all new transactions
 * Output: bank.transactions.fraud → publishes flagged/suspicious transactions
 *
 * Detection rules:
 * 1. Single transaction > 10,000 EUR → HIGH risk
 * 2. Velocity: > 3 transactions in 60 seconds from same sender → MEDIUM risk
 * 3. Round-number large amounts (e.g. 50000, 99000) → LOW risk flag
 * 4. Transfer to unknown/new receiver → LOW risk flag
 */
public class FraudDetectionConsumer implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(FraudDetectionConsumer.class);

	private static final String TOPIC_PENDING = "bank.transactions.pending";
	private static final String TOPIC_FRAUD = "bank.transactions.fraud";
	private static final double HIGH_AMOUNT_LIMIT = 10_000.0;
	private static final int VELOCITY_LIMIT = 3;
	private static final int VELOCITY_WINDOW_S = 60;

	// In-memory velocity tracking: senderId → list of transaction timestamps
	private final Map<String, Deque<LocalDateTime>> velocityTracker = new ConcurrentHashMap<>();

	// Known receivers (whitelist — in production, load from a database)
	private final Set<String> knownReceivers = new HashSet<>(
			Arrays.asList("ACC-001", "ACC-002", "ACC-003", "ACC-004", "ACC-005"));

	private final KafkaConsumer<String, BankTransaction> consumer;
	private final KafkaProducer<String, BankTransaction> fraudProducer;
	private volatile boolean running = true;

	public FraudDetectionConsumer(String bootstrapServers, String groupId) {
		// ─── Consumer config ───
		Properties cProps = new Properties();
		cProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		cProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		cProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BankTransactionDeserializer.class.getName());
		cProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		cProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		cProps.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		this.consumer = new KafkaConsumer<>(cProps);

		// ─── Producer config (to publish to fraud topic) ───
		Properties pProps = new Properties();
		pProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		pProps.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		pProps.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, BankTransactionSerializer.class.getName());
		pProps.setProperty(ProducerConfig.ACKS_CONFIG, "all");
		pProps.setProperty(ProducerConfig.RETRIES_CONFIG, "3");
		pProps.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
		this.fraudProducer = new KafkaProducer<>(pProps);
	}

	@Override
	public void run() {
		try {
			consumer.subscribe(List.of(TOPIC_PENDING));
			log.info("FraudDetectionConsumer started — listening on {}", TOPIC_PENDING);

			while (running) {
				ConsumerRecords<String, BankTransaction> records = consumer.poll(Duration.ofMillis(300));

				for (ConsumerRecord<String, BankTransaction> record : records) {
					analyze(record.value());
				}

				if (!records.isEmpty()) {
					consumer.commitSync();
				}
			}
		} catch (WakeupException e) {
			if (running)
				throw e;
		} finally {
			fraudProducer.close();
			consumer.close();
			log.info("FraudDetectionConsumer closed.");
		}
	}

	private void analyze(BankTransaction tx) {
		List<String> alerts = new ArrayList<>();

		// Rule 1 – High amount
		if (tx.getAmount() > HIGH_AMOUNT_LIMIT) {
			alerts.add(String.format("HIGH-AMOUNT (%.2f EUR > %.2f limit)", tx.getAmount(), HIGH_AMOUNT_LIMIT));
		}

		// Rule 2 – Velocity check
		if (tx.getSenderId() != null && checkVelocity(tx.getSenderId())) {
			alerts.add(String.format("VELOCITY-EXCEEDED (>%d txns in %ds)", VELOCITY_LIMIT, VELOCITY_WINDOW_S));
		}

		// Rule 3 – Suspicious round number
		if (isRoundLargeAmount(tx.getAmount())) {
			alerts.add("ROUND-LARGE-AMOUNT");
		}

		// Rule 4 – Unknown receiver on transfers
		if (tx.getType() == TransactionType.TRANSFER && tx.getReceiverId() != null
				&& !knownReceivers.contains(tx.getReceiverId())) {
			alerts.add("UNKNOWN-RECEIVER (" + tx.getReceiverId() + ")");
		}

		if (!alerts.isEmpty()) {
			tx.setStatus(TransactionStatus.FLAGGED);
			log.warn("🚨 FRAUD ALERT | txId={} | sender={} | amount={} EUR | flags={}", tx.getTransactionId(),
					tx.getSenderId(), tx.getAmount(), alerts);

			// ── Publish to fraud topic ──
			fraudProducer.send(new ProducerRecord<>(TOPIC_FRAUD, tx.getSenderId(), tx), (metadata, ex) -> {
				if (ex == null) {
					log.info("  → Published to {} | partition={} offset={}", TOPIC_FRAUD, metadata.partition(),
							metadata.offset());
				} else {
					log.error("  → Failed to publish to {}", TOPIC_FRAUD, ex);
				}
			});
			fraudProducer.flush();
		} else {
			log.info("✅ Clean transaction | txId={} | sender={} | amount={} EUR", tx.getTransactionId(),
					tx.getSenderId(), tx.getAmount());
		}
	}

	/**
	 * Checks if the sender exceeded the velocity limit within the time window.
	 * Returns true if limit exceeded (suspicious).
	 */
	private boolean checkVelocity(String senderId) {
		LocalDateTime now = LocalDateTime.now();
		velocityTracker.putIfAbsent(senderId, new ArrayDeque<>());
		Deque<LocalDateTime> timestamps = velocityTracker.get(senderId);

		// Remove timestamps outside the window
		timestamps.removeIf(t -> Duration.between(t, now).getSeconds() > VELOCITY_WINDOW_S);

		timestamps.addLast(now);
		return timestamps.size() > VELOCITY_LIMIT;
	}

	private boolean isRoundLargeAmount(double amount) {
		return amount >= 5000 && amount % 1000 == 0;
	}

	public void shutdown() {
		running = false;
		consumer.wakeup();
	}

	// ─── Demo ────────────────────────────────────────────────────────────────

	public static void main(String[] args) {
		FraudDetectionConsumer fraud = new FraudDetectionConsumer("127.0.0.1:9092", "bank-fraud-detector");

		Runtime.getRuntime().addShutdownHook(new Thread(fraud::shutdown));
		fraud.run();
	}
}