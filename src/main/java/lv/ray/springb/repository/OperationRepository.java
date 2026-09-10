package lv.ray.springb.repository;

import jakarta.persistence.QueryHint;
import lv.ray.springb.dto.LedgerEntry;
import lv.ray.springb.entity.Operation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;


@Repository
public interface OperationRepository extends JpaRepository<Operation, Long>
{
	List<Operation> findByAccountIdOrderByCreatedAtDesc(Long accountId);

	/**
	 * Chronological ledger replay feed - see {@code AccountLedgerReplayer}. Ordered by id rather
	 * than createdAt: id is a strictly monotonic identity sequence, so it can never tie the way two
	 * timestamps theoretically could.
	 *
	 * <p><b>A {@code Stream} of projections, not a {@code List} of entities, and both halves of
	 * that matter.</b> This replaced {@code List<Operation> findByAccountIdOrderByIdAsc(Long)},
	 * which materialised an account's entire history at once: every row became a managed entity in
	 * the persistence context, held live until the transaction closed, for a calculation that only
	 * ever needed four scalars per row and never looked back at a row once it had passed it. Heap
	 * use grew linearly with the length of the ledger, so the failure mode was not slowness but an
	 * {@code OutOfMemoryError} on whichever account happened to be busiest - the one where
	 * reconciliation matters most.
	 *
	 * <p>{@link LedgerEntry} removes the persistence-context retention (a constructor expression
	 * produces unmanaged objects); the {@code Stream} removes the materialisation, so rows arrive
	 * incrementally and each one is garbage as soon as the replay loop moves past it. Together they
	 * turn peak heap from "proportional to the ledger" into "proportional to the fetch size".
	 *
	 * <p>The fetch-size hint is what actually makes the streaming real on Postgres. Its JDBC driver
	 * buffers the <em>entire</em> result set in the client by default, which would reintroduce the
	 * same unbounded allocation one layer lower down, where no amount of JPA-level streaming could
	 * help. It only switches to a server-side cursor when a fetch size is set and autocommit is off
	 * - and autocommit is off precisely because the caller is inside a Spring-managed transaction,
	 * which is also what keeps the cursor legal for the life of the stream.
	 *
	 * <p>Must be consumed inside a transaction and closed - see the try-with-resources in
	 * {@code AccountLedgerReplayer}. An unclosed stream leaks its cursor and its connection.
	 */
	@QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "500"))
	@Query("select new lv.ray.springb.dto.LedgerEntry(o.id, o.type, o.amount, o.balanceAfter) "
			+ "from Operation o where o.account.id = :accountId order by o.id asc")
	Stream<LedgerEntry> streamLedgerEntries(@Param("accountId") Long accountId);

	List<Operation> findByTransferGroupId(String transferGroupId);

	/**
	 * Fetch-joins {@code account} so the result is safe to read from once the transaction that
	 * loaded it has already closed - used to resolve an idempotency-key replay, which by design
	 * happens outside any surrounding transaction (see {@code AccountServiceImpl}).
	 */
	@Query("select o from Operation o join fetch o.account where o.id = :id")
	Optional<Operation> findByIdWithAccount(@Param("id") Long id);

	/** Same fetch-join, for the two legs of a transfer. */
	@Query("select o from Operation o join fetch o.account where o.transferGroupId = :transferGroupId")
	List<Operation> findByTransferGroupIdWithAccount(@Param("transferGroupId") String transferGroupId);
}
