# Kafka Dedup Module — Requirements

A reusable Java module for deduplicating Kafka messages consumed via Restate's built-in Kafka subscriptions. Duplicate detection is application-defined: callers supply the dedup key.

## Scope

### In scope
- **B** — Dedup key drawn from message payload, nested within the calling VO's domain key.
- **C** — Dedup key drawn from message payload, orthogonal to (or absent from) the calling VO's key.
- **D** — Subscription targets a Service (non-VO, parallel delivery, no ordering).

### Out of scope (v1)
- Dedup by Kafka message headers (Restate doesn't expose Kafka headers to handlers).
- Optimized "in-VO library" form — designed and documented (see Appendix), deferred until profiling shows the per-check RPC is a bottleneck.
- Runtime-modifiable TTL via a durable config VO. TTL is supplied per call site as a code constant; changes require redeploy.
- Watermark-style event-time semantics, late-data handling, ordering across keys.
- Built-in metrics beyond what Restate already provides for VO invocations.

## Performance budget

The hot-path constraint is uniform:

- **1 RPC per Kafka handler invocation** (the call to `Deduplicator.checkAndRecord`).
- **1 state lookup** inside that RPC (reading `SEEN`).

No factory RPC, no config RPC, no JVM cache — every dedup check makes exactly one durable RPC and reads one state slot.

## Architecture

The module ships **one Restate Virtual Object** plus a Java client facade.

### `Deduplicator` — the dedup VO
- Keyed by composite `(namespace, key)`. VO key encoding: `namespace + ":" + dedupKey`. Namespaces are validated to match `[a-zA-Z0-9_.\-]+` to disallow separator collisions.
- State per instance: a single `SEEN` boolean (presence is the meaningful signal).
- Handler `checkAndRecord(ttl: Duration) → boolean`:
  - State already set → return `false` (duplicate).
  - State empty → write `SEEN`, schedule self-destruct via durable delayed self-call (`send(ttl).clear()`), return `true` (first sighting).
- Handler `clear()` — internal, called by self-destruct timer.

### Concurrency
- `Deduplicator[(ns, key)]` is single-writer per dedup key. Serializes concurrent sightings for the same key, including across parallel Service-target delivery.

## Java client API

```java
@Handler
public void process(ObjectContext ctx, OrderEvent event) {
  DedupHelper dedup = DedupHelper.of(ctx, "orders", Duration.ofHours(24));
  if (!dedup.checkAndRecord(event.id()).await()) return;
  // business logic
}
```

`DedupHelper` exposes:
- `static DedupHelper of(Context ctx, String namespace, Duration ttl)` — synchronous handle constructor; no RPC. Validates namespace.
- `Awaitable<Boolean> checkAndRecord(String key)` — `true` on first sighting, `false` on duplicate. Caller branches on the result; module does not provide lambda/wrapper sugar.

`DedupHelper.of` accepts the SDK's base `Context`, so it works whether the caller is in a `@VirtualObject` (`ObjectContext`) or `@Service` (regular `Context`). Same surface for use cases B/C/D.

## Behavior details

### Dedup semantics
Strict by-key: any sighting after the first is a duplicate, regardless of payload, timestamps, or ordering. The module does not compare event times; the key alone is what matters.

### TTL semantics — processing time
Self-destruct timer fires `ttl` after first sighting in **wall-clock time** via Restate's durable delayed-call mechanism. No event-time abstraction, no watermark, no per-call timestamp argument.

### TTL configuration
- TTL is supplied as a `Duration` argument to `DedupHelper.of`. It is per call site; users define a shared constant if they want a single source of truth.
- TTL is passed through to `Deduplicator.checkAndRecord` and used at first sighting to schedule self-destruct.
- If two call sites pass different TTLs for the same namespace, the dedup VO uses whichever TTL was passed at first sighting of a given key. Existing scheduled timers run their original deadline regardless of subsequent calls.
- Changing TTL requires a code change and redeploy.

### Failure modes
- Failures (network, Restate runtime) propagate via Restate's standard durable-execution retry semantics; the module does not add custom retry logic.
- Restate journals durable RPC results, so handler retries after `checkAndRecord` returned `true` will replay the same `true` result without re-incrementing dedup state — exactly-once at the dedup layer is preserved.

### Isolation
Two unrelated namespaces with coincidentally-equal dedup keys do not collide: `(ns, key)` is the VO key, encoded with `:` as separator and namespace-validation guaranteeing no separator characters in the namespace.

## Wiring

```java
RestateHttpEndpointBuilder.builder()
    .bind(new Deduplicator())
    .bind(/* user's services */)
    .buildAndListen();
```

No startup configuration; the module is plug-and-play.

## Project setup (locked decisions)

- **Java target:** 21.
- **Build tool:** Gradle, Kotlin DSL.
- **Restate Java SDK version:** 2.7.0 (latest at time of writing).
- **Artifact:** single JAR — `dev.restate.kafka:restate-kafka-dedup`.
- **Java package root:** `dev.restate.kafka.dedup`.
- **Test strategy:** integration tests via `dev.restate:sdk-testing`; unit tests for `KeyEncoding` only.

## Appendix — Optimized in-VO library form (deferred)

If per-check RPC becomes a bottleneck, the module can grow a second mode where dedup state lives in the user's VO directly (no extra VOs, no RPC per check). Tradeoffs:

- **Pros:** sub-millisecond dedup, no separate VO, no RPC cost.
- **Cons:**
  - Dedup keys must nest within the VO's key domain (case B only — not C, not D).
  - User's VO must include a `__dedupClear(ns, key)` callback handler (mixin, base class, or copy-paste) for self-destruct timers to call back.
  - State storage uses Restate state keys (`"dedup:" + ns + ":" + key`), not Maps.

Recommendation: do not build until profiling demands it.
