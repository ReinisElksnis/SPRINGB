package lv.ray.springb.service.impl;

import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.dto.ReconciliationResult;
import lv.ray.springb.entity.Account;
import lv.ray.springb.entity.Operation;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.repository.OperationRepository;
import lv.ray.springb.service.AccountException;
import lv.ray.springb.service.LedgerReconciliationService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;


/**
 * Verifies an account's ledger is internally consistent by replaying it from scratch, rather than
 * trusting the cached {@link Account#getBalance()} on its own: that column is written by the same
 * code path that appends each {@link Operation}, so a bug there could move both in lockstep and a
 * balance-only check would never notice. Replaying the ledger and comparing every step against
 * what was actually recorded catches two distinct failure modes a balance comparison alone cannot -
 * a row that was altered or deleted after being committed (tampering, a bad manual fix), and a
 * balance that was ever written by anything other than the normal mutation path.
 */
@Service
public class LedgerReconciliationServiceImpl implements LedgerReconciliationService
{

	private final AccountRepository accountRepository;

	private final OperationRepository operationRepository;

	public LedgerReconciliationServiceImpl(final AccountRepository accountRepository,
			final OperationRepository operationRepository)
	{
		this.accountRepository = accountRepository;
		this.operationRepository = operationRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public ReconciliationResult reconcileAccount(final Long accountId)
	{
		final Account account = accountRepository.findById(accountId)
				.orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
						String.format(Messages.FUNDS_ACCOUNT_NOT_FOUND, accountId)));

		final List<Operation> operations = operationRepository.findByAccountIdOrderByIdAsc(accountId);
		final List<String> discrepancies = new ArrayList<>();

		BigDecimal expectedRunningBalance = BigDecimal.ZERO;
		for (final Operation operation : operations)
		{
			expectedRunningBalance = expectedRunningBalance.add(signedAmount(operation));

			if (expectedRunningBalance.compareTo(operation.getBalanceAfter()) != 0)
			{
				discrepancies.add(String.format(
						"Operation %d (%s %s): ledger math gives a running balance of %s, but %s was recorded as balanceAfter",
						operation.getId(), operation.getType(), operation.getAmount(), expectedRunningBalance,
						operation.getBalanceAfter()));
				// Resync to what was actually recorded so one bad row is reported once, rather than
				// every operation after it also being flagged as a cascading false positive.
				expectedRunningBalance = operation.getBalanceAfter();
			}
		}

		if (expectedRunningBalance.compareTo(account.getBalance()) != 0)
		{
			discrepancies.add(String.format(
					"Account %d balance is %s, but the ledger's final balance is %s",
					accountId, account.getBalance(), expectedRunningBalance));
		}

		return new ReconciliationResult(accountId, discrepancies.isEmpty(), discrepancies);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ReconciliationResult> reconcileAllAccounts()
	{
		return accountRepository.findAll().stream()
				.map(account -> reconcileAccount(account.getId()))
				.toList();
	}

	private static BigDecimal signedAmount(final Operation operation)
	{
		return switch (operation.getType())
		{
			case DEPOSIT, TRANSFER_IN -> operation.getAmount();
			case WITHDRAWAL, TRANSFER_OUT -> operation.getAmount().negate();
		};
	}
}
