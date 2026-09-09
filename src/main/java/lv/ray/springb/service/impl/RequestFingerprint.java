package lv.ray.springb.service.impl;

import lv.ray.springb.entity.OperationType;

import java.math.BigDecimal;


/**
 * Builds the short, deterministic description of a balance-mutating request that gets stored
 * against its idempotency key, so a replay of that key can be checked for <em>shape</em> and not
 * only for caller. Both sides of that check live in different classes - the write side in
 * {@code AccountMutationExecutor}, the comparison in {@code AccountServiceImpl} - so the format
 * lives here rather than being spelled out twice: two implementations that drifted apart would
 * start rejecting legitimate retries, which is worse than the bug this guards against.
 *
 * <p>Amounts are normalised with {@code stripTrailingZeros().toPlainString()} on purpose. A client
 * retrying the same logical request may serialise {@code 100.0} one time and {@code 100.00} the
 * next; those are the same amount of money and must produce the same fingerprint, even though
 * their {@code BigDecimal} scales differ (the same scale-sensitivity that makes
 * {@code BigDecimal.equals} the wrong comparison for money).
 */
public final class RequestFingerprint
{

	private RequestFingerprint()
	{
	}

	public static String forDeposit(final Long accountId, final BigDecimal amount)
	{
		return OperationType.DEPOSIT + ":" + accountId + ":" + normalise(amount);
	}

	public static String forWithdrawal(final Long accountId, final BigDecimal amount)
	{
		return OperationType.WITHDRAWAL + ":" + accountId + ":" + normalise(amount);
	}

	public static String forTransfer(final Long fromAccountId, final Long toAccountId, final BigDecimal amount)
	{
		// Direction is part of the shape: A->B and B->A are different requests, so the ids are
		// deliberately not sorted the way AccountMutationExecutor sorts them for lock ordering.
		return OperationType.TRANSFER_OUT + ":" + fromAccountId + ":" + toAccountId + ":" + normalise(amount);
	}

	private static String normalise(final BigDecimal amount)
	{
		return amount == null ? "null" : amount.stripTrailingZeros().toPlainString();
	}
}
