package lv.ray.springb.service.impl;

import lv.ray.springb.config.AuthProperties;
import lv.ray.springb.dto.RegistrationRequest;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.service.AppUserService;
import lv.ray.springb.service.validation.RegistrationValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


@Service
public class AppUserServiceImpl implements AppUserService
{

	private static final Logger logger = LoggerFactory.getLogger(AppUserServiceImpl.class);

	private final AppUserRepository appUserRepository;

	private final PasswordEncoder passwordEncoder;

	private final AuthProperties authProperties;

	private final RegistrationValidator registrationValidator;

	public AppUserServiceImpl(final AppUserRepository appUserRepository,
			final PasswordEncoder passwordEncoder,
			final AuthProperties authProperties,
			final RegistrationValidator registrationValidator)
	{
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.authProperties = authProperties;
		this.registrationValidator = registrationValidator;
	}

	@Override
	@Transactional
	public AppUser register(final RegistrationRequest request)
	{
		final String username = trimmed(request.username());
		final String email = trimmed(request.email()).toLowerCase();
		final String password = request.password() == null ? "" : request.password();

		registrationValidator.validate(username, email, password);

		final String displayName = trimmed(request.displayName()).isEmpty() ? username : trimmed(request.displayName());
		final AppUser user = new AppUser(username, email, passwordEncoder.encode(password), displayName);
		user.setRole(authProperties.defaultRole());

		logger.info("AppUserService: registering new account '{}'", username);

		return appUserRepository.save(user);
	}

	@Override
	public Optional<AppUser> getByUsername(final String username)
	{
		return appUserRepository.findByUsername(username);
	}

	private static String trimmed(final String value)
	{
		return value == null ? "" : value.trim();
	}
}
