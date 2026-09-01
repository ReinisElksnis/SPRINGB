package lv.ray.springb.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;

import java.time.LocalDateTime;


@Entity
@Table(name = "app_users")
public class AppUser
{

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String username;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	@JsonIgnore
	private String passwordHash;

	@Column(name = "display_name")
	private String displayName;

	@Column(nullable = false)
	private String role;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public AppUser()
	{
		this.createdAt = LocalDateTime.now();
	}

	public AppUser(final String username, final String email, final String passwordHash, final String displayName)
	{
		this();
		this.username = username;
		this.email = email;
		this.passwordHash = passwordHash;
		this.displayName = displayName;
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

	public String getUsername()
	{
		return username;
	}

	public void setUsername(final String username)
	{
		this.username = username;
	}

	public String getEmail()
	{
		return email;
	}

	public void setEmail(final String email)
	{
		this.email = email;
	}

	public String getPasswordHash()
	{
		return passwordHash;
	}

	public void setPasswordHash(final String passwordHash)
	{
		this.passwordHash = passwordHash;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public void setDisplayName(final String displayName)
	{
		this.displayName = displayName;
	}

	public String getRole()
	{
		return role;
	}

	public void setRole(final String role)
	{
		this.role = role;
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public void setEnabled(final boolean enabled)
	{
		this.enabled = enabled;
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
