package lv.ray.springb.dto;

import lv.ray.springb.entity.Operation;
import lv.ray.springb.entity.OperationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;


public class OperationDTO
{
	private Long id;
	private Long accountId;
	private OperationType type;
	private BigDecimal amount;
	private BigDecimal balanceAfter;
	private String transferGroupId;
	private LocalDateTime createdAt;

	public OperationDTO()
	{
	}

	public OperationDTO(final Operation operation)
	{
		this.id = operation.getId();
		this.accountId = operation.getAccount().getId();
		this.type = operation.getType();
		this.amount = operation.getAmount();
		this.balanceAfter = operation.getBalanceAfter();
		this.transferGroupId = operation.getTransferGroupId();
		this.createdAt = operation.getCreatedAt();
	}

	// Getters and Setters
	public Long getId()
	{
		return id;
	}

	public void setId(Long id)
	{
		this.id = id;
	}

	public Long getAccountId()
	{
		return accountId;
	}

	public void setAccountId(Long accountId)
	{
		this.accountId = accountId;
	}

	public OperationType getType()
	{
		return type;
	}

	public void setType(OperationType type)
	{
		this.type = type;
	}

	public BigDecimal getAmount()
	{
		return amount;
	}

	public void setAmount(BigDecimal amount)
	{
		this.amount = amount;
	}

	public BigDecimal getBalanceAfter()
	{
		return balanceAfter;
	}

	public void setBalanceAfter(BigDecimal balanceAfter)
	{
		this.balanceAfter = balanceAfter;
	}

	public String getTransferGroupId()
	{
		return transferGroupId;
	}

	public void setTransferGroupId(String transferGroupId)
	{
		this.transferGroupId = transferGroupId;
	}

	public LocalDateTime getCreatedAt()
	{
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt)
	{
		this.createdAt = createdAt;
	}
}
