package lv.ray.springb.service;

import lv.ray.springb.TestcontainersConfiguration;
import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.OptimisticTransferResultDTO;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.repository.AppUserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

/**
 * Measures the pessimistic and optimistic transfer paths against identical concurrent load, to find
 * where one stops being the better choice. Not a test - it asserts almost nothing, it prints a
 * table. Run it deliberately:
 *
 * <pre>
 * ./gradlew integrationTest -Dspringb.benchmark=true --tests '*TransferStrategyBenchmark*'
 * </pre>
 *
 * <h2>The experiment</h2>
 * A fixed amount of work - {@value #TOTAL_TRANSFERS} transfers - is divided over a varying number of
 * threads, all drawing from <b>one shared source account</b>. Threads are the contention dial:
 * because the total is fixed, more threads does not mean more work, only more collision on the same
 * row. Each thread transfers into its own destination so the only contended row is the source, and
 * the two strategies are never measured against a different amount of work.
 *
 * <h2>Confounds that were deliberately removed</h2>
 * A benchmark is mostly a list of things you stopped measuring by accident:
 * <ul>
 * <li><b>SQL logging.</b> {@code spring.jpa.show-sql} and {@code format_sql} are on in
 *       {@code application.properties}. Left alone, this measures the cost of formatting and printing
 *       SQL to a console - which dwarfs the lock behaviour and would affect the two strategies
 *       unequally, since the optimistic one issues more statements when it retries.</li>
 * <li><b>The outbox relay.</b> Its scheduled poll would run concurrently, competing for connections
 *       at unpredictable moments. The outbox <em>writes</em> stay on, because both strategies do them
 *       and they are part of the work being compared.</li>
 * <li><b>The connection pool.</b> Raised well above the highest thread count. This one matters
 *       specifically for the pessimistic path: a thread blocked on a row lock is holding a connection
 *       while it waits, so with a default pool of 10 and 16 threads, some threads would be queueing
 *       for a connection rather than for the lock, and the measurement would silently become one of
 *       pool exhaustion. That is a real production failure mode - it just isn't this experiment.</li>
 * <li><b>The retry budget.</b> Raised to 50 so every transfer completes and throughput stays
 *       comparable. The number that would have failed under the production budget of 5 is reported
 *       separately, because "the optimistic path starts returning 409" is itself part of the crossover
 *       story and would otherwise be hidden as a missing row.</li>
 * <li><b>JIT warmup.</b> A warmup round runs before each measurement. Without it the first strategy
 *       measured pays for compiling code the second one then reuses, which alone can invert the
 *       result.</li>
 * </ul>
 *
 * <h2>What it cannot tell you</h2>
 * One machine, one Postgres container, one shaped workload, no network between app and database.
 * The <em>shape</em> of the curves is the transferable finding; the absolute numbers are not.
 */
@SpringBootTest(properties = {
		"spring.jpa.show-sql=false",
		"spring.jpa.properties.hibernate.format_sql=false",
		"springb.outbox.enabled=false",
		"spring.datasource.hikari.maximum-pool-size=40",
		"springb.optimistic-transfer.max-attempts=50" })
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "springb.benchmark", matches = "true",
		disabledReason = "Benchmark; run with -Dspringb.benchmark=true")
class TransferStrategyBenchmark
{

	private static final int TOTAL_TRANSFERS = 96;

	/** Divisors of TOTAL_TRANSFERS, so every level does exactly the same amount of work. */
	private static final int[] THREAD_LEVELS = { 1, 2, 4, 8, 16 };

	private static final BigDecimal AMOUNT = new BigDecimal("1.00");

	private static final BigDecimal SEED = new BigDecimal("500.00");

	/** The budget the application actually ships with - see {@code OptimisticTransferProperties}. */
	private static final int PRODUCTION_ATTEMPT_BUDGET = 5;

	/**
	 * Runs per cell, reported as medians. One run per cell produced throughput varying by about a
	 * third between neighbouring rows - enough that the 8-thread case appeared to contradict both the
	 * 4- and 16-thread cases either side of it. Wall-clock throughput on a shared machine is simply a
	 * noisy measure. The median of several runs is far less so, and the median is used rather than
	 * the mean because one scheduling hiccup should not move the number being compared.
	 */
	private static final int REPETITIONS = 5;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private enum Strategy
	{
		PESSIMISTIC, OPTIMISTIC
	}

	private record Sample(long latencyNanos, int attempts)
	{
	}

	private record Result(Strategy strategy, int threads, long elapsedMillis, double throughput,
			int totalAttempts, int wastedAttempts, int overProductionBudget, long meanMicros,
			long p95Micros)
	{
	}

	@Test
	void compareStrategiesAcrossContentionLevels() throws Exception
	{
		final List<Result> results = new ArrayList<>();

		globalWarmUp();

		for (final int threads : THREAD_LEVELS)
		{
			for (final Strategy strategy : Strategy.values())
			{
				warmUp(strategy);

				final List<Result> repetitions = new ArrayList<>();
				for (int repetition = 0; repetition < REPETITIONS; repetition++)
				{
					repetitions.add(measureOnce(strategy, threads));
				}
				results.add(median(strategy, threads, repetitions));
			}
		}

		report(results);
	}

	/** Per-field medians across one cell's repetitions. */
	private static Result median(final Strategy strategy, final int threads, final List<Result> reps)
	{
		return new Result(strategy, threads,
				medianOf(reps, Result::elapsedMillis),
				medianOf(reps, r -> Math.round(r.throughput() * 10)) / 10.0,
				(int) medianOf(reps, Result::totalAttempts),
				(int) medianOf(reps, Result::wastedAttempts),
				(int) medianOf(reps, Result::overProductionBudget),
				medianOf(reps, Result::meanMicros),
				medianOf(reps, Result::p95Micros));
	}

	private static long medianOf(final List<Result> reps, final ToLongFunction<Result> field)
	{
		final long[] values = reps.stream().mapToLong(field).sorted().toArray();
		return values[values.length / 2];
	}

	/**
	 * Unmeasured rounds before anything is timed.
	 *
	 * <p>This is sized from an observed failure rather than a guess. With only a short per-strategy
	 * warmup, the very first measured row - single-threaded, i.e. the <em>least</em> contended case -
	 * came out at 1179ms against roughly 500ms for the same strategy at every higher thread count.
	 * Less contention cannot be slower; the first measurement was simply paying for class loading,
	 * JIT compilation and connection-pool fill that every later row then inherited for free. Left
	 * uncorrected it would have produced a confident and completely inverted conclusion about the
	 * uncontended case.
	 *
	 * <p>Both strategies are warmed, and warmed under contention, so the branches that only execute
	 * when a conflict occurs are compiled too.
	 */
	private void globalWarmUp() throws Exception
	{
		for (int round = 0; round < 3; round++)
		{
			for (final Strategy strategy : Strategy.values())
			{
				run(strategy, 4, 48);
			}
		}
	}

	/** Keeps each measurement's immediately preceding state consistent across strategies. */
	private void warmUp(final Strategy strategy) throws Exception
	{
		run(strategy, 4, 16);
	}

	private Result measureOnce(final Strategy strategy, final int threads) throws Exception
	{
		final long startedAt = System.nanoTime();
		final Run run = run(strategy, threads, TOTAL_TRANSFERS);
		final long elapsedNanos = System.nanoTime() - startedAt;

		// The one hard assertion in this class. Every run at every contention level must leave the
		// source at exactly seed - (transfers x amount); anything else means a lost update slipped
		// through, and a throughput number measured on incorrect behaviour is worthless. Both
		// strategies are held to it, which is what makes comparing their speed meaningful at all.
		final BigDecimal expected = SEED.subtract(AMOUNT.multiply(BigDecimal.valueOf(TOTAL_TRANSFERS)));
		final BigDecimal actual = accountRepository.findById(run.sourceId()).orElseThrow().getBalance();
		if (actual.compareTo(expected) != 0)
		{
			throw new AssertionError(String.format("%s at %d threads lost money: expected %s, got %s",
					strategy, threads, expected, actual));
		}

		final long[] latencies = run.samples.stream().mapToLong(Sample::latencyNanos).sorted().toArray();
		final int totalAttempts = run.samples.stream().mapToInt(Sample::attempts).sum();
		final int overBudget = (int) run.samples.stream()
				.filter(sample -> sample.attempts() > PRODUCTION_ATTEMPT_BUDGET).count();

		return new Result(strategy, threads,
				TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
				TOTAL_TRANSFERS / (elapsedNanos / 1_000_000_000.0),
				totalAttempts,
				totalAttempts - TOTAL_TRANSFERS,
				overBudget,
				TimeUnit.NANOSECONDS.toMicros(Arrays.stream(latencies).sum() / latencies.length),
				TimeUnit.NANOSECONDS.toMicros(latencies[(int) (0.95 * (latencies.length - 1))]));
	}

	private record Run(List<Sample> samples, Long sourceId)
	{
	}

	private Run run(final Strategy strategy, final int threads, final int totalTransfers) throws Exception
	{
		final String suffix = UUID.randomUUID().toString().substring(0, 8);
		final AppUser owner = newOwner(suffix);

		// One hot source; a destination per thread so the source is the only contended row.
		final AccountDTO source = accountService.createAccount(owner.getUsername(), "EUR");
		accountService.deposit(source.getId(), owner.getUsername(), SEED, "seed-" + suffix);

		final List<AccountDTO> destinations = new ArrayList<>();
		for (int i = 0; i < threads; i++)
		{
			destinations.add(accountService.createAccount(owner.getUsername(), "EUR"));
		}

		final int perThread = totalTransfers / threads;
		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		final CountDownLatch startTogether = new CountDownLatch(1);
		final List<Sample> samples = new ArrayList<>();

		try
		{
			final List<Future<List<Sample>>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++)
			{
				final Long destinationId = destinations.get(i).getId();
				futures.add(pool.submit(() ->
				{
					startTogether.await(30, TimeUnit.SECONDS);

					final List<Sample> mine = new ArrayList<>();
					for (int t = 0; t < perThread; t++)
					{
						final long began = System.nanoTime();
						final int attempts = transfer(strategy, source.getId(), destinationId,
								owner.getUsername());
						mine.add(new Sample(System.nanoTime() - began, attempts));
					}
					return mine;
				}));
			}

			startTogether.countDown();
			for (final Future<List<Sample>> future : futures)
			{
				samples.addAll(future.get(5, TimeUnit.MINUTES));
			}
		}
		finally
		{
			pool.shutdownNow();
		}

		return new Run(samples, source.getId());
	}

	private int transfer(final Strategy strategy, final Long from, final Long to, final String owner)
	{
		final String key = "bench-" + UUID.randomUUID();

		if (strategy == Strategy.PESSIMISTIC)
		{
			accountService.transfer(from, to, owner, AMOUNT, key);
			// The pessimistic path has no notion of attempts - it waits rather than retrying - so it
			// is recorded as one by definition. That asymmetry is the finding, not a gap in the data.
			return 1;
		}

		final OptimisticTransferResultDTO result = accountService.transferOptimistic(from, to, owner, AMOUNT, key);
		return result.attempts();
	}

	private AppUser newOwner(final String suffix)
	{
		final AppUser owner = new AppUser("bench-" + suffix, "bench-" + suffix + "@example.com",
				passwordEncoder.encode("irrelevant-password"), "Benchmark");
		owner.setRole("ROLE_USER");
		return appUserRepository.save(owner);
	}

	private static void report(final List<Result> results)
	{
		final StringBuilder out = new StringBuilder();
		out.append("\n\n=== Transfer strategy under identical load ===\n");
		out.append(String.format("%d transfers total, split over N threads, all from one account.%n%n",
				TOTAL_TRANSFERS));
		out.append(String.format("%-8s %-12s %9s %10s %9s %9s %9s %8s%n",
				"threads", "strategy", "millis", "txn/sec", "mean us", "p95 us", "wasted", ">budget"));

		for (final Result r : results)
		{
			out.append(String.format("%-8d %-12s %9d %10.1f %9d %9d %9d %8d%n",
					r.threads(), r.strategy(), r.elapsedMillis(), r.throughput(), r.meanMicros(),
					r.p95Micros(), r.wastedAttempts(), r.overProductionBudget()));
		}

		out.append("\nwasted  = attempts that did the work and then threw it away (optimistic only)\n");
		out.append(">budget = transfers that would have failed with 409 under the shipped budget of ")
				.append(PRODUCTION_ATTEMPT_BUDGET).append("\n");
		out.append("medians of ").append(REPETITIONS)
				.append(" runs per cell; every run was checked to leave the source balance exact\n");

		// Printed rather than logged: the point of running this is to read the table.
		System.out.println(out);
	}
}
