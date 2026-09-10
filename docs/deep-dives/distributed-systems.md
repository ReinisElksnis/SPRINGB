# Distributed systems, from this codebase outwards

This repo is a single service with one database, which sounds like the wrong place to learn
distributed systems. It isn't: the moment it publishes an event, it has two systems that can fail
independently, and that is the entire subject in miniature.

---

## 1. The dual-write problem — the reason the outbox exists

You need to move money **and** tell the outside world. That's two writes to two systems, and there
is no ordering that survives a crash in between:

- **Commit, then publish.** Crash after commit: money moved, nobody was told. A downstream ledger,
  a notification, a fraud check — all permanently missing an event.
- **Publish, then commit.** Transaction rolls back: an event exists announcing a transfer that never
  happened. Strictly worse; you have now lied.
- **Both in one transaction.** There is no such thing across two systems without a distributed
  transaction (2PC), which needs every participant to support it, blocks if the coordinator dies,
  and is why almost nobody uses it.

**The outbox removes the second system from the write path.**
`AccountMutationExecutor` writes the event into `outbox_messages` in the *same local transaction* as
the balance change — `src/main/java/lv/ray/springb/service/outbox/OutboxAppender.java:45`, which
deliberately has **no** `@Transactional` so it joins the caller. One database, one commit, atomic by
construction. A separate poller publishes afterwards, and *that* can fail and retry freely, because
it is no longer entangled with the money.

`OutboxIntegrationTests.aRejectedOperationLeavesNoEventBehind` is the proof: an overdrawn withdrawal
rolls back, and its event vanishes with it.

**Probe:** *"Why not Change Data Capture instead?"* CDC (Debezium reading the WAL) gets the same
guarantee without an application-level table, and adds no write-path latency. Trade-off: it couples
you to the database's replication log and infrastructure, and your events become a reflection of
your *schema* rather than a deliberate contract. An outbox table lets you shape the event
explicitly. Naming both, with the trade, is the answer.

---

## 2. At-least-once, and why exactly-once is a myth

The relay can publish successfully and crash before marking the row `PUBLISHED`. The next poll
publishes it again. **That is not a bug to be fixed — it is inherent.**

The general statement: in an asynchronous network you cannot distinguish *"the message was lost"*
from *"the response was lost"*. So a sender that gets no acknowledgement has exactly two options —
retry (risking duplicates: at-least-once) or don't (risking loss: at-most-once). There is no third
choice, which is why **exactly-once delivery is impossible**.

What *is* achievable is **exactly-once processing**: at-least-once delivery plus an idempotent
consumer. The duplicate still arrives; it just has no additional effect.

This codebase already has the consumer-side version of that idea — `idempotency_records`. A client
retrying a deposit with the same key gets the original result rather than a second deposit. Note the
subtlety in `AccountMutationExecutor`: the key is claimed **last**, so a concurrent duplicate loses
the unique-constraint race and rolls back the whole attempt. The mutation and its key commit
together or not at all — the same co-commit reasoning as the outbox, applied to deduplication.

Being able to say *"we're at-least-once at the transport and idempotent at the consumer, which
composes to exactly-once processing"* is the sentence that ends this question well.

---

## 3. Retry, backoff, jitter — and why jitter is the part people omit

`RetryBackoff` — `src/main/java/lv/ray/springb/service/outbox/RetryBackoff.java:70`

- **Retry** because most failures are transient.
- **Exponential backoff** because the failures that matter are *not* transient and are *correlated*:
  a broker is down for minutes, and it's down for every message at once. Fixed-interval retry turns
  one outage into a sustained flood.
- **Jitter** because deterministic backoff means everyone who failed together retries together. The
  broker recovers, the whole fleet's backlog lands simultaneously, it falls over again, and the
  system oscillates instead of recovering. **A thundering herd is caused by the retry policy, not by
  the outage.**

"Full jitter" — uniform over `[0, cap]` rather than `cap ± wobble` — spreads load best. Occasionally
retrying sooner than nominal is cheap; a synchronised stampede is not.

Also here: **a retry budget**. `max-attempts` parks a message as `DEAD` rather than retrying
forever. Without it, one permanently-malformed message is re-claimed on every poll — and since the
relay takes oldest-first, it **starves the entire queue behind it**. A terminal state is what lets
the relay give up on one message without giving up on the queue.

What this deliberately does *not* have, and you should be ready to name: a **circuit breaker**.
Backoff is per-message; a breaker is per-dependency, and trips to fail fast for *everyone* when a
downstream is known-down, rather than having every message discover it independently. For a
single-threaded poller the distinction barely matters; for a request path it matters a lot.

**And the retry that is genuinely dangerous:** retrying a non-idempotent operation. Retrying a
`POST /transfer` without an idempotency key moves the money twice. Backoff is the easy half; knowing
*what is safe to retry* is the real one.

---

## 4. Where the concurrency actually lives: `SKIP LOCKED`

`OutboxMessageRepository.claimDueBatch` — `src/main/java/lv/ray/springb/repository/OutboxMessageRepository.java:54`

Multiple relay instances need to divide the backlog, not queue behind each other. `FOR UPDATE SKIP
LOCKED` makes the database's row locks the partitioning mechanism: each poller claims a disjoint
batch, with **no leader election, no distributed lock, no queue service, and no coordination between
the instances at all.**

That last part is the interview-grade observation. The instinct is to reach for a distributed lock
(Redis, ZooKeeper) to make a job "run once". Usually you don't need one — you need work that is
*claimable*. And distributed locks are genuinely hard: a lock with a TTL can expire while its holder
is still running (a GC pause is enough), so two holders act simultaneously. The correct fix is a
**fencing token** — a monotonically increasing number the resource checks and rejects if stale.
"A lock without a fencing token is an optimisation, not a guarantee" is worth being able to say.

Here the fencing question dissolves: the lock and the work are the same transaction, so if the relay
dies, the transaction aborts and the rows are simply unlocked.

See the concurrency doc §4 for why the *same* SQL keyword is deliberately used without `SKIP LOCKED`
on account withdrawals.

---

## 5. CAP, and how to talk about it without sounding like a flashcard

The formal statement: during a **network partition**, you must choose availability or consistency.

The misuses to avoid:
- **CAP is not a menu of three.** P is not optional — networks partition. You choose between C and A
  *when a partition occurs*, and behave normally otherwise.
- **"CA systems" don't exist** in a distributed setting. A single-node database isn't CA; it's
  not distributed.
- **The C in CAP is linearizability**, not ACID's C. Different word, different meaning.

**PACELC** is the better model and worth raising: *if* **P**artition, choose **A** or **C**; **E**lse
(normal operation) choose **L**atency or **C**onsistency. It captures the trade you actually make
every day — synchronous replication costs latency on every write, not just during failures.

**Where this app sits:** one Postgres, so strong consistency for the money, and the events are
asynchronous and eventually consistent. That is a deliberate split and a good thing to articulate:
**the ledger is CP; the notifications are AP.** You rarely choose one for a whole system — you choose
per piece of data, according to what the business actually requires. A balance must be right; a
notification can be a few seconds late.

---

## 6. Consistency models, ordered

- **Linearizable** — every read sees the most recent write; the system behaves as one copy. Costs
  coordination on every operation.
- **Sequential** — all nodes see operations in the same order, not necessarily real-time order.
- **Causal** — operations that are causally related are seen in order; concurrent ones may differ.
  Often the sweet spot: cheap, and matches intuition ("the reply appears after the comment").
- **Eventual** — replicas converge if writes stop. Says nothing about *when*, which is why bare
  eventual consistency is so often a bad user experience.

**Read-your-own-writes** is the one that bites in practice: a user updates their profile, a read hits
a lagging replica, and their change appears to have vanished. Fixes: route a user's reads to the
primary for a window, or track a version/LSN per session. This is a very common real interview
question because it's a real production bug.

---

## 7. Consensus, briefly but correctly

**Raft/Paxos** solve one problem: getting a set of nodes to agree on an ordered log, tolerating
`f` failures with `2f+1` nodes. Majority quorums are why cluster sizes are odd — 3 tolerates 1, 5
tolerates 2; adding a 4th node buys nothing and costs latency.

**FLP impossibility**: in a fully asynchronous system with even one faulty process, no deterministic
consensus algorithm can guarantee termination. Real systems escape it with timeouts and randomised
elections — which is why Raft has randomised election timeouts, and why "is the leader dead or just
slow?" is undecidable. **Failure detection is a timeout, always, and every timeout is a guess.**

Practical corollary: a service marked down by a health check may be perfectly alive and merely
paused (see: GC). That is the same reason lock TTLs need fencing tokens (§4).

---

## 8. What this repo would need next, honestly

Named as gaps rather than pretended away:

- **An outbox cleanup job.** `PUBLISHED` rows accumulate forever. The partial index keeps queries
  fast, but the table still grows — archive or delete on a schedule.
- **A real dispatcher.** `LoggingOutboxDispatcher` stands in for a broker; the interface exists so
  swapping it is one class.
- **Ordering guarantees.** Messages are claimed oldest-first, but two relay instances publish
  concurrently, so **per-aggregate ordering is not guaranteed**. If a consumer needs "TRANSFER_OUT
  before TRANSFER_IN", the fix is partitioning by `aggregate_id` (claim per key, or a broker
  partition key) — not hoping.
- **A saga**, if a transfer ever spans two services. Then there is no shared transaction at all, and
  you need compensating actions plus a state machine per transfer. Today both legs are one local
  transaction, which is exactly why this is simple — and knowing *that's* why is the point.
- **Observability**: backlog depth, oldest pending message age, dead count. The single most useful
  alert is *age of the oldest pending message* — it catches a stalled relay that a throughput metric
  would miss entirely.

---

## Interview drill

1. Why can't you just publish to Kafka after committing to Postgres?
2. Why is exactly-once delivery impossible, and what is achievable instead?
3. What does this codebase do to make duplicate requests safe? Why is the idempotency key claimed
   *last*?
4. Why jitter and not just exponential backoff?
5. Why does one permanently-failing message need a `DEAD` state?
6. How do two relay instances avoid publishing the same message, with no lock service?
7. Why does a distributed lock with a TTL need a fencing token?
8. Which parts of this system are CP and which are AP, and who decided?
9. Your consumer needs strict per-account event ordering. What breaks, and what would you change?
