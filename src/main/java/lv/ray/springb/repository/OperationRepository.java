package lv.ray.springb.repository;

import lv.ray.springb.entity.Operation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface OperationRepository extends JpaRepository<Operation, Long>
{
	List<Operation> findByAccountIdOrderByCreatedAtDesc(Long accountId);

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
