package lv.ray.springb.entity;

/**
 * What a {@link Operation} did to the account it is attached to. A transfer is recorded as a pair
 * of rows - {@code TRANSFER_OUT} on the source account and {@code TRANSFER_IN} on the destination
 * - rather than a single row with a signed amount, so each account's ledger is self-contained.
 */
public enum OperationType
{
	DEPOSIT,
	WITHDRAWAL,
	TRANSFER_OUT,
	TRANSFER_IN
}
