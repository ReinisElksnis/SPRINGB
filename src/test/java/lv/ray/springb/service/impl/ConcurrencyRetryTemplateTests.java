package lv.ray.springb.service.impl;

import lv.ray.springb.config.OptimisticTransferProperties;
import lv.ray.springb.service.AccountException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zero backoff throughout - the delay is {@code RetryBackoff}'s and is tested there; what matters
 * here is which exceptions are retried, how many times, and what is thrown when the budget runs out.
 */
class ConcurrencyRetryTemplateTests
{

	private static ConcurrencyRetryTemplate template(final int maxAttempts)
	{
		return new ConcurrencyRetryTemplate(
				new OptimisticTransferProperties(maxAttempts, Duration.ZERO, Duration.ZERO));
	}

	@Test
	void anAttemptThatSucceedsFirstTimeReportsOneAttempt()
	{
		final ConcurrencyRetryTemplate.Outcome<String> outcome = template(5).execute(() -> "done");

		assertThat(outcome.value()).isEqualTo("done");
		assertThat(outcome.attempts()).isEqualTo(1);
	}

	@Test
	void anOptimisticConflictIsRetriedAndTheAttemptCountReflectsIt()
	{
		final AtomicInteger calls = new AtomicInteger();

		final ConcurrencyRetryTemplate.Outcome<String> outcome = template(5).execute(() ->
		{
			if (calls.incrementAndGet() < 3)
			{
				throw new OptimisticLockingFailureException("version mismatch");
			}
			return "done";
		});

		assertThat(outcome.value()).isEqualTo("done");
		assertThat(outcome.attempts()).isEqualTo(3);
		assertThat(calls.get()).isEqualTo(3);
	}

	/**
	 * A database deadlock is not an optimistic conflict, but it is the same instruction to the
	 * caller: you raced, you lost, try again. Removing read locks does not remove the write locks
	 * taken at flush, so this genuinely happens on the optimistic path.
	 */
	@Test
	void aDeadlockIsRetriedToo()
	{
		final AtomicInteger calls = new AtomicInteger();

		final ConcurrencyRetryTemplate.Outcome<String> outcome = template(3).execute(() ->
		{
			if (calls.incrementAndGet() < 2)
			{
				throw new CannotAcquireLockException("deadlock detected");
			}
			return "done";
		});

		assertThat(outcome.attempts()).isEqualTo(2);
	}

	/**
	 * The most important test here. A lost idempotency-key race means the work is <em>already
	 * done</em>; retrying it is precisely what the idempotency key exists to prevent, and the caller
	 * needs this exception to escape so it can return the winner's result instead. Spring's split
	 * between transient and non-transient failures is what makes one catch clause able to tell the
	 * difference.
	 */
	@Test
	void aDataIntegrityViolationIsNotRetriedAndPropagatesUnchanged()
	{
		final AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> template(5).execute(() ->
		{
			calls.incrementAndGet();
			throw new DataIntegrityViolationException("duplicate key");
		})).isInstanceOf(DataIntegrityViolationException.class);

		assertThat(calls.get()).isEqualTo(1);
	}

	/**
	 * Likewise a domain rejection. Insufficient funds is a fact about the request, not a race, and
	 * retrying it would turn a clean 409 into five pointless database round trips before the same
	 * answer.
	 */
	@Test
	void anOrdinaryDomainExceptionIsNotRetried()
	{
		final AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> template(5).execute(() ->
		{
			calls.incrementAndGet();
			throw new AccountException(HttpStatus.CONFLICT, "insufficient funds");
		})).isInstanceOf(AccountException.class);

		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void theBudgetIsSpentAndA409IsThrownWhenEveryAttemptLoses()
	{
		final AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> template(4).execute(() ->
		{
			calls.incrementAndGet();
			throw new OptimisticLockingFailureException("version mismatch");
		}))
				.isInstanceOf(AccountException.class)
				.hasMessageContaining("4 attempts")
				.satisfies(e -> assertThat(((AccountException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
				// The cause must survive, or a contention 409 is undiagnosable in the logs.
				.hasCauseInstanceOf(OptimisticLockingFailureException.class);

		assertThat(calls.get()).isEqualTo(4);
	}

	/**
	 * maxAttempts counts attempts, not retries after the first. One means "try once, never retry",
	 * which is what the integration tests use to expose a raw conflict.
	 */
	@Test
	void aBudgetOfOneDisablesRetryingEntirely()
	{
		final AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> template(1).execute(() ->
		{
			calls.incrementAndGet();
			throw new OptimisticLockingFailureException("version mismatch");
		})).isInstanceOf(AccountException.class);

		assertThat(calls.get()).isEqualTo(1);
	}
}
