package lv.ray.springb.service.impl;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;


/**
* In-JVM mutual exclusion per account, by lock striping.
*
* <h2>Why not a lock per account</h2>
* The obvious implementation is {@code ConcurrentHashMap<Long, ReentrantLock>} with
* {@code computeIfAbsent}. It gives perfect granularity and it is a memory leak: an entry is created
* for every account id ever touched and nothing ever removes it, so the map grows for the lifetime
* of the process. Removing entries safely is harder than it looks - you cannot evict a lock while
* somebody holds it, and check-then-remove is itself a race - which is why the usual answer is to
* stop trying and bound the space instead.
*
* <h2>What striping trades</h2>
* A fixed array of locks, indexed by the account id's hash. Memory is constant and known at startup,
* and no entry is ever created or destroyed. The cost is <b>false contention</b>: two unrelated
* accounts sharing a stripe serialize against each other for no reason. That is a throughput cost,
* never a correctness one - being over-serialized is safe, and it is why the stripe count is a
* tuning knob rather than a design decision.
*
* <h2>Why locks are ordered by stripe, not by account id</h2>
* A transfer needs two accounts, so it needs two locks, so the ordering rule from
* {@code AccountMutationExecutor} applies - but the naive translation of it is subtly wrong.
* Sorting by <em>account id</em> is not enough here, because the stripe is a hash of the id and the
* hash does not preserve order: accounts (1, 4) might map to stripes (1, 0) while accounts (0, 3)
* map to stripes (0, 3), and two threads following "lowest id first" would then acquire stripes in
* opposite orders and deadlock. <b>The ordering must be over the objects actually being locked</b>,
* so this sorts by stripe index. That distinction is invisible until the day two accounts collide
* in the hash.
*
* <p>Reentrancy covers the remaining case: when both accounts land on the same stripe, the second
* acquisition is by the thread that already holds it, and {@link ReentrantLock} permits that instead
* of deadlocking against itself. It is unlocked the matching number of times.
*
* <h2>Why {@link ReentrantLock} and not {@code synchronized}</h2>
* Two reasons. It can be acquired in one method and released in another - which this needs, because
* the lock must span a transaction that begins and ends outside the acquiring frame. And on Java 21+
* a virtual thread blocking inside {@code synchronized} could pin its carrier thread, while
* {@code ReentrantLock} never had that problem.
*
* <h2>The limitation that matters more than any of the above</h2>
* <b>This object is per JVM.</b> Two instances of this application have two of these registries
* guarding nothing in common, and the mutual exclusion is worth exactly nothing. See
* {@link JavaLockAccountMutationExecutor}.
*/
@Component
public class AccountLockRegistry
{

	/**
	* Power of two so the index can be a mask rather than a modulo, and comfortably larger than any
	* plausible thread count so genuine collisions stay rare.
	*/
	private static final int DEFAULT_STRIPES = 1024;

	private final ReentrantLock[] stripes;

	public AccountLockRegistry()
	{
		this(DEFAULT_STRIPES);
	}

	public AccountLockRegistry(final int stripeCount)
	{
		if (stripeCount <= 0 || Integer.bitCount(stripeCount) != 1)
		{
			throw new IllegalArgumentException("stripeCount must be a positive power of two");
		}

		// Pre-populated once, at construction. Nothing is ever added or removed afterwards, which is
		// the property that makes the memory bounded and the array safe to read without publication
		// concerns - it is final and fully initialised before this constructor returns.
		this.stripes = IntStream.range(0, stripeCount)
				.mapToObj(i -> new ReentrantLock())
				.toArray(ReentrantLock[]::new);
	}

	/**
	* Locks the stripes guarding both accounts, in a globally consistent order, and returns a handle
	* that releases them.
	*
	* <p>Returns {@link AutoCloseable} so callers use try-with-resources and cannot forget the
	* {@code finally}. A lock leaked by an early return or an exception is not a bug that shows up
	* in the test that caused it - it shows up later, as an unrelated request hanging forever.
	*/
	public Held lock(final Long firstAccountId, final Long secondAccountId)
	{
		final int a = indexFor(firstAccountId);
		final int b = indexFor(secondAccountId);

		// Ascending stripe order, always - see the class javadoc for why ordering by account id
		// instead would be wrong. Equal indices collapse to one acquisition plus one reentrant one.
		final int lower = Math.min(a, b);
		final int higher = Math.max(a, b);

		stripes[lower].lock();
		if (higher != lower)
		{
			stripes[higher].lock();
		}

		return () ->
		{
			// Reverse order on release. Not required for correctness - releasing cannot deadlock -
			// but it keeps the acquire/release pattern symmetrical and obvious.
			if (higher != lower)
			{
				stripes[higher].unlock();
			}
			stripes[lower].unlock();
		};
	}

	/**
	* {@code Long.hashCode} is just the value xor'd with its high bits, so consecutive identity ids
	* would land on consecutive stripes - fine here, but spread anyway so that a workload keyed to
	* round numbers cannot pile onto one stripe.
	*/
	private int indexFor(final Long accountId)
	{
		final int spread = accountId.hashCode() * 0x9E3779B1;
		return (spread ^ (spread >>> 16)) & (stripes.length - 1);
	}

	/** Visible for tests: how many accounts' locks this registry can hold independently. */
	public int stripeCount()
	{
		return stripes.length;
	}

	/** Visible for tests. */
	public boolean isHeldByCurrentThread(final Long accountId)
	{
		return stripes[indexFor(accountId)].isHeldByCurrentThread();
	}

	/** Visible for tests. */
	public boolean isLocked(final Long accountId)
	{
		return stripes[indexFor(accountId)].isLocked();
	}

	@FunctionalInterface
	public interface Held extends AutoCloseable
	{
		@Override
		void close();
	}
}
