package lv.ray.springb.service.outbox;


/**
 * Thrown by an {@link OutboxDispatcher} when a message could not be handed to the broker. Treated
 * by {@code OutboxRelay} as retryable - the relay cannot tell a transient network failure from a
 * permanently malformed payload, so it retries everything and lets the attempt budget be the thing
 * that eventually distinguishes them.
 */
public class OutboxDispatchException extends RuntimeException
{

	public OutboxDispatchException(final String message)
	{
		super(message);
	}

	public OutboxDispatchException(final String message, final Throwable cause)
	{
		super(message, cause);
	}
}
