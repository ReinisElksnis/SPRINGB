package lv.ray.springb.dto;

import lv.ray.springb.entity.AppUser;

import java.time.LocalDateTime;


/**
 * The public view of an account - never carries the password hash.
 */
public record UserDTO(Long id, String username, String email, String displayName, String role, LocalDateTime createdAt)
{
	public static UserDTO from(final AppUser user)
	{
		return new UserDTO(user.getId(),
				user.getUsername(),
				user.getEmail(),
				user.getDisplayName(),
				user.getRole(),
				user.getCreatedAt());
	}
}
