# SPRINGB

A Spring Boot 4 demo application built around a small money-transfer domain: users register,
open currency accounts, and deposit, withdraw and transfer funds against an append-only
operation ledger that can be replayed and reconciled against the cached balance.

Alongside the accounts API there is a simpler customer/order CRUD area and a plain-HTML
front end served from `src/main/resources/static`, so the whole thing is browsable at
`http://localhost:8080/` without any front-end build step.

- **Java 25** (Gradle toolchain), **Spring Boot 4.1.1**, **Gradle** wrapper
- **PostgreSQL** with **Flyway** migrations; Hibernate runs in `validate` mode only
- **Spring Security** with session-cookie auth, sessions persisted via **Spring Session JDBC**
- **Testcontainers** for the integration test tier

## Requirements

- JDK 25 (or let the Gradle toolchain provision it)
- PostgreSQL reachable on `localhost:5432`
- Docker, only for the integration test tier (Testcontainers)

## Running locally

The app expects a database named `springb_db` owned by `myuser` / `secret`
(see `src/main/resources/application.properties`):

```sql
CREATE USER myuser WITH PASSWORD 'secret';
CREATE DATABASE springb_db OWNER myuser;
```

Then:

```bash
./gradlew bootRun
```

Flyway applies `src/main/resources/db/migration/V1..V3` on startup, so the schema is created
for you. Open `http://localhost:8080/`, register a user, and the account panel becomes live.

Optional demo rows for the customer/order endpoints:

```bash
psql -U myuser -d springb_db -f mock-data.sql
```

> `compose.yaml` is the stock Spring Initializr file and provisions `mydatabase`/`myuser` on a
> random host port — it does not line up with the datasource above as-is. Either point
> `spring.datasource.url` at it or create the database as shown.

### Docker

The `Dockerfile` is a two-stage build (Temurin 25 JDK to build the jar, JRE-only runtime, non-root
user). It builds with `bootJar` only — tests are expected to have run in CI:

```bash
docker build -t springb .
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/springb_db \
  springb
```

## Tests

Two tiers on separate source sets, so the fast one stays fast:

| Task | Source set | What it does |
| --- | --- | --- |
| `./gradlew test` | `src/test/java` | Plain JUnit 5 + Mockito, no Spring context, no database |
| `./gradlew integrationTest` | `src/integrationTest/java` | Real application context against a Testcontainers Postgres; web tests drive the real security filter chain through MockMvc |
| `./gradlew build` | both | Adds Checkstyle and the aggregated JaCoCo report |

`TransferStrategyBenchmark` also lives in the integration source set but is skipped unless asked
for — it measures rather than asserts, and a timing number is the wrong thing to gate CI on:

```bash
./gradlew integrationTest -Dspringb.benchmark=true --tests '*TransferStrategyBenchmark*'
```

It runs a fixed number of transfers over a rising number of threads contending on one account and
prints a table comparing all three locking strategies. On the machine this was developed on, the
optimistic/pessimistic crossover sits between one and two concurrent writers, and the fastest
strategy at every level is the in-JVM lock — which is also the one that must not be deployed. See
[the concurrency deep dive](docs/deep-dives/concurrency.md) for the full table and what it means.

Checkstyle is **report-only** (`ignoreFailures = true`) — the existing code predates the ruleset,
so it is a signal for new code rather than a gate. Reports land under `build/reports/`.

CI (`.github/workflows/ci.yml`) runs the unit tier first, then the full `build`, and uploads test,
Checkstyle and coverage reports as artifacts. CodeQL analysis runs on push, PR and weekly.

## API

Everything under `/api/accounts` and `/api/auth/members` requires an authenticated session;
everything else is public so the demo stays browsable signed out.

### Auth — `/api/auth`

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/register` | Creates the user and signs it straight in |
| `POST` | `/login` | Session id is rotated on sign-in |
| `POST` | `/logout` | Invalidates the session |
| `GET` | `/me` | Always 200; reports `authenticated` true/false |
| `GET` | `/members` | The one endpoint that answers 401 without a session |

Registration policy (username/password length, email pattern, default role) is configurable
under the `springb.auth.*` keys in `application.properties`.

### Accounts — `/api/accounts`

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/` | Open an account in a given ISO 4217 currency |
| `GET` | `/` | The caller's accounts |
| `GET` | `/{id}` | 404 whether the id is unknown or belongs to someone else |
| `GET` | `/{id}/operations` | The account's ledger |
| `GET` | `/{id}/reconcile` | Replays the ledger and reports discrepancies |
| `POST` | `/{id}/deposits` | Requires `Idempotency-Key` |
| `POST` | `/{id}/withdrawals` | Requires `Idempotency-Key` |
| `POST` | `/transfers` | Requires `Idempotency-Key`; pessimistic row locks |
| `POST` | `/transfers/optimistic` | Same contract, optimistic locking + bounded retry; reports `attempts` |

There is no "act on behalf of another user" path — an account id in a URL can only ever resolve
to one of the caller's own accounts.

### Demo endpoints

`GET /api/hello`, and CRUD on `/api/customers` and `/api/orders`.

## Design notes worth knowing

- **CSRF is on.** Session-cookie auth is what makes the app CSRF-vulnerable, so
  `CookieCsrfTokenRepository` issues an `XSRF-TOKEN` cookie that the static pages echo back as a
  header on every mutating request.
- **Money writes are idempotent.** Deposits, withdrawals and transfers take an `Idempotency-Key`
  header. The stored record keeps both the owner and a fingerprint of the request shape (type,
  account(s), amount), so reusing one key for a *different* request is rejected rather than
  silently returning the first result. Rows predating the fingerprint column skip that check.
- **Concurrent writes take a pessimistic row lock** (`AccountRepository`, `PESSIMISTIC_WRITE`);
  `AccountServiceConcurrencyTests` covers it. Transfers always lock in ascending account-id order,
  so two transfers running in opposite directions between the same pair cannot deadlock.
- **Two transfer implementations, on purpose.** `/transfers` takes pessimistic row locks;
  `/transfers/optimistic` takes none and relies on an `@Version` counter on `accounts` (V5) to make
  a write built on a stale read match zero rows, with `ConcurrencyRetryTemplate` retrying the whole
  transaction on a fresh read. The optimistic response reports how many `attempts` it took, which is
  the signal for deciding which strategy suits a given account: consistently 1 means optimistic is
  winning, consistently high means a pessimistic lock would do less total work. Both paths share the
  same validation, ledger and idempotency contract, so they are directly comparable.
- **A third, deliberately unusable strategy.** `JavaLockAccountMutationExecutor` holds a striped
  in-JVM `ReentrantLock` per account instead of any database lock. It is correct on one instance,
  it is the fastest of the three in the benchmark, and it has **no HTTP endpoint on purpose**: its
  guarantee is a heap object, so a second instance of this application silently removes it. It
  exists to be measured and rejected, and its tests demonstrate both failure modes — two registries
  (i.e. two JVMs), and a lock released before commit rather than after.
- **Events go through a transactional outbox.** Every ledger row also writes an `outbox_messages`
  row *in the same transaction* (`OutboxAppender`, which deliberately has no `@Transactional` of
  its own so it joins the caller), so an event and the money it describes commit together or not
  at all — no dual write to a broker. `OutboxRelay` polls with `SELECT … FOR UPDATE SKIP LOCKED`,
  which lets several instances divide the backlog with no leader election, and retries with
  exponential backoff plus full jitter before parking a message as `DEAD`. Delivery is
  at-least-once by design; consumers must be idempotent. Tuning lives under `springb.outbox.*`.
- **Ledger replay streams.** Reconciliation reads a projection through a cursor
  (`OperationRepository.streamLedgerEntries`) rather than materialising every `Operation` entity,
  and runs one transaction per account (`AccountLedgerReplayer`), so peak heap is bounded by the
  fetch size instead of by the length of the ledger.
- **Amount scale follows ISO 4217 per currency**, not a blanket two decimals — JPY allows none,
  KWD/BHD/OMR/JOD/TND allow three. Money columns are `NUMERIC(19,3)` (widened in `V2`) so a
  three-decimal amount is not silently rounded on write.
- **Flyway owns the schema, including Spring Session's tables** (`spring.session.jdbc.initialize-schema=never`),
  so there is a single migration history. `baseline-on-migrate` exists for environments that
  predate Flyway and already have the V1 schema; a fresh database is unaffected.
- **Schema changes go in a new migration**, never in `ddl-auto`. If startup fails validation,
  that is the fix.

## Layout

```
src/main/java/lv/ray/springb/
  config/      security filter chain, CSRF plumbing, AuthProperties
  constants/   ApiConstants — paths, JSON keys, user-facing text
  controller/  Auth, Account, Customer, Order, Demo
  dto/         request/response records
  entity/      JPA entities (AppUser, Account, Operation, Customer, Order, IdempotencyRecord,
               OutboxMessage)
  repository/  Spring Data repositories
  service/     interfaces + impl/ + validation/ + outbox/
src/main/resources/
  db/migration/  Flyway migrations
  static/        index / login / register pages
  messages*.properties  i18n for CustomerType display names (en, lv)
```

## Deep dives

`docs/deep-dives/` explains the reasoning behind the mechanisms above — why the row lock is
pessimistic rather than optimistic, why `SKIP LOCKED` is right for the outbox and wrong for an
account, why the same `REQUIRES_NEW` annotation is used for rollback semantics in one place and for
bounding heap in another, and what each of them fails like when removed. Written to be read with the
code open: [concurrency](docs/deep-dives/concurrency.md),
[memory management](docs/deep-dives/memory-management.md),
[distributed systems](docs/deep-dives/distributed-systems.md).
