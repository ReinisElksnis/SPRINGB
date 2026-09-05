package lv.ray.springb.dto;

import lv.ray.springb.entity.Account;

import java.math.BigDecimal;
import java.time.LocalDateTime;


public class AccountDTO
{
	private Long id;
	private String ownerUsername;
	private String currency;
	private BigDecimal balance;
	private LocalDateTime createdAt;

	public AccountDTO()
	{
	}

	public AccountDTO(final Account account)
	{
		this.id = account.getId();
		this.ownerUsername = account.getOwner().getUsername();
		this.currency = account.getCurrency();
		this.balance = account.getBalance();
		this.createdAt = account.getCreatedAt();
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

	public String getOwnerUsername()
	{
		return ownerUsername;
	}

	public void setOwnerUsername(String ownerUsername)
	{
		this.ownerUsername = ownerUsername;
	}

	public String getCurrency()
	{
		return currency;
	}

	public void setCurrency(String currency)
	{
		this.currency = currency;
	}

	public BigDecimal getBalance()
	{
		return balance;
	}

	public void setBalance(BigDecimal balance)
	{
		this.balance = balance;
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
