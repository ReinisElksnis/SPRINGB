package lv.ray.springb.service.impl;

import lv.ray.springb.dto.ReconciliationResult;
import lv.ray.springb.entity.Account;
import lv.ray.springb.entity.Operation;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.service.LedgerReconciliationService;

import org.springframework.stereotype.Service;

import java.util.List;


/**
 * Verifies an account's ledger is internally consistent by replaying it from scratch, rather than
 * trusting the cached {@link Account#getBalance()} on its own: that column is written by the same
 * code path that appends each {@link Operation}, so a bug there could move both in lockstep and a
 * balance-only check would never notice. Replaying the ledger and comparing every step against
 * what was actually recorded catches two distinct failure modes a balance comparison alone cannot -
 * a row that was altered or deleted after being committed (tampering, a bad manual fix), and a
 * balance that was ever written by anything other than the normal mutation path.
 *
 * <p>The replay itself lives in {@link AccountLedgerReplayer}; this class only decides which
 * accounts to replay. Note that nothing here is {@code @Transactional} - deliberately, and it is
 * the opposite of the usual instinct. A transaction opened at this level would enclose every
 * account's replay in one long-lived unit of work, which is exactly the shape that made the old
 * implementation accumulate the whole database in one persistence context. Leaving this method
 * untransacted is what lets each replay be its own short transaction that releases its memory and
 * its connection when it finishes.
 */
@Service
public class LedgerReconciliationServiceImpl implements LedgerReconciliationService
{

	private final AccountRepository accountRepository;

	private final AccountLedgerReplayer replayer;

	public LedgerReconciliationServiceImpl(final AccountRepository accountRepository,
			final AccountLedgerReplayer replayer)
	{
		this.accountRepository = accountRepository;
		this.replayer = replayer;
	}

	@Override
	public ReconciliationResult reconcileAccount(final Long accountId)
	{
		return replayer.replay(accountId);
	}

	/**
	 * Sweeps every account, one transaction each.
	 *
	 * <p>Identifiers are fetched rather than entities: the sweep needs nothing from an
	 * {@link Account} except which ledger to replay, and a list of boxed longs is a few tens of
	 * bytes per account against a fully hydrated entity plus its persistence-context snapshot.
	 *
	 * <p><b>What is still unbounded here, honestly:</b> both the id list and the returned results
	 * grow with the number of accounts. That is a far smaller coefficient than the old
	 * "every operation of every account" behaviour, and it is fine at this application's scale -
	 * but it is the same shape of problem, and at a few million accounts the answer would be to
	 * page through the ids and stream the results out to wherever they are going rather than
	 * collecting them into a list at all. Worth naming rather than leaving as a lurking surprise.
	 */
	@Override
	public List<ReconciliationResult> reconcileAllAccounts()
	{
		return accountRepository.findAllAccountIds().stream()
				.map(replayer::replay)
				.toList();
	}
}
