package lv.ray.springb.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * One immutable ledger row - the audit trail {@link Account#getBalance()} is derived from. Rows
 * here are never updated or deleted once written. A transfer produces two rows (a
 * {@link OperationType#TRANSFER_OUT} on the source account and a {@link OperationType#TRANSFER_IN}
 * on the destination) sharing the same {@link #transferGroupId}.
 */
@Entity
@Table(name = "operations")
public class Operation
{

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OperationType type;

	/**
	 * Always positive - direction is carried by {@link #type}, never by the sign of this value.
	 */
	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	/**
	 * Snapshot of {@link Account#getBalance()} immediately after this entry was applied.
	 */
	@Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
	private BigDecimal balanceAfter;

	/**
	 * Links the two legs of a transfer. {@code null} for a plain deposit or withdrawal.
	 */
	@Column(name = "transfer_group_id", length = 36)
	private String transferGroupId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public Operation()
	{
		this.createdAt = LocalDateTime.now();
	}

	public Operation(final Account account, final OperationType type, final BigDecimal amount,
			final BigDecimal balanceAfter, final String transferGroupId)
	{
		this();
		this.account = account;
		this.type = type;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.transferGroupId = transferGroupId;
	}

	public Long getId()
	{
		return id;
	}

	public void setId(final Long id)
	{
		this.id = id;
	}

	public Account getAccount()
	{
		return account;
	}

	public void setAccount(final Account account)
	{
		this.account = account;
	}

	public OperationType getType()
	{
		return type;
	}

	public void setType(final OperationType type)
	{
		this.type = type;
	}

	public BigDecimal getAmount()
	{
		return amount;
	}

	public void setAmount(final BigDecimal amount)
	{
		this.amount = amount;
	}

	public BigDecimal getBalanceAfter()
	{
		return balanceAfter;
	}

	public void setBalanceAfter(final BigDecimal balanceAfter)
	{
		this.balanceAfter = balanceAfter;
	}

	public String getTransferGroupId()
	{
		return transferGroupId;
	}

	public void setTransferGroupId(final String transferGroupId)
	{
		this.transferGroupId = transferGroupId;
	}

	public LocalDateTime getCreatedAt()
	{
		return createdAt;
	}

	public void setCreatedAt(final LocalDateTime createdAt)
	{
		this.createdAt = createdAt;
	}
}
