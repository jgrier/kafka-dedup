package dev.restate.kafka.dedup.internal;

import java.util.regex.Pattern;

public final class KeyEncoding {

  private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-zA-Z0-9_.\\-]+");
  private static final String SEPARATOR = ":";

  private KeyEncoding() {}

  public static void validateNamespace(String namespace) {
    if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
      throw new IllegalArgumentException(
          "namespace must match [a-zA-Z0-9_.-]+, got: " + namespace);
    }
  }

  public static String encode(String namespace, String key) {
    return namespace + SEPARATOR + key;
  }
}
