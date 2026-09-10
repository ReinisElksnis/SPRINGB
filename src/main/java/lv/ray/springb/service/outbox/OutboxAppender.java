package lv.ray.springb.service.outbox;

import lv.ray.springb.entity.OutboxMessage;
import lv.ray.springb.repository.OutboxMessageRepository;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


/**
 * Appends an event to the outbox <em>inside the caller's transaction</em>.
 *
 * <p>Note the absence of {@code @Transactional} on {@link #append}. That is the single most
 * important line of this class, and it is a line that is not here. Spring's default propagation is
 * {@code REQUIRED}, so with no annotation at all this method joins whatever transaction
 * {@code AccountMutationExecutor} already has open - which means the outbox row and the balance
 * change are written by the same transaction and commit or roll back as one unit. That co-commit
 * is the entire reason the outbox pattern works.
 *
 * <p>It is worth contrasting this with {@code AccountMutationExecutor}, which annotates every
 * method {@code REQUIRES_NEW} for exactly the opposite reason: it <em>needs</em> a transaction
 * boundary of its own so that a lost idempotency race rolls back only the failed attempt. Same
 * codebase, same framework, opposite propagation - because one wants isolation from its caller and
 * the other wants to be inseparable from it. Putting {@code REQUIRES_NEW} on this method would
 * quietly reintroduce the dual-write bug the outbox exists to remove: the event would commit in its
 * own transaction, and a rollback of the money afterwards would leave an event announcing a
 * transfer that never happened.
 */
@Component
public class OutboxAppender
{

	private final OutboxMessageRepository outboxMessageRepository;

	private final ObjectMapper objectMapper;

	public OutboxAppender(final OutboxMessageRepository outboxMessageRepository, final ObjectMapper objectMapper)
	{
		this.outboxMessageRepository = outboxMessageRepository;
		this.objectMapper = objectMapper;
	}

	public void append(final String aggregateType, final String aggregateId, final String eventType,
			final Object payload)
	{
		outboxMessageRepository.save(
				new OutboxMessage(aggregateType, aggregateId, eventType, serialise(payload)));
	}

	/**
	 * Jackson 3 (Spring Boot 4's default) made its exceptions unchecked - {@code JacksonException}
	 * extends {@code RuntimeException}, where Jackson 2's {@code JsonProcessingException} was
	 * checked - so nothing forces this to be caught at all. It is caught anyway, to attach the
	 * payload type to the message; an unchecked serialisation failure that escapes with only a field
	 * name in it is painful to diagnose.
	 *
	 * <p>Serialisation failure is rethrown, not swallowed and logged. Swallowing it would let the
	 * money commit without its event - silently recreating the exact inconsistency this class
	 * exists to prevent - whereas failing loudly rolls the whole operation back, and a caller
	 * retrying a rejected request is a far better outcome than a ledger nobody downstream ever
	 * hears about.
	 */
	private String serialise(final Object payload)
	{
		try
		{
			return objectMapper.writeValueAsString(payload);
		}
		catch (final JacksonException e)
		{
			throw new OutboxDispatchException("Could not serialise outbox payload of type "
					+ payload.getClass().getName(), e);
		}
	}
}
