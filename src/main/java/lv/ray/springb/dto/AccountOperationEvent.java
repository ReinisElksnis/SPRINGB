package lv.ray.springb.dto;

import lv.ray.springb.entity.OperationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * The payload published for one ledger entry. A deliberately flat, self-contained snapshot rather
 * than a reference like {@code {"operationId": 42}}: a consumer that receives only an id has to
 * call back into this service to find out what happened, which re-couples the two systems the
 * outbox just decoupled, and - worse - that read-back returns the state of the account *now*, not
 * the state at the moment the event was emitted. Carrying the facts in the event keeps it a true
 * historical statement.
 */
public record AccountOperationEvent(

		Long operationId,

		Long accountId,

		String ownerUsername,

		OperationType type,

		BigDecimal amount,

		BigDecimal balanceAfter,

		String currency,

		String transferGroupId,

		LocalDateTime occurredAt)
{
}
