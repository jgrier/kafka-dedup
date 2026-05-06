package dev.restate.kafka.dedup.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class ProducerMain {
  public static void main(String[] args) throws Exception {
    String bootstrap = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    String topic = env("INPUT_TOPIC", "orders-raw");
    int total = Integer.parseInt(env("MESSAGE_COUNT", "100"));
    double dupRate = Double.parseDouble(env("DUPLICATE_RATE", "0.3"));

    ObjectMapper mapper = new ObjectMapper();
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

    Set<String> issuedIds = new HashSet<>();
    String lastId = null;
    int duplicates = 0;

    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      for (int i = 0; i < total; i++) {
        String id;
        if (lastId != null && ThreadLocalRandom.current().nextDouble() < dupRate) {
          id = lastId;
          duplicates++;
        } else {
          id = UUID.randomUUID().toString();
          issuedIds.add(id);
          lastId = id;
        }
        OrderEvent event = new OrderEvent(
            id,
            "cust-" + (i % 10),
            Math.round(ThreadLocalRandom.current().nextDouble() * 10000) / 100.0);
        String json = mapper.writeValueAsString(event);
        producer.send(new ProducerRecord<>(topic, id, json));
        if ((i + 1) % 25 == 0) {
          System.out.printf("  ...sent %d/%d%n", i + 1, total);
        }
      }
      producer.flush();
    }

    System.out.printf(
        "Done. %d messages sent: %d unique eventIds, %d duplicate copies.%n",
        total, issuedIds.size(), duplicates);
  }

  private static String env(String key, String fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }
}
