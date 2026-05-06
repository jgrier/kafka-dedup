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
class DedupEntryIntegrationTest {

  @BindService
  DedupEntry dedupEntry = new DedupEntry();

  @Test
  void firstSightingReturnsTrueDuplicateReturnsFalse(@RestateClient Client client) {
    var dedup = DedupEntryClient.fromClient(client, "orders:abc-123");
    assertTrue(dedup.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(dedup.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(dedup.checkAndRecord(Duration.ofMinutes(5)));
  }

  @Test
  void differentCompositeKeysAreIndependent(@RestateClient Client client) {
    var ordersAbc = DedupEntryClient.fromClient(client, "orders:abc-1");
    var ordersDef = DedupEntryClient.fromClient(client, "orders:def-2");
    var paymentsAbc = DedupEntryClient.fromClient(client, "payments:abc-1");

    assertTrue(ordersAbc.checkAndRecord(Duration.ofMinutes(5)));
    assertTrue(ordersDef.checkAndRecord(Duration.ofMinutes(5)));
    assertTrue(paymentsAbc.checkAndRecord(Duration.ofMinutes(5)));

    assertFalse(ordersAbc.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(ordersDef.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(paymentsAbc.checkAndRecord(Duration.ofMinutes(5)));
  }

  @Test
  void clearResetsState(@RestateClient Client client) {
    var dedup = DedupEntryClient.fromClient(client, "orders:cleared-key");

    assertTrue(dedup.checkAndRecord(Duration.ofMinutes(5)));
    assertFalse(dedup.checkAndRecord(Duration.ofMinutes(5)));

    dedup.clear();

    assertTrue(dedup.checkAndRecord(Duration.ofMinutes(5)));
  }
}
