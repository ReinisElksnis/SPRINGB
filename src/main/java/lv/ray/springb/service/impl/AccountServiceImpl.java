package lv.ray.springb.service.impl;

import lv.ray.springb.constants.ApiConstants.Defaults;
import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.OperationDTO;
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
import lv.ray.springb.service.AccountService;
import lv.ray.springb.service.validation.AccountOperationValidator;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;


/**
 * Orchestrates account reads and delegates every balance mutation to {@link AccountMutationExecutor},
 * which runs each attempt in its own {@code REQUIRES_NEW} transaction. When an attempt loses the
 * idempotency-key race (see {@link AccountMutationExecutor}), the {@link DataIntegrityViolationException}
 * that bubbles up here is treated as "someone already processed this key" rather than an error - the
 * previously committed result is looked up fresh and returned instead.
 *
 * <p>{@link #deposit}, {@link #withdraw} and {@link #transfer} are deliberately <em>not</em>
 * {@code @Transactional}: each already delegates its actual write to a {@code REQUIRES_NEW} method
 * on {@link AccountMutationExecutor}, which always needs its own connection out of the pool. Wrapping
 * these methods in a transaction too would hold a second connection for the same request just to sit
 * idle around that call - with enough concurrent requests that starves the pool (every thread holding
 * one connection while waiting for a second that can never free up, since every other thread is doing
 * the same thing). Each repository call these methods make is transactional on its own regardless.
 */
@Service
public class AccountServiceImpl implements AccountService
{

	private final AccountRepository accountRepository;

	private final OperationRepository operationRepository;

	private final IdempotencyRecordRepository idempotencyRecordRepository;

	private final AppUserRepository appUserRepository;

	private final AccountOperationValidator validator;

	private final AccountMutationExecutor mutationExecutor;

	public AccountServiceImpl(final AccountRepository accountRepository,
			final OperationRepository operationRepository,
			final IdempotencyRecordRepository idempotencyRecordRepository,
			final AppUserRepository appUserRepository,
			final AccountOperationValidator validator,
			final AccountMutationExecutor mutationExecutor)
	{
		this.accountRepository = accountRepository;
		this.operationRepository = operationRepository;
		this.idempotencyRecordRepository = idempotencyRecordRepository;
		this.appUserRepository = appUserRepository;
		this.validator = validator;
		this.mutationExecutor = mutationExecutor;
	}

	@Override
	@Transactional
	public AccountDTO createAccount(final String ownerUsername, final String currency)
	{
		final String resolvedCurrency = (currency == null || currency.isBlank())
				? Defaults.ACCOUNT_CURRENCY
				: currency.toUpperCase();
		validator.validateCurrency(resolvedCurrency);

		final AppUser owner = appUserRepository.findByUsername(ownerUsername)
				.orElseThrow(() -> new IllegalStateException("Authenticated user '" + ownerUsername + "' has no AppUser row"));

		return new AccountDTO(accountRepository.save(new Account(owner, resolvedCurrency)));
	}

	@Override
	@Transactional(readOnly = true)
	public List<AccountDTO> getAccountsForOwner(final String ownerUsername)
	{
		return accountRepository.findByOwnerUsername(ownerUsername).stream().map(AccountDTO::new).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public AccountDTO getAccount(final Long accountId, final String ownerUsername)
	{
		return new AccountDTO(findOwned(accountId, ownerUsername));
	}

	@Override
	@Transactional(readOnly = true)
	public List<OperationDTO> getOperations(final Long accountId, final String ownerUsername)
	{
		findOwned(accountId, ownerUsername);
		return operationRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
				.map(OperationDTO::new).toList();
	}

	@Override
	public OperationDTO deposit(final Long accountId, final String ownerUsername, final BigDecimal amount,
			final String idempotencyKey)
	{
		validator.validateAmount(amount);
		requireIdempotencyKey(idempotencyKey);
		final String fingerprint = RequestFingerprint.forDeposit(accountId, amount);

		final Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent())
		{
			return operationDto(requireSameRequest(existing.get(), ownerUsername, fingerprint).getOperationId());
		}

		try
		{
			return new OperationDTO(mutationExecutor.depositOnce(accountId, ownerUsername, amount, idempotencyKey));
		}
		catch (final DataIntegrityViolationException lostIdempotencyRace)
		{
			return resolveRacedSingleOperation(idempotencyKey, ownerUsername, fingerprint, lostIdempotencyRace);
		}
	}

	@Override
	public OperationDTO withdraw(final Long accountId, final String ownerUsername, final BigDecimal amount,
			final String idempotencyKey)
	{
		validator.validateAmount(amount);
		requireIdempotencyKey(idempotencyKey);
		final String fingerprint = RequestFingerprint.forWithdrawal(accountId, amount);

		final Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent())
		{
			return operationDto(requireSameRequest(existing.get(), ownerUsername, fingerprint).getOperationId());
		}

		try
		{
			return new OperationDTO(mutationExecutor.withdrawOnce(accountId, ownerUsername, amount, idempotencyKey));
		}
		catch (final DataIntegrityViolationException lostIdempotencyRace)
		{
			return resolveRacedSingleOperation(idempotencyKey, ownerUsername, fingerprint, lostIdempotencyRace);
		}
	}

	@Override
	public TransferResultDTO transfer(final Long fromAccountId, final Long toAccountId, final String ownerUsername,
			final BigDecimal amount, final String idempotencyKey)
	{
		validator.validateAmount(amount);
		validator.validateDistinctAccounts(fromAccountId, toAccountId);
		requireIdempotencyKey(idempotencyKey);
		final String fingerprint = RequestFingerprint.forTransfer(fromAccountId, toAccountId, amount);

		final Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent())
		{
			return transferResultDto(requireSameRequest(existing.get(), ownerUsername, fingerprint).getTransferGroupId());
		}

		try
		{
			final Pair<Operation, Operation> legs = mutationExecutor.transferOnce(fromAccountId, toAccountId,
					ownerUsername, amount, idempotencyKey);
			return new TransferResultDTO(new OperationDTO(legs.getFirst()), new OperationDTO(legs.getSecond()));
		}
		catch (final DataIntegrityViolationException lostIdempotencyRace)
		{
			return idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey)
					.map(record -> transferResultDto(
							requireSameRequest(record, ownerUsername, fingerprint).getTransferGroupId()))
					.orElseThrow(() -> lostIdempotencyRace);
		}
	}

	private Account findOwned(final Long accountId, final String ownerUsername)
	{
		final Account account = accountRepository.findById(accountId)
				.orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
						String.format(Messages.FUNDS_ACCOUNT_NOT_FOUND, accountId)));
		validator.validateOwnership(account, ownerUsername);
		return account;
	}

	private void requireIdempotencyKey(final String idempotencyKey)
	{
		if (idempotencyKey == null || idempotencyKey.isBlank())
		{
			throw new AccountException(HttpStatus.BAD_REQUEST, Messages.IDEMPOTENCY_KEY_REQUIRED);
		}
	}

	private OperationDTO resolveRacedSingleOperation(final String idempotencyKey, final String ownerUsername,
			final String requestFingerprint, final DataIntegrityViolationException lostIdempotencyRace)
	{
		return idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey)
				.map(record -> operationDto(
						requireSameRequest(record, ownerUsername, requestFingerprint).getOperationId()))
				.orElseThrow(() -> lostIdempotencyRace);
	}

	/**
	 * A key match alone is not proof that a cached result answers the request being made, so both
	 * things recorded alongside the key are re-checked before it is returned.
	 *
	 * <p>The caller check stops a collision handing one user's operation details to a different
	 * caller. The fingerprint check stops the more dangerous case, which the caller check cannot
	 * see at all: the <em>same</em> caller reusing one key across two genuinely different requests.
	 * Without it, a key first spent on a deposit and then replayed on a withdrawal passed straight
	 * through and returned the deposit's result with HTTP 200 - the withdrawal never happened and
	 * the client was told it had succeeded. Rejecting outright is the only safe answer: the cached
	 * result is not an answer to this request, and performing the request would break the promise
	 * that one key means one mutation.
	 *
	 * <p>A null fingerprint means a row written before that column existed. Those skip the shape
	 * check rather than failing every replay of a pre-existing key; the caller check still applies.
	 */
	private IdempotencyRecord requireSameRequest(final IdempotencyRecord record, final String ownerUsername,
			final String requestFingerprint)
	{
		if (!record.getOwnerUsername().equals(ownerUsername))
		{
			throw new AccountException(HttpStatus.CONFLICT, Messages.IDEMPOTENCY_KEY_CONFLICT);
		}

		if (record.getRequestFingerprint() != null && !record.getRequestFingerprint().equals(requestFingerprint))
		{
			throw new AccountException(HttpStatus.CONFLICT, Messages.IDEMPOTENCY_KEY_CONFLICT);
		}

		return record;
	}

	private OperationDTO operationDto(final Long operationId)
	{
		// Fetch-joined: this runs outside any surrounding transaction (see the class comment),
		// so the account it needs to read must already be loaded, not a lazy proxy.
		return new OperationDTO(operationRepository.findByIdWithAccount(operationId).orElseThrow());
	}

	private TransferResultDTO transferResultDto(final String transferGroupId)
	{
		final List<Operation> legs = operationRepository.findByTransferGroupIdWithAccount(transferGroupId);
		final Operation debit = legs.stream().filter(op -> op.getType() == OperationType.TRANSFER_OUT).findFirst()
				.orElseThrow();
		final Operation credit = legs.stream().filter(op -> op.getType() == OperationType.TRANSFER_IN).findFirst()
				.orElseThrow();
		return new TransferResultDTO(new OperationDTO(debit), new OperationDTO(credit));
	}
}
