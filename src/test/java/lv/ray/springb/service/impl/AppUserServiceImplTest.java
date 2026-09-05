package lv.ray.springb.service.impl;

import lv.ray.springb.config.AuthProperties;
import lv.ray.springb.dto.RegistrationRequest;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.repository.AppUserRepository;
import lv.ray.springb.service.RegistrationException;
import lv.ray.springb.service.validation.RegistrationValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests: every collaborator is mocked, nothing here touches Spring or a database - see
 * {@code AccountServiceConcurrencyTests} for the integration-style tests that exercise real
 * transactions and locking against Testcontainers Postgres.
 */
@ExtendWith(MockitoExtension.class)
class AppUserServiceImplTest
{

	private static final AuthProperties AUTH_PROPERTIES =
			new AuthProperties(3, 8, "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", "ROLE_USER");

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private RegistrationValidator registrationValidator;

	private AppUserServiceImpl appUserService;

	@BeforeEach
	void setUp()
	{
		appUserService = new AppUserServiceImpl(appUserRepository, passwordEncoder, AUTH_PROPERTIES,
				registrationValidator);
	}

	@Test
	void register_trimsInputAndSavesUserWithEncodedPasswordAndDefaultRole()
	{
		final RegistrationRequest request =
				new RegistrationRequest("  alice  ", "  ALICE@Example.com  ", "password123", "Alice");
		when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
		when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

		final AppUser saved = appUserService.register(request);

		assertThat(saved.getUsername()).isEqualTo("alice");
		assertThat(saved.getEmail()).isEqualTo("alice@example.com");
		assertThat(saved.getPasswordHash()).isEqualTo("hashed-password");
		assertThat(saved.getDisplayName()).isEqualTo("Alice");
		assertThat(saved.getRole()).isEqualTo("ROLE_USER");

		verify(registrationValidator).validate("alice", "alice@example.com", "password123");
	}

	@Test
	void register_fallsBackToUsernameWhenDisplayNameBlank()
	{
		final RegistrationRequest request =
				new RegistrationRequest("bob", "bob@example.com", "password123", "   ");
		when(passwordEncoder.encode(any())).thenReturn("hashed");
		when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

		final AppUser saved = appUserService.register(request);

		assertThat(saved.getDisplayName()).isEqualTo("bob");
	}

	@Test
	void register_propagatesValidationFailureAndNeverSaves()
	{
		final RegistrationRequest request = new RegistrationRequest("ab", "bad-email", "short", null);
		doThrow(new RegistrationException("Username must be at least 3 characters long"))
				.when(registrationValidator).validate("ab", "bad-email", "short");

		assertThatThrownBy(() -> appUserService.register(request))
				.isInstanceOf(RegistrationException.class)
				.hasMessage("Username must be at least 3 characters long");

		verify(appUserRepository, never()).save(any());
		verify(passwordEncoder, never()).encode(any());
	}

	@Test
	void register_treatsNullPasswordAsEmptyStringRatherThanThrowingNpe()
	{
		final RegistrationRequest request = new RegistrationRequest("carol", "carol@example.com", null, "Carol");
		final ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
		when(passwordEncoder.encode(passwordCaptor.capture())).thenReturn("hashed");
		when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

		appUserService.register(request);

		assertThat(passwordCaptor.getValue()).isEmpty();
		verify(registrationValidator).validate("carol", "carol@example.com", "");
	}

	@Test
	void getByUsername_delegatesDirectlyToRepository()
	{
		final AppUser user = new AppUser("dave", "dave@example.com", "hash", "Dave");
		when(appUserRepository.findByUsername("dave")).thenReturn(Optional.of(user));

		final Optional<AppUser> result = appUserService.getByUsername("dave");

		assertThat(result).contains(user);
	}

	@Test
	void getByUsername_returnsEmptyWhenNoSuchUser()
	{
		when(appUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

		assertThat(appUserService.getByUsername("ghost")).isEmpty();
	}
}
