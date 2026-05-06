# Kafka Dedup for Restate

A reusable Java module for deduplicating Kafka messages consumed via [Restate's](https://restate.dev) Kafka subscriptions, plus a runnable end-to-end demo.

The dedup module gives a Restate handler exactly-once message processing semantics on top of Kafka's at-least-once delivery: regardless of how many duplicate copies of a message land in the topic, your handler's business logic runs at most once per logical event.

---

## What's in this repo

| Path                | What                                                                                  |
|---------------------|---------------------------------------------------------------------------------------|
| `lib/`              | The reusable dedup module (`DedupService`, `Deduplicator`).                           |
| `demo/`             | Working demo: `OrderProcessor` (Restate Service) + `ProducerMain` (data generator).   |
| `docker-compose.yml`| Single-broker Kafka (KRaft) + Restate server.                                         |
| `restate.toml`      | Restate config that names the Kafka cluster `default`.                                |
| `bin/`              | Runner scripts: `start.sh`, `stop.sh`, `produce.sh`, `consume-raw.sh`, `consume-deduped.sh`. |
| `REQUIREMENTS.md`   | Requirements doc — the "why" behind the design.                                       |

---

## Demo: 4 terminals, see dedup happen live

The demo wires up:

- A **dirty input topic** `orders-raw` — receives messages that include intentional duplicates.
- A Restate **`OrderProcessor` Service** subscribed to that topic. Its handler uses the dedup module and republishes first-sightings to a clean topic.
- A **clean output topic** `orders-clean` — only contains unique events.

```
                                       ┌─────────────────────────────────────────┐
   Terminal C                          │  Restate (subscribed to orders-raw)     │
   ──────────                          │                                         │
   producer (with dups) ──┐            │   OrderProcessor.process(event):        │
                          │            │     dedup = Deduplicator.of(...)        │
                          │            │     if !dedup.checkAndRecord(eventId):  │
                          ▼            │       return    ← duplicates exit here  │
                   ┌──────────────┐    │     publish(orders-clean, event)        │
                   │ orders-raw   │───►│                                         │
                   └──────────────┘    └────────────────────┬────────────────────┘
                          │                                 │
                          │                                 ▼
                          │                         ┌──────────────┐
   Terminal A             │                         │ orders-clean │
   ──────────             │                         └──────────────┘
   consume-raw.sh ◄───────┘                                 │
   (sees duplicates)                                        │
                                                            │
   Terminal B                                               │
   ──────────                                               │
   consume-deduped.sh ◄─────────────────────────────────────┘
   (no duplicates)
```

### Prerequisites

- **Docker** (Docker Desktop on macOS; the demo uses `host.docker.internal` so the in-container Restate can reach the local JVM).
- **Java toolchain** auto-provisioned by Gradle's foojay resolver — you don't need a specific JDK installed locally; Gradle will fetch one if needed.

### Run it

**Boot the stack** (Kafka + Restate + the demo app, plus topic creation, deployment and subscription registration):

```bash
./bin/start.sh
```

When it finishes, you have:
- Kafka on `localhost:19092`
- Restate ingress on `localhost:18080`, admin on `localhost:19070`
- The demo JVM listening on `localhost:9080` (logs in `.demo/app.log`)
- Topics `orders-raw` and `orders-clean` created
- A Kafka subscription wired to `OrderProcessor.process`

**Open three terminals:**

| Terminal | Command                          | What you see                                      |
|----------|----------------------------------|---------------------------------------------------|
| A        | `./bin/consume-raw.sh`           | The dirty stream as it arrives — duplicates and all. |
| B        | `./bin/consume-deduped.sh`       | The clean stream — only unique events.            |
| C        | `./bin/produce.sh`               | Generates 100 messages with ~30% duplicates.      |

You'll see:
- Terminal A prints all 100 messages, with ~30 duplicate `eventId`s.
- Terminal B prints only the unique events — every duplicate Terminal A shows is silently dropped.

**Adjust the run** with env vars:

```bash
MESSAGE_COUNT=500 DUPLICATE_RATE=0.5 ./bin/produce.sh
```

**Stop everything:**

```bash
./bin/stop.sh
```

---

## Using the dedup module in your own Restate app

### Add the dependency

This repo doesn't publish the lib to a registry yet, but in your project you'd reference it with the same Maven coordinates we use locally: `dev.restate.kafka:lib`.

### Bind `DedupService` alongside your services

```java
Endpoint endpoint = Endpoint.builder()
    .bind(new DedupService())   // from dev.restate.kafka.dedup
    .bind(new MyService())      // your code
    .build();

RestateHttpServer.listen(endpoint);
```

### Use the `Deduplicator` inside any handler

```java
@Service
public class MyService {

  @Handler
  public void process(Context ctx, MyEvent event) {
    var dedup = Deduplicator.of(ctx, "my-namespace", Duration.ofHours(1));
    if (!dedup.checkAndRecord(event.eventId())) {
      return;  // duplicate — silently drop
    }

    // first sighting — your business logic
    ctx.run("publish", () -> myKafkaProducer.send(...));
  }
}
```

The same call works inside `@VirtualObject` handlers too (`Deduplicator.of` accepts the SDK's base `Context`, which both `Context` and `ObjectContext` satisfy).

### What `Deduplicator.of(ctx, namespace, ttl)` does

- **Synchronous** — no RPC. Just constructs a handle bound to the namespace + TTL.
- **Namespace** — a string identifier (validated against `[a-zA-Z0-9_.-]+`). Two namespaces with the same dedup key are isolated.
- **TTL** — how long the dedup state for a key lives before being garbage-collected. After the TTL elapses, a re-arrival of the same key is treated as a fresh first-sighting.

### What `dedup.checkAndRecord(key)` costs

**Exactly one durable RPC and one state read.** That's the entire hot-path budget: the handler invokes `DedupService[namespace:key].checkAndRecord(ttl)`, which reads its `SEEN` state once, and returns. On first sighting it also writes the state and schedules a self-destruct timer (a journaled durable side-effect, not an additional round-trip).

---

## How the dedup module works under the hood

The library is intentionally minimal — one Restate Virtual Object plus a thin Java client.

### `DedupService` (Virtual Object, keyed by `namespace + ":" + key`)

```java
@VirtualObject
public class DedupService {
  private static final StateKey<Boolean> SEEN = StateKey.of("seen", Boolean.class);

  @Handler
  public boolean checkAndRecord(ObjectContext ctx, Duration ttl) {
    if (ctx.get(SEEN).isPresent()) return false;
    ctx.set(SEEN, Boolean.TRUE);
    DedupServiceClient.fromContext(ctx, ctx.key()).send().clear(ttl);
    return true;
  }

  @Handler
  public void clear(ObjectContext ctx) { ctx.clearAll(); }
}
```

- One VO instance per `(namespace, dedupKey)` pair. Single-writer per key — concurrent sightings of the same key are serialized through the VO, including across parallel Service-target Kafka delivery.
- **State is presence**: `SEEN` holds `true` if and only if this key has been observed. No accumulating set, no map.
- **Self-destruct**: on first sighting, the VO schedules a durable delayed self-call to `clear()` after `ttl`. When it fires, the VO's state is wiped and the key is "forgotten" — a future sighting with the same key is treated as a fresh first-sighting.

### `Deduplicator` (Java client facade)

```java
public final class Deduplicator {
  public static Deduplicator of(Context ctx, String namespace, Duration ttl) { ... }
  public boolean checkAndRecord(String key) { ... }
}
```

The `of` factory is purely synchronous — no I/O, just validates the namespace and stores the TTL. `checkAndRecord` invokes `DedupService[namespace:key].checkAndRecord(ttl)` and returns the boolean.

### Performance budget

| Path                     | RPCs | State lookups |
|--------------------------|------|---------------|
| First sighting           | 1    | 1             |
| Duplicate hit (hot path) | 1    | 1             |

That's it. There's no factory RPC, no central config lookup, no JVM cache. Every dedup check makes exactly one durable RPC and reads one state slot.

### What's deliberately *not* in v1

- Runtime-modifiable TTL config — TTL is a code constant per call site. Changing it requires redeploy.
- Watermarks, late-data handling, event-time semantics — TTL is wall-clock time from first sighting.
- Built-in metrics — Restate's standard observability covers VO invocations.
- An optimized in-VO library form — possible but deferred (see `REQUIREMENTS.md` Appendix); the per-check RPC is fast enough for nearly every Kafka pipeline.

---

## Design rationale

For the full design discussion — what alternatives we considered, why we rejected `DedupConfig`/runtime modifiability, what we'd change if profiling demanded a sub-millisecond path, etc. — see [`REQUIREMENTS.md`](REQUIREMENTS.md).

---

## Troubleshooting

**Port 8080 or 9070 already in use.** The compose file maps Restate ports to `18080` (ingress) and `19070` (admin) on the host to avoid common conflicts. If even those are taken, edit `docker-compose.yml`.

**App can't reach Kafka.** The local JVM uses `localhost:19092` (the EXTERNAL listener); Restate-in-container uses `kafka:9092` (the internal listener). Both are configured automatically by `start.sh` and `restate.toml`.

**Restate can't reach the app.** Restate calls back to your local JVM at `host.docker.internal:9080`. On Docker Desktop (Mac/Windows) that resolves automatically; on Linux, the compose file declares `extra_hosts: host-gateway` to make it work too.

**No deduplication happening.** Check `.demo/app.log` for handler errors. If `OrderProcessor.process` is throwing on the Kafka producer call, the dedup state still records "seen" but the publish doesn't happen — Restate retries the whole invocation, and on retry the recorded `true` is replayed (no re-record), but the publish path still fails the same way. Fix the underlying producer issue.

**Topic offsets to confirm dedup worked:**

```bash
docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server localhost:9092 --topic orders-raw
docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server localhost:9092 --topic orders-clean
```

`orders-clean` should have strictly fewer offsets than `orders-raw` (by the duplicate count).

---

## Tests

```bash
./gradlew :lib:test
```

- **Unit tests** for `KeyEncoding` — namespace validation and composite-key encoding.
- **Integration tests** for `DedupService` — runs against a real `restatedev/restate` container (via `dev.restate:sdk-testing` + Testcontainers). Covers first-sighting/duplicate semantics, namespace isolation, and the `clear()` reset path.
