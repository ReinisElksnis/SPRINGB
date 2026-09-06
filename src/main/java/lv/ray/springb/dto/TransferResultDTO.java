package lv.ray.springb.dto;

/**
 * Both legs of one transfer: the withdrawal from the source account and the deposit into the
 * destination account, sharing {@code debit.getTransferGroupId() == credit.getTransferGroupId()}.
 */
public record TransferResultDTO(OperationDTO debit, OperationDTO credit)
{
}
