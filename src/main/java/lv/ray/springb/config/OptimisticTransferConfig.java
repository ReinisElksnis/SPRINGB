package lv.ray.springb.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;


/**
 * Binds {@code springb.optimistic-transfer.*} for the optimistic transfer path. Separate from
 * {@code OutboxConfig} because the two share a retry <em>mechanism</em>
 * ({@code RetryBackoff}) but nothing else - they retry different things, on different timescales,
 * for different reasons, and coupling their configuration would invite someone to "unify" the
 * delays, which would be wrong in both directions.
 */
@Configuration
@EnableConfigurationProperties(OptimisticTransferProperties.class)
public class OptimisticTransferConfig
{
}
