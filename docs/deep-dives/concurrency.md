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

**Both of the first two are now implemented in this repo, against the same domain**, which is the
best way to compare them:

| | Pessimistic | Optimistic |
|---|---|---|
| Class | `AccountMutationExecutor` | `OptimisticAccountMutationExecutor` |
| Endpoint | `POST /api/accounts/transfers` | `POST /api/accounts/transfers/optimistic` |
| Read | `findByIdForUpdate` (`FOR UPDATE`) | `findById` — no lock at all |
| Conflict is | *prevented* at read time | *detected* at write time |
| Cost of contention | a blocked connection | a discarded transaction, retried |
| On failure | (cannot happen) | `ObjectOptimisticLockingFailureException` → retry → 409 |

The single most important thing to be able to say about the optimistic version: **every validation
in it runs against a possibly stale read.** `validateSufficientFunds` can pass on a balance another
transaction has already spent. That is not a tolerated bug — correctness does not come from the
check, it comes from the version predicate on the UPDATE (`WHERE id = ? AND version = 5`), which
matches zero rows if anything moved and aborts the attempt before it commits. The retry then
re-reads and re-validates, and *that* pass decides the outcome. The pessimistic version validates
against data nobody can change, because it locked first. Both are correct; they pay at different
times.

**Measured, not asserted.** I removed `@Version` from `Account` and re-ran
`OptimisticTransferIntegrationTests`: ten concurrent transfers of 5.00 from a 100.00 account left
the source at **95.00 instead of 50.00** — nine of the ten debits were lost updates — while all ten
credits landed on the destination. The system invented 45.00. That is what §1's table describes,
actually happening.

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

**Optimistic locking does not remove deadlock — it moves it.** No locks are taken on read, but the
UPDATEs at flush still take write locks, so two opposite-direction transfers can still deadlock at
commit time, in a narrower window. `OptimisticAccountMutationExecutor` therefore *still* loads
accounts in id order — not for read locks this time, but because Hibernate's flush follows its
action queue, so a consistent load order makes a consistent update order. That makes deadlock rare
rather than impossible (flush ordering is not a contract), which is why the retry policy treats a
deadlock as retryable rather than assuming it away.

---

## 2a. Retrying: what may be retried, and what must not

`ConcurrencyRetryTemplate` — `src/main/java/lv/ray/springb/service/impl/ConcurrencyRetryTemplate.java`

Two things make this correct, and both are easy to get wrong.

**The retry must live outside the transaction.** An optimistic conflict is discovered at flush, and
by then the transaction is doomed and marked rollback-only. Retrying inside it would reuse a
persistence context full of entities whose versions are already known to be stale, so every attempt
would fail identically. That is precisely why the executor is `REQUIRES_NEW` and the template has no
transactional annotation: each retry needs a new transaction, a new persistence context, and above
all **a new read**.

**Not every failure is retryable, and Spring's exception hierarchy encodes which.**

- `ConcurrencyFailureException` (transient) — retry. Covers `ObjectOptimisticLockingFailureException`
  (version mismatch), `CannotAcquireLockException` (deadlock), and serialization failures. One catch
  clause, three failures that all mean "you raced and lost".
- `DataIntegrityViolationException` (**non**-transient) — must **not** be retried. This is what a
  lost idempotency-key race throws, and it means *the work is already done*. Retrying it is exactly
  what the idempotency key exists to prevent; it has to pass through to the caller so the winner's
  result can be returned.
- A domain rejection (insufficient funds) — must not be retried either. It is a fact about the
  request, not a race, and retrying burns the budget rediscovering the same answer.

Being able to say *"transient means retry, non-transient means don't, and the framework already made
that distinction for me"* is a much better answer than "I catch the exception and try again".

**The budget matters too.** Five attempts, then a 409 telling the client to retry later — because
an unbounded retry on a permanently hot row pins a request thread forever. Same reasoning as the
outbox's `DEAD` state, one layer up. And the backoff is jittered for the same reason: two
transactions that collided would otherwise back off identically and collide again in lockstep.

**Probe:** *"When would you pick optimistic over pessimistic here?"* Contention frequency decides it.
The endpoint reports `attempts` in its response for exactly this reason — consistently 1 means
optimistic is winning (no locks, no blocking); consistently high means every attempt is wasted work
and a pessimistic lock does less total work by making writers wait once instead of repeatedly
redoing and discarding a whole transaction.

---

## 2b. Locking in Java instead — and why it is the fastest wrong answer

`JavaLockAccountMutationExecutor`, `AccountLockRegistry` — `src/main/java/lv/ray/springb/service/impl/`

The third strategy: no database lock, no version reliance, just a `ReentrantLock` per account held in
this JVM. It is worth building because it is the answer most people reach for first, because it
**genuinely works**, and because the benchmark below says it is the fastest of the three.

### It is correct on one node

`withinOneJvmTheJavaLockPreventsEveryConflict` records **zero** conflicts under 8 threads hammering
one account. That is not a weak result — with real mutual exclusion a conflict is impossible, not
merely unlikely, so zero is a deterministic assertion rather than a hopeful one. On a single
instance this is a legitimate design.

### Failure one: there is more than one JVM

`AccountLockRegistry` is an object on a heap. A second instance of the application has its own,
guarding nothing in common. `acrossTwoInstancesTheJavaLockStopsProtectingAnything` models this by
giving half the threads a second registry — and conflicts appear immediately.

**Nothing about the code changed. Only the number of copies of the process did.** The guarantee is
not weakened by scaling out, it is deleted, by a deployment decision that touches no source file.
This repo ships a Dockerfile; that is not hypothetical.

The fix is not a better Java lock. It is a lock both instances can see — which is the database row
lock the pessimistic path was already using.

### Failure two: the lock released before the commit

This one bites on a single node, and it is the version most people write first:

```java
@Transactional
public void transfer(...) {
    lock.lock();
    try { ...work... } finally { lock.unlock(); }   // unlocked here — commit happens AFTER
}
```

The method returns to the transaction interceptor, which has not committed yet. In the gap between
`unlock` and commit, another thread takes the lock, reads the account, and sees the balance *before*
this transfer — because this transfer is still uncommitted. Both compute from the same starting
value. Each held the lock for the whole of its own work, and the update was still lost.
`lockingInsideTheTransactionReleasesItBeforeCommitAndLosesUpdates` demonstrates it with one registry
in one JVM.

**A lock must outlive the transaction it protects.** A lock released before commit protects the
computation, not the data. Same reason `OutboxRelay` holds its transaction open across the dispatch:
a claim that ends before the work does is not a claim.

### Two details in the registry worth stealing

**A lock per account is a memory leak.** `ConcurrentHashMap<Long, Lock>` with `computeIfAbsent`
grows an entry per account ever touched and never removes one — you cannot evict a lock somebody
holds, and check-then-remove is itself a race. Striping (a fixed array, indexed by hash) makes
memory constant. The cost is *false contention* — two unrelated accounts on one stripe serialize
needlessly — which is a throughput cost and never a correctness one. Over-serializing is safe.

**Order by the lock, not by the entity.** The two-lock ordering rule from §2 still applies, but
translating it as "lowest account id first" is wrong once locks are striped, because the hash does
not preserve order: ids (1, 4) might map to stripes (1, 0) while (0, 3) map to (0, 3), and two
threads would acquire in opposite orders and deadlock. The ordering has to be over the objects
actually being locked.

---

## 2c. All three, measured under identical load

`TransferStrategyBenchmark` — `src/integrationTest/java/lv/ray/springb/service/TransferStrategyBenchmark.java`

```
./gradlew integrationTest -Dspringb.benchmark=true --tests '*TransferStrategyBenchmark*'
```

96 transfers, fixed, split over N threads all drawing from **one shared account**. Total work is
constant, so threads is purely a contention dial. Each thread transfers to its own destination, so
the source is the only contended row. Medians of 5 runs; every run balance-checked.

```
threads  strategy        millis    txn/sec   mean us    p95 us    wasted  >budget
1        PESSIMISTIC        567      169.1      5030      7114         0        0
1        OPTIMISTIC         419      228.6      3528      4348         0        0
1        JAVA_LOCK          350      273.8      2817      3389         0        0
2        PESSIMISTIC        327      293.3      5050      6675         0        0
2        OPTIMISTIC         453      211.6      6082      4432         8        0
2        JAVA_LOCK          325      294.9      4945      8824         0        0
4        PESSIMISTIC        310      309.0      9382     11985         0        0
4        OPTIMISTIC         486      197.5     10582      5706        18        1
4        JAVA_LOCK          313      305.8      8669     25657         0        0
8        PESSIMISTIC        379      253.2     22219     30065         0        0
8        OPTIMISTIC         582      164.9     20492    106029        38        3
8        JAVA_LOCK          310      309.0     15563     61844         0        0
16       PESSIMISTIC        323      297.0     35167     45411         0        0
16       OPTIMISTIC         646      148.4     38897    272037        66        6
16       JAVA_LOCK          315      304.3     30426     87508         0        0
```

**The headline: the fastest strategy at every single contention level is the one you must not
ship.** JAVA_LOCK leads throughput throughout — 273.8 txn/sec uncontended, still 304.3 at 16 threads
— because an in-JVM lock costs nanoseconds while a `FOR UPDATE` costs a database round trip and pins
a connection for the duration. A benchmark that measured only speed would recommend it, and would be
wrong, because the thing that disqualifies it does not appear in any column. **Correctness is not a
performance metric, and it is not discoverable by measuring.**

Beyond that:

**Optimistic vs pessimistic crosses over between one and two concurrent writers** — much earlier
than intuition suggests. At one thread optimistic is ~35% faster (228.6 vs 169.1); from two threads
onward pessimistic leads and the gap widens to roughly 2x by 16.

**Pessimistic throughput is flat** — 253–309 txn/sec from 2 threads to 16. Writes to one row
serialize regardless, so threads add no throughput; but they add no *waste* either. A queue's total
work does not grow with its length.

**Optimistic wasted work grows faster than the contention:** 0 → 8 → 18 → 38 → 66. At 16 threads
that is 66 discarded attempts per 96 transfers — each a full transaction with reads, validation,
ledger and outbox rows, all rolled back. Every loser makes the next round more expensive, which is
why it degrades rather than plateaus.

**Tail latency separates all three, and it is where averages lie.** At 16 threads the means are
close (35.2ms / 38.9ms / 30.4ms) while p95 is 45ms pessimistic, 88ms Java-lock, 272ms optimistic.
Optimistic p95 blows up ~62x from 1 to 16 threads against pessimistic's ~6x. **A lock queue is
approximately fair; retrying is not** — a blocked thread progresses when its turn comes, while a
retrying thread re-enters the same lottery each time and can lose repeatedly. That is starvation,
and it hides completely behind a mean.

Note also that JAVA_LOCK's p95 is consistently worse than the database lock's despite better
throughput. `new ReentrantLock()` is **non-fair** by default: it allows barging, which raises
throughput and lengthens the worst case. `new ReentrantLock(true)` reverses that trade. Same
fairness-versus-throughput dial, one layer up from the database.

And under the shipped retry budget of 5, `>budget` says 6 of 96 transfers (6.25%) would have
returned 409 at 16 threads. The optimistic path does not merely slow down under contention — it
starts refusing work.

**So which would you choose?** Not one globally — per account. A retail account has one writer at a
time and optimistic is strictly better. A treasury or settlement account is exactly where it
collapses. That is what the `attempts` field in the response is for. And the Java lock is off the
table entirely for anything that scales horizontally, however fast it looks.

**On trusting these numbers.** One machine, one container, no network between app and database, one
workload shape. The *shape* of the curves transfers; the absolute figures do not. Two confounds were
caught by results that made no sense: `spring.jpa.show-sql=true` would have measured console
formatting (and unequally, since the optimistic path emits more statements when it retries), and an
insufficient warmup initially made the *least* contended case the slowest — 1179ms against ~500ms
for the same strategy under more contention. Less contention cannot be slower; that impossibility is
what exposed the artifact. Being able to say "I distrusted my own benchmark, and here is what it was
actually measuring" is worth more in an interview than any number in the table.

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
8. In the optimistic transfer, `validateSufficientFunds` runs on a possibly stale balance. Why is
   that not a bug?
9. Why must the retry sit outside the transaction rather than inside it?
10. Why is `DataIntegrityViolationException` excluded from the retry when
    `OptimisticLockingFailureException` is included?
11. Does optimistic locking eliminate deadlock? Justify the answer.
12. Pessimistic throughput stays flat as threads rise while optimistic degrades. Why does the flat
    one not also degrade?
13. At 16 threads both strategies have a ~50ms mean, but p95 is 64ms vs 269ms. What causes that
    divergence, and why is the mean misleading?
14. Which strategy would you put on a treasury account, and which on a retail one?
15. An in-JVM `ReentrantLock` per account is the fastest of the three here. Why is it still the
    wrong answer, and what exactly changes to break it?
16. Why is `ConcurrentHashMap<Long, Lock>` a memory leak, and why is evicting from it hard?
17. With striped locks, why is "acquire the lower account id first" not sufficient?
18. A `@Transactional` method takes a lock on entry and releases it on exit. What is still wrong?
