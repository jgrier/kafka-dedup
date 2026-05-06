package dev.restate.kafka.dedup;

import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.annotation.Handler;
import dev.restate.sdk.annotation.VirtualObject;
import dev.restate.sdk.common.StateKey;

import java.time.Duration;

@VirtualObject
public class DedupEntry {

  private static final StateKey<Boolean> SEEN = StateKey.of("seen", Boolean.class);

  @Handler
  public boolean checkAndRecord(ObjectContext ctx, Duration ttl) {
    if (ctx.get(SEEN).isPresent()) {
      return false;
    }
    ctx.set(SEEN, Boolean.TRUE);
    DedupEntryClient.fromContext(ctx, ctx.key()).send().clear(ttl);
    return true;
  }

  @Handler
  public void clear(ObjectContext ctx) {
    ctx.clearAll();
  }
}
