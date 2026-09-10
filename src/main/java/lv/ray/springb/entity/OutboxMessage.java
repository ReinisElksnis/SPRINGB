package lv.ray.springb.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;


/**
 * One event awaiting publication, written in the same transaction as the ledger row it describes -
 * see {@code V4__create_outbox_messages.sql} for why that co-commit is the entire point of the
 * pattern.
 *
 * <p>Unlike {@link Operation}, which is append-only, rows here are mutable by design: the relay
 * owns {@link #status}, {@link #attempts}, {@link #nextAttemptAt} and {@link #lastError}, and
 * mutates them as delivery is attempted. The immutable audit trail is the ledger; this table is a
 * work queue, and a work queue that could not record its own progress would have to re-do it.
 */
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage
{

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "aggregate_type", nullable = false, length = 50)
	private String aggregateType;

	/**
	 * Stored as text rather than a numeric id because the things this app emits events about are
	 * not all keyed the same way - an operation has a numeric id, a transfer is identified by its
	 * {@link Operation#getTransferGroupId()} UUID.
	 */
	@Column(name = "aggregate_id", nullable = false, length = 64)
	private String aggregateId;

	@Column(name = "event_type", nullable = false, length = 50)
	private String eventType;

	/**
	 * The serialised event. Kept as an opaque string rather than mapped columns so that changing
	 * the shape of an event does not require a migration, and so a row written by an older version
	 * of the code is still publishable by a newer one.
	 */
	@Column(nullable = false, columnDefinition = "TEXT")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OutboxStatus status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private LocalDateTime nextAttemptAt;

	/**
	 * Truncated to fit the column - see {@code OutboxRelay}. A diagnostic breadcrumb, not a log:
	 * an exception message from a broker driver can be kilobytes long, and the whole point of the
	 * backlog table is that it stays small enough to scan cheaply.
	 */
	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "published_at")
	private LocalDateTime publishedAt;

	protected OutboxMessage()
	{
		// for JPA
	}

	public OutboxMessage(final String aggregateType, final String aggregateId, final String eventType,
			final String payload)
	{
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.status = OutboxStatus.PENDING;
		this.attempts = 0;
		this.createdAt = LocalDateTime.now();
		// Due immediately: the relay's next poll should pick this up without an artificial delay.
		this.nextAttemptAt = this.createdAt;
	}

	public void markPublished()
	{
		this.status = OutboxStatus.PUBLISHED;
		this.publishedAt = LocalDateTime.now();
		this.lastError = null;
	}

	public void markFailed(final LocalDateTime retryAt, final String error)
	{
		this.attempts++;
		this.nextAttemptAt = retryAt;
		this.lastError = error;
	}

	public void markDead(final String error)
	{
		this.attempts++;
		this.status = OutboxStatus.DEAD;
		this.lastError = error;
	}

	public Long getId()
	{
		return id;
	}

	public String getAggregateType()
	{
		return aggregateType;
	}

	public String getAggregateId()
	{
		return aggregateId;
	}

	public String getEventType()
	{
		return eventType;
	}

	public String getPayload()
	{
		return payload;
	}

	public OutboxStatus getStatus()
	{
		return status;
	}

	public int getAttempts()
	{
		return attempts;
	}

	public LocalDateTime getNextAttemptAt()
	{
		return nextAttemptAt;
	}

	public String getLastError()
	{
		return lastError;
	}

	public LocalDateTime getCreatedAt()
	{
		return createdAt;
	}

	public LocalDateTime getPublishedAt()
	{
		return publishedAt;
	}
}
