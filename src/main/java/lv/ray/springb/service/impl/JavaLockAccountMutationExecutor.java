package lv.ray.springb.service.impl;

import lv.ray.springb.entity.Operation;

import org.springframework.context.annotation.Lazy;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;


/**
* A third transfer strategy: mutual exclusion held in <b>Java</b>, with no database lock and no
* version check relied upon. It exists to be understood and then rejected, so the two ways it fails
* are built into it rather than described.
*
* <h2>It genuinely works — in exactly one JVM</h2>
* {@link #transferOnce} is correct on a single node. A {@link java.util.concurrent.locks.ReentrantLock}
* gives real mutual exclusion, and no two threads in this process can be inside the same account's
* transfer at once. If this application only ever ran as one instance, this would be a legitimate
* design and the cheapest of the three.
*
* <h2>Failure one: there is more than one JVM</h2>
* {@link AccountLockRegistry} is an object on a heap. A second instance of this application has its
* own, guarding nothing in common with the first, and two threads on two nodes walk into the same
* account's transfer simultaneously with both locks "held". The guarantee is not weakened by scaling
* out - it is <em>deleted</em>, silently, by a deployment change that touches none of this code.
* This repository ships a Dockerfile; horizontal scaling is not a hypothetical.
*
* <p>{@link #transferOnce(AccountLockRegistry, Long, Long, String, BigDecimal, String)} takes the
* registry as a parameter for exactly this reason: passing two different registries models two
* instances, and the tests use it to show the guarantee evaporating.
*
* <h2>Failure two: the lock is released before the commit</h2>
* Subtler, and it bites on a single node. See
* {@link #transferOnceLockingInsideTransaction} - the version that puts the lock <em>inside</em> the
* transaction, which is where almost everyone writes it first.
*
* <h2>Why the correct version has to inject itself</h2>
* {@link #transferOnce} must hold the lock across a transaction that it does not itself open, which
* means calling a {@code @Transactional} method on this same class. A plain {@code this.} call would
* never reach the proxy and the annotation would do nothing - the self-invocation trap that already
* cost this codebase a real bug in {@code LedgerReconciliationServiceImpl}. Injecting this bean into
* itself {@code @Lazy} (lazily, or the constructor would depend on the object it is constructing)
* yields the proxy, so the call goes through the transactional advice. It looks odd; it is the
* standard remedy, and needing it at all is a reminder that the annotation is a wrapper and not a
* language feature.
*/
@Component
public class JavaLockAccountMutationExecutor
{

	private final AccountLockRegistry lockRegistry;

	private final UnlockedTransferWriter transferWriter;

	private final JavaLockAccountMutationExecutor self;

	public JavaLockAccountMutationExecutor(final AccountLockRegistry lockRegistry,
			final UnlockedTransferWriter transferWriter,
			@Lazy final JavaLockAccountMutationExecutor self)
	{
		this.lockRegistry = lockRegistry;
		this.transferWriter = transferWriter;
		this.self = self;
	}

	/** The correct single-JVM version, using this instance's registry. */
	public Pair<Operation, Operation> transferOnce(final Long fromAccountId, final Long toAccountId,
			final String ownerUsername, final BigDecimal amount, final String idempotencyKey)
	{
		return transferOnce(lockRegistry, fromAccountId, toAccountId, ownerUsername, amount, idempotencyKey);
	}

	/**
	* The correct single-JVM version, against a caller-supplied registry so that a test can simulate
	* more than one instance of this application.
	*
	* <p><b>The lock is acquired before the transaction opens and released after it commits.</b>
	* That ordering is the entire correctness argument, and it is why the transactional work sits
	* behind {@link #self}: the {@code try}-with-resources block wraps the proxied call, so the
	* transaction begins and ends strictly inside the critical section. A competing thread cannot
	* observe the half-open state where the balance has been written but not yet committed, because
	* it cannot get in at all until the commit is finished.
	*/
	public Pair<Operation, Operation> transferOnce(final AccountLockRegistry registry,
			final Long fromAccountId, final Long toAccountId, final String ownerUsername,
			final BigDecimal amount, final String idempotencyKey)
	{
		try (AccountLockRegistry.Held held = registry.lock(fromAccountId, toAccountId))
		{
			return self.insideNewTransaction(fromAccountId, toAccountId, ownerUsername, amount, idempotencyKey);
		}
	}

	/**
	* The transaction boundary, called through the proxy by {@link #transferOnce}. Public only
	* because Spring's default proxying cannot advise a non-public method; it is not meant to be
	* called directly, since calling it without holding the lock is exactly the unguarded write this
	* class exists to prevent.
	*/
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Pair<Operation, Operation> insideNewTransaction(final Long fromAccountId, final Long toAccountId,
			final String ownerUsername, final BigDecimal amount, final String idempotencyKey)
	{
		return transferWriter.applyTransfer(fromAccountId, toAccountId, ownerUsername, amount, idempotencyKey);
	}

	/**
	* The version almost everyone writes first, and it is broken on a single node.
	*
	* <p>The lock is taken <em>inside</em> a transactional method, so it is released when this method
	* returns - and this method returns to the transaction interceptor, which has <b>not committed
	* yet</b>. Between the {@code unlock} and the commit there is a window in which another thread
	* acquires the lock, reads the account, and sees the balance as it was <em>before</em> this
	* transfer, because that transfer is still uncommitted. Both threads then compute from the same
	* starting balance. The lock was held the whole time each thread did its work, and it still lost
	* the update.
	*
	* <p>The general rule, worth stating in exactly these terms: <b>a lock must be at least as
	* long-lived as the transaction it protects.</b> Any lock released before commit protects the
	* computation and not the data. It is the same reason {@code OutboxRelay} keeps its transaction
	* open across the dispatch instead of committing first - a claim that ends before the work does
	* is not a claim.
	*
	* <p>Only the {@code @Version} column stops this corrupting the ledger here, by turning the lost
	* update into a detected conflict. That is a second line of defence doing the first line's job;
	* remove it and these become silent.
	*/
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Pair<Operation, Operation> transferOnceLockingInsideTransaction(final Long fromAccountId,
			final Long toAccountId, final String ownerUsername, final BigDecimal amount,
			final String idempotencyKey)
	{
		try (AccountLockRegistry.Held held = lockRegistry.lock(fromAccountId, toAccountId))
		{
			return transferWriter.applyTransfer(fromAccountId, toAccountId, ownerUsername, amount,
					idempotencyKey);
		}
	}
}
