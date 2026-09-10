package lv.ray.springb.service.impl;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
* The registry's claims, proven without a database - mutual exclusion is a property of the locks
* themselves, and testing it through Postgres would only add ways for the test to be right for the
* wrong reason.
*/
class AccountLockRegistryTests
{

	@Test
	void stripeCountMustBeAPositivePowerOfTwo()
	{
		assertThatThrownBy(() -> new AccountLockRegistry(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AccountLockRegistry(-8)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AccountLockRegistry(6)).isInstanceOf(IllegalArgumentException.class);

		assertThat(new AccountLockRegistry(64).stripeCount()).isEqualTo(64);
	}

	@Test
	void lockingReleasesEverythingItTook()
	{
		final AccountLockRegistry registry = new AccountLockRegistry(64);

		try (AccountLockRegistry.Held held = registry.lock(1L, 2L))
		{
			assertThat(registry.isHeldByCurrentThread(1L)).isTrue();
			assertThat(registry.isHeldByCurrentThread(2L)).isTrue();
		}

		assertThat(registry.isLocked(1L)).isFalse();
		assertThat(registry.isLocked(2L)).isFalse();
	}

	/**
	* Both accounts on one stripe must not deadlock against itself. With a single stripe every pair
	* collides, so this exercises the reentrant path deterministically rather than hoping for a hash
	* collision.
	*/
	@Test
	void twoAccountsSharingAStripeDoNotDeadlockAndAreFullyReleased()
	{
		final AccountLockRegistry registry = new AccountLockRegistry(1);

		try (AccountLockRegistry.Held held = registry.lock(7L, 99L))
		{
			assertThat(registry.isHeldByCurrentThread(7L)).isTrue();
		}

		// Both acquisitions must be undone - a single unlock would leave the stripe held forever,
		// and every later transfer in the process would hang on it.
		assertThat(registry.isLocked(7L)).isFalse();
	}

	/** The actual guarantee: one registry, one account, never two threads inside at once. */
	@Test
	void oneRegistryGivesRealMutualExclusion() throws Exception
	{
		assertThat(concurrentOverlaps(new AccountLockRegistry(64), new AccountLockRegistry(64), false))
				.as("threads sharing a registry must never overlap")
				.isZero();
	}

	/**
	* The point of the whole exercise. Two registries model two JVMs: the locks are different
	* objects, so both threads walk straight into the critical section together. Nothing about the
	* calling code changed - only how many copies of the process there are.
	*/
	@Test
	void twoRegistriesSimulatingTwoInstancesGiveNoExclusionAtAll() throws Exception
	{
		assertThat(concurrentOverlaps(new AccountLockRegistry(64), new AccountLockRegistry(64), true))
				.as("threads on separate registries must be able to overlap")
				.isPositive();
	}

	/**
	* Runs two threads through the same account's critical section and counts how often they were
	* inside simultaneously.
	*
	* @param separateRegistries when true the second thread uses its own registry, modelling a
	*                           second application instance
	*/
	private static int concurrentOverlaps(final AccountLockRegistry first, final AccountLockRegistry second,
			final boolean separateRegistries) throws Exception
	{
		final int rounds = 200;
		final AtomicInteger inside = new AtomicInteger();
		final AtomicInteger overlaps = new AtomicInteger();
		final CountDownLatch startTogether = new CountDownLatch(1);
		final ExecutorService pool = Executors.newFixedThreadPool(2);

		try
		{
			for (int thread = 0; thread < 2; thread++)
			{
				final AccountLockRegistry registry =
						(thread == 1 && separateRegistries) ? second : first;

				pool.submit(() ->
				{
					startTogether.await(10, TimeUnit.SECONDS);
					for (int round = 0; round < rounds; round++)
					{
						try (AccountLockRegistry.Held held = registry.lock(42L, 43L))
						{
							if (inside.incrementAndGet() > 1)
							{
								overlaps.incrementAndGet();
							}
							// Widen the window so a missing guarantee is observed rather than merely
							// possible; with real exclusion this cannot register an overlap however
							// long it is held.
							Thread.sleep(1);
							inside.decrementAndGet();
						}
					}
					return null;
				});
			}

			startTogether.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
		}
		finally
		{
			pool.shutdownNow();
		}

		return overlaps.get();
	}

	/**
	* Ordering must be over the stripes, not the account ids - the hash does not preserve order, so
	* "lowest id first" can acquire two stripes in opposite orders on two threads and deadlock. This
	* hammers many id pairs in both directions on a deliberately small stripe array, where
	* collisions are frequent; if the ordering rule were wrong it would hang rather than fail, so
	* the timeout is the assertion.
	*/
	@Test
	void concurrentTransfersInOppositeDirectionsNeverDeadlock() throws Exception
	{
		final AccountLockRegistry registry = new AccountLockRegistry(4);
		final ExecutorService pool = Executors.newFixedThreadPool(8);
		final AtomicBoolean failed = new AtomicBoolean();

		try
		{
			for (int thread = 0; thread < 8; thread++)
			{
				final boolean reversed = thread % 2 == 0;
				pool.submit(() ->
				{
					try
					{
						for (long a = 0; a < 40; a++)
						{
							final long b = a + 1;
							try (AccountLockRegistry.Held held =
									reversed ? registry.lock(b, a) : registry.lock(a, b))
							{
								Thread.onSpinWait();
							}
						}
					}
					catch (final RuntimeException e)
					{
						failed.set(true);
					}
					return null;
				});
			}

			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
					.as("a deadlock would show up here as a timeout")
					.isTrue();
		}
		finally
		{
			pool.shutdownNow();
		}

		assertThat(failed).isFalse();
	}

	@Test
	void everyAccountMapsIntoTheStripeArray()
	{
		final AccountLockRegistry registry = new AccountLockRegistry(8);
		final Set<Boolean> results = new HashSet<>();

		for (long id = -1000; id < 1000; id++)
		{
			// Would throw ArrayIndexOutOfBounds if the mask arithmetic let a negative hash through.
			results.add(registry.isLocked(id));
		}

		assertThat(results).containsExactly(false);
	}
}
