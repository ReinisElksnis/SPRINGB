package lv.ray.springb.repository;

import lv.ray.springb.entity.OutboxMessage;
import lv.ray.springb.entity.OutboxStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;


@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long>
{

	/**
	 * Claims up to {@code batchSize} messages that are due now, taking a row lock on each one that
	 * is held until the caller's transaction ends.
	 *
	 * <p><b>{@code SKIP LOCKED} is the whole trick, and it is the opposite of what
	 * {@link AccountRepository#findByIdForUpdate} wants.</b> That query uses a plain
	 * {@code FOR UPDATE} because two concurrent withdrawals from one account must <em>serialize</em>
	 * - the second one is required to wait, re-read the balance the first one wrote, and only then
	 * decide whether there are still funds. Blocking is the correct behaviour there; it is what
	 * makes the overdraft check sound.
	 *
	 * <p>Here, waiting would be pure waste. Two relay instances polling the same backlog are not
	 * competing for one row, they are trying to <em>divide</em> the queue. Under plain
	 * {@code FOR UPDATE} the second instance would block on the first instance's rows and then wake
	 * up to find them all published - it would do nothing but wait, and adding instances would add
	 * no throughput at all. {@code SKIP LOCKED} tells Postgres to step over any row another
	 * transaction already holds and keep scanning, so each poller walks away with a disjoint batch
	 * and they scale horizontally with zero coordination between them - no leader election, no
	 * distributed lock, no shared queue service. The database's own row locks are the partitioning
	 * mechanism.
	 *
	 * <p>Native rather than JPQL because JPQL has no way to express {@code SKIP LOCKED}:
	 * {@code LockModeType.PESSIMISTIC_WRITE} maps to {@code FOR UPDATE} and Jakarta Persistence
	 * defines no skip-locked lock mode.
	 *
	 * <p>{@code LIMIT} must precede {@code FOR UPDATE} in Postgres, and the ordering by id is what
	 * keeps the queue roughly FIFO so an old message cannot be starved by newer arrivals.
	 */
	@Query(value = """
			SELECT * FROM outbox_messages
			WHERE status = 'PENDING' AND next_attempt_at <= :now
			ORDER BY id
			LIMIT :batchSize
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboxMessage> claimDueBatch(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

	long countByStatus(OutboxStatus status);

	List<OutboxMessage> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType, String aggregateId);
}
