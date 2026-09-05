package lv.ray.springb.dto;

/**
 * Both legs of one transfer: the withdrawal from the source account and the deposit into the
 * destination account, sharing {@code debit.getTransferGroupId() == credit.getTransferGroupId()}.
 */
public class TransferResultDTO
{
	private OperationDTO debit;
	private OperationDTO credit;

	public TransferResultDTO()
	{
	}

	public TransferResultDTO(final OperationDTO debit, final OperationDTO credit)
	{
		this.debit = debit;
		this.credit = credit;
	}

	// Getters and Setters
	public OperationDTO getDebit()
	{
		return debit;
	}

	public void setDebit(OperationDTO debit)
	{
		this.debit = debit;
	}

	public OperationDTO getCredit()
	{
		return credit;
	}

	public void setCredit(OperationDTO credit)
	{
		this.credit = credit;
	}
}
