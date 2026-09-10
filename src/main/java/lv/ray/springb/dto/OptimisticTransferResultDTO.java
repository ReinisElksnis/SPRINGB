package lv.ray.springb.dto;


/**
 * A transfer completed through the optimistic path, with the contention it encountered on its way
 * through.
 *
 * <p>{@link #attempts} is reported rather than hidden because it is the only externally visible
 * difference between the two strategies, and it is the number that tells you whether optimistic
 * locking was the right choice for this workload. Consistently 1 means contention is rare and the
 * optimistic path is winning - no locks held, no blocking. Consistently high means the row is hot,
 * every attempt is wasted work, and a pessimistic lock would do less total work by making writers
 * wait once rather than repeatedly redoing and discarding a whole transaction.
 *
 * <p>Read as a metric rather than as a curiosity: this is the signal you would alert on before
 * switching a hot account back to {@code /api/accounts/transfers}.
 */
public record OptimisticTransferResultDTO(

		OperationDTO debit,

		OperationDTO credit,

		/** Total attempts including the successful one; 1 means it went through uncontended. */
		int attempts,

		/** Convenience for clients and tests - simply {@code attempts > 1}. */
		boolean contended)
{
	public OptimisticTransferResultDTO(final OperationDTO debit, final OperationDTO credit, final int attempts)
	{
		this(debit, credit, attempts, attempts > 1);
	}
}
