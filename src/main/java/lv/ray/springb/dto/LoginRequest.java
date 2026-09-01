package lv.ray.springb.dto;

/**
 * Payload for POST /api/auth/login.
 */
public record LoginRequest(String username, String password)
{
}
