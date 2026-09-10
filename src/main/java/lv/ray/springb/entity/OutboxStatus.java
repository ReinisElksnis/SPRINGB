package lv.ray.springb.entity;


/**
 * Lifecycle of an {@link OutboxMessage}. Deliberately three states and not two: without
 * {@link #DEAD}, a message whose payload the broker will never accept (a malformed event, a topic
 * that no longer exists) is retried forever, and because the relay claims the oldest due rows
 * first, that one poisoned row is re-claimed on every single poll and starves everything queued
 * behind it. A terminal failure state is what lets the relay give up on one message without
 * giving up on the queue.
 */
public enum OutboxStatus
{
	/** Committed alongside its ledger row, not yet accepted by the broker. */
	PENDING,

	/** Handed to the broker successfully at least once. */
	PUBLISHED,

	/** Exhausted its retry budget. Never claimed again; needs a human or a replay tool. */
	DEAD
}
