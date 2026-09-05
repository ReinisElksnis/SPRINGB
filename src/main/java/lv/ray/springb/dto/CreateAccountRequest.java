package lv.ray.springb.dto;

/**
 * Payload for POST /api/accounts. {@code currency} is optional - {@code AccountService} falls
 * back to {@code ApiConstants.Defaults.ACCOUNT_CURRENCY} when it is blank.
 */
public record CreateAccountRequest(String currency)
{
}
