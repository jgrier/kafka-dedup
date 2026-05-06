package dev.restate.kafka.dedup.demo;

import dev.restate.kafka.dedup.DedupService;
import dev.restate.sdk.endpoint.Endpoint;
import dev.restate.sdk.http.vertx.RestateHttpServer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

public class DemoMain {
  public static void main(String[] args) {
    String bootstrap = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    String outputTopic = env("OUTPUT_TOPIC", "orders-clean");

    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

    KafkaProducer<String, String> producer = new KafkaProducer<>(props);
    Runtime.getRuntime().addShutdownHook(new Thread(producer::close));
    OrderProcessor.configureProducer(producer, outputTopic);

    Endpoint endpoint = Endpoint.builder()
        .bind(new DedupService())
        .bind(new OrderProcessor())
        .build();

    RestateHttpServer.listen(endpoint);
  }

  private static String env(String key, String fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }
}
