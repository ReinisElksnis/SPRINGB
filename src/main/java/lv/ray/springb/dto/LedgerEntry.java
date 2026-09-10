package lv.ray.springb.dto;

import lv.ray.springb.entity.Operation;
import lv.ray.springb.entity.OperationType;

import java.math.BigDecimal;


/**
 * The four fields ledger replay actually reads from an {@link Operation}, as a plain record.
 *
 * <p>This exists for memory reasons, not tidiness. Reconciliation used to load full
 * {@code Operation} entities, and a JPA entity returned by a repository is a <em>managed</em>
 * object: the persistence context keeps a strong reference to every one it hands out, plus a
 * snapshot copy of its original field values for dirty checking, and holds all of it until the
 * transaction ends. So replaying a ledger cost roughly twice the entity graph in live heap, none of
 * which could be collected while the replay ran, and none of which was ever going to be modified.
 *
 * <p>A record built by a constructor expression is not managed by anything. The persistence context
 * never learns it exists, no snapshot is taken, and each one becomes garbage the moment the replay
 * loop moves past it - which is the cheapest thing a JVM heap can hold, because a short-lived
 * object that dies in the young generation is reclaimed by a minor collection that never even
 * looks at it.
 */
public record LedgerEntry(

		Long id,

		OperationType type,

		BigDecimal amount,

		BigDecimal balanceAfter)
{
}
