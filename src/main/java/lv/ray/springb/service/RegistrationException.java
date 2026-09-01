package lv.ray.springb.service;

/**
 * Thrown when a registration request is rejected (bad input or an account that already exists).
 */
public class RegistrationException extends RuntimeException
{
	public RegistrationException(final String message)
	{
		super(message);
	}
}
