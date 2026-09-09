package lv.ray.springb.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Payload for POST /api/accounts/{id}/deposits and /withdrawals. Presence-only by design - the
 * positive/scale/sufficient-funds rules are currency-aware and live in
 * {@code AccountOperationValidator}.
 */
public record MoneyRequest(@NotNull BigDecimal amount)
{
}
