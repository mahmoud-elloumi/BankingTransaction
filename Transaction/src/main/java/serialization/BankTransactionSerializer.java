package serialization;

import org.apache.kafka.common.serialization.Serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import model.BankTransaction;

public class BankTransactionSerializer implements Serializer<BankTransaction> {

	private final ObjectMapper objectMapper;

	public BankTransactionSerializer() {
		this.objectMapper = new ObjectMapper();
		this.objectMapper.registerModule(new JavaTimeModule());
	}

	@Override
	public byte[] serialize(String topic, BankTransaction transaction) {
		if (transaction == null)
			return null;
		try {
			return objectMapper.writeValueAsBytes(transaction);
		} catch (Exception e) {
			throw new RuntimeException("Error serializing BankTransaction", e);
		}
	}
}