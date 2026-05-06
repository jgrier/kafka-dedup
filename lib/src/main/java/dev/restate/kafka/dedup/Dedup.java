package dev.restate.kafka.dedup;

import dev.restate.kafka.dedup.internal.KeyEncoding;
import dev.restate.sdk.Context;

import java.time.Duration;

public final class Dedup {

  private final Context ctx;
  private final String namespace;
  private final Duration ttl;

  private Dedup(Context ctx, String namespace, Duration ttl) {
    this.ctx = ctx;
    this.namespace = namespace;
    this.ttl = ttl;
  }

  public static Dedup of(Context ctx, String namespace, Duration ttl) {
    KeyEncoding.validateNamespace(namespace);
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be a positive Duration");
    }
    return new Dedup(ctx, namespace, ttl);
  }

  public boolean checkAndRecord(String key) {
    String composite = KeyEncoding.encode(namespace, key);
    return DeduplicatorClient.fromContext(ctx, composite).checkAndRecord(ttl).await();
  }
}
