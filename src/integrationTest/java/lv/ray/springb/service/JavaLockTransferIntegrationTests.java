package lv.ray.springb.service;

import lv.ray.springb.TestcontainersConfiguration;
import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.service.impl.AccountLockRegistry;
import lv.ray.springb.service.impl.JavaLockAccountMutationExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
* Locking held in Java rather than in the database, and the two ways it fails.
*
* <h2>How the failures are detected</h2>
* Every account carries a {@code @Version} column, so a lost update does not corrupt anything here -
* it surfaces as an {@code ObjectOptimisticLockingFailureException} instead. That makes the version
* check a convenient <b>instrument</b>: a conflict is proof that two writers were inside the same
* account's critical section at once, which is exactly the property the Java lock claims to prevent.
* Zero conflicts means the lock worked; any conflict means it did not.
*
* <p>Worth being explicit that this instrument is also a safety net that a real Java-locking design
* would probably not have. Take the version column away and these tests would not report conflicts -
* they would report the wrong balance, silently.
*
* <h2>Why the assertions are not flaky</h2>
* The strong direction is asserted as <b>zero</b>. If mutual exclusion holds, a conflict is not
* unlikely, it is impossible - so {@code assertThat(conflicts).isZero()} can only fail if the
* guarantee is genuinely broken. The failing cases run enough concurrent work that the windows
* involved (an entire commit) are hit many times over.
*/
@SpringBootTest(properties = { "spring.jpa.show-sql=false", "springb.outbox.enabled=false" })
@Import(TestcontainersConfiguration.class)
class JavaLockTransferIntegrationTests
{

	private static final BigDecimal SEED = new BigDecimal("500.00");

	private static final BigDecimal AMOUNT = new BigDecimal("1.00");

	private static final int THREADS = 8;

	private static final int TRANSFERS_PER_THREAD = 8;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JavaLockAccountMutationExecutor javaLockExecutor;

	private AppUser owner;

	private AccountDTO source;

	private List<AccountDTO> destinations;

	@BeforeEach
	void setUp()
	{
		final String suffix = UUID.randomUUID().toString().substring(0, 8);
		final AppUser newOwner = new AppUser("javalock-" + suffix, "javalock-" + suffix + "@example.com",
				passwordEncoder.encode("irrelevant-password"), "Java Lock Test");
		newOwner.setRole("ROLE_USER");
		owner = appUserRepository.save(newOwner);

		source = accountService.createAccount(owner.getUsername(), "EUR");
		accountService.deposit(source.getId(), owner.getUsername(), SEED, "seed-" + suffix);

		destinations = new ArrayList<>();
		for (int i = 0; i < THREADS; i++)
		{
			destinations.add(accountService.createAccount(owner.getUsername(), "EUR"));
		}
	}

	/**
	* One JVM, one registry: the lock does its job completely. Zero conflicts under heavy contention
	* on a single row, which is the same result the pessimistic database lock produces - and for the
	* same reason, since a correct mutual exclusion is a correct mutual exclusion wherever it lives.
	*/
	@Test
	void withinOneJvmTheJavaLockPreventsEveryConflict() throws Exception
	{
		final AccountLockRegistry registry = new AccountLockRegistry(64);

		final Outcome outcome = runConcurrently((threadIndex, destinationId, key) ->
				javaLockExecutor.transferOnce(registry, source.getId(), destinationId,
						owner.getUsername(), AMOUNT, key));

		assertThat(outcome.conflicts()).isZero();
		assertThat(outcome.successes()).isEqualTo(THREADS * TRANSFERS_PER_THREAD);
		assertThat(balanceOf(source.getId()))
				.isEqualByComparingTo(SEED.subtract(AMOUNT.multiply(BigDecimal.valueOf(outcome.successes()))));
	}

	/**
	* The headline failure. Half the threads use a second registry, modelling a second instance of
	* this application. Not one line of the transfer changed - the only difference is how many copies
	* of the process exist - and the guarantee is gone.
	*
	* <p>This is why "just use a lock" is the wrong answer for anything that scales horizontally,
	* and why the fix is not a better Java lock but a lock somewhere both instances can see: the
	* database row lock the pessimistic path already uses.
	*/
	@Test
	void acrossTwoInstancesTheJavaLockStopsProtectingAnything() throws Exception
	{
		final AccountLockRegistry instanceOne = new AccountLockRegistry(64);
		final AccountLockRegistry instanceTwo = new AccountLockRegistry(64);

		final Outcome outcome = runConcurrently((threadIndex, destinationId, key) ->
				javaLockExecutor.transferOnce(threadIndex % 2 == 0 ? instanceOne : instanceTwo,
						source.getId(), destinationId, owner.getUsername(), AMOUNT, key));

		assertThat(outcome.conflicts())
				.as("two registries cannot exclude each other, so writers must have collided")
				.isPositive();

		// Nothing was corrupted - but only because the database was still checking. The lock
		// contributed nothing here; the version column did all of the work.
		assertThat(balanceOf(source.getId()))
				.isEqualByComparingTo(SEED.subtract(AMOUNT.multiply(BigDecimal.valueOf(outcome.successes()))));
	}

	/**
	* The single-node failure, and the subtler of the two. The lock is taken inside the transactional
	* method, so it is released when that method returns - which is before the interceptor commits.
	* Another thread acquires the lock in that window, reads the not-yet-committed balance, and
	* computes from a stale value despite having held the lock for the whole of its own work.
	*
	* <p>One registry, one JVM, a correct lock, correctly ordered, never leaked - and still wrong,
	* because it was released too early. <b>A lock must outlive the transaction it protects.</b>
	*/
	@Test
	void lockingInsideTheTransactionReleasesItBeforeCommitAndLosesUpdates() throws Exception
	{
		final Outcome outcome = runConcurrently((threadIndex, destinationId, key) ->
				javaLockExecutor.transferOnceLockingInsideTransaction(source.getId(), destinationId,
						owner.getUsername(), AMOUNT, key));

		assertThat(outcome.conflicts())
				.as("a lock released before commit leaves a window for a stale read")
				.isPositive();

		assertThat(balanceOf(source.getId()))
				.isEqualByComparingTo(SEED.subtract(AMOUNT.multiply(BigDecimal.valueOf(outcome.successes()))));
	}

	// ---------------------------------------------------------------------------------------

	@FunctionalInterface
	private interface TransferAttempt
	{
		void run(int threadIndex, Long destinationId, String idempotencyKey);
	}

	private record Outcome(int successes, int conflicts)
	{
	}

	private Outcome runConcurrently(final TransferAttempt attempt) throws Exception
	{
		final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		final CountDownLatch startTogether = new CountDownLatch(1);
		final AtomicInteger successes = new AtomicInteger();
		final AtomicInteger conflicts = new AtomicInteger();

		try
		{
			final List<Future<?>> futures = new ArrayList<>();
			for (int thread = 0; thread < THREADS; thread++)
			{
				final int threadIndex = thread;
				final Long destinationId = destinations.get(thread).getId();

				futures.add(pool.submit(() ->
				{
					startTogether.await(30, TimeUnit.SECONDS);
					for (int i = 0; i < TRANSFERS_PER_THREAD; i++)
					{
						try
						{
							attempt.run(threadIndex, destinationId, "javalock-" + UUID.randomUUID());
							successes.incrementAndGet();
						}
						catch (final ConcurrencyFailureException collided)
						{
							// Deliberately not retried: the count of these IS the measurement.
							conflicts.incrementAndGet();
						}
					}
					return null;
				}));
			}

			startTogether.countDown();
			for (final Future<?> future : futures)
			{
				future.get(2, TimeUnit.MINUTES);
			}
		}
		finally
		{
			pool.shutdownNow();
		}

		return new Outcome(successes.get(), conflicts.get());
	}

	private BigDecimal balanceOf(final Long accountId)
	{
		return accountRepository.findById(accountId).orElseThrow().getBalance();
	}
}
