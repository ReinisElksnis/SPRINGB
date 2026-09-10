package lv.ray.springb.service.impl;

import lv.ray.springb.entity.Operation;

import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;


/**
 * The optimistic counterpart to {@link AccountMutationExecutor#transferOnce}. Same money, same
 * ledger, same idempotency contract - the only difference is how it defends against a lost update,
 * and that difference is the whole reason this class exists.
 *
 * <h2>What it does differently, in one line</h2>
 * It never takes a lock. It reads both accounts with a plain {@code findById}, and relies on
 * {@link Account#getVersion()} to make the write fail if anything changed underneath it.
 *
 * <h2>The consequence people miss</h2>
 * Every validation in this method is performed against a <b>possibly stale read</b>.
 * {@code validateSufficientFunds} can pass on a balance that another transaction has already spent.
 * That is not a bug being tolerated - it is how the strategy works. Correctness does not come from
 * the check; it comes from the version predicate on the UPDATE, which matches zero rows if the
 * balance moved since the read, aborting this attempt before it can commit anything. The retry then
 * re-reads and re-validates against the new state, and it is *that* pass which decides the outcome.
 *
 * <p>This is exactly what a pessimistic lock buys you up front: {@code AccountMutationExecutor}
 * validates against data nobody can change, because it locked the rows first. Here the check is
 * provisional and the commit is authoritative. Both are correct; they pay for it at different times.
 *
 * <h2>Why the accounts are still loaded in id order</h2>
 * Not for read locks - there are none. For <em>flush</em> order. Hibernate queues its UPDATEs and
 * sends them at flush, and those UPDATEs take write locks like any other. Two transfers running in
 * opposite directions between the same pair can therefore still deadlock at commit time, just in a
 * much narrower window than the pessimistic version would. Loading in a consistent order pushes the
 * updates into a consistent order and makes that rare. It does not make it impossible - Hibernate's
 * flush ordering is not a contract - which is why {@link ConcurrencyRetryTemplate} treats a deadlock
 * as retryable rather than assuming it away. <b>Optimistic locking does not remove deadlock; it
 * moves it from read time to flush time.</b>
 *
 * <h2>Why it flushes explicitly</h2>
 * See {@link #transferOnce}. Without the flush, the version conflict surfaces during commit, as the
 * transaction interceptor unwinds - outside this method, where Spring's persistence-exception
 * translation is not applied and a raw {@code jakarta.persistence.OptimisticLockException} can
 * escape instead of the {@code ObjectOptimisticLockingFailureException} the retry policy is written
 * against.
 */
@Component
public class OptimisticAccountMutationExecutor
{

	private final UnlockedTransferWriter transferWriter;

	public OptimisticAccountMutationExecutor(final UnlockedTransferWriter transferWriter)
	{
		this.transferWriter = transferWriter;
	}

	/**
	 * One attempt, in its own transaction. {@code REQUIRES_NEW} for the same reason
	 * {@code AccountMutationExecutor} uses it, plus one specific to this path: a retry is only
	 * meaningful if the previous attempt's transaction is completely finished and its persistence
	 * context discarded, so the next read genuinely goes back to the database instead of returning
	 * the same stale, already-doomed entity from a first-level cache.
	 *
	 * <p>The work itself is {@link UnlockedTransferWriter}'s, shared with the in-JVM locking
	 * strategy so the two differ only in their guarantee. Everything this class contributes is the
	 * transaction boundary and the absence of a lock inside it.
	 *
	 * @throws org.springframework.dao.OptimisticLockingFailureException if either account was
	 *           modified between this attempt's read and its flush - the caller is expected to retry
	 * @throws org.springframework.dao.DataIntegrityViolationException if the idempotency key was
	 *           claimed by a concurrent request - the caller must <em>not</em> retry, it must return
	 *           the winner's result
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Pair<Operation, Operation> transferOnce(final Long fromAccountId, final Long toAccountId,
			final String ownerUsername, final BigDecimal amount, final String idempotencyKey)
	{
		return transferWriter.applyTransfer(fromAccountId, toAccountId, ownerUsername, amount, idempotencyKey);
	}
}
