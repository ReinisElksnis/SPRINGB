package lv.ray.springb.service.impl;

import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.dto.LedgerEntry;
import lv.ray.springb.dto.ReconciliationResult;
import lv.ray.springb.entity.Account;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.repository.OperationRepository;
import lv.ray.springb.service.AccountException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;


/**
 * Replays one account's ledger in exactly one transaction per call. Extracted from
 * {@code LedgerReconciliationServiceImpl} for two reasons, both of which are worth understanding
 * because they are the kind of thing that looks like refactoring and is actually a bug fix.
 *
 * <h2>Self-invocation silently discarded the transaction boundary</h2>
 * {@code reconcileAllAccounts()} used to call {@code this.reconcileAccount(id)} in a loop. Both
 * methods were annotated {@code @Transactional}, which reads as "each account gets its own
 * transaction" - but Spring's declarative transactions are implemented with a proxy that wraps the
 * bean, and an internal {@code this.} call never leaves the object, so it never passes through that
 * proxy. The inner annotation was inert. Every account was in fact replayed inside the single outer
 * transaction, which is precisely why the persistence context grew without bound across the whole
 * sweep instead of being reset per account. Calling across a bean boundary is what makes the
 * boundary real.
 *
 * <h2>The transaction is the lifetime of the memory</h2>
 * A persistence context lives as long as its transaction and holds everything loaded into it. A
 * transaction that spans every account is therefore also a heap allocation that spans every
 * account. {@code REQUIRES_NEW} bounds the replay's memory to a single account no matter how the
 * caller is written - the same reasoning {@code AccountMutationExecutor} uses for the same
 * annotation, applied to memory rather than to rollback semantics.
 */
@Component
public class AccountLedgerReplayer
{

	private final AccountRepository accountRepository;

	private final OperationRepository operationRepository;

	public AccountLedgerReplayer(final AccountRepository accountRepository,
			final OperationRepository operationRepository)
	{
		this.accountRepository = accountRepository;
		this.operationRepository = operationRepository;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public ReconciliationResult replay(final Long accountId)
	{
		final Account account = accountRepository.findById(accountId)
				.orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
						String.format(Messages.FUNDS_ACCOUNT_NOT_FOUND, accountId)));

		// Read out of the entity once, up front, rather than touching it after the stream has been
		// walked: the comparison at the end needs a value, not a live handle on a managed object.
		final BigDecimal recordedBalance = account.getBalance();

		final List<String> discrepancies = new ArrayList<>();
		BigDecimal expectedRunningBalance = BigDecimal.ZERO;

		// try-with-resources, not a bare stream: this one is backed by an open server-side cursor
		// and a JDBC connection, and neither is released by the stream simply going out of scope.
		// Leaking them exhausts the connection pool long before it exhausts the heap - a slower and
		// far more confusing outage than the one this streaming was introduced to prevent.
		try (Stream<LedgerEntry> entries = operationRepository.streamLedgerEntries(accountId))
		{
			for (final LedgerEntry entry : (Iterable<LedgerEntry>) entries::iterator)
			{
				expectedRunningBalance = expectedRunningBalance.add(signedAmount(entry));

				if (expectedRunningBalance.compareTo(entry.balanceAfter()) != 0)
				{
					discrepancies.add(String.format(
							"Operation %d (%s %s): ledger math gives a running balance of %s, but %s was recorded as balanceAfter",
							entry.id(), entry.type(), entry.amount(), expectedRunningBalance,
							entry.balanceAfter()));
					// Resync to what was actually recorded so one bad row is reported once, rather
					// than every operation after it also being flagged as a cascading false
					// positive.
					expectedRunningBalance = entry.balanceAfter();
				}
			}
		}

		if (expectedRunningBalance.compareTo(recordedBalance) != 0)
		{
			discrepancies.add(String.format(
					"Account %d balance is %s, but the ledger's final balance is %s",
					accountId, recordedBalance, expectedRunningBalance));
		}

		return new ReconciliationResult(accountId, discrepancies.isEmpty(), discrepancies);
	}

	private static BigDecimal signedAmount(final LedgerEntry entry)
	{
		return switch (entry.type())
		{
			case DEPOSIT, TRANSFER_IN -> entry.amount();
			case WITHDRAWAL, TRANSFER_OUT -> entry.amount().negate();
		};
	}
}
