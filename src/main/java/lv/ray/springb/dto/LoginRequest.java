package lv.ray.springb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/auth/login.
 */
public record LoginRequest(
		@NotBlank String username,
		@NotBlank String password)
{
}
