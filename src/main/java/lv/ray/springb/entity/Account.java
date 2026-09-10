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

	/** Scale 3, not 2 - some real currencies (KWD, BHD, OMR...) have 3 minor units; see
	 * {@code AccountOperationValidator.validateAmountScale}. */
	@Column(nullable = false, precision = 19, scale = 3)
	private BigDecimal balance = BigDecimal.ZERO;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	/**
	 * Optimistic-locking counter, incremented by Hibernate on every update and appended to each
	 * UPDATE's WHERE clause so a write built on a stale read matches zero rows and throws rather
	 * than silently overwriting. Read {@code V5__add_account_version_for_optimistic_locking.sql}
	 * for the mechanism.
	 *
	 * <p>Present on the entity, so it applies to <em>every</em> write path - but only the optimistic
	 * one can ever observe a conflict. {@code AccountMutationExecutor} takes a row lock before
	 * reading, so by construction nobody else can have read the same version in the meantime; there
	 * the counter just advances. Keeping one entity rather than two is deliberate: two mappings of
	 * the same table, one versioned and one not, would let the unversioned path bump the row without
	 * the versioned path noticing, and the check would quietly stop meaning anything.
	 *
	 * <p>No setter. This field belongs to the persistence provider - assigning it from application
	 * code is how optimistic locking gets accidentally disabled.
	 */
	@Version
	@Column(nullable = false)
	private long version;

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

	public long getVersion()
	{
		return version;
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
