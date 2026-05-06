package dev.restate.kafka.dedup;

import dev.restate.client.Client;
import dev.restate.sdk.testing.BindService;
import dev.restate.sdk.testing.RestateClient;
import dev.restate.sdk.testing.RestateTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RestateTest
class DedupServiceIntegrationTest {

  @BindService
  DedupService dedupService = new DedupService();

  @Test
  void firstSightingReturnsTrueDuplicateReturnsFalse(@RestateClient Client client) {
    var dedup = DedupServiceClient.fromClient(client, "orders:abc-123");
    assertTrue(dedup.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(dedup.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(dedup.checkAndRecord(Duration.ofMinutes(5)));
  }

  @Test
  void differentCompositeKeysAreIndependent(@RestateClient Client client) {
    var ordersAbc = DedupServiceClient.fromClient(client, "orders:abc-1");
    var ordersDef = DedupServiceClient.fromClient(client, "orders:def-2");
    var paymentsAbc = DedupServiceClient.fromClient(client, "payments:abc-1");

    assertTrue(ordersAbc.checkAndRecord(Duration.ofMinutes(5)));
    assertTrue(ordersDef.checkAndRecord(Duration.ofMinutes(5)));
    assertTrue(paymentsAbc.checkAndRecord(Duration.ofMinutes(5)));

    assertFalse(ordersAbc.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(ordersDef.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(paymentsAbc.checkAndRecord(Duration.ofMinutes(5)));
  }

  @Test
  void clearResetsState(@RestateClient Client client) {
    var dedup = DedupServiceClient.fromClient(client, "orders:cleared-key");

    assertTrue(dedup.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(dedup.checkAndRecord(Duration.ofMinutes(5)));

    dedup.clear();

    assertTrue(dedup.checkAndRecord(Duration.ofMinutes(5)));
  }
}
