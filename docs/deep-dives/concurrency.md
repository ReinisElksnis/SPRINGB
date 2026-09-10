# Concurrency, from this codebase outwards

Anchored on code in this repo. Each section starts from a line you can open, then works down to the
mechanism underneath it — the layer interviews actually probe.

---

## 1. Why `SELECT … FOR UPDATE` is here at all

`AccountRepository.findByIdForUpdate` — `src/main/java/lv/ray/springb/repository/AccountRepository.java:28`

Delete the lock and this is the failure, in order:

| | Thread A | Thread B |
|---|---|---|
| t1 | `SELECT balance` → 100 | |
| t2 | | `SELECT balance` → 100 |
| t3 | check 100 ≥ 80 ✓ | |
| t4 | | check 100 ≥ 80 ✓ |
| t5 | `UPDATE balance = 20` | |
| t6 | | `UPDATE balance = 20` |

Two withdrawals of 80 against a balance of 100 both succeed, and the account ends at 20 instead of
−60. This is a **lost update**, and the important thing to be able to say is *why the database's
default isolation does not stop it*.

Postgres defaults to `READ COMMITTED`. That guarantees you never read uncommitted data — it says
nothing about a value staying stable across two statements in your transaction. B's read at t2 was
perfectly legal; it read committed data. The problem is that the decision at t4 was made on a value
that was stale by the time it was acted on at t6. Isolation levels constrain what you may *read*;
they do not, at this level, constrain you from acting on something you read a moment ago.

Three ways out, and knowing why this codebase chose the first is the actual interview answer:

- **Pessimistic (`FOR UPDATE`) — what's used here.** Take the row lock before reading, so B blocks
  at t2 and re-reads 20 after A commits. Correct by construction, costs a blocked connection.
  Right choice when contention is likely and retrying is expensive. A hot account genuinely does
  get concurrent writes, and a bank customer will not accept "please try again" on a withdrawal.
- **Optimistic (`@Version`).** Read freely, and on write check the version has not moved; if it
  has, throw and let the caller retry. Cheaper under low contention, worse under high — a hot row
  becomes a retry storm where most attempts are wasted work.
- **`SERIALIZABLE` isolation.** Let Postgres detect the anomaly and abort one transaction. Correct,
  but it converts a *blocking* problem into a *retry* problem: you must now handle serialization
  failures everywhere, which is more code than the explicit lock, not less.

There is a fourth that sidesteps the whole thing: `UPDATE accounts SET balance = balance - 80 WHERE
id = ? AND balance >= 80`, then check the affected row count. One atomic statement, no read-then-
write gap. It doesn't fit here because the code needs the resulting balance for the ledger's
`balance_after` — but naming it shows you know the read-modify-write cycle is the enemy, not
concurrency itself.

**Probe you should expect:** *"What if the two operations are on different accounts?"* Then they
never contend, because the lock is per row, not per table. That is exactly why row-level locking
scales and why `synchronized` in the JVM would not: a JVM lock is per process, so it would serialize
unrelated accounts and would still be wrong the moment you run a second instance.

---

## 2. Lock ordering, and why deadlock is a *cycle* not a *wait*

`AccountMutationExecutor.transferOnce` — `src/main/java/lv/ray/springb/service/impl/AccountMutationExecutor.java:121`

```java
final boolean fromFirst = fromAccountId.compareTo(toAccountId) < 0;
final Account first  = lock(fromFirst ? fromAccountId : toAccountId);
final Account second = lock(fromFirst ? toAccountId   : fromAccountId);
```

Without the sort, a transfer A→B and a simultaneous transfer B→A each grab their source account,
then wait for the other's — forever. Postgres detects it and kills one with a deadlock error, so
the symptom is an intermittent failure under load that never reproduces in a single-threaded test.

The general statement worth having ready: **deadlock requires four conditions simultaneously —
mutual exclusion, hold-and-wait, no preemption, and circular wait.** Break any one and deadlock
becomes impossible. Sorting the lock acquisition order breaks *circular wait*, and it is usually
the cheapest of the four to break because it needs no coordination between threads at all — just a
rule every thread follows independently. That last clause is the insight: a global ordering is a
protocol that requires no communication.

The other three, and why they're worse here: you can't drop mutual exclusion (that's the point of
the lock); "no hold-and-wait" means acquiring both locks atomically, which SQL doesn't offer;
preemption means lock timeouts and retries, which is what Postgres's deadlock detector is already
doing as a backstop.

**Probe:** *"Does this still work with three accounts?"* Yes — a total order over all lockable
resources prevents cycles for any number of them, which is why the rule is stated over ids rather
than over "from and to".

---

## 3. `REQUIRES_NEW`, and the fact that Spring transactions are a proxy

`AccountMutationExecutor` — `src/main/java/lv/ray/springb/service/impl/AccountMutationExecutor.java:36`

`@Transactional` is not a language feature. Spring wraps the bean in a proxy; the annotation is
advice applied when a call crosses that proxy. Two consequences, both of which are in this repo:

**(a) Self-invocation silently does nothing.** `LedgerReconciliationServiceImpl` used to call
`this.reconcileAccount(id)` in a loop, with both methods annotated. The inner annotation never
applied, because a `this.` call never leaves the object. Everything ran in one transaction. See
`AccountLedgerReplayer`'s class javadoc — the fix was to move the method onto a different bean, and
it was a memory fix as much as a transactional one.

**(b) Propagation is the difference between two behaviours that look identical.** This codebase
uses both, deliberately, in opposite directions:

- `AccountMutationExecutor` is `REQUIRES_NEW` because it needs its own boundary. When a duplicate
  request loses the idempotency-key race, its transaction is marked rollback-only. If it had
  *joined* the caller's transaction, the caller's transaction would be poisoned too, and the
  fallback "return the winner's result" lookup would fail with `UnexpectedRollbackException`.
- `OutboxAppender.append` — `src/main/java/lv/ray/springb/service/outbox/OutboxAppender.java:45` —
  has **no** annotation, so it inherits `REQUIRED` and joins the caller. That is the whole outbox
  pattern: the event must commit with the money or not at all. Putting `REQUIRES_NEW` on it would
  reintroduce the dual-write bug it exists to remove.

Same framework, same file tree, opposite choices, each for a stated reason. If you can walk an
interviewer through that contrast you have demonstrated exactly the "why, not the term" they asked
for.

---

## 4. `FOR UPDATE` vs `FOR UPDATE SKIP LOCKED`

`OutboxMessageRepository.claimDueBatch` — `src/main/java/lv/ray/springb/repository/OutboxMessageRepository.java:54`

The same SQL keyword, two opposite intents, and the reason is *what the contending parties want*:

- **Account withdrawals contend for one row and must serialize.** Blocking is correct. The second
  writer's whole job is to re-read what the first one wrote.
- **Relay instances contend for a queue and must divide it.** Blocking is pure waste. Under plain
  `FOR UPDATE`, a second relay blocks on the first's rows and wakes to find them published — it did
  nothing but wait, and adding instances adds zero throughput. `SKIP LOCKED` steps over locked rows,
  so each poller walks off with a disjoint batch.

That last point is worth stating sharply in an interview: **`SKIP LOCKED` turns the database's row
locks into a work-distribution mechanism.** No leader election, no distributed lock, no queue
service — the locks you already have partition the work.

`OutboxIntegrationTests.skipLockedLetsASecondClaimStepOverLockedRowsInsteadOfBlockingOnThem` proves
it: it holds one claiming transaction open and gives a second claim a timeout. Remove `SKIP LOCKED`
and it fails with `TimeoutException`. (I verified this by actually removing it — the test failed as
designed, then I restored it.)

**Probe:** *"Could you just use a message queue?"* Yes, and then you have the dual-write problem
back, because the queue is a second system that cannot commit atomically with Postgres. The outbox
exists precisely to avoid needing one on the write path.

---

## 5. `fixedDelay` vs `fixedRate` — self-limiting vs self-amplifying

`OutboxRelay.relayOnce` — `src/main/java/lv/ray/springb/service/outbox/OutboxRelay.java:93`

`fixedRate` starts a run every N ms regardless of whether the last finished. Spring's default
scheduler is **a single thread**, so overrunning runs don't overlap — they queue, in an unbounded
queue. A broker that slows down builds a backlog of pending *executions* in heap on top of the
backlog of pending *messages* in the database, and keeps firing catch-up runs long after recovery.

`fixedDelay` measures from the *end* of one run to the start of the next, so a slow dependency
slows polling down instead of piling work up. **Self-limiting rather than self-amplifying** — the
same property good backpressure has, and worth recognising as that.

**Probe:** *"What if you want parallel polling?"* You don't need it here — `SKIP LOCKED` gives you
parallelism across *processes*, which also survives one JVM dying. Adding threads inside one JVM
would mean sharing a transaction across threads, which is wrong: a JDBC connection is not
thread-safe and a Spring transaction is bound to a thread via `ThreadLocal`.

---

## 6. The Java Memory Model — the part behind all of the above

None of the above needed the JMM, because the shared state lives in Postgres, not in the heap. That
is itself an answer worth giving. But the JMM is asked about directly, so:

**The problem.** Without synchronisation, a write by thread A may never become visible to thread B —
not "eventually visible", *never*, legally. The JIT may hoist a field read out of a loop into a
register; a CPU may hold a write in a store buffer. Both are legal because the JMM only promises
visibility across a **happens-before** edge.

**Happens-before** is the whole model. An edge is created by:
- unlocking a monitor → a subsequent lock of the *same* monitor
- a `volatile` write → a subsequent read of the *same* variable
- `Thread.start()` → everything in that thread; everything in a thread → `join()` returning
- writing a `final` field in a constructor → seeing the fully constructed object, provided `this`
  didn't escape during construction

Crucially the edge publishes **everything the writing thread did before it**, not just the one
variable. That's why the "cheap" idiom works: write your data into plain fields, then write a
`volatile` flag last; a reader that sees the flag is guaranteed to see the data.

**Why `volatile` is not a lock.** It gives visibility and ordering, not atomicity. `count++` on a
volatile long is still read-modify-write and still loses updates — exactly the same shape as the
`FOR UPDATE` bug in §1, one layer down. For that you need `AtomicLong` (a CAS loop) or a lock. Being
able to say "`volatile` fixes visibility, not compound actions, and here is the identical bug at the
database layer" is a strong answer.

**Double-checked locking** is the classic trap: without `volatile` on the instance field, a thread
can observe a non-null reference to a partially constructed object, because the constructor's writes
and the reference write may be reordered.

---

## 7. Thread pools and virtual threads (Java 25 — this project's toolchain)

**Sizing.** The starting points are `N_cpu + 1` for CPU-bound work and, for I/O-bound work,
`N_cpu × (1 + wait/service)`. What matters more than the formula: a thread blocked on I/O holds a
stack (~1 MB of reserved address space) and contributes context-switch pressure while doing nothing.

**Queues and backpressure.** `Executors.newFixedThreadPool` uses an *unbounded* `LinkedBlockingQueue`.
Under sustained overload it does not reject work — it accumulates it until the heap dies. That is the
single most common way a thread pool causes an `OutOfMemoryError`, and it's the same shape as the
`fixedRate` problem in §5. A bounded queue plus an explicit `RejectedExecutionHandler` is a design
decision about what to do when you are overloaded; the default is a decision to fall over later.
`CallerRunsPolicy` is the neat one — it pushes work back onto the submitting thread, which slows the
producer down. That is backpressure, implemented in one line.

**Virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`).** A virtual thread's stack lives
on the heap and is unmounted from its carrier thread whenever it blocks, so blocking costs almost
nothing and you can have millions. This inverts the usual advice: **do not pool virtual threads** —
pooling exists to amortise expensive creation, and they are cheap. What they do *not* do is make
blocking free at the *resource* level: a virtual thread waiting on a JDBC connection still holds
that connection, so with a 10-connection Hikari pool your real concurrency limit is 10 no matter how
many virtual threads you spawn. Recognising that the bottleneck moves rather than disappears is the
answer that distinguishes depth from vocabulary.

The catch to know: a virtual thread **pins** its carrier if it blocks inside a `synchronized` block
(largely resolved in recent JDKs, but the reason it happened is the point — the object monitor was
tied to the OS thread). `ReentrantLock` never had that problem.

---

## Interview drill

Answer out loud before reading the section back.

1. Two threads withdraw from one account with no lock. What exactly goes wrong, and why doesn't
   `READ COMMITTED` prevent it?
2. Why sort account ids before locking? Which of the four deadlock conditions does that break, and
   why is it the cheapest one to break?
3. Why is `AccountMutationExecutor` `REQUIRES_NEW` while `OutboxAppender` has no annotation at all?
4. Why does the outbox use `SKIP LOCKED` when the account lock deliberately does not?
5. `volatile` vs `AtomicInteger` vs `synchronized` — what does each actually guarantee?
6. You have 10 DB connections and 10,000 virtual threads. What is your concurrency?
7. Why is `Executors.newFixedThreadPool` a latent OOM under sustained overload?
