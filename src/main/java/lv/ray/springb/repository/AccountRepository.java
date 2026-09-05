package lv.ray.springb.repository;

import jakarta.persistence.LockModeType;
import lv.ray.springb.entity.Account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface AccountRepository extends JpaRepository<Account, Long>
{
	List<Account> findByOwnerUsername(String username);

	/**
	 * Takes a row-level {@code SELECT ... FOR UPDATE} lock on the account, held for the rest of
	 * the caller's transaction, so two concurrent mutations against the same account serialize
	 * instead of both reading the same stale balance.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
