package web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import model.BankTransaction;
import model.BankTransaction.TransactionType;
import model.BankTransaction.TransactionStatus;
import serialization.BankTransactionSerializer;
import serialization.BankTransactionDeserializer;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Embedded HTTP Dashboard Server for Banking Kafka.
 * 
 * Endpoints:
 * GET / → Login page
 * GET /dashboard → Dashboard HTML (requires auth)
 * POST /api/login → Authenticate user
 * POST /api/send → Send a transaction to Kafka (requires auth)
 * GET /api/transactions → Get all processed transactions (requires auth)
 * GET /api/stats → Get statistics (requires auth)
 */
public class DashboardServer {

    private static final Logger log = LoggerFactory.getLogger(DashboardServer.class);
    private static final String TOPIC_PENDING = "bank.transactions.pending";
    private static final String TOPIC_APPROVED = "bank.transactions.approved";
    private static final String TOPIC_REJECTED = "bank.transactions.rejected";
    private static final String TOPIC_FRAUD = "bank.transactions.fraud";
    private static final String BOOTSTRAP = "127.0.0.1:9092";
    private static final int PORT = 8080;
    private static final double HIGH_AMOUNT_LIMIT = 10_000.0;
    private static final double MAX_AMOUNT = 50_000.0;
    private static final int VELOCITY_LIMIT = 3;
    private static final int VELOCITY_WINDOW_S = 60;

    private final ObjectMapper mapper;
    private final KafkaProducer<String, BankTransaction> producer;
    private final List<Map<String, Object>> processedTransactions = new CopyOnWriteArrayList<>();
    private final ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    // Velocity tracking: senderId → list of transaction timestamps
    private final Map<String, Deque<LocalDateTime>> velocityTracker = new ConcurrentHashMap<>();

    // Session management
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>(); // token → username
    private static final Map<String, String> USERS = Map.of(
            "admin", "admin123",
            "mahmoud", "kafka2026",
            "operateur", "bank@2026",
            "anis", "kafka2026");

    // Stats
    private int totalSent = 0;
    private int totalApproved = 0;
    private int totalRejected = 0;
    private int totalFlagged = 0;

    public DashboardServer() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Kafka Producer
        Properties pProps = new Properties();
        pProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        pProps.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pProps.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, BankTransactionSerializer.class.getName());
        pProps.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        pProps.setProperty(ProducerConfig.RETRIES_CONFIG, "3");
        pProps.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        this.producer = new KafkaProducer<>(pProps);
    }

    public void start() throws IOException {
        // Start consumer thread
        consumerExecutor.submit(this::consumeLoop);

        // Start HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", this::handleLogin);
        server.createContext("/dashboard", this::handleDashboard);
        server.createContext("/api/login", this::handleApiLogin);
        server.createContext("/api/send", this::handleSend);
        server.createContext("/api/transactions", this::handleTransactions);
        server.createContext("/api/stats", this::handleStats);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        log.info("========================================");
        log.info("  Dashboard started: http://localhost:{}", PORT);
        log.info("  Login credentials:");
        log.info("    admin / admin123");
        log.info("    mahmoud / kafka2026");
        log.info("    operateur / bank@2026");
        log.info("========================================");
    }

    // ─── Consumer Loop ───────────────────────────────────────────────────────

    private void consumeLoop() {
        Properties cProps = new Properties();
        cProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        cProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BankTransactionDeserializer.class.getName());
        cProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG,
                "dashboard-consumer-" + UUID.randomUUID().toString().substring(0, 8));
        cProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        cProps.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        try (KafkaConsumer<String, BankTransaction> consumer = new KafkaConsumer<>(cProps)) {
            consumer.subscribe(List.of(TOPIC_PENDING));
            log.info("Dashboard consumer started, listening on {}", TOPIC_PENDING);

            while (running) {
                ConsumerRecords<String, BankTransaction> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, BankTransaction> record : records) {
                    processTransaction(record.value(), record.partition(), record.offset());
                }
            }
        } catch (Exception e) {
            if (running)
                log.error("Consumer error", e);
        }
    }

    private void processTransaction(BankTransaction tx, int partition, long offset) {
        List<String> fraudAlerts = new ArrayList<>();
        String validationResult;

        // ─── Validation (same as TransactionConsumer) ───
        String rejectReason = null;
        if (tx.getAmount() <= 0) {
            rejectReason = "Amount must be positive";
        } else if (tx.getAmount() > MAX_AMOUNT) {
            rejectReason = "Amount exceeds hard limit of " + MAX_AMOUNT;
        } else if (tx.getType() != TransactionType.DEPOSIT && tx.getSenderId() == null) {
            rejectReason = "Sender account required for " + tx.getType();
        }

        if (rejectReason != null) {
            tx.setStatus(TransactionStatus.REJECTED);
            validationResult = "REJECTED: " + rejectReason;
            totalRejected++;
        } else {
            tx.setStatus(TransactionStatus.APPROVED);
            validationResult = "APPROVED";
            totalApproved++;
        }

        // ─── Fraud Detection (same as FraudDetectionConsumer) ───
        if (tx.getAmount() > HIGH_AMOUNT_LIMIT) {
            fraudAlerts.add(String.format("HIGH-AMOUNT (%.2f EUR > %.2f limit)", tx.getAmount(), HIGH_AMOUNT_LIMIT));
        }
        // Rule 2 – Velocity check (> 3 txns in 60s from same sender → MEDIUM risk)
        if (tx.getSenderId() != null && checkVelocity(tx.getSenderId())) {
            fraudAlerts.add(String.format("VELOCITY-EXCEEDED (>%d txns in %ds)", VELOCITY_LIMIT, VELOCITY_WINDOW_S));
        }
        if (tx.getAmount() >= 5000 && tx.getAmount() % 1000 == 0) {
            fraudAlerts.add("ROUND-LARGE-AMOUNT");
        }
        Set<String> knownReceivers = Set.of("ACC-001", "ACC-002", "ACC-003", "ACC-004", "ACC-005");
        if (tx.getType() == TransactionType.TRANSFER && tx.getReceiverId() != null
                && !knownReceivers.contains(tx.getReceiverId())) {
            fraudAlerts.add("UNKNOWN-RECEIVER (" + tx.getReceiverId() + ")");
        }

        // ─── Route to downstream topics ───
        String routedTo;
        if (!fraudAlerts.isEmpty()) {
            tx.setStatus(TransactionStatus.FLAGGED);
            totalFlagged++;
            if (validationResult.equals("APPROVED"))
                totalApproved--; // correct the count
            routedTo = TOPIC_FRAUD;
            producer.send(new ProducerRecord<>(TOPIC_FRAUD, tx.getSenderId(), tx));
        } else if (rejectReason != null) {
            routedTo = TOPIC_REJECTED;
            producer.send(new ProducerRecord<>(TOPIC_REJECTED, tx.getSenderId(), tx));
        } else {
            routedTo = TOPIC_APPROVED;
            producer.send(new ProducerRecord<>(TOPIC_APPROVED, tx.getSenderId(), tx));
        }
        producer.flush();

        // Store result
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("transactionId", tx.getTransactionId());
        entry.put("senderId", tx.getSenderId());
        entry.put("receiverId", tx.getReceiverId());
        entry.put("amount", tx.getAmount());
        entry.put("currency", tx.getCurrency());
        entry.put("type", tx.getType() != null ? tx.getType().name() : null);
        entry.put("status", tx.getStatus().name());
        entry.put("timestamp", tx.getTimestamp() != null ? tx.getTimestamp().toString() : null);
        entry.put("description", tx.getDescription());
        entry.put("partition", partition);
        entry.put("offset", offset);
        entry.put("validation", validationResult);
        entry.put("fraudAlerts", fraudAlerts);
        entry.put("routedTo", routedTo);
        entry.put("processedAt", LocalDateTime.now().toString());

        processedTransactions.add(0, entry); // newest first
        if (processedTransactions.size() > 100) {
            processedTransactions.remove(processedTransactions.size() - 1);
        }

        log.info("Processed: {} | {} | {} | fraud={}", tx.getTransactionId(), validationResult,
                tx.getAmount() + " " + tx.getCurrency(), fraudAlerts);
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

    // ─── HTTP Handlers ───────────────────────────────────────────────────────

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("login.html")) {
            if (is == null) {
                sendResponse(exchange, 500, "text/plain", "login.html not found in classpath");
                return;
            }
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            sendResponse(exchange, 200, "text/html; charset=UTF-8", html);
        }
    }

    private void handleDashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("dashboard.html")) {
            if (is == null) {
                sendResponse(exchange, 500, "text/plain", "dashboard.html not found in classpath");
                return;
            }
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            sendResponse(exchange, 200, "text/html; charset=UTF-8", html);
        }
    }

    private void handleApiLogin(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = mapper.readValue(body, Map.class);
            String username = params.getOrDefault("username", "").trim();
            String password = params.getOrDefault("password", "");

            if (USERS.containsKey(username) && USERS.get(username).equals(password)) {
                // Generate session token
                String token = UUID.randomUUID().toString();
                activeSessions.put(token, username);
                log.info("User '{}' logged in successfully", username);
                sendJson(exchange, 200, Map.of(
                        "success", true,
                        "token", token,
                        "username", username,
                        "message", "Connexion réussie"));
            } else {
                log.warn("Failed login attempt for user '{}'", username);
                sendJson(exchange, 401, Map.of(
                        "success", false,
                        "error", "Nom d'utilisateur ou mot de passe incorrect"));
            }
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    private boolean isAuthenticated(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return activeSessions.containsKey(token);
        }
        return false;
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!isAuthenticated(exchange)) {
            sendJson(exchange, 401, Map.of("error", "Non autorisé"));
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = mapper.readValue(body, Map.class);

            String senderId = params.getOrDefault("senderId", null);
            String receiverId = params.getOrDefault("receiverId", null);
            double amount = Double.parseDouble(params.getOrDefault("amount", "0"));
            String currency = params.getOrDefault("currency", "TND");
            TransactionType type = TransactionType.valueOf(params.getOrDefault("type", "TRANSFER"));
            String description = params.getOrDefault("description", "");

            if (senderId != null && senderId.isBlank())
                senderId = null;
            if (receiverId != null && receiverId.isBlank())
                receiverId = null;

            BankTransaction tx = new BankTransaction(senderId, receiverId, amount, currency, type, description);

            ProducerRecord<String, BankTransaction> record = new ProducerRecord<>(TOPIC_PENDING, tx.getSenderId(), tx);
            producer.send(record, (metadata, exception) -> {
                if (exception == null) {
                    log.info("Sent to Kafka: {} partition={} offset={}", tx.getTransactionId(), metadata.partition(),
                            metadata.offset());
                } else {
                    log.error("Send failed: {}", tx.getTransactionId(), exception);
                }
            });
            producer.flush();
            totalSent++;

            sendJson(exchange, 200, Map.of(
                    "success", true,
                    "transactionId", tx.getTransactionId(),
                    "message", "Transaction envoyée au topic Kafka: " + TOPIC_PENDING));
        } catch (Exception e) {
            log.error("Error sending transaction", e);
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    private void handleTransactions(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!isAuthenticated(exchange)) {
            sendJson(exchange, 401, Map.of("error", "Non autorisé"));
            return;
        }
        sendJson(exchange, 200, processedTransactions);
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!isAuthenticated(exchange)) {
            sendJson(exchange, 401, Map.of("error", "Non autorisé"));
            return;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSent", totalSent);
        stats.put("totalProcessed", processedTransactions.size());
        stats.put("totalApproved", totalApproved);
        stats.put("totalRejected", totalRejected);
        stats.put("totalFlagged", totalFlagged);
        sendJson(exchange, 200, stats);
    }

    // ─── Utils ───────────────────────────────────────────────────────────────

    private void setCors(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendResponse(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int code, Object obj) throws IOException {
        String json = mapper.writeValueAsString(obj);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ─── Main ────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        DashboardServer dashboard = new DashboardServer();
        dashboard.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dashboard.running = false;
            dashboard.producer.close();
            dashboard.consumerExecutor.shutdownNow();
            log.info("Dashboard server stopped.");
        }));
    }
}
