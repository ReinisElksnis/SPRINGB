package lv.ray.springb.dto;

import java.util.List;


/**
 * The outcome of replaying an account's {@code Operation} ledger from scratch and comparing it
 * against both itself (does each row's {@code balanceAfter} follow from the previous one plus its
 * own signed amount?) and the account's current cached balance. A non-empty {@code discrepancies}
 * list means either the ledger was corrupted after being written, or something upstream computed
 * a balance without going through the normal mutation path - both are integrity failures a
 * balance-only check would miss.
 */
public record ReconciliationResult(Long accountId, boolean consistent, List<String> discrepancies)
{
}
