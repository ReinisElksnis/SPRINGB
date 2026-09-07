package lv.ray.springb.service.impl;

import lv.ray.springb.dto.ReconciliationResult;
import lv.ray.springb.entity.Account;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.entity.Operation;
import lv.ray.springb.entity.OperationType;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.repository.OperationRepository;
import lv.ray.springb.service.AccountException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests: the ledger-replay math itself, with every operation hand-built here rather than
 * produced by a real deposit/withdraw/transfer flow. {@code LedgerReconciliationIntegrationTests}
 * covers the case that actually matters most - a real ledger, corrupted after the fact, against a
 * real Postgres.
 */
@ExtendWith(MockitoExtension.class)
class LedgerReconciliationServiceImplTests
{

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private OperationRepository operationRepository;

	private LedgerReconciliationServiceImpl reconciliationService;

	private Account account;

	@BeforeEach
	void setUp()
	{
		reconciliationService = new LedgerReconciliationServiceImpl(accountRepository, operationRepository);

		final AppUser owner = new AppUser("alice", "alice@example.com", "hash", "Alice");
		account = new Account(owner, "EUR");
		account.setId(10L);
	}

	private Operation operation(final Long id, final OperationType type, final String amount,
			final String balanceAfter)
	{
		final Operation operation = new Operation(account, type, new BigDecimal(amount), new BigDecimal(balanceAfter),
				null);
		operation.setId(id);
		return operation;
	}

	@Test
	void reconcileAccount_isConsistentWhenLedgerMathAndAccountBalanceAllAgree()
	{
		account.setBalance(new BigDecimal("50.00"));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.findByAccountIdOrderByIdAsc(10L)).thenReturn(List.of(
				operation(1L, OperationType.DEPOSIT, "100.00", "100.00"),
				operation(2L, OperationType.WITHDRAWAL, "30.00", "70.00"),
				operation(3L, OperationType.TRANSFER_OUT, "20.00", "50.00")));

		final ReconciliationResult result = reconciliationService.reconcileAccount(10L);

		assertThat(result.consistent()).isTrue();
		assertThat(result.discrepancies()).isEmpty();
	}

	@Test
	void reconcileAccount_flagsAnOperationWhoseBalanceAfterDoesNotFollowFromTheLedgerMath()
	{
		// Op 2 is corrupted: 100.00 - 30.00 should be 70.00, but 80.00 was recorded. Op 3 correctly
		// follows from the corrupted 80.00 (90.00 after a +10 deposit) - proving the resync after a
		// flagged row means only the one bad row is reported, not everything after it too.
		account.setBalance(new BigDecimal("90.00"));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.findByAccountIdOrderByIdAsc(10L)).thenReturn(List.of(
				operation(1L, OperationType.DEPOSIT, "100.00", "100.00"),
				operation(2L, OperationType.WITHDRAWAL, "30.00", "80.00"),
				operation(3L, OperationType.DEPOSIT, "10.00", "90.00")));

		final ReconciliationResult result = reconciliationService.reconcileAccount(10L);

		assertThat(result.consistent()).isFalse();
		assertThat(result.discrepancies()).hasSize(1);
		assertThat(result.discrepancies().get(0)).contains("Operation 2").contains("70.00").contains("80.00");
	}

	@Test
	void reconcileAccount_flagsAnAccountBalanceThatDisagreesWithAnOtherwiseConsistentLedger()
	{
		// The ledger itself is perfectly self-consistent (each row follows from the last) - only
		// the cached Account.balance column has drifted from what the ledger actually says.
		account.setBalance(new BigDecimal("999.99"));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.findByAccountIdOrderByIdAsc(10L)).thenReturn(List.of(
				operation(1L, OperationType.DEPOSIT, "100.00", "100.00"),
				operation(2L, OperationType.WITHDRAWAL, "30.00", "70.00")));

		final ReconciliationResult result = reconciliationService.reconcileAccount(10L);

		assertThat(result.consistent()).isFalse();
		assertThat(result.discrepancies()).hasSize(1);
		assertThat(result.discrepancies().get(0)).contains("999.99").contains("70.00");
	}

	@Test
	void reconcileAccount_isConsistentForAnAccountWithNoOperationsYet()
	{
		account.setBalance(BigDecimal.ZERO);
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.findByAccountIdOrderByIdAsc(10L)).thenReturn(List.of());

		final ReconciliationResult result = reconciliationService.reconcileAccount(10L);

		assertThat(result.consistent()).isTrue();
	}

	@Test
	void reconcileAccount_throwsNotFoundForAnUnknownAccount()
	{
		when(accountRepository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> reconciliationService.reconcileAccount(404L))
				.isInstanceOf(AccountException.class);
	}

	@Test
	void reconcileAllAccounts_reconcilesEveryAccountIndependently()
	{
		final Account other = new Account(account.getOwner(), "EUR");
		other.setId(20L);
		other.setBalance(BigDecimal.ZERO);

		account.setBalance(new BigDecimal("100.00"));

		when(accountRepository.findAll()).thenReturn(List.of(account, other));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(accountRepository.findById(20L)).thenReturn(Optional.of(other));
		when(operationRepository.findByAccountIdOrderByIdAsc(10L)).thenReturn(List.of(
				operation(1L, OperationType.DEPOSIT, "100.00", "100.00")));
		when(operationRepository.findByAccountIdOrderByIdAsc(20L)).thenReturn(List.of());

		final List<ReconciliationResult> results = reconciliationService.reconcileAllAccounts();

		assertThat(results).hasSize(2);
		assertThat(results).allMatch(ReconciliationResult::consistent);
	}
}
