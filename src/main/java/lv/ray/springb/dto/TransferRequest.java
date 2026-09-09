package lv.ray.springb.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Payload for POST /api/accounts/transfers. The caller must own {@code fromAccountId};
 * {@code toAccountId} may belong to anyone.
 *
 * <p>The constraints here are presence-only on purpose. Whether the amount is positive, correctly
 * scaled for the account's currency, actually available, or between two distinct accounts the
 * caller owns are all business rules that need the accounts loaded - those stay in
 * {@code AccountOperationValidator}. What annotations buy is rejecting a structurally incomplete
 * body at the edge with a 400, before any of that runs: a null id used to reach
 * {@code fromAccountId.equals(...)} and surface as a 500.
 */
public record TransferRequest(
		@NotNull Long fromAccountId,
		@NotNull Long toAccountId,
		@NotNull BigDecimal amount)
{
}
