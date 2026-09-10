package lv.ray.springb.service.impl;

import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.dto.AccountOperationEvent;
import lv.ray.springb.entity.Account;
import lv.ray.springb.entity.IdempotencyRecord;
import lv.ray.springb.entity.Operation;
import lv.ray.springb.entity.OperationType;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.repository.IdempotencyRecordRepository;
import lv.ray.springb.repository.OperationRepository;
import lv.ray.springb.service.AccountException;
import lv.ray.springb.service.outbox.OutboxAppender;
import lv.ray.springb.service.validation.AccountOperationValidator;

import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;


/**
 * Executes exactly one balance-mutating attempt per call, each in its own database transaction:
 * locks every account it touches with {@code SELECT ... FOR UPDATE} (always in ascending id
 * order, so a transfer running the other way can never deadlock against it), applies the change,
 * appends the ledger row(s), then claims the idempotency key as the last statement. Because the
 * claim is last, a concurrent duplicate request loses the unique-key race on that insert and rolls
 * this whole transaction back - the mutation and its key are always committed together, never
 * separately. {@code AccountServiceImpl} is what reacts to that rollback and turns it into
 * "return the winner's result" instead of surfacing an error to the loser.
 *
 * <p>Every method here is {@code REQUIRES_NEW} on purpose: {@code AccountServiceImpl} calls these
 * through the Spring proxy from a read-only transaction of its own, and if this ran under the
 * default {@code REQUIRED} propagation it would join that outer transaction - so a lost
 * idempotency race would mark the *outer* transaction rollback-only too, and the fallback lookup
 * that follows would blow up with {@code UnexpectedRollbackException} instead of returning the
 * winner's result. Suspending into a genuinely separate transaction is what makes the try/catch
 * in {@code AccountServiceImpl} safe to do at all.
 *
 * <p>Each method also appends an event to the outbox through {@link OutboxAppender}, inside this
 * same transaction. That placement is load-bearing: the event, the ledger row, the balance and the
 * idempotency key all commit together or not at all, so there is no ordering of failures that can
 * leave an event describing money that never moved, or moved money nobody downstream was told
 * about. See {@code OutboxAppender} for why it must not open a transaction of its own.
 */
@Component
public class AccountMutationExecutor
{

	private final AccountRepository accountRepository;

	private final OperationRepository operationRepository;

	private final IdempotencyRecordRepository idempotencyRecordRepository;

	private final AccountOperationValidator validator;

	private final OutboxAppender outboxAppender;

	public AccountMutationExecutor(final AccountRepository accountRepository,
			final OperationRepository operationRepository,
			final IdempotencyRecordRepository idempotencyRecordRepository,
			final AccountOperationValidator validator,
			final OutboxAppender outboxAppender)
	{
		this.accountRepository = accountRepository;
		this.operationRepository = operationRepository;
		this.idempotencyRecordRepository = idempotencyRecordRepository;
		this.validator = validator;
		this.outboxAppender = outboxAppender;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Operation depositOnce(final Long accountId, final String ownerUsername, final BigDecimal amount,
			final String idempotencyKey)
	{
		final Account account = lock(accountId);
		validator.validateOwnership(account, ownerUsername);
		validator.validateAmountScale(amount, account.getCurrency());

		account.setBalance(account.getBalance().add(amount));
		final Operation operation = operationRepository.save(
				new Operation(account, OperationType.DEPOSIT, amount, account.getBalance(), null));

		publish(account, operation);
		claimKey(idempotencyKey, ownerUsername, operation.getId(), null,
				RequestFingerprint.forDeposit(accountId, amount));
		return operation;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Operation withdrawOnce(final Long accountId, final String ownerUsername, final BigDecimal amount,
			final String idempotencyKey)
	{
		final Account account = lock(accountId);
		validator.validateOwnership(account, ownerUsername);
		validator.validateAmountScale(amount, account.getCurrency());
		validator.validateSufficientFunds(account, amount);

		account.setBalance(account.getBalance().subtract(amount));
		final Operation operation = operationRepository.save(
				new Operation(account, OperationType.WITHDRAWAL, amount, account.getBalance(), null));

		publish(account, operation);
		claimKey(idempotencyKey, ownerUsername, operation.getId(), null,
				RequestFingerprint.forWithdrawal(accountId, amount));
		return operation;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Pair<Operation, Operation> transferOnce(final Long fromAccountId, final Long toAccountId,
			final String ownerUsername, final BigDecimal amount, final String idempotencyKey)
	{
		// Always lock in the same global order, regardless of transfer direction, so two
		// concurrent transfers between the same pair of accounts can never deadlock on each
		// other's locks.
		final boolean fromFirst = fromAccountId.compareTo(toAccountId) < 0;
		final Account first = lock(fromFirst ? fromAccountId : toAccountId);
		final Account second = lock(fromFirst ? toAccountId : fromAccountId);
		final Account from = fromFirst ? first : second;
		final Account to = fromFirst ? second : first;

		validator.validateOwnership(from, ownerUsername);
		validator.validateSufficientFunds(from, amount);
		validator.validateSameCurrency(from, to);
		validator.validateAmountScale(amount, from.getCurrency());

		final String transferGroupId = UUID.randomUUID().toString();

		from.setBalance(from.getBalance().subtract(amount));
		final Operation debit = operationRepository.save(
				new Operation(from, OperationType.TRANSFER_OUT, amount, from.getBalance(), transferGroupId));

		to.setBalance(to.getBalance().add(amount));
		final Operation credit = operationRepository.save(
				new Operation(to, OperationType.TRANSFER_IN, amount, to.getBalance(), transferGroupId));

		// Two events, not one: the debit and the credit are facts about two different accounts, and
		// a consumer that cares about only one of them should not have to parse an event about the
		// other to find it. They stay correlatable through the shared transferGroupId.
		publish(from, debit);
		publish(to, credit);

		claimKey(idempotencyKey, ownerUsername, null, transferGroupId,
				RequestFingerprint.forTransfer(fromAccountId, toAccountId, amount));
		return Pair.of(debit, credit);
	}

	/**
	 * Emits the ledger entry as an outbox event. Takes the owner from the account rather than from
	 * the caller's {@code ownerUsername} parameter: for the credit leg of a transfer those two are
	 * different people, and attributing the recipient's event to the sender would be wrong.
	 */
	private void publish(final Account account, final Operation operation)
	{
		outboxAppender.append("ACCOUNT", String.valueOf(account.getId()), operation.getType().name(),
				new AccountOperationEvent(operation.getId(), account.getId(),
						account.getOwner().getUsername(), operation.getType(), operation.getAmount(),
						operation.getBalanceAfter(), account.getCurrency(), operation.getTransferGroupId(),
						operation.getCreatedAt()));
	}

	private Account lock(final Long accountId)
	{
		return accountRepository.findByIdForUpdate(accountId)
				.orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
						String.format(Messages.FUNDS_ACCOUNT_NOT_FOUND, accountId)));
	}

	private void claimKey(final String idempotencyKey, final String ownerUsername, final Long operationId,
			final String transferGroupId, final String requestFingerprint)
	{
		idempotencyRecordRepository.save(
				new IdempotencyRecord(idempotencyKey, ownerUsername, operationId, transferGroupId,
						requestFingerprint));
	}
}
