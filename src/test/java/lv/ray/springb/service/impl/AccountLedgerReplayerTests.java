package lv.ray.springb.service.impl;

import lv.ray.springb.dto.LedgerEntry;
import lv.ray.springb.dto.ReconciliationResult;
import lv.ray.springb.entity.Account;
import lv.ray.springb.entity.AppUser;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the ledger-replay math itself, with every entry hand-built here rather than
 * produced by a real deposit/withdraw/transfer flow. {@code LedgerReconciliationIntegrationTests}
 * covers the case that actually matters most - a real ledger, corrupted after the fact, against a
 * real Postgres.
 *
 * <p>These moved off {@code LedgerReconciliationServiceImpl} when the replay did: the math is now
 * {@link AccountLedgerReplayer}'s, and the service above it only chooses which accounts to feed in.
 */
@ExtendWith(MockitoExtension.class)
class AccountLedgerReplayerTests
{

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private OperationRepository operationRepository;

	private AccountLedgerReplayer replayer;

	private Account account;

	@BeforeEach
	void setUp()
	{
		replayer = new AccountLedgerReplayer(accountRepository, operationRepository);

		final AppUser owner = new AppUser("alice", "alice@example.com", "hash", "Alice");
		account = new Account(owner, "EUR");
		account.setId(10L);
	}

	private static LedgerEntry entry(final Long id, final OperationType type, final String amount,
			final String balanceAfter)
	{
		return new LedgerEntry(id, type, new BigDecimal(amount), new BigDecimal(balanceAfter));
	}

	@Test
	void replay_isConsistentWhenLedgerMathAndAccountBalanceAllAgree()
	{
		account.setBalance(new BigDecimal("50.00"));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.streamLedgerEntries(10L)).thenReturn(Stream.of(
				entry(1L, OperationType.DEPOSIT, "100.00", "100.00"),
				entry(2L, OperationType.WITHDRAWAL, "30.00", "70.00"),
				entry(3L, OperationType.TRANSFER_OUT, "20.00", "50.00")));

		final ReconciliationResult result = replayer.replay(10L);

		assertThat(result.consistent()).isTrue();
		assertThat(result.discrepancies()).isEmpty();
	}

	@Test
	void replay_flagsAnOperationWhoseBalanceAfterDoesNotFollowFromTheLedgerMath()
	{
		// Op 2 is corrupted: 100.00 - 30.00 should be 70.00, but 80.00 was recorded. Op 3 correctly
		// follows from the corrupted 80.00 (90.00 after a +10 deposit) - proving the resync after a
		// flagged row means only the one bad row is reported, not everything after it too.
		account.setBalance(new BigDecimal("90.00"));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.streamLedgerEntries(10L)).thenReturn(Stream.of(
				entry(1L, OperationType.DEPOSIT, "100.00", "100.00"),
				entry(2L, OperationType.WITHDRAWAL, "30.00", "80.00"),
				entry(3L, OperationType.DEPOSIT, "10.00", "90.00")));

		final ReconciliationResult result = replayer.replay(10L);

		assertThat(result.consistent()).isFalse();
		assertThat(result.discrepancies()).hasSize(1);
		assertThat(result.discrepancies().get(0)).contains("Operation 2").contains("70.00").contains("80.00");
	}

	@Test
	void replay_flagsAnAccountBalanceThatDisagreesWithAnOtherwiseConsistentLedger()
	{
		// The ledger itself is perfectly self-consistent (each row follows from the last) - only
		// the cached Account.balance column has drifted from what the ledger actually says.
		account.setBalance(new BigDecimal("999.99"));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.streamLedgerEntries(10L)).thenReturn(Stream.of(
				entry(1L, OperationType.DEPOSIT, "100.00", "100.00"),
				entry(2L, OperationType.WITHDRAWAL, "30.00", "70.00")));

		final ReconciliationResult result = replayer.replay(10L);

		assertThat(result.consistent()).isFalse();
		assertThat(result.discrepancies()).hasSize(1);
		assertThat(result.discrepancies().get(0)).contains("999.99").contains("70.00");
	}

	@Test
	void replay_isConsistentForAnAccountWithNoOperationsYet()
	{
		account.setBalance(BigDecimal.ZERO);
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.streamLedgerEntries(10L)).thenReturn(Stream.of());

		final ReconciliationResult result = replayer.replay(10L);

		assertThat(result.consistent()).isTrue();
	}

	@Test
	void replay_throwsNotFoundForAnUnknownAccount()
	{
		when(accountRepository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> replayer.replay(404L))
				.isInstanceOf(AccountException.class);
	}

	/**
	 * The production stream is backed by a database cursor that a bare loop would never release -
	 * see {@code OperationRepository#streamLedgerEntries}. Mockito hands back a plain in-memory
	 * stream here, so this asserts the try-with-resources exists rather than that a cursor closed;
	 * it is a regression guard for someone later "simplifying" the block away, which would compile,
	 * pass every other test, and leak a connection per reconciliation in production.
	 */
	@Test
	void replay_closesTheLedgerStream()
	{
		account.setBalance(new BigDecimal("100.00"));

		final boolean[] closed = { false };
		final Stream<LedgerEntry> tracked = Stream.of(entry(1L, OperationType.DEPOSIT, "100.00", "100.00"))
				.onClose(() -> closed[0] = true);

		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.streamLedgerEntries(10L)).thenReturn(tracked);

		replayer.replay(10L);

		assertThat(closed[0]).isTrue();
	}

	@Test
	void replay_reportsEveryCorruptedRowWhenThereIsMoreThanOne()
	{
		// Two corrupted rows with a valid one between them. Each flagged row resyncs the running
		// balance to what was actually recorded, so op 2 is checked against 111.00 (not 100.00) and
		// lines up correctly - which is what keeps this at two discrepancies rather than a cascade.
		account.setBalance(new BigDecimal("60.00"));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.streamLedgerEntries(10L)).thenReturn(Stream.of(
				entry(1L, OperationType.DEPOSIT, "100.00", "111.00"),
				entry(2L, OperationType.WITHDRAWAL, "30.00", "81.00"),
				entry(3L, OperationType.DEPOSIT, "10.00", "60.00")));

		final ReconciliationResult result = replayer.replay(10L);

		assertThat(result.consistent()).isFalse();
		assertThat(result.discrepancies()).hasSize(2);
		assertThat(result.discrepancies()).anySatisfy(d -> assertThat(d).contains("Operation 1"));
		assertThat(result.discrepancies()).anySatisfy(d -> assertThat(d).contains("Operation 3"));
	}

	@Test
	void replay_treatsListedEntriesAsTheOnlySourceOfTruthForOrdering()
	{
		// TRANSFER_IN credits, TRANSFER_OUT debits - the sign comes from the type, never from the
		// amount, which is always stored positive.
		account.setBalance(new BigDecimal("25.00"));
		when(accountRepository.findById(10L)).thenReturn(Optional.of(account));
		when(operationRepository.streamLedgerEntries(10L)).thenReturn(Stream.of(
				entry(1L, OperationType.TRANSFER_IN, "40.00", "40.00"),
				entry(2L, OperationType.TRANSFER_OUT, "15.00", "25.00")));

		final ReconciliationResult result = replayer.replay(10L);

		assertThat(result.consistent()).isTrue();
	}
}
