package lv.ray.springb.service;

import lv.ray.springb.TestcontainersConfiguration;
import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.OptimisticTransferResultDTO;
import lv.ray.springb.entity.Account;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.repository.AccountRepository;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.service.validation.AccountOperationValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * The optimistic transfer path, demonstrated end to end against a real Postgres.
 *
 * <h2>Why a spy is used to create the conflicts</h2>
 * The obvious way to test optimistic locking is to fire a dozen threads at one account and hope they
 * collide. That produces a test that passes for the wrong reason on a fast machine and flakes on a
 * loaded CI runner, and - worse - one that still passes if the version check is deleted, because
 * without contention there is nothing to detect.
 *
 * <p>So most tests here create the race <em>deterministically</em>. {@link AccountOperationValidator}
 * is spied on and used as a timing seam: {@code validateSameCurrency} runs after the executor has
 * read both accounts but before it writes anything, which is exactly the window a competing
 * transaction has to slip through. Committing a change from another thread at that instant
 * guarantees the version the attempt read is stale by the time it flushes. The conflict is then a
 * fact of the test, not a hope.
 *
 * <p>{@code concurrentTransfersFromOneAccountAllSucceed} keeps the realistic version too, because
 * "does it hold up under genuine parallel load" is a different question from "is the mechanism
 * wired correctly", and both are worth answering.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OptimisticTransferIntegrationTests
{

	private static final BigDecimal SEED = new BigDecimal("100.00");

	/** Amount the competing transaction adds each time it interferes. */
	private static final BigDecimal INTERFERENCE = new BigDecimal("1.00");

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/**
	 * A spy, not a mock: every call still runs the real validation, and only the specific tests
	 * below add a side effect to one method.
	 */
	@MockitoSpyBean
	private AccountOperationValidator validator;

	private AppUser owner;

	private AccountDTO source;

	private AccountDTO destination;

	@BeforeEach
	void setUp()
	{
		reset(validator);

		final String suffix = UUID.randomUUID().toString().substring(0, 8);
		final AppUser newOwner = new AppUser("optimistic-" + suffix, "optimistic-" + suffix + "@example.com",
				passwordEncoder.encode("irrelevant-password"), "Optimistic Test");
		newOwner.setRole("ROLE_USER");
		owner = appUserRepository.save(newOwner);

		source = accountService.createAccount(owner.getUsername(), "EUR");
		destination = accountService.createAccount(owner.getUsername(), "EUR");
		accountService.deposit(source.getId(), owner.getUsername(), SEED, "seed-" + suffix);
	}

	@Test
	void anUncontendedTransferSucceedsOnTheFirstAttempt()
	{
		final OptimisticTransferResultDTO result = transfer(new BigDecimal("30.00"));

		assertThat(result.attempts()).isEqualTo(1);
		assertThat(result.contended()).isFalse();
		assertThat(balanceOf(source.getId())).isEqualByComparingTo("70.00");
		assertThat(balanceOf(destination.getId())).isEqualByComparingTo("30.00");
	}

	/**
	 * The version counter must actually advance, or every other assertion here is vacuous - a
	 * version stuck at 0 would make each UPDATE's {@code WHERE version = 0} keep matching and the
	 * conflict would never be detected.
	 */
	@Test
	void everyWriteAdvancesTheAccountVersion()
	{
		final long before = versionOf(source.getId());

		transfer(new BigDecimal("10.00"));

		assertThat(versionOf(source.getId())).isGreaterThan(before);
	}

	/**
	 * The core demonstration. One competing commit lands between the first attempt's read and its
	 * flush; that attempt's versioned UPDATE therefore matches zero rows and is rolled back, and the
	 * retry re-reads the now-current state and succeeds.
	 *
	 * <p>The balance assertion is the part that shows the retry is *correct* and not merely
	 * *successful*: the retry re-read the balance the interfering transaction wrote, so the
	 * interference is still present in the total. Had the first attempt been allowed to commit on
	 * its stale read, that +1 would have been silently overwritten - which is the lost update this
	 * whole mechanism exists to prevent.
	 */
	@Test
	void aConflictOnTheFirstAttemptIsDetectedAndTheRetrySucceeds()
	{
		interfereOnFirstAttemptOnly();

		final OptimisticTransferResultDTO result = transfer(new BigDecimal("30.00"));

		assertThat(result.attempts()).isEqualTo(2);
		assertThat(result.contended()).isTrue();

		// 100 seed + 1 interference - 30 transferred. The +1 survives, so nothing was lost.
		assertThat(balanceOf(source.getId())).isEqualByComparingTo("71.00");
		assertThat(balanceOf(destination.getId())).isEqualByComparingTo("30.00");
	}

	/**
	 * The budget exists so a permanently contended row cannot pin a request thread forever. Here
	 * every attempt is made to lose, so all five are spent and the caller gets a 409 telling it to
	 * retry later - and, importantly, no partial transfer is left behind.
	 */
	@Test
	void losingEveryAttemptExhaustsTheBudgetAndAnswers409WithoutMovingAnyMoney()
	{
		interfereOnEveryAttempt();

		assertThatThrownBy(() -> transfer(new BigDecimal("30.00")))
				.isInstanceOf(AccountException.class)
				.hasMessageContaining("5 attempts");

		// Only the interference moved the balance; the transfer itself committed nothing.
		assertThat(balanceOf(source.getId())).isEqualByComparingTo("105.00");
		assertThat(balanceOf(destination.getId())).isEqualByComparingTo("0.00");
	}

	/**
	 * The idempotency contract is unchanged by the locking strategy - a replay returns the original
	 * result rather than transferring twice. Reported as one attempt because a replay does no work
	 * and contends with nothing.
	 */
	@Test
	void replayingAnIdempotencyKeyReturnsTheOriginalTransferRatherThanRepeatingIt()
	{
		final String key = "replay-" + UUID.randomUUID();

		final OptimisticTransferResultDTO first = accountService.transferOptimistic(source.getId(),
				destination.getId(), owner.getUsername(), new BigDecimal("25.00"), key);
		final OptimisticTransferResultDTO replay = accountService.transferOptimistic(source.getId(),
				destination.getId(), owner.getUsername(), new BigDecimal("25.00"), key);

		assertThat(replay.debit().getTransferGroupId()).isEqualTo(first.debit().getTransferGroupId());
		assertThat(replay.attempts()).isEqualTo(1);
		assertThat(balanceOf(source.getId())).isEqualByComparingTo("75.00");
	}

	/**
	 * Insufficient funds is a fact about the request, not a race. It must fail immediately rather
	 * than burning the whole retry budget rediscovering the same answer - which is why the retry
	 * policy discriminates on Spring's transient/non-transient split rather than catching
	 * everything.
	 */
	@Test
	void aDomainRejectionFailsImmediatelyWithoutConsumingTheRetryBudget()
	{
		assertThatThrownBy(() -> transfer(new BigDecimal("999.00")))
				.isInstanceOf(AccountException.class)
				.hasMessageContaining("insufficient funds");

		assertThat(balanceOf(source.getId())).isEqualByComparingTo(SEED);
	}

	/**
	 * The realistic counterpart to the deterministic tests: genuine parallel load, no seam, no
	 * stubbing. Ten concurrent transfers out of one account must all succeed and the arithmetic must
	 * come out exactly - which is only possible if every conflict was both detected and retried. If
	 * the version check were removed, lost updates would leave the source with more money than it
	 * should have.
	 */
	@Test
	void concurrentTransfersFromOneAccountAllSucceedAndTheArithmeticIsExact() throws Exception
	{
		final int transfers = 10;
		final BigDecimal each = new BigDecimal("5.00");

		final ExecutorService pool = Executors.newFixedThreadPool(transfers);
		final CountDownLatch startTogether = new CountDownLatch(1);
		final AtomicInteger totalAttempts = new AtomicInteger();

		try
		{
			final List<Future<OptimisticTransferResultDTO>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < transfers; i++)
			{
				futures.add(pool.submit(() ->
				{
					startTogether.await(10, TimeUnit.SECONDS);
					return transfer(each);
				}));
			}

			startTogether.countDown();

			for (final Future<OptimisticTransferResultDTO> future : futures)
			{
				totalAttempts.addAndGet(future.get(60, TimeUnit.SECONDS).attempts());
			}
		}
		finally
		{
			pool.shutdownNow();
		}

		assertThat(balanceOf(source.getId())).isEqualByComparingTo("50.00");
		assertThat(balanceOf(destination.getId())).isEqualByComparingTo("50.00");
		// At minimum one attempt each; anything above that is contention that was retried away.
		assertThat(totalAttempts.get()).isGreaterThanOrEqualTo(transfers);
	}

	// ---------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------

	private OptimisticTransferResultDTO transfer(final BigDecimal amount)
	{
		return accountService.transferOptimistic(source.getId(), destination.getId(), owner.getUsername(),
				amount, "transfer-" + UUID.randomUUID());
	}

	/**
	 * Installs the timing seam. {@code validateSameCurrency} is called after both accounts have been
	 * read and before either is modified, so a commit made here is guaranteed to land inside the
	 * attempt's read-to-flush window.
	 */
	private void interfereOnFirstAttemptOnly()
	{
		final AtomicBoolean firstAttempt = new AtomicBoolean(true);
		installInterference(() -> firstAttempt.compareAndSet(true, false));
	}

	private void interfereOnEveryAttempt()
	{
		installInterference(() -> true);
	}

	private void installInterference(final java.util.function.BooleanSupplier shouldInterfere)
	{
		doAnswer(invocation ->
		{
			if (shouldInterfere.getAsBoolean())
			{
				bumpSourceBalanceInACommittedTransaction();
			}
			return invocation.callRealMethod();
		}).when(validator).validateSameCurrency(any(Account.class), any(Account.class));
	}

	/**
	 * Runs a competing update on <em>another thread</em>, and waits for it. Another thread is
	 * required rather than merely another transaction: the caller is inside the attempt's
	 * transaction on this thread, and Spring binds a transaction (and its connection) to the thread,
	 * so a nested attempt here would either join the attempt's own transaction - changing nothing
	 * another transaction could see - or need its own connection anyway.
	 */
	private void bumpSourceBalanceInACommittedTransaction()
	{
		final ExecutorService competitor = Executors.newSingleThreadExecutor();
		try
		{
			final Callable<Void> task = () -> new TransactionTemplate(transactionManager).execute(status ->
			{
				final Account account = accountRepository.findById(source.getId()).orElseThrow();
				account.setBalance(account.getBalance().add(INTERFERENCE));
				accountRepository.saveAndFlush(account);
				return null;
			});
			competitor.submit(task).get(10, TimeUnit.SECONDS);
		}
		catch (final InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		catch (final Exception e)
		{
			throw new IllegalStateException("competing transaction failed", e);
		}
		finally
		{
			competitor.shutdown();
		}
	}

	private BigDecimal balanceOf(final Long accountId)
	{
		return accountRepository.findById(accountId).orElseThrow().getBalance();
	}

	private long versionOf(final Long accountId)
	{
		return accountRepository.findById(accountId).orElseThrow().getVersion();
	}
}
