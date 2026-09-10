package lv.ray.springb.service.impl;

import lv.ray.springb.dto.ReconciliationResult;
import lv.ray.springb.repository.AccountRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The replay math is {@link AccountLedgerReplayerTests}'. What is left to test here is the part
 * this class is actually responsible for: which accounts get swept, and - the point of the split -
 * that it delegates instead of replaying inline, since an inline call would be a self-invocation
 * that silently bypasses the proxy and collapses every account back into one transaction.
 */
@ExtendWith(MockitoExtension.class)
class LedgerReconciliationServiceImplTests
{

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private AccountLedgerReplayer replayer;

	private LedgerReconciliationServiceImpl reconciliationService;

	@BeforeEach
	void setUp()
	{
		reconciliationService = new LedgerReconciliationServiceImpl(accountRepository, replayer);
	}

	private static ReconciliationResult consistent(final Long accountId)
	{
		return new ReconciliationResult(accountId, true, List.of());
	}

	@Test
	void reconcileAccount_delegatesToTheReplayer()
	{
		final ReconciliationResult expected = consistent(10L);
		when(replayer.replay(10L)).thenReturn(expected);

		assertThat(reconciliationService.reconcileAccount(10L)).isSameAs(expected);
	}

	@Test
	void reconcileAllAccounts_replaysEveryAccountSeparately()
	{
		when(accountRepository.findAllAccountIds()).thenReturn(List.of(10L, 20L));
		when(replayer.replay(10L)).thenReturn(consistent(10L));
		when(replayer.replay(20L)).thenReturn(consistent(20L));

		final List<ReconciliationResult> results = reconciliationService.reconcileAllAccounts();

		assertThat(results).hasSize(2);
		assertThat(results).allMatch(ReconciliationResult::consistent);

		// One replay call per account, each of which is a separate REQUIRES_NEW transaction - this
		// is what bounds the sweep's memory to a single account at a time.
		verify(replayer).replay(10L);
		verify(replayer).replay(20L);
		verifyNoMoreInteractions(replayer);
	}

	/**
	 * Guards the memory fix directly: the sweep must ask for identifiers, never hydrate every
	 * account entity to read one field off each.
	 */
	@Test
	void reconcileAllAccounts_fetchesIdentifiersRatherThanEntities()
	{
		when(accountRepository.findAllAccountIds()).thenReturn(List.of());

		reconciliationService.reconcileAllAccounts();

		verify(accountRepository).findAllAccountIds();
		verifyNoMoreInteractions(accountRepository);
	}
}
