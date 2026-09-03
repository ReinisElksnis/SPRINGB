package lv.ray.springb.service.validation;

import lv.ray.springb.config.AuthProperties;
import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.service.RegistrationException;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;


/**
 * Validates the (already trimmed) fields of a registration request against {@link AuthProperties}
 * policy and username/email uniqueness.
 */
@Component
public class RegistrationValidator
{

	private final AppUserRepository appUserRepository;

	private final AuthProperties authProperties;

	private final Pattern emailPattern;

	public RegistrationValidator(final AppUserRepository appUserRepository, final AuthProperties authProperties)
	{
		this.appUserRepository = appUserRepository;
		this.authProperties = authProperties;
		// Compiled once here so a malformed pattern fails the context startup, not a registration.
		this.emailPattern = Pattern.compile(authProperties.emailPattern());
	}

	/**
	 * @throws RegistrationException if the request violates policy or the username/email is already taken
	 */
	public void validate(final String username, final String email, final String password)
	{
		if (username.length() < authProperties.minUsernameLength())
		{
			throw new RegistrationException(
					String.format(Messages.USERNAME_TOO_SHORT, authProperties.minUsernameLength()));
		}
		if (!emailPattern.matcher(email).matches())
		{
			throw new RegistrationException(Messages.INVALID_EMAIL);
		}
		if (password.length() < authProperties.minPasswordLength())
		{
			throw new RegistrationException(
					String.format(Messages.PASSWORD_TOO_SHORT, authProperties.minPasswordLength()));
		}
		if (appUserRepository.existsByUsername(username))
		{
			throw new RegistrationException(Messages.USERNAME_TAKEN);
		}
		if (appUserRepository.existsByEmail(email))
		{
			throw new RegistrationException(Messages.EMAIL_TAKEN);
		}
	}
}
