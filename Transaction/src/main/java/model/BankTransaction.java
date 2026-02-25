package model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.UUID;

public class BankTransaction {

    public enum TransactionType {
        DEPOSIT, WITHDRAWAL, TRANSFER, PAYMENT
    }

    public enum TransactionStatus {
        PENDING, APPROVED, REJECTED, FLAGGED
    }

    private String transactionId;
    private String senderId;
    private String receiverId;
    private double amount;
    private String currency;
    private TransactionType type;
    private TransactionStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    private String description;
    private String ipAddress;

    // Default constructor (required for Jackson deserialization)
    public BankTransaction() {}

    // Builder-style constructor
    public BankTransaction(String senderId, String receiverId, double amount,
                           String currency, TransactionType type, String description) {
        this.transactionId = UUID.randomUUID().toString();
        this.senderId      = senderId;
        this.receiverId    = receiverId;
        this.amount        = amount;
        this.currency      = currency;
        this.type          = type;
        this.status        = TransactionStatus.PENDING;
        this.timestamp     = LocalDateTime.now();
        this.description   = description;
    }

    // ─── Getters & Setters ────────────────────────────────────────────────────

    public String getTransactionId()              { return transactionId; }
    public void   setTransactionId(String v)      { this.transactionId = v; }

    public String getSenderId()                   { return senderId; }
    public void   setSenderId(String v)           { this.senderId = v; }

    public String getReceiverId()                 { return receiverId; }
    public void   setReceiverId(String v)         { this.receiverId = v; }

    public double getAmount()                     { return amount; }
    public void   setAmount(double v)             { this.amount = v; }

    public String getCurrency()                   { return currency; }
    public void   setCurrency(String v)           { this.currency = v; }

    public TransactionType  getType()             { return type; }
    public void             setType(TransactionType v) { this.type = v; }

    public TransactionStatus getStatus()          { return status; }
    public void              setStatus(TransactionStatus v) { this.status = v; }

    public LocalDateTime getTimestamp()           { return timestamp; }
    public void          setTimestamp(LocalDateTime v) { this.timestamp = v; }

    public String getDescription()                { return description; }
    public void   setDescription(String v)        { this.description = v; }

    public String getIpAddress()                  { return ipAddress; }
    public void   setIpAddress(String v)          { this.ipAddress = v; }

    @Override
    public String toString() {
        return String.format("[%s] %s | %s → %s | %.2f %s | %s | %s",
                transactionId, type, senderId, receiverId, amount, currency, status, timestamp);
    }
}