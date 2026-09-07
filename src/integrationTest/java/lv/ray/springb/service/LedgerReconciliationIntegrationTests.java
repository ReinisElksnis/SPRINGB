package lv.ray.springb.service;

import lv.ray.springb.TestcontainersConfiguration;
import lv.ray.springb.dto.AccountDTO;
import lv.ray.springb.dto.OperationDTO;
import lv.ray.springb.dto.ReconciliationResult;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.entity.Operation;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.repository.OperationRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test that actually matters for {@code LedgerReconciliationServiceImpl}: a real ledger, built
 * through the normal {@code AccountService} mutation path against a real Postgres, then corrupted
 * directly through the repository the same way a rogue manual fix or a future bug in the mutation
 * path could - completely bypassing {@code AccountMutationExecutor}. If reconciliation didn't
 * genuinely replay the ledger, this is the test that would fail to notice.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerReconciliationIntegrationTests
{

	@Autowired
	private AccountService accountService;

	@Autowired
	private LedgerReconciliationService reconciliationService;

	@Autowired
	private OperationRepository operationRepository;

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
		final AppUser newOwner = new AppUser("reconcile-" + suffix, "reconcile-" + suffix + "@example.com",
				passwordEncoder.encode("irrelevant-password"), "Reconciliation Test");
		newOwner.setRole("ROLE_USER");
		owner = appUserRepository.save(newOwner);

		account = accountService.createAccount(owner.getUsername(), "EUR");
	}

	@Test
	void reconciliationSucceedsForARealLedgerBuiltEntirelyThroughTheNormalMutationPath()
	{
		accountService.deposit(account.getId(), owner.getUsername(), new BigDecimal("100.00"),
				"seed-deposit-" + UUID.randomUUID());
		accountService.withdraw(account.getId(), owner.getUsername(), new BigDecimal("30.00"),
				"seed-withdraw-" + UUID.randomUUID());

		final ReconciliationResult result = reconciliationService.reconcileAccount(account.getId());

		assertThat(result.consistent()).isTrue();
		assertThat(result.discrepancies()).isEmpty();
	}

	@Test
	void reconciliationCatchesALedgerRowCorruptedDirectlyInTheDatabase()
	{
		final OperationDTO deposit = accountService.deposit(account.getId(), owner.getUsername(),
				new BigDecimal("100.00"), "seed-deposit-" + UUID.randomUUID());
		accountService.withdraw(account.getId(), owner.getUsername(), new BigDecimal("30.00"),
				"seed-withdraw-" + UUID.randomUUID());

		// Simulate tampering: overwrite the deposit's balanceAfter directly through the repository,
		// never going through AccountMutationExecutor at all.
		final Operation corrupted = operationRepository.findById(deposit.getId()).orElseThrow();
		corrupted.setBalanceAfter(new BigDecimal("999.99"));
		operationRepository.save(corrupted);

		final ReconciliationResult result = reconciliationService.reconcileAccount(account.getId());

		assertThat(result.consistent()).isFalse();
		assertThat(result.discrepancies()).anyMatch(message -> message.contains("Operation " + deposit.getId()));
	}
}
