package lv.ray.springb.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


/**
 * A single-currency balance owned by an {@link AppUser}. {@code balance} is a cache kept in sync,
 * inside the same database transaction, with every {@link Operation} appended against this
 * account - the operations are the source of truth for how the balance got here; this column
 * exists purely so reading it doesn't require summing the whole ledger. The {@code balance >= 0}
 * check is a database-level backstop behind the application-level checks in
 * {@code AccountOperationValidator} - belt and suspenders.
 */
@Entity
@Table(name = "accounts", check = @CheckConstraint(name = "accounts_balance_check", constraint = "balance >= 0"))
public class Account
{

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private AppUser owner;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal balance = BigDecimal.ZERO;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
	@JsonIgnore
	private List<Operation> operations = new ArrayList<>();

	public Account()
	{
		this.createdAt = LocalDateTime.now();
	}

	public Account(final AppUser owner, final String currency)
	{
		this();
		this.owner = owner;
		this.currency = currency;
	}

	// Getters and Setters
	public Long getId()
	{
		return id;
	}

	public void setId(final Long id)
	{
		this.id = id;
	}

	public AppUser getOwner()
	{
		return owner;
	}

	public void setOwner(final AppUser owner)
	{
		this.owner = owner;
	}

	public String getCurrency()
	{
		return currency;
	}

	public void setCurrency(final String currency)
	{
		this.currency = currency;
	}

	public BigDecimal getBalance()
	{
		return balance;
	}

	public void setBalance(final BigDecimal balance)
	{
		this.balance = balance;
	}

	public LocalDateTime getCreatedAt()
	{
		return createdAt;
	}

	public void setCreatedAt(final LocalDateTime createdAt)
	{
		this.createdAt = createdAt;
	}

	public List<Operation> getOperations()
	{
		return operations;
	}

	public void setOperations(final List<Operation> operations)
	{
		this.operations = operations;
	}
}
