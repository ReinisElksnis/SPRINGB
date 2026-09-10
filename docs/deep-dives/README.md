# Deep dives

Written to go with the code in this repository, not instead of it. Every section anchors on a file
and line you can open — the aim is to be able to explain *why* a mechanism is there and what breaks
without it, rather than to recognise its name.

| | Anchored on |
|---|---|
| [Concurrency](concurrency.md) | `AccountMutationExecutor`, `AccountRepository.findByIdForUpdate`, `OutboxMessageRepository.claimDueBatch`, `OutboxRelay` |
| [Memory management](memory-management.md) | `AccountLedgerReplayer`, `OperationRepository.streamLedgerEntries`, `LedgerEntry` |
| [Distributed systems](distributed-systems.md) | `OutboxAppender`, `RetryBackoff`, `outbox_messages` (V4), `idempotency_records` |

Each ends with a drill — questions to answer out loud before re-reading the section.

## The three threads that run through all of them

Worth noticing, because interviews reward connecting things rather than reciting them:

**The same bug appears at three layers.** A read-modify-write race is a lost update on an account
balance (`FOR UPDATE`), *and* it is `count++` on a `volatile` field, *and* it is a dual write to a
database and a broker. One shape, three altitudes.

**Unbounded is the recurring failure.** An unbounded result set OOMs reconciliation; an unbounded
thread-pool queue OOMs a service; an unbounded retry budget starves a queue; `fixedRate` scheduling
piles up overdue runs. Every fix in this repo is "put a bound on it" — batch size, fetch size,
attempt budget, `fixedDelay`.

**Transaction boundaries are simultaneously a correctness tool and a memory tool.** `REQUIRES_NEW`
on `AccountMutationExecutor` exists for rollback semantics; the same annotation on
`AccountLedgerReplayer` exists to bound heap. And the *absence* of an annotation on `OutboxAppender`
is what makes the outbox atomic. Three different reasons, one mechanism.
