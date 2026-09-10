package lv.ray.springb.service.outbox;

import lv.ray.springb.entity.OutboxMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Default dispatcher: writes the event to the log and reports success. Stands in for a broker so
 * the relay is runnable end to end without one.
 *
 * <p>Registered by {@code OutboxConfig} as a conditional {@code @Bean} rather than carrying
 * {@code @Component} here, because {@code @ConditionalOnMissingBean} is only well-defined on
 * {@code @Bean} methods: on a component-scanned class it races the scan order and may or may not
 * back off depending on which bean definition happens to be registered first.
 */
public class LoggingOutboxDispatcher implements OutboxDispatcher
{

	private static final Logger LOG = LoggerFactory.getLogger(LoggingOutboxDispatcher.class);

	@Override
	public void dispatch(final OutboxMessage message)
	{
		LOG.info("[outbox] publishing {} for {}#{}: {}", message.getEventType(), message.getAggregateType(),
				message.getAggregateId(), message.getPayload());
	}
}
