# Kafka Dedup for Restate

A reusable Java module for deduplicating Kafka messages consumed via [Restate's](https://restate.dev) Kafka subscriptions, plus a runnable end-to-end demo.

The dedup module is for **application-defined** duplicate detection: when the same logical event arrives as multiple distinct Kafka messages — different offsets, e.g. an upstream producer retried a write, two sources independently emitted the same event, or an idempotency key spans producer instances — and you want your handler's business logic to run at most once per logical event.

Restate's Kafka subscriptions already deduplicate at the `(topic, partition, offset)` level on their own. This module sits a layer above that, where the caller supplies the application-meaningful key that defines what "the same event" means.

---

## What's in this repo

| Path                | What                                                                                  |
|---------------------|---------------------------------------------------------------------------------------|
| `lib/`              | The reusable dedup module (`Deduplicator`, `DedupHelper`).                           |
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
                          │            │     dedup = DedupHelper.of(...)        │
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
- Kafka on `localhost:9092`
- Restate ingress on `localhost:8080`, admin on `localhost:9070`
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

### Bind `Deduplicator` alongside your services

```java
Endpoint endpoint = Endpoint.builder()
    .bind(new Deduplicator())   // from dev.restate.kafka.dedup
    .bind(new MyService())      // your code
    .build();

RestateHttpServer.listen(endpoint);
```

### Use `DedupHelper` inside any handler

```java
@Service
public class MyService {

  @Handler
  public void process(Context ctx, MyEvent event) {
    var dedup = DedupHelper.of(ctx, "my-namespace", Duration.ofHours(1));
    if (!dedup.checkAndRecord(event.eventId())) {
      return;  // duplicate — silently drop
    }

    // first sighting — your business logic
    ctx.run("publish", () -> myKafkaProducer.send(...));
  }
}
```

The same call works inside `@VirtualObject` handlers too (`DedupHelper.of` accepts the SDK's base `Context`, which both `Context` and `ObjectContext` satisfy).

### What `DedupHelper.of(ctx, namespace, ttl)` does

- **Synchronous** — no RPC. Just constructs a handle bound to the namespace + TTL.
- **Namespace** — a string identifier (validated against `[a-zA-Z0-9_.-]+`). Two namespaces with the same dedup key are isolated.
- **TTL** — how long the dedup state for a key lives before being garbage-collected. After the TTL elapses, a re-arrival of the same key is treated as a fresh first-sighting.

---

## Tests

```bash
./gradlew :lib:test
```

- **Unit tests** for `KeyEncoding` — namespace validation and composite-key encoding.
- **Integration tests** for `Deduplicator` — runs against a real `restatedev/restate` container (via `dev.restate:sdk-testing` + Testcontainers). Covers first-sighting/duplicate semantics, namespace isolation, and the `clear()` reset path.
