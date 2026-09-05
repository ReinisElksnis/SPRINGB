package lv.ray.springb.service;

import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.OperationDTO;
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
}
