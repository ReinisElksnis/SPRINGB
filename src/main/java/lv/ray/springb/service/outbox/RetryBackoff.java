package lv.ray.springb.service.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;


/**
 * Exponential backoff with full jitter. Pure function of (attempt number, config) plus a random
 * draw, so it is unit-testable without a clock, a broker or a database.
 *
 * <p><b>Why exponential:</b> a fixed retry delay assumes the failure is brief and independent. The
 * failures that actually matter are neither - a broker is down for minutes, and it is down for
 * every message at once. Backing off doubles the time between attempts so a long outage costs a
 * handful of probes rather than one per second per message, and the retry traffic does not become
 * a second outage on top of the first.
 *
 * <p><b>Why jitter, which is the part people leave out:</b> pure exponential backoff is
 * deterministic, so every client that failed at the same moment retries at the same moment. The
 * broker comes back, and the entire fleet's backlog hits it simultaneously - a thundering herd that
 * knocks it straight back down, whereupon everyone backs off in lockstep again and the system
 * oscillates instead of recovering. Randomising the delay decorrelates the retries and spreads that
 * load out.
 *
 * <p>"Full" jitter - a uniform draw over {@code [0, cap]} rather than {@code cap} plus a small
 * wobble - is the variant that spreads load best, at the cost of sometimes retrying sooner than the
 * nominal delay. That trade is right for a backlog drain, where an early retry is cheap and a
 * synchronised stampede is not.
 */
public final class RetryBackoff
{

	private RetryBackoff()
	{
	}

	/**
	 * @param attemptsSoFar attempts already made and failed; 0 means "about to make the first retry"
	 * @return how long to wait before the next attempt
	 */
	public static Duration nextDelay(final int attemptsSoFar, final Duration initial, final Duration max)
	{
		final long cap = exponentialCapMillis(attemptsSoFar, initial, max);
		// Upper bound is exclusive, hence +1: with cap = 0 this would otherwise throw rather than
		// return zero.
		return Duration.ofMillis(ThreadLocalRandom.current().nextLong(cap + 1));
	}

	/**
	 * The un-jittered ceiling for this attempt: {@code initial * 2^attemptsSoFar}, clamped to
	 * {@code max}. Split out from the random draw so tests can assert the growth curve
	 * deterministically.
	 *
	 * <p>Two distinct hazards are guarded here, both of which turn "wait longer" into "retry
	 * immediately" - the exact opposite of the intent, and silently:
	 *
	 * <ul>
	 * <li><b>Shift distance wraps.</b> In Java the right-hand operand of {@code <<} on a long is
	 * taken modulo 64, so {@code 1L << 64} is {@code 1L << 0} - i.e. 1. A message on its 64th
	 * attempt would compute the <em>shortest</em> possible delay rather than the longest.</li>
	 * <li><b>The value itself overflows.</b> Even at a legal shift distance, {@code initial * 2^n}
	 * runs past {@code Long.MAX_VALUE} quickly and wraps - possibly back to a small positive
	 * number, which a {@code grown < 0} check would not catch.</li>
	 * </ul>
	 *
	 * <p>Both are avoided by never performing the overflowing multiplication: instead of shifting
	 * {@code initial} up and checking whether it passed the cap, shift the <em>cap</em> down and
	 * ask whether {@code initial} already exceeds it. That comparison is exact and cannot overflow,
	 * because shifting right only ever makes a non-negative number smaller.
	 */
	public static long exponentialCapMillis(final int attemptsSoFar, final Duration initial, final Duration max)
	{
		final long maxMillis = max.toMillis();
		final long initialMillis = initial.toMillis();

		if (attemptsSoFar >= 62 || initialMillis <= 0)
		{
			return maxMillis;
		}

		if (initialMillis > (maxMillis >> attemptsSoFar))
		{
			return maxMillis;
		}

		return initialMillis << attemptsSoFar;
	}
}
