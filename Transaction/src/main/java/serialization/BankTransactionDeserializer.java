package serialization;

import model.BankTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;

public class BankTransactionDeserializer implements Deserializer<BankTransaction> {

    private final ObjectMapper objectMapper;

    public BankTransactionDeserializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public BankTransaction deserialize(String topic, byte[] data) {
        if (data == null) return null;
        try {
            return objectMapper.readValue(data, BankTransaction.class);
        } catch (Exception e) {
            throw new RuntimeException("Error deserializing BankTransaction", e);
        }
    }
}