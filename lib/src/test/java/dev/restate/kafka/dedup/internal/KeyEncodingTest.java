package dev.restate.kafka.dedup.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyEncodingTest {

  @Test
  void encodeJoinsNamespaceAndKey() {
    assertEquals("orders:abc-123", KeyEncoding.encode("orders", "abc-123"));
  }

  @Test
  void encodePreservesKeyContents() {
    assertEquals("orders:k:with:colons", KeyEncoding.encode("orders", "k:with:colons"));
  }

  @Test
  void validateNamespaceAcceptsAlphanumericAndAllowedPunct() {
    assertDoesNotThrow(() -> KeyEncoding.validateNamespace("orders"));
    assertDoesNotThrow(() -> KeyEncoding.validateNamespace("ORDERS_42"));
    assertDoesNotThrow(() -> KeyEncoding.validateNamespace("a.b-c_d"));
  }

  @Test
  void validateNamespaceRejectsSeparator() {
    assertThrows(IllegalArgumentException.class,
        () -> KeyEncoding.validateNamespace("with:colon"));
  }

  @Test
  void validateNamespaceRejectsEmpty() {
    assertThrows(IllegalArgumentException.class,
        () -> KeyEncoding.validateNamespace(""));
  }

  @Test
  void validateNamespaceRejectsNull() {
    assertThrows(IllegalArgumentException.class,
        () -> KeyEncoding.validateNamespace(null));
  }
}
