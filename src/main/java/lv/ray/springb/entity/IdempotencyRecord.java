package lv.ray.springb.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;


/**
 * One row per client-supplied idempotency key that has already been spent on a balance-mutating
 * request. This row is written last, inside the very same transaction as the balance mutation and
 * ledger row(s) it guards - so a concurrent duplicate request loses the unique-key race on this
 * insert and rolls that whole transaction back, undoing its mutation along with it. The mutation
 * and its key are therefore always committed together, or not at all.
 *
 * <p>{@code idempotencyKey} is a plain unique column, not the primary key: a database-generated
 * {@code id} keeps this entity's newness detection ordinary (a fresh instance really does have a
 * null id, so {@code save()} correctly calls {@code persist()}), instead of needing to implement
 * {@code Persistable} to override it - the unique constraint on {@code idempotencyKey} still gives
 * the same guarantee, since a concurrent duplicate still fails that constraint on insert.
 *
 * <p>A key match alone is never proof that a replay is legitimate, so two things are recorded
 * alongside it and both are re-checked before any cached result is returned.
 * {@code ownerUsername} records who the key was originally claimed by, so a collision cannot hand
 * one user's operation details to a different caller. {@code requestFingerprint} records <em>what
 * the key was spent on</em> - the operation type, the account(s) involved and the amount - so a
 * client that reuses one key across two genuinely different requests is rejected outright rather
 * than being told its second request succeeded while the first one's result is quietly returned
 * instead. Without it, a deposit key replayed on a withdrawal would drop the withdrawal and answer
 * 200; that is the precise failure mode idempotency exists to prevent.
 *
 * <p>The fingerprint is nullable only because rows written before it existed have none. A null is
 * read as "legacy row, shape unknown" and skips the shape check rather than failing every replay
 * of a pre-existing key; the owner check still applies to those rows.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord
{

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
	private String idempotencyKey;

	@Column(name = "owner_username", nullable = false)
	private String ownerUsername;

	@Column(name = "operation_id")
	private Long operationId;

	@Column(name = "transfer_group_id", length = 36)
	private String transferGroupId;

	@Column(name = "request_fingerprint", length = 200)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public IdempotencyRecord()
	{
		this.createdAt = LocalDateTime.now();
	}

	public IdempotencyRecord(final String idempotencyKey, final String ownerUsername, final Long operationId,
			final String transferGroupId, final String requestFingerprint)
	{
		this();
		this.idempotencyKey = idempotencyKey;
		this.ownerUsername = ownerUsername;
		this.operationId = operationId;
		this.transferGroupId = transferGroupId;
		this.requestFingerprint = requestFingerprint;
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

	public String getIdempotencyKey()
	{
		return idempotencyKey;
	}

	public void setIdempotencyKey(final String idempotencyKey)
	{
		this.idempotencyKey = idempotencyKey;
	}

	public String getOwnerUsername()
	{
		return ownerUsername;
	}

	public void setOwnerUsername(final String ownerUsername)
	{
		this.ownerUsername = ownerUsername;
	}

	public Long getOperationId()
	{
		return operationId;
	}

	public void setOperationId(final Long operationId)
	{
		this.operationId = operationId;
	}

	public String getTransferGroupId()
	{
		return transferGroupId;
	}

	public void setTransferGroupId(final String transferGroupId)
	{
		this.transferGroupId = transferGroupId;
	}

	public String getRequestFingerprint()
	{
		return requestFingerprint;
	}

	public void setRequestFingerprint(final String requestFingerprint)
	{
		this.requestFingerprint = requestFingerprint;
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
