package lv.ray.springb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/auth/register. {@code displayName} is optional - {@code AppUserService}
 * falls back to the username when it is blank. Length and format rules stay in
 * {@code RegistrationValidator}, which owns the configurable policy in {@code AuthProperties}.
 */
public record RegistrationRequest(
		@NotBlank String username,
		@NotBlank String email,
		@NotBlank String password,
		String displayName)
{
}
