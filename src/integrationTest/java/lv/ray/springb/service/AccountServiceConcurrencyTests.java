package lv.ray.springb.service;

import lv.ray.springb.TestcontainersConfiguration;
import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.OperationDTO;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.entity.OperationType;
import lv.ray.springb.repository.AppUserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises {@code AccountMutationExecutor} against a real Postgres (see
 * {@link TestcontainersConfiguration}) - these prove the pessimistic-locking and idempotency-key
 * mechanisms actually hold under real concurrent transactions, which a mocked repository could
 * not. The container is disposable and destroyed with the test JVM, so nothing here needs
 * cleaning up afterwards, and none of it touches the local dev database.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountServiceConcurrencyTests
{

	@Autowired
	private AccountService accountService;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private AppUser owner;
	private AccountDTO account;

	@BeforeEach
	void setUp()
	{
		final String suffix = UUID.randomUUID().toString().substring(0, 8);
		final AppUser newOwner = new AppUser("concurrency-" + suffix, "concurrency-" + suffix + "@example.com",
				passwordEncoder.encode("irrelevant-password"), "Concurrency Test");
		newOwner.setRole("ROLE_USER");
		owner = appUserRepository.save(newOwner);

		account = accountService.createAccount(owner.getUsername(), "EUR");
		accountService.deposit(account.getId(), owner.getUsername(), new BigDecimal("100.00"), "seed-" + suffix);
	}

	@Test
	void concurrentWithdrawalsNeverOverdrawTheAccount() throws InterruptedException
	{
		final int attempts = 20;
		final BigDecimal amountPerAttempt = new BigDecimal("10.00"); // 20 * 10.00 = 200.00 against a 100.00 balance

		final ExecutorService pool = Executors.newFixedThreadPool(10);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(attempts);
		final AtomicInteger succeeded = new AtomicInteger();
		final AtomicInteger rejected = new AtomicInteger();

		for (int i = 0; i < attempts; i++)
		{
			final String idempotencyKey = "withdraw-" + i + "-" + UUID.randomUUID();
			pool.submit(() ->
			{
				try
				{
					start.await();
					accountService.withdraw(account.getId(), owner.getUsername(), amountPerAttempt, idempotencyKey);
					succeeded.incrementAndGet();
				}
				catch (final AccountException insufficientFunds)
				{
					rejected.incrementAndGet();
				}
				catch (final InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
				finally
				{
					done.countDown();
				}
			});
		}

		start.countDown();
		assertEquals(true, done.await(30, TimeUnit.SECONDS));
		pool.shutdown();

		// Exactly 10 of the 20 concurrent withdrawals can be satisfied by a 100.00 balance.
		assertEquals(10, succeeded.get());
		assertEquals(10, rejected.get());

		final AccountDTO finalState = accountService.getAccount(account.getId(), owner.getUsername());
		assertEquals(0, BigDecimal.ZERO.compareTo(finalState.getBalance()));

		final List<OperationDTO> operations = accountService.getOperations(account.getId(), owner.getUsername());
		final long withdrawalCount = operations.stream().filter(op -> op.getType() == OperationType.WITHDRAWAL)
				.count();
		assertEquals(10, withdrawalCount);
	}

	@Test
	void retryingWithTheSameIdempotencyKeyDoesNotDoubleDebit()
	{
		final String idempotencyKey = "retry-" + UUID.randomUUID();

		final OperationDTO first = accountService.withdraw(account.getId(), owner.getUsername(),
				new BigDecimal("30.00"), idempotencyKey);
		final OperationDTO retry = accountService.withdraw(account.getId(), owner.getUsername(),
				new BigDecimal("30.00"), idempotencyKey);

		assertNotNull(first.getId());
		assertEquals(first.getId(), retry.getId());

		final AccountDTO finalState = accountService.getAccount(account.getId(), owner.getUsername());
		assertEquals(0, new BigDecimal("70.00").compareTo(finalState.getBalance()));
	}
}
