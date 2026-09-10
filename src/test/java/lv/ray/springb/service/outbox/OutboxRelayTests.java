package lv.ray.springb.service.outbox;

import lv.ray.springb.config.OutboxProperties;
import lv.ray.springb.entity.OutboxMessage;
import lv.ray.springb.entity.OutboxStatus;
import lv.ray.springb.repository.OutboxMessageRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay's state machine, with the broker and the database both mocked. What is being tested is
 * the decision-making - published versus retried versus given up on, and whether one bad message
 * can damage the rest of its batch - rather than any locking behaviour, which is Postgres's and is
 * covered by {@code OutboxRelayIntegrationTests}.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTests
{

	private static final int MAX_ATTEMPTS = 3;

	@Mock
	private OutboxMessageRepository outboxMessageRepository;

	@Mock
	private OutboxDispatcher dispatcher;

	private OutboxRelay relay;

	@BeforeEach
	void setUp()
	{
		final OutboxProperties properties = new OutboxProperties(true, 50, Duration.ofSeconds(1),
				Duration.ofSeconds(1), Duration.ofMinutes(5), MAX_ATTEMPTS);
		relay = new OutboxRelay(outboxMessageRepository, dispatcher, properties);
	}

	private static OutboxMessage message()
	{
		return new OutboxMessage("ACCOUNT", "1", "DEPOSIT", "{}");
	}

	private void claimReturns(final OutboxMessage... messages)
	{
		when(outboxMessageRepository.claimDueBatch(any(LocalDateTime.class), anyInt()))
				.thenReturn(List.of(messages));
	}

	@Test
	void aSuccessfulDispatchMarksTheMessagePublished()
	{
		final OutboxMessage message = message();
		claimReturns(message);

		relay.relayOnce();

		assertThat(message.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
		assertThat(message.getPublishedAt()).isNotNull();
		verify(dispatcher).dispatch(message);
	}

	@Test
	void aFailedDispatchSchedulesARetryInTheFutureAndKeepsTheMessagePending()
	{
		final OutboxMessage message = message();
		claimReturns(message);
		doThrow(new OutboxDispatchException("broker down")).when(dispatcher).dispatch(message);

		final LocalDateTime before = LocalDateTime.now();
		relay.relayOnce();

		assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(message.getAttempts()).isEqualTo(1);
		assertThat(message.getLastError()).contains("broker down");
		// Full jitter can draw zero, so the retry may be scheduled for "now" - it must simply never
		// be scheduled in the past.
		assertThat(message.getNextAttemptAt()).isAfterOrEqualTo(before);
	}

	@Test
	void aMessageIsParkedAsDeadOnceItsAttemptBudgetIsSpent()
	{
		final OutboxMessage message = message();
		doThrow(new OutboxDispatchException("permanently malformed")).when(dispatcher).dispatch(message);

		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++)
		{
			when(outboxMessageRepository.claimDueBatch(any(LocalDateTime.class), anyInt()))
					.thenReturn(List.of(message));
			relay.relayOnce();
		}

		assertThat(message.getAttempts()).isEqualTo(MAX_ATTEMPTS);
		assertThat(message.getStatus()).isEqualTo(OutboxStatus.DEAD);
	}

	/**
	 * The failure this guards is the reason each dispatch is caught individually. With a single
	 * try/catch around the loop, the first exception would abandon the rest of the batch - and
	 * because those messages would never be marked {@code PUBLISHED}, the ones that had already
	 * been published successfully would be published again on the next poll, and again on the one
	 * after that. A duplicate rate that compounds every cycle, from one bad payload.
	 */
	@Test
	void onePoisonedMessageDoesNotStopTheRestOfTheBatch()
	{
		final OutboxMessage first = message();
		final OutboxMessage poisoned = message();
		final OutboxMessage third = message();

		claimReturns(first, poisoned, third);
		doNothing().when(dispatcher).dispatch(first);
		doThrow(new OutboxDispatchException("bad payload")).when(dispatcher).dispatch(poisoned);
		doNothing().when(dispatcher).dispatch(third);

		relay.relayOnce();

		assertThat(first.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
		assertThat(poisoned.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(third.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
		verify(dispatcher).dispatch(third);
	}

	/**
	 * A dispatcher is third-party code and may throw anything, not only the exception this package
	 * defines. If the relay only caught {@link OutboxDispatchException}, an unexpected
	 * {@code NullPointerException} from a broker client would escape the transactional method and
	 * roll the whole batch back - losing the successful publishes' status updates while the
	 * publishes themselves had already happened.
	 */
	@Test
	void anUnexpectedRuntimeExceptionIsTreatedAsAFailedDispatchRatherThanEscaping()
	{
		final OutboxMessage message = message();
		claimReturns(message);
		doThrow(new IllegalStateException("broker client blew up")).when(dispatcher).dispatch(message);

		relay.relayOnce();

		assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(message.getAttempts()).isEqualTo(1);
		assertThat(message.getLastError()).contains("broker client blew up");
	}

	@Test
	void anEmptyBacklogIsANoOp()
	{
		claimReturns();

		assertThat(relay.relayOnce()).isZero();
	}

	/**
	 * {@code last_error} is a 500-character column. An unbounded broker stack trace written into it
	 * would fail the insert, which would roll back the transaction - turning a recoverable dispatch
	 * failure into a relay that cannot record any progress at all.
	 */
	@Test
	void anOversizedErrorMessageIsTruncatedToFitItsColumn()
	{
		final OutboxMessage message = message();
		claimReturns(message);
		doThrow(new OutboxDispatchException("x".repeat(5_000))).when(dispatcher).dispatch(message);

		relay.relayOnce();

		assertThat(message.getLastError()).hasSize(500);
	}
}
