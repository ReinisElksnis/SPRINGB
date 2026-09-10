# Memory management, from this codebase outwards

This was the weakest area in the repo before the reconciliation rewrite, and it is the one with the
most "term vs mechanism" traps in interviews. The repo now contains one real, fixed example; the
rest of this builds the model underneath it.

---

## 1. The bug that was actually here

Reconciliation used to do this:

```java
List<Operation> operations = operationRepository.findByAccountIdOrderByIdAsc(accountId);
```

Every operation an account had ever had, materialised at once, as **managed JPA entities**, inside a
transaction that `reconcileAllAccounts` held open across *every account in the database*.

Four separate things made that expensive, and being able to separate them is the point:

1. **The result list itself.** N `Operation` objects — object header, 5 fields, plus the `BigDecimal`s
   and `String`s they point at. Order of a few hundred bytes each.
2. **The persistence context.** Hibernate keeps a strong reference to every entity it hands out, in a
   map, for the life of the transaction. Nothing loaded can be collected until the transaction ends,
   however finished with it you are.
3. **Dirty-checking snapshots.** It also keeps a *copy* of each entity's original field values, so it
   can detect changes at flush. Roughly doubles the cost — for a read-only replay that will never
   modify anything.
4. **The JDBC layer.** The Postgres driver buffers the entire `ResultSet` client-side by default. So
   even before Hibernate builds a single entity, the whole result set is already in heap.

The failure mode is the nasty kind: not slow, but `OutOfMemoryError`, on whichever account has the
longest history — the busiest one, where reconciliation matters most. It would pass every test and
every small-dataset staging run.

**The fix** — `OperationRepository.streamLedgerEntries`,
`src/main/java/lv/ray/springb/repository/OperationRepository.java:55`:

```java
@QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "500"))
@Query("select new lv.ray.springb.dto.LedgerEntry(o.id, o.type, o.amount, o.balanceAfter) …")
Stream<LedgerEntry> streamLedgerEntries(Long accountId);
```

Each element addresses one of the four:

- **A projection, not an entity.** A record built by a constructor expression is never managed —
  no persistence-context reference, no dirty-check snapshot. Kills (2) and (3) outright.
- **A `Stream`, not a `List`.** Rows arrive incrementally; each is garbage as soon as the loop moves
  past it. Kills (1).
- **The fetch-size hint.** This is the one people miss. The Postgres JDBC driver only switches to a
  server-side cursor when a fetch size is set *and* autocommit is off. Without it, (4) stands and
  everything above it is theatre — you'd have "streaming" code sitting on a fully buffered result
  set. Autocommit is off here because the caller is inside a Spring transaction, which is also what
  keeps the cursor valid.
- **`REQUIRES_NEW` per account** — `AccountLedgerReplayer` — so the persistence context's *lifetime*
  is one account, not the whole sweep.

**Peak heap went from "proportional to the ledger" to "proportional to the fetch size."**

If you must load real entities in a loop, the equivalent tool is `entityManager.clear()` (or
`detach`) every N rows. Worth knowing, but a projection is better: it makes the retention
unrepresentable rather than merely managed.

**Probe:** *"Why not just raise `-Xmx`?"* Because the requirement is unbounded and the heap is not.
A bigger heap moves the failure later and makes the eventual GC pauses worse. Bounded memory is a
design property, not a tuning parameter.

---

## 2. Where objects actually live

**Stack:** one per thread, holds frames — locals, and *references*. Primitives live here directly.
Freed by returning; never collected.

**Heap:** all objects. Shared across threads. This is what the GC manages.

**Metaspace:** class metadata. **Native/off-heap**, since Java 8 — which is why a classloader leak
now exhausts native memory rather than throwing `OutOfMemoryError: PermGen`. Typical cause: an app
redeployed repeatedly in a container, each deploy leaking a classloader via a lingering thread or a
`ThreadLocal`.

**A JVM's footprint is heap plus a lot else** — Metaspace, thread stacks (~1 MB *reserved* each),
code cache, GC structures, direct byte buffers. This is why a container with `-Xmx512m` and a 512 MB
memory limit gets OOM-killed by the kernel: the kernel counts everything, `-Xmx` counts one part.
Modern JVMs read cgroup limits and size the heap as a *fraction* of the container limit
(`MaxRAMPercentage`) for exactly this reason. Good thing to raise unprompted — it shows you've
operated a JVM, not just written one.

---

## 3. Generational GC — why short-lived garbage is nearly free

The **weak generational hypothesis**: most objects die young. Measured, not assumed — and it holds
overwhelmingly for request-scoped server work, including every `LedgerEntry` in §1.

The heap is split into a young generation (Eden + two survivor spaces) and an old generation.

- Allocation is a **pointer bump** in Eden. Nearly free.
- A **minor GC** copies the *survivors* out and declares the rest of Eden empty. Cost is
  proportional to what **lives**, not to what died. Garbage is genuinely free to collect.
- Objects surviving enough minor GCs are **promoted** to the old generation, collected by rarer,
  more expensive **major/full** GCs.

Two consequences that matter more than the mechanism:

- **Allocating a lot of short-lived garbage is cheap.** The instinct to "reduce allocations" is
  usually misplaced; the JVM is extremely good at this. `LedgerEntry` records dying immediately cost
  almost nothing.
- **The expensive thing is *retention*.** Objects held alive — the persistence context in §1, a
  cache, a growing queue — get promoted, and then they're in the expensive generation *and* they
  make every subsequent collection scan more. Long-lived garbage is the enemy, not garbage.

This reframes §1 exactly: the old code's problem was never that it allocated. It was that it held on.

---

## 4. Which collector, and why

| | Design point | Use when |
|---|---|---|
| **Serial** | one thread, no parallelism | tiny heaps, single-core containers |
| **Parallel** | maximise **throughput**, accepts long stop-the-world pauses | batch jobs where total time matters and latency doesn't |
| **G1** (default) | *pause-time target*; heap in regions, collects the ones with most garbage first ("garbage first") | general server default; predictable-ish pauses at moderate heaps |
| **ZGC** | pauses sub-millisecond and **independent of heap size**; concurrent via load barriers | large heaps with tight latency SLOs |
| **Shenandoah** | same goal, concurrent evacuation via Brooks pointers | as ZGC |

The framing that shows understanding: **you are trading throughput, latency, and footprint — you
cannot have all three.** ZGC's tiny pauses are bought with barrier overhead on every reference load
and more CPU spent concurrently; G1 gives some pause predictability for some throughput. "ZGC is
newest so it's best" is the shallow answer.

**Nothing here eliminates stop-the-world entirely** — all of these still pause briefly at safepoints
for root scanning. "Pauseless" means "pauses that don't grow with the heap."

**Probe:** *"Your service has a 99.9th-percentile latency spike every few minutes."* Suspect full
GCs from promotion pressure; check GC logs before touching code. If pause length tracks heap size,
that's the collector; if allocation rate is the driver, that's a retention bug like §1.

---

## 5. Reachability, and what a Java "leak" is

Java has no dangling pointers, so a leak is always: **something is still reachable that you are done
with.** The GC is behaving correctly; your object graph is wrong.

The four references, which get asked directly:
- **Strong** — ordinary. Never collected while reachable.
- **Soft** — cleared only under memory pressure. Nominally for caches; in practice it delays an OOM
  into a thrashing phase, so a bounded cache is almost always better.
- **Weak** — cleared at the next GC once no strong refs remain. This is what `WeakHashMap` uses to
  key a cache without pinning the key.
- **Phantom** — for cleanup after finalisation; the modern replacement for `finalize()`, which is
  deprecated because it could resurrect objects and ran on an unbounded queue with no timing
  guarantee. Use `Cleaner` or, better, `try`-with-resources.

The classic leak sources, all reachability:
- **`static` collections** — a `static Map` cache with no eviction is a memory leak with good PR.
- **`ThreadLocal` on a pooled thread.** The thread outlives the request, so the value is never
  cleared. Endemic in servlet containers. `remove()` in a `finally`.
- **Unbounded queues** (§7 of the concurrency doc — same shape).
- **Listeners/callbacks never unregistered.**
- **An open resource** — a `Stream` over a DB cursor, which is exactly why
  `AccountLedgerReplayer` at `src/main/java/lv/ray/springb/service/impl/AccountLedgerReplayer.java:77`
  uses try-with-resources. That one exhausts the *connection pool* before the heap, which is a
  slower and much more confusing outage.

**How you'd actually diagnose one:** it is not guesswork, and saying so matters.
`jcmd <pid> GC.heap_info` for the trend; `jmap -histo:live` for what dominates; a heap dump
(`jcmd <pid> GC.heap_dump`) into Eclipse MAT and look at the **dominator tree** — it answers "what
is *keeping* this alive", which is the actual question. Enable
`-XX:+HeapDumpOnOutOfMemoryError` in production before you need it.

---

## 6. Escape analysis, and why "objects are always on the heap" is wrong

The JIT proves whether a reference can escape its method. If it cannot, it may perform **scalar
replacement**: the object is never allocated at all, and its fields become registers/stack slots.
Lock elision follows the same analysis — a `synchronized` block on a provably thread-confined object
is removed.

The honest caveats, which are the interesting half: it's a C2 optimisation, so it needs the method
to be hot; it is defeated by things like an object escaping into a non-inlined call; and it is
*opportunistic* — you cannot rely on it, only benefit from it. It's a good answer to "how would you
reduce allocation?" precisely because the answer is often "write clear code and let the JIT do it,
then measure."

---

## 7. Value-type-ish things worth naming

`BigDecimal` (used throughout this ledger for correctness — binary floating point cannot represent
0.1, so money in `double` is a bug) is an object per value, with an internal `BigInteger` for large
unscaled values. It is the right call here: correctness beats allocation, and per §3 the allocation
is cheap. Knowing *why* it's acceptable is better than either using it blindly or avoiding it
prematurely.

---

## Interview drill

1. Reconciliation loads a million ledger rows and OOMs. Name four distinct contributors and the fix
   for each.
2. Why is a JPA projection cheaper than an entity, beyond "fewer fields"?
3. Why does the Postgres fetch-size hint matter even though the code already returns a `Stream`?
4. Why is allocating a million short-lived objects cheap, but caching a million long-lived ones
   expensive?
5. Your container has a 512 MB limit and `-Xmx512m`. What happens, and why?
6. What is a memory leak in a garbage-collected language? Give three concrete causes.
7. When would you choose G1 over ZGC?
8. What is escape analysis, and why can't you depend on it?
