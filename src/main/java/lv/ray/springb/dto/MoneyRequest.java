package lv.ray.springb.dto;

import java.math.BigDecimal;

/**
 * Payload for POST /api/accounts/{id}/deposits and /withdrawals.
 */
public record MoneyRequest(BigDecimal amount)
{
}
