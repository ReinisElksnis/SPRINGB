package lv.ray.springb.service;

import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.OperationDTO;
import lv.ray.springb.dto.OptimisticTransferResultDTO;
import lv.ray.springb.dto.TransferResultDTO;

import java.math.BigDecimal;
import java.util.List;


public interface AccountService
{
	AccountDTO createAccount(String ownerUsername, String currency);

	List<AccountDTO> getAccountsForOwner(String ownerUsername);

	AccountDTO getAccount(Long accountId, String ownerUsername);

	List<OperationDTO> getOperations(Long accountId, String ownerUsername);

	OperationDTO deposit(Long accountId, String ownerUsername, BigDecimal amount, String idempotencyKey);

	OperationDTO withdraw(Long accountId, String ownerUsername, BigDecimal amount, String idempotencyKey);

	TransferResultDTO transfer(Long fromAccountId, Long toAccountId, String ownerUsername, BigDecimal amount,
			String idempotencyKey);

	/**
	 * The same transfer, defended with optimistic locking and a bounded retry instead of a
	 * pessimistic row lock. Behaviourally identical from the caller's point of view - same
	 * validation, same ledger rows, same idempotency contract - and it exists so the two strategies
	 * can be compared against the same domain rather than described in the abstract.
	 *
	 * <p>Answers 409 if it loses every attempt it is given, which a caller should treat as "retry
	 * later", not as a failure of the request.
	 */
	OptimisticTransferResultDTO transferOptimistic(Long fromAccountId, Long toAccountId, String ownerUsername,
			BigDecimal amount, String idempotencyKey);
}
