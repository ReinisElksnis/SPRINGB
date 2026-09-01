package lv.ray.springb.service.impl;

import lv.ray.springb.config.AuthProperties;
import lv.ray.springb.dto.RegistrationRequest;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.service.AppUserService;
import lv.ray.springb.service.RegistrationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Pattern;


@Service
@Transactional
public class AppUserServiceImpl implements AppUserService
{

	private static final Logger logger = LoggerFactory.getLogger(AppUserServiceImpl.class);

	private final AppUserRepository appUserRepository;

	private final PasswordEncoder passwordEncoder;

	private final AuthProperties authProperties;

	private final Pattern emailPattern;

	public AppUserServiceImpl(final AppUserRepository appUserRepository,
			final PasswordEncoder passwordEncoder,
			final AuthProperties authProperties)
	{
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.authProperties = authProperties;
		// Compiled once here so a malformed pattern fails the context startup, not a registration.
		this.emailPattern = Pattern.compile(authProperties.emailPattern());
	}

	@Override
	public AppUser register(final RegistrationRequest request)
	{
		final String username = trimmed(request.username());
		final String email = trimmed(request.email()).toLowerCase();
		final String password = request.password() == null ? "" : request.password();

		if (username.length() < authProperties.minUsernameLength())
		{
			throw new RegistrationException(
					"Username must be at least " + authProperties.minUsernameLength() + " characters long");
		}
		if (!emailPattern.matcher(email).matches())
		{
			throw new RegistrationException("A valid email address is required");
		}
		if (password.length() < authProperties.minPasswordLength())
		{
			throw new RegistrationException(
					"Password must be at least " + authProperties.minPasswordLength() + " characters long");
		}
		if (appUserRepository.existsByUsername(username))
		{
			throw new RegistrationException("That username is already taken");
		}
		if (appUserRepository.existsByEmail(email))
		{
			throw new RegistrationException("An account with that email already exists");
		}

		final String displayName = trimmed(request.displayName()).isEmpty() ? username : trimmed(request.displayName());
		final AppUser user = new AppUser(username, email, passwordEncoder.encode(password), displayName);
		user.setRole(authProperties.defaultRole());

		logger.info("AppUserService: registering new account '{}'", username);

		return appUserRepository.save(user);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AppUser> getByUsername(final String username)
	{
		return appUserRepository.findByUsername(username);
	}

	private static String trimmed(final String value)
	{
		return value == null ? "" : value.trim();
	}
}
