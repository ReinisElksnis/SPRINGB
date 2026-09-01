package lv.ray.springb.dto;

/**
 * Payload for POST /api/auth/register.
 */
public record RegistrationRequest(String username, String email, String password, String displayName)
{
}
