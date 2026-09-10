package lv.ray.springb.service.outbox;

import lv.ray.springb.entity.OutboxMessage;


/**
 * Where a claimed message actually goes. Kept as an interface with the broker deliberately absent:
 * this repository has no Kafka or RabbitMQ, and pretending otherwise would hide the part worth
 * understanding, which is the relay's state machine rather than any particular client library.
 * Swapping {@link LoggingOutboxDispatcher} for a real producer is a one-class change precisely
 * because the outbox pattern keeps the broker off the transactional write path.
 */
public interface OutboxDispatcher
{

	/**
	 * Hands one message to the broker. Must be safe to call more than once with the same message -
	 * the relay guarantees at-least-once, not exactly-once, so a redelivery after a crash is normal
	 * operation and not an error.
	 *
	 * @throws OutboxDispatchException if the message could not be delivered
	 */
	void dispatch(OutboxMessage message);
}
