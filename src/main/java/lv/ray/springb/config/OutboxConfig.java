package lv.ray.springb.config;

import lv.ray.springb.service.outbox.LoggingOutboxDispatcher;
import lv.ray.springb.service.outbox.OutboxDispatcher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;


/**
 * Wires the outbox relay. {@link EnableScheduling} is switched on here rather than on the
 * application class so the reason it exists sits next to the only thing that uses it.
 *
 * <p>Note what is <em>not</em> configured: a thread pool for the relay. Spring's default scheduler
 * is a single thread, and for this relay that is the right size, not an oversight - see
 * {@code OutboxRelay} for why the concurrency that matters here lives in the database's row locks
 * rather than in this JVM's threads.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig
{

	/**
	 * Backs off if anything else defines an {@link OutboxDispatcher} - a real broker producer in
	 * production, or a failure-injecting stub in a test.
	 */
	@Bean
	@ConditionalOnMissingBean(OutboxDispatcher.class)
	public OutboxDispatcher outboxDispatcher()
	{
		return new LoggingOutboxDispatcher();
	}
}
