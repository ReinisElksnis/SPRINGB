package lv.ray.springb.dto;

import java.math.BigDecimal;

/**
 * Payload for POST /api/accounts/transfers. The caller must own {@code fromAccountId};
 * {@code toAccountId} may belong to anyone.
 */
public record TransferRequest(Long fromAccountId, Long toAccountId, BigDecimal amount)
{
}
