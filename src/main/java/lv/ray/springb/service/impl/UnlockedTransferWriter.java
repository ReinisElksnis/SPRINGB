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

import java.math.BigDecimal;
import java.util.UUID;


/**
* The body of a transfer, with <b>no</b> concurrency control of its own: it reads both accounts
* without taking a database lock and writes them without waiting for anybody. On its own it is
* unsafe, and that is the point - it is the shared payload that each locking strategy wraps in a
* different guarantee.
*
* <p>Three callers, three strategies, one body:
* <ul>
*   <li>{@link OptimisticAccountMutationExecutor} — no lock at all; relies on {@code @Version}
*       detecting the collision at flush, and retries.</li>
*   <li>{@link JavaLockAccountMutationExecutor} — an in-JVM {@code ReentrantLock} per account,
*       held across the transaction boundary.</li>
*   <li>{@code AccountMutationExecutor} is the exception: it does <em>not</em> use this, because
*       its guarantee comes from how it <em>reads</em> ({@code SELECT ... FOR UPDATE}) rather than
*       from anything wrapped around the write, so its body genuinely differs.</li>
* </ul>
*
* <p>Sharing this matters for the comparison, not just for tidiness: if each strategy had its own
* copy of the transfer, any measured difference between them could be an artefact of the copies
* drifting apart. Holding the work identical is what leaves the locking strategy as the only
* variable.
*
* <p>No {@code @Transactional} annotation, deliberately - it inherits {@code REQUIRED} and joins
* whatever transaction the caller opened. The caller owns the boundary, because <em>where the
* boundary sits relative to the lock</em> is precisely what distinguishes a correct in-JVM lock
* from a broken one.
*/
@Component
public class UnlockedTransferWriter
{

	private final AccountRepository accountRepository;

	private final OperationRepository operationRepository;

	private final IdempotencyRecordRepository idempotencyRecordRepository;

	private final AccountOperationValidator validator;

	private final OutboxAppender outboxAppender;

	public UnlockedTransferWriter(final AccountRepository accountRepository,
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

	/**
	* Applies both legs, appends the ledger rows and events, claims the idempotency key, and flushes.
	*
	* <p>Accounts are loaded in id order. There are no read locks to order here, but Hibernate
	* queues its UPDATEs and sends them at flush, and those do take write locks - so a consistent
	* load order produces a consistent update order and keeps two opposite-direction transfers from
	* deadlocking against each other at commit time.
	*
	* <p>The explicit flush forces the versioned UPDATEs and the idempotency-key INSERT to be sent
	* now, so both failure modes are raised inside the caller's transaction - where Spring Data's
	* repository proxy translates them into {@code DataAccessException} types callers can
	* discriminate on - rather than at commit time, where an untranslated provider exception could
	* escape.
	*/
	public Pair<Operation, Operation> applyTransfer(final Long fromAccountId, final Long toAccountId,
			final String ownerUsername, final BigDecimal amount, final String idempotencyKey)
	{
		final boolean fromFirst = fromAccountId.compareTo(toAccountId) < 0;
		final Account first = load(fromFirst ? fromAccountId : toAccountId);
		final Account second = load(fromFirst ? toAccountId : fromAccountId);
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

		publish(from, debit);
		publish(to, credit);

		claimKey(idempotencyKey, ownerUsername, transferGroupId,
				RequestFingerprint.forTransfer(fromAccountId, toAccountId, amount));

		accountRepository.flush();

		return Pair.of(debit, credit);
	}

	private Account load(final Long accountId)
	{
		// findById, not findByIdForUpdate. The single line that makes this body unguarded.
		return accountRepository.findById(accountId)
				.orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
						String.format(Messages.FUNDS_ACCOUNT_NOT_FOUND, accountId)));
	}

	private void publish(final Account account, final Operation operation)
	{
		outboxAppender.append("ACCOUNT", String.valueOf(account.getId()), operation.getType().name(),
				new AccountOperationEvent(operation.getId(), account.getId(),
						account.getOwner().getUsername(), operation.getType(), operation.getAmount(),
						operation.getBalanceAfter(), account.getCurrency(), operation.getTransferGroupId(),
						operation.getCreatedAt()));
	}

	private void claimKey(final String idempotencyKey, final String ownerUsername, final String transferGroupId,
			final String requestFingerprint)
	{
		idempotencyRecordRepository.save(
				new IdempotencyRecord(idempotencyKey, ownerUsername, null, transferGroupId, requestFingerprint));
	}
}
