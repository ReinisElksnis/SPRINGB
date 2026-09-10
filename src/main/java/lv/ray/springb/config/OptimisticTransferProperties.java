package lv.ray.springb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;


/**
 * Retry policy for the optimistic transfer path, bound from {@code springb.optimistic-transfer.*}.
 *
 * <p>The delays here are two orders of magnitude shorter than the outbox's ({@code OutboxProperties})
 * on purpose, and the contrast is the useful part: the outbox waits out a <em>broker outage</em>,
 * which lasts seconds to minutes, whereas this waits out a <em>competing transaction</em>, which
 * lasts as long as one commit. Backing off for a second here would be strictly worse than blocking
 * on a pessimistic lock, and would make the optimistic path pointless.
 */
@ConfigurationProperties(prefix = "springb.optimistic-transfer")
public record OptimisticTransferProperties(

		/**
		 * Total attempts, not retries after the first - so 1 disables retrying entirely, which is
		 * what {@code OptimisticTransferIntegrationTests} uses to show the raw conflict.
		 */
		@DefaultValue("5") int maxAttempts,

		@DefaultValue("20ms") Duration initialBackoff,

		@DefaultValue("250ms") Duration maxBackoff)
{
}
