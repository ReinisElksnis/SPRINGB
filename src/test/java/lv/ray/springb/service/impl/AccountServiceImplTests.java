package lv.ray.springb.service.impl;

import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.TransferResultDTO;
import lv.ray.springb.entity.Account;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.entity.IdempotencyRecord;
import lv.ray.springb.entity.Operation;
import lv.ray.springb.entity.OperationType;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.repository.IdempotencyRecordRepository;
import lv.ray.springb.repository.OperationRepository;
import lv.ray.springb.service.AccountException;
import lv.ray.springb.service.validation.AccountOperationValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the orchestration logic in {@link AccountServiceImpl} - the idempotency-key
 * replay/caller-validation branches in particular (see {@code IdempotencyRecord}'s Javadoc for why
 * that check exists). {@code AccountMutationExecutor} is mocked here, so none of this touches real
 * locking or transactions; {@code AccountServiceConcurrencyTests} covers that against a real
 * Postgres via Testcontainers.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceImplTests
{

	private static final String ALICE = "alice";
	private static final String BOB = "bob";

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private OperationRepository operationRepository;

	@Mock
	private IdempotencyRecordRepository idempotencyRecordRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private AccountOperationValidator validator;

	@Mock
	private AccountMutationExecutor mutationExecutor;

	private AccountServiceImpl accountService;

	private Account aliceAccount;

	@BeforeEach
	void setUp()
	{
		accountService = new AccountServiceImpl(accountRepository, operationRepository,
				idempotencyRecordRepository, appUserRepository, validator, mutationExecutor);

		final AppUser alice = new AppUser(ALICE, "alice@example.com", "hash", "Alice");
		aliceAccount = new Account(alice, "EUR");
		aliceAccount.setId(10L);
		aliceAccount.setBalance(new BigDecimal("100.00"));
	}

	private Operation operation(final Long id, final OperationType type, final String amount,
			final String balanceAfter, final String transferGroupId)
	{
		final Operation operation = new Operation(aliceAccount, type, new BigDecimal(amount),
				new BigDecimal(balanceAfter), transferGroupId);
		operation.setId(id);
		return operation;
	}

	// --- createAccount ---

	@Test
	void createAccount_defaultsBlankCurrencyAndSavesUnderResolvedOwner()
	{
		final AppUser alice = aliceAccount.getOwner();
		when(appUserRepository.findByUsername(ALICE)).thenReturn(Optional.of(alice));
		when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

		final AccountDTO result = accountService.createAccount(ALICE, "  ");

		assertThat(result.getCurrency()).isEqualTo("EUR");
		assertThat(result.getOwnerUsername()).isEqualTo(ALICE);
		verify(validator).validateCurrency("EUR");
	}

	@Test
	void createAccount_upperCasesAndValidatesAnExplicitCurrency()
	{
		when(appUserRepository.findByUsername(ALICE)).thenReturn(Optional.of(aliceAccount.getOwner()));
		when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

		accountService.createAccount(ALICE, "usd");

		verify(validator).validateCurrency("USD");
	}

	@Test
	void createAccount_throwsWhenAuthenticatedUsernameHasNoAppUserRow()
	{
		when(appUserRepository.findByUsername(ALICE)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.createAccount(ALICE, "EUR"))
				.isInstanceOf(IllegalStateException.class);

		verify(accountRepository, never()).save(any());
	}

	// --- getAccount / getOperations (ownership) ---

	@Test
	void getAccount_returnsDtoWhenOwnedByCaller()
	{
		when(accountRepository.findById(10L)).thenReturn(Optional.of(aliceAccount));

		final AccountDTO result = accountService.getAccount(10L, ALICE);

		assertThat(result.getId()).isEqualTo(10L);
		verify(validator).validateOwnership(aliceAccount, ALICE);
	}

	@Test
	void getAccount_throwsNotFoundWhenAccountIdUnknown()
	{
		when(accountRepository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.getAccount(404L, ALICE))
				.isInstanceOf(AccountException.class)
				.satisfies(ex -> assertThat(((AccountException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void getAccount_propagatesNotFoundWhenValidatorRejectsOwnership()
	{
		when(accountRepository.findById(10L)).thenReturn(Optional.of(aliceAccount));
		doThrow(new AccountException(HttpStatus.NOT_FOUND, "No account found with id 10"))
				.when(validator).validateOwnership(aliceAccount, BOB);

		assertThatThrownBy(() -> accountService.getAccount(10L, BOB))
				.isInstanceOf(AccountException.class);
	}

	// --- deposit ---

	@Test
	void deposit_delegatesToMutationExecutorWhenKeyIsUnseen()
	{
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
		when(mutationExecutor.depositOnce(10L, ALICE, new BigDecimal("25.00"), "key-1"))
				.thenReturn(operation(1L, OperationType.DEPOSIT, "25.00", "125.00", null));

		assertThat(accountService.deposit(10L, ALICE, new BigDecimal("25.00"), "key-1").getBalanceAfter())
				.isEqualByComparingTo("125.00");
	}

	@Test
	void deposit_requiresAnIdempotencyKeyBeforeTouchingTheRepository()
	{
		assertThatThrownBy(() -> accountService.deposit(10L, ALICE, new BigDecimal("25.00"), " "))
				.isInstanceOf(AccountException.class)
				.satisfies(ex -> assertThat(((AccountException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

		verify(idempotencyRecordRepository, never()).findByIdempotencyKey(any());
		verify(mutationExecutor, never()).depositOnce(any(), any(), any(), any());
	}

	@Test
	void deposit_returnsCachedResultOnLegitimateRetryByTheSameCaller()
	{
		final IdempotencyRecord existing = new IdempotencyRecord("key-1", ALICE, 1L, null, null);
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
		when(operationRepository.findByIdWithAccount(1L))
				.thenReturn(Optional.of(operation(1L, OperationType.DEPOSIT, "25.00", "125.00", null)));

		final var result = accountService.deposit(10L, ALICE, new BigDecimal("25.00"), "key-1");

		assertThat(result.getId()).isEqualTo(1L);
		verify(mutationExecutor, never()).depositOnce(any(), any(), any(), any());
	}

	@Test
	void deposit_returnsCachedResultWhenTheReplayedRequestHasTheSameShape()
	{
		final IdempotencyRecord existing = new IdempotencyRecord("key-1", ALICE, 1L, null,
				RequestFingerprint.forDeposit(10L, new BigDecimal("25.00")));
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
		when(operationRepository.findByIdWithAccount(1L))
				.thenReturn(Optional.of(operation(1L, OperationType.DEPOSIT, "25.00", "125.00", null)));

		final var result = accountService.deposit(10L, ALICE, new BigDecimal("25.00"), "key-1");

		assertThat(result.getId()).isEqualTo(1L);
		verify(mutationExecutor, never()).depositOnce(any(), any(), any(), any());
	}

	@Test
	void deposit_treatsADifferentlyScaledAmountAsTheSameRequest()
	{
		// A client retrying the same logical request may serialise 25.0 one time and 25.00 the next.
		// Those are the same amount of money, so the replay must still be recognised as a retry.
		final IdempotencyRecord existing = new IdempotencyRecord("key-1", ALICE, 1L, null,
				RequestFingerprint.forDeposit(10L, new BigDecimal("25.00")));
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
		when(operationRepository.findByIdWithAccount(1L))
				.thenReturn(Optional.of(operation(1L, OperationType.DEPOSIT, "25.00", "125.00", null)));

		final var result = accountService.deposit(10L, ALICE, new BigDecimal("25.0"), "key-1");

		assertThat(result.getId()).isEqualTo(1L);
	}

	@Test
	void withdraw_rejectsAKeyTheSameCallerAlreadySpentOnADifferentRequest()
	{
		// The bug this guards: the key was spent on a deposit, and is now replayed on a withdrawal
		// of a different amount. Before the fingerprint check this passed the caller check, returned
		// the earlier DEPOSIT with HTTP 200, and silently never performed the withdrawal.
		final IdempotencyRecord spentOnADeposit = new IdempotencyRecord("key-1", ALICE, 1L, null,
				RequestFingerprint.forDeposit(10L, new BigDecimal("25.00")));
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(spentOnADeposit));

		assertThatThrownBy(() -> accountService.withdraw(10L, ALICE, new BigDecimal("500.00"), "key-1"))
				.isInstanceOf(AccountException.class)
				.satisfies(ex -> assertThat(((AccountException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

		verify(mutationExecutor, never()).withdrawOnce(any(), any(), any(), any());
	}

	@Test
	void deposit_rejectsAReplayAimedAtADifferentAccount()
	{
		final IdempotencyRecord spentOnAccountTen = new IdempotencyRecord("key-1", ALICE, 1L, null,
				RequestFingerprint.forDeposit(10L, new BigDecimal("25.00")));
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(spentOnAccountTen));

		assertThatThrownBy(() -> accountService.deposit(99L, ALICE, new BigDecimal("25.00"), "key-1"))
				.isInstanceOf(AccountException.class)
				.satisfies(ex -> assertThat(((AccountException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

		verify(mutationExecutor, never()).depositOnce(any(), any(), any(), any());
	}

	@Test
	void deposit_stillReplaysLegacyRecordsThatPredateTheFingerprintColumn()
	{
		// Rows written before request_fingerprint existed have none; a replay of one of those keys
		// must keep working rather than failing the shape check it has no data for.
		final IdempotencyRecord legacy = new IdempotencyRecord("key-1", ALICE, 1L, null, null);
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(legacy));
		when(operationRepository.findByIdWithAccount(1L))
				.thenReturn(Optional.of(operation(1L, OperationType.DEPOSIT, "25.00", "125.00", null)));

		final var result = accountService.deposit(10L, ALICE, new BigDecimal("25.00"), "key-1");

		assertThat(result.getId()).isEqualTo(1L);
	}

	@Test
	void deposit_rejectsReplayOfAnotherUsersIdempotencyKey()
	{
		final IdempotencyRecord bobsRecord = new IdempotencyRecord("key-1", BOB, 1L, null, null);
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(bobsRecord));

		assertThatThrownBy(() -> accountService.deposit(10L, ALICE, new BigDecimal("25.00"), "key-1"))
				.isInstanceOf(AccountException.class)
				.satisfies(ex -> assertThat(((AccountException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

		verify(mutationExecutor, never()).depositOnce(any(), any(), any(), any());
		verify(operationRepository, never()).findByIdWithAccount(any());
	}

	@Test
	void deposit_resolvesALostIdempotencyRaceToTheWinnersResultForTheSameCaller()
	{
		final IdempotencyRecord winner = new IdempotencyRecord("key-1", ALICE, 1L, null, null);
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1"))
				.thenReturn(Optional.empty(), Optional.of(winner));
		when(mutationExecutor.depositOnce(10L, ALICE, new BigDecimal("25.00"), "key-1"))
				.thenThrow(new DataIntegrityViolationException("duplicate key"));
		when(operationRepository.findByIdWithAccount(1L))
				.thenReturn(Optional.of(operation(1L, OperationType.DEPOSIT, "25.00", "125.00", null)));

		final var result = accountService.deposit(10L, ALICE, new BigDecimal("25.00"), "key-1");

		assertThat(result.getId()).isEqualTo(1L);
	}

	@Test
	void deposit_rejectsALostIdempotencyRaceWonByAnotherCaller()
	{
		final IdempotencyRecord winner = new IdempotencyRecord("key-1", BOB, 1L, null, null);
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1"))
				.thenReturn(Optional.empty(), Optional.of(winner));
		when(mutationExecutor.depositOnce(10L, ALICE, new BigDecimal("25.00"), "key-1"))
				.thenThrow(new DataIntegrityViolationException("duplicate key"));

		assertThatThrownBy(() -> accountService.deposit(10L, ALICE, new BigDecimal("25.00"), "key-1"))
				.isInstanceOf(AccountException.class)
				.satisfies(ex -> assertThat(((AccountException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void deposit_rethrowsTheOriginalRaceFailureWhenNoWinnerRecordExistsEither()
	{
		when(idempotencyRecordRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
		final DataIntegrityViolationException original = new DataIntegrityViolationException("duplicate key");
		when(mutationExecutor.depositOnce(10L, ALICE, new BigDecimal("25.00"), "key-1")).thenThrow(original);

		assertThatThrownBy(() -> accountService.deposit(10L, ALICE, new BigDecimal("25.00"), "key-1"))
				.isSameAs(original);
	}

	// --- withdraw ---

	@Test
	void withdraw_propagatesInsufficientFundsFromMutationExecutorUnchanged()
	{
		when(idempotencyRecordRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
		when(mutationExecutor.withdrawOnce(10L, ALICE, new BigDecimal("500.00"), "key-2"))
				.thenThrow(new AccountException(HttpStatus.CONFLICT, "Account 10 has insufficient funds for this operation"));

		assertThatThrownBy(() -> accountService.withdraw(10L, ALICE, new BigDecimal("500.00"), "key-2"))
				.isInstanceOf(AccountException.class)
				.satisfies(ex -> assertThat(((AccountException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
	}

	// --- transfer ---

	@Test
	void transfer_returnsBothLegsOnHappyPath()
	{
		final Operation debit = operation(1L, OperationType.TRANSFER_OUT, "30.00", "70.00", "group-1");
		final Operation credit = operation(2L, OperationType.TRANSFER_IN, "30.00", "30.00", "group-1");
		when(idempotencyRecordRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.empty());
		when(mutationExecutor.transferOnce(10L, 20L, ALICE, new BigDecimal("30.00"), "key-3"))
				.thenReturn(Pair.of(debit, credit));

		final TransferResultDTO result = accountService.transfer(10L, 20L, ALICE, new BigDecimal("30.00"), "key-3");

		assertThat(result.debit().getId()).isEqualTo(1L);
		assertThat(result.credit().getId()).isEqualTo(2L);
	}

	@Test
	void transfer_rejectsTransferBetweenTheSameAccountBeforeCallingMutationExecutor()
	{
		doThrow(new AccountException(HttpStatus.BAD_REQUEST, "Cannot transfer an account to itself"))
				.when(validator).validateDistinctAccounts(10L, 10L);

		assertThatThrownBy(() -> accountService.transfer(10L, 10L, ALICE, new BigDecimal("30.00"), "key-4"))
				.isInstanceOf(AccountException.class);

		verify(mutationExecutor, never()).transferOnce(any(), any(), any(), any(), any());
	}

	@Test
	void transfer_returnsCachedResultOnLegitimateRetryByTheSameCaller()
	{
		final IdempotencyRecord existing = new IdempotencyRecord("key-3", ALICE, null, "group-1", null);
		when(idempotencyRecordRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.of(existing));
		when(operationRepository.findByTransferGroupIdWithAccount("group-1")).thenReturn(List.of(
				operation(1L, OperationType.TRANSFER_OUT, "30.00", "70.00", "group-1"),
				operation(2L, OperationType.TRANSFER_IN, "30.00", "30.00", "group-1")));

		final TransferResultDTO result = accountService.transfer(10L, 20L, ALICE, new BigDecimal("30.00"), "key-3");

		assertThat(result.debit().getTransferGroupId()).isEqualTo("group-1");
		verify(mutationExecutor, never()).transferOnce(any(), any(), any(), any(), any());
	}

	@Test
	void transfer_rejectsReplayOfAnotherUsersIdempotencyKey()
	{
		final IdempotencyRecord bobsRecord = new IdempotencyRecord("key-3", BOB, null, "group-1", null);
		when(idempotencyRecordRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.of(bobsRecord));

		assertThatThrownBy(() -> accountService.transfer(10L, 20L, ALICE, new BigDecimal("30.00"), "key-3"))
				.isInstanceOf(AccountException.class)
				.satisfies(ex -> assertThat(((AccountException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

		verify(mutationExecutor, never()).transferOnce(any(), any(), any(), any(), any());
	}
}
