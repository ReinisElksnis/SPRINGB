package lv.ray.springb.service.outbox;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The growth curve is asserted through {@link RetryBackoff#exponentialCapMillis}, which is
 * deterministic; the jitter is asserted as a range rather than a value, because a test that pinned
 * a random draw to an exact number would only be testing the seed.
 */
class RetryBackoffTests
{

	private static final Duration INITIAL = Duration.ofSeconds(1);

	private static final Duration MAX = Duration.ofMinutes(5);

	@Test
	void capDoublesWithEachFailedAttempt()
	{
		assertThat(RetryBackoff.exponentialCapMillis(0, INITIAL, MAX)).isEqualTo(1_000L);
		assertThat(RetryBackoff.exponentialCapMillis(1, INITIAL, MAX)).isEqualTo(2_000L);
		assertThat(RetryBackoff.exponentialCapMillis(2, INITIAL, MAX)).isEqualTo(4_000L);
		assertThat(RetryBackoff.exponentialCapMillis(3, INITIAL, MAX)).isEqualTo(8_000L);
	}

	@Test
	void capIsClampedToTheConfiguredMaximum()
	{
		// 2^9 seconds is 512s, past the 300s ceiling.
		assertThat(RetryBackoff.exponentialCapMillis(9, INITIAL, MAX)).isEqualTo(MAX.toMillis());
		assertThat(RetryBackoff.exponentialCapMillis(30, INITIAL, MAX)).isEqualTo(MAX.toMillis());
	}

	/**
	 * The regression this guards is subtle and silent: in Java the shift distance of {@code <<} on
	 * a long is taken modulo 64, so a naive {@code initial << attempts} at attempt 64 computes
	 * {@code initial << 0} - the shortest delay rather than the longest. A message that had been
	 * failing for hours would suddenly start hammering the broker once per second.
	 */
	@Test
	void absurdlyHighAttemptCountsStayClampedRatherThanWrappingToATinyDelay()
	{
		for (final int attempts : new int[] { 61, 62, 63, 64, 65, 128, Integer.MAX_VALUE })
		{
			assertThat(RetryBackoff.exponentialCapMillis(attempts, INITIAL, MAX))
					.as("attempt %d must stay clamped", attempts)
					.isEqualTo(MAX.toMillis());
		}
	}

	/**
	 * The same overflow reachable from the other direction - a large initial delay rather than a
	 * large attempt count. The multiplication must never be performed at all.
	 */
	@Test
	void aLargeInitialDelayDoesNotOverflowIntoASmallOne()
	{
		final Duration hugeInitial = Duration.ofDays(365);
		final Duration hugeMax = Duration.ofDays(3650);

		for (int attempts = 0; attempts < 61; attempts++)
		{
			assertThat(RetryBackoff.exponentialCapMillis(attempts, hugeInitial, hugeMax))
					.as("attempt %d must never fall below the initial delay", attempts)
					.isGreaterThanOrEqualTo(hugeInitial.toMillis());
		}
	}

	@RepeatedTest(50)
	void jitterStaysWithinZeroAndTheCap()
	{
		final long cap = RetryBackoff.exponentialCapMillis(3, INITIAL, MAX);
		final Duration delay = RetryBackoff.nextDelay(3, INITIAL, MAX);

		assertThat(delay.toMillis()).isBetween(0L, cap);
	}

	/**
	 * Full jitter must actually be random. If this ever produced one repeated value, every instance
	 * in a fleet would retry in lockstep and the thundering herd the jitter exists to prevent would
	 * be back - while every other test here still passed.
	 */
	@Test
	void jitterProducesASpreadOfValuesRatherThanAConstant()
	{
		final long distinct = java.util.stream.IntStream.range(0, 200)
				.mapToObj(i -> RetryBackoff.nextDelay(5, INITIAL, MAX))
				.map(Duration::toMillis)
				.distinct()
				.count();

		assertThat(distinct).isGreaterThan(50L);
	}

	@Test
	void aZeroInitialDelayDegradesToTheMaximumRatherThanDividingByZero()
	{
		assertThat(RetryBackoff.exponentialCapMillis(3, Duration.ZERO, MAX)).isEqualTo(MAX.toMillis());
	}
}
