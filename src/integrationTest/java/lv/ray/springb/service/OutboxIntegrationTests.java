package lv.ray.springb.service;

import lv.ray.springb.TestcontainersConfiguration;
import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.entity.OutboxMessage;
import lv.ray.springb.entity.OutboxStatus;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.repository.OutboxMessageRepository;
import lv.ray.springb.service.outbox.OutboxDispatchException;
import lv.ray.springb.service.outbox.OutboxDispatcher;
import lv.ray.springb.service.outbox.OutboxRelay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the two claims the outbox pattern actually rests on, neither of which a mocked test can
 * reach: that an event and the money it describes commit as one unit against a real database, and
 * that {@code SELECT ... FOR UPDATE SKIP LOCKED} lets two relay instances divide a backlog instead
 * of queueing behind one another.
 *
 * <p>The scheduled poll is pushed out to an hour so it cannot publish messages behind these tests'
 * backs; every relay run here is driven explicitly through the autowired (and therefore
 * transactionally proxied) {@link OutboxRelay}. The backoff is squeezed to a millisecond so the
 * retry path can be exercised without the test actually waiting out a production-sized delay.
 */
@SpringBootTest(properties = {
		"springb.outbox.poll-interval=1h",
		"springb.outbox.initial-backoff=1ms",
		"springb.outbox.max-backoff=1ms",
		"springb.outbox.max-attempts=3" })
@Import({ TestcontainersConfiguration.class, OutboxIntegrationTests.ControllableDispatcherConfiguration.class })
class OutboxIntegrationTests
{

	/**
	 * Replaces {@code LoggingOutboxDispatcher} so a test can decide whether "the broker" accepts a
	 * message. Marked {@link Primary} rather than relying on {@code OutboxConfig}'s
	 * {@code @ConditionalOnMissingBean} backing off: that condition is order-sensitive between two
	 * user configurations, and {@code @Primary} settles the injection either way.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class ControllableDispatcherConfiguration
	{
		@Bean
		@Primary
		ControllableDispatcher controllableDispatcher()
		{
			return new ControllableDispatcher();
		}
	}

	static class ControllableDispatcher implements OutboxDispatcher
	{
		private final List<Long> dispatched = new CopyOnWriteArrayList<>();

		private final AtomicInteger delayMillis = new AtomicInteger();

		private volatile boolean failing;

		@Override
		public void dispatch(final OutboxMessage message)
		{
			if (failing)
			{
				throw new OutboxDispatchException("simulated broker failure");
			}

			final int delay = delayMillis.get();
			if (delay > 0)
			{
				try
				{
					Thread.sleep(delay);
				}
				catch (final InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new OutboxDispatchException("interrupted", e);
				}
			}

			dispatched.add(message.getId());
		}

		void reset()
		{
			dispatched.clear();
			failing = false;
			delayMillis.set(0);
		}
	}

	@Autowired
	private AccountService accountService;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private OutboxMessageRepository outboxMessageRepository;

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private ControllableDispatcher dispatcher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private AppUser owner;

	private AccountDTO account;

	@BeforeEach
	void setUp()
	{
		// These tests are not @Transactional (they must observe real commits), so state carries
		// between them unless it is cleared. The outbox is emptied rather than the whole schema
		// because every assertion here is about which messages exist right now.
		outboxMessageRepository.deleteAll();
		dispatcher.reset();

		final String suffix = UUID.randomUUID().toString().substring(0, 8);
		final AppUser newOwner = new AppUser("outbox-" + suffix, "outbox-" + suffix + "@example.com",
				passwordEncoder.encode("irrelevant-password"), "Outbox Test");
		newOwner.setRole("ROLE_USER");
		owner = appUserRepository.save(newOwner);

		account = accountService.createAccount(owner.getUsername(), "EUR");
	}

	@Test
	void aDepositCommitsItsEventTogetherWithTheLedgerRow()
	{
		accountService.deposit(account.getId(), owner.getUsername(), new BigDecimal("100.00"),
				"deposit-" + UUID.randomUUID());

		final List<OutboxMessage> messages = outboxMessageRepository
				.findByAggregateTypeAndAggregateIdOrderByIdAsc("ACCOUNT", String.valueOf(account.getId()));

		assertThat(messages).hasSize(1);
		assertThat(messages.get(0).getEventType()).isEqualTo("DEPOSIT");
		assertThat(messages.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(messages.get(0).getPayload()).contains("\"amount\":100.00");
	}

	/**
	 * The heart of the pattern. A withdrawal that fails validation rolls its transaction back, and
	 * because the event was written by that same transaction it disappears with the money. Under a
	 * dual write - publish to a broker after committing, or before - this is precisely the case
	 * that leaks an event announcing a withdrawal that never happened.
	 */
	@Test
	void aRejectedOperationLeavesNoEventBehind()
	{
		accountService.deposit(account.getId(), owner.getUsername(), new BigDecimal("50.00"),
				"seed-" + UUID.randomUUID());
		final long afterDeposit = outboxMessageRepository.count();

		assertThatThrownBy(() -> accountService.withdraw(account.getId(), owner.getUsername(),
				new BigDecimal("999.00"), "overdraw-" + UUID.randomUUID()))
				.isInstanceOf(AccountException.class);

		assertThat(outboxMessageRepository.count()).isEqualTo(afterDeposit);
	}

	@Test
	void aTransferEmitsOneEventPerLeg()
	{
		final AccountDTO destination = accountService.createAccount(owner.getUsername(), "EUR");
		accountService.deposit(account.getId(), owner.getUsername(), new BigDecimal("100.00"),
				"seed-" + UUID.randomUUID());
		outboxMessageRepository.deleteAll();

		accountService.transfer(account.getId(), destination.getId(), owner.getUsername(),
				new BigDecimal("40.00"), "transfer-" + UUID.randomUUID());

		final List<OutboxMessage> messages = outboxMessageRepository.findAll();

		assertThat(messages).hasSize(2);
		assertThat(messages).extracting(OutboxMessage::getEventType)
				.containsExactlyInAnyOrder("TRANSFER_OUT", "TRANSFER_IN");
		assertThat(messages).extracting(OutboxMessage::getAggregateId)
				.containsExactlyInAnyOrder(String.valueOf(account.getId()),
						String.valueOf(destination.getId()));
	}

	@Test
	void theRelayPublishesPendingMessagesAndMarksThemPublished()
	{
		accountService.deposit(account.getId(), owner.getUsername(), new BigDecimal("10.00"),
				"deposit-" + UUID.randomUUID());

		assertThat(outboxRelay.relayOnce()).isEqualTo(1);

		assertThat(outboxMessageRepository.countByStatus(OutboxStatus.PENDING)).isZero();
		assertThat(outboxMessageRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(1);
		assertThat(dispatcher.dispatched).hasSize(1);
	}

	@Test
	void aMessageIsRetriedWhileTheBrokerFailsAndParkedAsDeadOnceItsBudgetIsSpent()
	{
		accountService.deposit(account.getId(), owner.getUsername(), new BigDecimal("10.00"),
				"deposit-" + UUID.randomUUID());
		dispatcher.failing = true;

		// max-attempts is 3 for this class, and the backoff is 1ms, so the message is due again by
		// the time the next poll runs.
		for (int poll = 0; poll < 3; poll++)
		{
			assertThat(outboxRelay.relayOnce()).isEqualTo(1);
		}

		assertThat(outboxMessageRepository.countByStatus(OutboxStatus.DEAD)).isEqualTo(1);

		// And a dead message is never claimed again - it must not starve the queue behind it.
		assertThat(outboxRelay.relayOnce()).isZero();
	}

	@Test
	void aMessageThatFailedEarlierIsPublishedOnceTheBrokerRecovers()
	{
		accountService.deposit(account.getId(), owner.getUsername(), new BigDecimal("10.00"),
				"deposit-" + UUID.randomUUID());

		dispatcher.failing = true;
		outboxRelay.relayOnce();
		assertThat(outboxMessageRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);

		dispatcher.failing = false;
		outboxRelay.relayOnce();
		assertThat(outboxMessageRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(1);
	}

	/**
	 * The {@code SKIP LOCKED} proof, and the reason it is written as a hanging-claim rather than as
	 * two racing relays: with plain {@code FOR UPDATE} the second claim would <em>block</em> on the
	 * first transaction's row locks rather than return wrong data, so the observable difference is
	 * a stall, not a bad value. Two threads simply racing would pass either way.
	 *
	 * <p>So one transaction claims a batch and is held open, and the second claim is then given a
	 * timeout. Under {@code SKIP LOCKED} it steps over the locked rows and returns the next ones
	 * immediately; remove {@code SKIP LOCKED} from the query and this test fails on the timeout.
	 * That is the difference between two relay instances sharing a backlog and one of them being a
	 * spectator.
	 */
	@Test
	void skipLockedLetsASecondClaimStepOverLockedRowsInsteadOfBlockingOnThem() throws Exception
	{
		final List<OutboxMessage> seeded = List.of(
				new OutboxMessage("ACCOUNT", "1", "DEPOSIT", "{}"),
				new OutboxMessage("ACCOUNT", "2", "DEPOSIT", "{}"),
				new OutboxMessage("ACCOUNT", "3", "DEPOSIT", "{}"),
				new OutboxMessage("ACCOUNT", "4", "DEPOSIT", "{}"));
		outboxMessageRepository.saveAll(seeded);

		final CountDownLatch firstHasClaimed = new CountDownLatch(1);
		final CountDownLatch releaseFirst = new CountDownLatch(1);
		final ExecutorService pool = Executors.newFixedThreadPool(2);

		try
		{
			final Future<List<Long>> firstClaim = pool.submit(() ->
					new TransactionTemplate(transactionManager).execute(status ->
					{
						final List<Long> ids = claimIds(2);
						firstHasClaimed.countDown();
						awaitQuietly(releaseFirst);
						return ids;
					}));

			assertThat(firstHasClaimed.await(10, TimeUnit.SECONDS)).isTrue();

			final Future<List<Long>> secondClaim = pool.submit(() ->
					new TransactionTemplate(transactionManager).execute(status -> claimIds(2)));

			// Times out here if the query ever loses SKIP LOCKED.
			final List<Long> secondIds = secondClaim.get(10, TimeUnit.SECONDS);

			releaseFirst.countDown();
			final List<Long> firstIds = firstClaim.get(10, TimeUnit.SECONDS);

			assertThat(firstIds).hasSize(2);
			assertThat(secondIds).hasSize(2);
			assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
		}
		finally
		{
			releaseFirst.countDown();
			pool.shutdownNow();
		}
	}

	private List<Long> claimIds(final int batchSize)
	{
		return outboxMessageRepository
				.claimDueBatch(java.time.LocalDateTime.now().plusMinutes(1), batchSize)
				.stream()
				.map(OutboxMessage::getId)
				.toList();
	}

	private static void awaitQuietly(final CountDownLatch latch)
	{
		try
		{
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (final InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
