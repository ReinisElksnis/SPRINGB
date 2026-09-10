package lv.ray.springb.service.outbox;

import lv.ray.springb.config.OutboxProperties;
import lv.ray.springb.entity.OutboxMessage;
import lv.ray.springb.repository.OutboxMessageRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;


/**
 * Drains the outbox: claims a batch of due messages, hands each to the {@link OutboxDispatcher},
 * and records the outcome. Everything interesting about this class is in the boundaries it draws,
 * so they are spelled out below.
 *
 * <h2>Why {@code fixedDelay} and not {@code fixedRate}</h2>
 * {@code fixedRate} starts a run every N milliseconds regardless of whether the previous run has
 * finished. Spring's default scheduler is single-threaded, so overrunning executions do not
 * actually overlap - they queue, and the queue is unbounded. A broker that starts responding slowly
 * would build a backlog of pending <em>runs</em> in memory on top of the backlog of pending
 * messages in the database, and the relay would keep firing catch-up executions long after the
 * broker recovered. {@code fixedDelay} measures the gap from the <em>end</em> of one run to the
 * start of the next, so a slow broker simply slows the polling down. Self-limiting instead of
 * self-amplifying.
 *
 * <h2>Why the dispatch happens inside the transaction</h2>
 * Holding a database transaction open across network I/O is normally a mistake, and here it is the
 * point. The claim in {@link OutboxMessageRepository#claimDueBatch} only holds its row locks until
 * the transaction ends, so if this method committed before dispatching, the messages would be
 * unlocked and another relay instance could claim and publish the same batch concurrently. Keeping
 * the transaction open for the dispatch is what makes the claim mean anything.
 *
 * <p>The cost is real and is paid for with the batch size: locks are held for as long as the whole
 * batch takes to publish, so {@code springb.outbox.batch-size} is really a "how long may one
 * instance hold part of the queue" dial. It is also why a dispatcher must have its own timeout - a
 * broker client that blocks forever would pin these rows forever, and the relay would stop dead
 * without any error to show for it.
 *
 * <h2>Why one failure does not fail the batch</h2>
 * Each dispatch is caught individually. A shared try/catch around the loop would mean one poisoned
 * message discards the successful publishes that came after it in the same batch - and since those
 * rows are then never marked {@code PUBLISHED}, they would be republished on the next poll,
 * multiplying duplicates every cycle.
 *
 * <h2>Why there is no explicit save</h2>
 * The claimed messages are managed entities inside this transaction, so mutating them is enough:
 * Hibernate's dirty checking writes the changes out at flush, immediately before commit. Calling
 * {@code save()} would be a no-op that merely looks reassuring.
 */
@Component
@ConditionalOnProperty(name = "springb.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay
{

	private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

	/** Matches {@code outbox_messages.last_error}'s column width. */
	private static final int MAX_ERROR_LENGTH = 500;

	private final OutboxMessageRepository outboxMessageRepository;

	private final OutboxDispatcher dispatcher;

	private final OutboxProperties properties;

	public OutboxRelay(final OutboxMessageRepository outboxMessageRepository, final OutboxDispatcher dispatcher,
			final OutboxProperties properties)
	{
		this.outboxMessageRepository = outboxMessageRepository;
		this.dispatcher = dispatcher;
		this.properties = properties;
	}

	/**
	 * One poll. Package-private return value is the number of messages claimed, so integration
	 * tests can drive the relay directly and assert on progress rather than sleeping and hoping.
	 *
	 * <p>{@code @Scheduled} and {@code @Transactional} coexist on one method safely because the
	 * scheduler holds the <em>proxy</em>, not the raw bean - the transactional advice is applied on
	 * the way in. That is the same proxy mechanism that silently does nothing when a bean calls its
	 * own {@code @Transactional} method internally.
	 */
	@Scheduled(fixedDelayString = "${springb.outbox.poll-interval:1s}")
	@Transactional
	public int relayOnce()
	{
		final List<OutboxMessage> claimed =
				outboxMessageRepository.claimDueBatch(LocalDateTime.now(), properties.batchSize());

		for (final OutboxMessage message : claimed)
		{
			try
			{
				dispatcher.dispatch(message);
				message.markPublished();
			}
			catch (final RuntimeException e)
			{
				recordFailure(message, e);
			}
		}

		return claimed.size();
	}

	private void recordFailure(final OutboxMessage message, final RuntimeException e)
	{
		final String error = truncate(e.toString());

		// attempts is the count *before* this failure, so the budget is spent when the increment
		// this failure is about to apply would reach maxAttempts.
		if (message.getAttempts() + 1 >= properties.maxAttempts())
		{
			message.markDead(error);
			LOG.error("[outbox] message {} dead after {} attempts: {}", message.getId(),
					message.getAttempts(), error);
			return;
		}

		final Duration delay = RetryBackoff.nextDelay(message.getAttempts(), properties.initialBackoff(),
				properties.maxBackoff());
		message.markFailed(LocalDateTime.now().plus(delay), error);
		LOG.warn("[outbox] message {} failed (attempt {}), retrying in {}ms: {}", message.getId(),
				message.getAttempts(), delay.toMillis(), error);
	}

	private static String truncate(final String error)
	{
		return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
	}
}
