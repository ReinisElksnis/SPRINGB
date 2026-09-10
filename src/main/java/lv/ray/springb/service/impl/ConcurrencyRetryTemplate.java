package lv.ray.springb.service.impl;

import lv.ray.springb.config.OptimisticTransferProperties;
import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.service.AccountException;
import lv.ray.springb.service.outbox.RetryBackoff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;


/**
 * Runs an attempt, and runs it again if it lost a race.
 *
 * <p><b>Why this cannot be inside the transaction.</b> An optimistic conflict is only discovered
 * when the UPDATE is sent - at flush - and by then the transaction is doomed and marked
 * rollback-only. Retrying within it would reuse a persistence context full of stale entities whose
 * versions are already known to be wrong, and every subsequent attempt would fail identically. The
 * retry must therefore sit <em>outside</em> the transaction boundary, so each attempt gets a fresh
 * transaction, a fresh persistence context, and - the entire point - a fresh read of the row it
 * lost on. That is why {@code OptimisticAccountMutationExecutor} is {@code REQUIRES_NEW} and why
 * this class has no transactional annotation at all.
 *
 * <p><b>Why it catches {@link ConcurrencyFailureException} and not just the optimistic one.</b>
 * That superclass covers three failures that are all "you raced and lost, try again":
 * {@code ObjectOptimisticLockingFailureException} (a version check matched no rows),
 * {@code CannotAcquireLockException} (a database deadlock - still possible here, because removing
 * read locks does not remove the write locks taken at flush), and serialization failures under
 * stricter isolation. Catching the common supertype handles all three with one policy.
 *
 * <p>Crucially it does <em>not</em> catch {@code DataIntegrityViolationException}, which sits on the
 * non-transient branch of Spring's exception hierarchy. That is what a lost idempotency-key race
 * throws, and it must pass straight through to {@code AccountServiceImpl}, which turns it into
 * "return the winner's result". Retrying it would be wrong - the work is already done, and doing it
 * again is exactly what the idempotency key exists to prevent. The hierarchy is doing real work
 * here: transient means retry, non-transient means do not.
 */
@Component
public class ConcurrencyRetryTemplate
{

	private static final Logger LOG = LoggerFactory.getLogger(ConcurrencyRetryTemplate.class);

	private final OptimisticTransferProperties properties;

	public ConcurrencyRetryTemplate(final OptimisticTransferProperties properties)
	{
		this.properties = properties;
	}

	/**
	 * @param attempt the unit of work; must be safe to run more than once, since a failed attempt
	 *                  has rolled back entirely and a retry re-reads everything from scratch
	 * @return the value the winning attempt produced, and how many attempts that took
	 */
	public <T> Outcome<T> execute(final Supplier<T> attempt)
	{
		ConcurrencyFailureException lastFailure = null;

		for (int attemptNumber = 1; attemptNumber <= properties.maxAttempts(); attemptNumber++)
		{
			try
			{
				return new Outcome<>(attempt.get(), attemptNumber);
			}
			catch (final ConcurrencyFailureException lostTheRace)
			{
				lastFailure = lostTheRace;
				LOG.debug("Attempt {}/{} lost a concurrency race: {}", attemptNumber,
						properties.maxAttempts(), lostTheRace.getMessage());

				if (attemptNumber < properties.maxAttempts())
				{
					// attemptNumber - 1 because RetryBackoff counts failures already made, and the
					// first retry should wait one initialBackoff rather than two.
					backOff(RetryBackoff.nextDelay(attemptNumber - 1, properties.initialBackoff(),
							properties.maxBackoff()));
				}
			}
		}

		// A budget is what stops a permanently contended row from retrying forever and holding a
		// request thread while it does - the same reasoning as the outbox's DEAD state.
		throw new AccountException(HttpStatus.CONFLICT,
				String.format(Messages.TRANSFER_CONTENTION_EXHAUSTED, properties.maxAttempts()),
				lastFailure);
	}

	/**
	 * Jittered, for the same reason the outbox jitters: without it, two transactions that collided
	 * would back off by an identical amount and collide again on the retry, in lockstep. Randomised
	 * delays decorrelate them so one wins immediately instead of both losing repeatedly.
	 */
	private static void backOff(final Duration delay)
	{
		if (delay.isZero() || delay.isNegative())
		{
			return;
		}

		try
		{
			Thread.sleep(delay);
		}
		catch (final InterruptedException e)
		{
			// Restore the flag rather than swallowing it: this runs on a request thread, and a
			// shutdown that cannot interrupt its workers waits out the full timeout instead.
			Thread.currentThread().interrupt();
			throw new AccountException(HttpStatus.SERVICE_UNAVAILABLE,
					"Transfer was interrupted while waiting to retry", e);
		}
	}

	/**
	 * A result plus the number of attempts it took. The count exists so the endpoint can report
	 * contention rather than hiding it - see {@code OptimisticTransferResultDTO}.
	 */
	public record Outcome<T>(T value, int attempts)
	{
	}
}
