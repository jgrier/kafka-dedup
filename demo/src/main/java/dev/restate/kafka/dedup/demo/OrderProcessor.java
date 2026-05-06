package dev.restate.kafka.dedup.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restate.kafka.dedup.Deduplicator;
import dev.restate.sdk.Context;
import dev.restate.sdk.annotation.Handler;
import dev.restate.sdk.annotation.Service;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Duration;

@Service
public class OrderProcessor {

  private static final Duration DEDUP_TTL = Duration.ofMinutes(5);
  private static final ObjectMapper JSON = new ObjectMapper();

  private static volatile KafkaProducer<String, String> producer;
  private static volatile String outputTopic;

  public static void configureProducer(KafkaProducer<String, String> p, String topic) {
    producer = p;
    outputTopic = topic;
  }

  @Handler
  public void process(Context ctx, OrderEvent event) {
    Deduplicator dedup = Deduplicator.of(ctx, "orders", DEDUP_TTL);
    if (!dedup.checkAndRecord(event.eventId())) {
      return;
    }

    ctx.run("publish-clean", () -> {
      String json = JSON.writeValueAsString(event);
      producer.send(new ProducerRecord<>(outputTopic, event.eventId(), json)).get();
    });
  }
}
