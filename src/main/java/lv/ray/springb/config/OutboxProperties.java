package lv.ray.springb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;


/**
 * Outbox relay tuning, bound from {@code springb.outbox.*}. The values below are the defaults
 * applied when a property is absent.
 */
@ConfigurationProperties(prefix = "springb.outbox")
public record OutboxProperties(

		@DefaultValue("true") boolean enabled,

		/**
		 * How many messages one poll claims. This is a latency-versus-lock-duration dial, not a
		 * "bigger is faster" one: the claiming transaction holds a row lock on every message in the
		 * batch for as long as it takes to dispatch all of them, so a large batch against a slow
		 * broker holds locks for a long time and keeps other relay instances from helping with
		 * those particular rows. Small enough that one batch is quick, large enough to amortise the
		 * poll's round trip.
		 */
		@DefaultValue("50") int batchSize,

		/** Gap between the end of one poll and the start of the next - see {@code OutboxRelay}. */
		@DefaultValue("1s") Duration pollInterval,

		/** Delay before the first retry; doubles from there. */
		@DefaultValue("1s") Duration initialBackoff,

		/**
		 * Ceiling on the exponential growth. Without a cap, doubling reaches absurd delays fast -
		 * a message that has failed 20 times would be scheduled a fortnight out and would look
		 * indistinguishable from one that had been silently lost.
		 */
		@DefaultValue("5m") Duration maxBackoff,

		/** Attempts before a message is given up on as {@code DEAD}. */
		@DefaultValue("8") int maxAttempts)
{
}
