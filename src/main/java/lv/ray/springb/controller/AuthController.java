package lv.ray.springb.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lv.ray.springb.constants.ApiConstants;
import lv.ray.springb.constants.ApiConstants.Endpoints;
import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.constants.ApiConstants.ResponseKeys;
import lv.ray.springb.constants.ApiConstants.SubPaths;
import lv.ray.springb.dto.LoginRequest;
import lv.ray.springb.dto.RegistrationRequest;
import lv.ray.springb.dto.UserDTO;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.service.AppUserService;
import lv.ray.springb.service.RegistrationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;


@RestController
@RequestMapping(Endpoints.AUTH)
@CrossOrigin(origins = ApiConstants.ALL_ORIGINS)
public class AuthController
{

	private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

	private final AppUserService appUserService;

	private final AuthenticationManager authenticationManager;

	private final SecurityContextRepository securityContextRepository;

	private final AuthenticationTrustResolver trustResolver;

	public AuthController(final AppUserService appUserService,
			final AuthenticationManager authenticationManager,
			final SecurityContextRepository securityContextRepository,
			final AuthenticationTrustResolver trustResolver)
	{
		this.appUserService = appUserService;
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
		this.trustResolver = trustResolver;
	}

	/**
	 * Creates an account and signs it straight in, so registering lands the visitor on the demo
	 * already logged in.
	 */
	@PostMapping(SubPaths.REGISTER)
	public ResponseEntity<UserDTO> register(@RequestBody final RegistrationRequest request,
			final HttpServletRequest httpRequest,
			final HttpServletResponse httpResponse)
	{
		final AppUser user = appUserService.register(request);
		authenticate(user.getUsername(), request.password(), httpRequest, httpResponse);

		return ResponseEntity.status(HttpStatus.CREATED).body(UserDTO.from(user));
	}

	@PostMapping(SubPaths.LOGIN)
	public ResponseEntity<UserDTO> login(@RequestBody final LoginRequest request,
			final HttpServletRequest httpRequest,
			final HttpServletResponse httpResponse)
	{
		final Authentication authentication =
				authenticate(request.username(), request.password(), httpRequest, httpResponse);

		return appUserService.getByUsername(authentication.getName())
				.map(user -> ResponseEntity.ok(UserDTO.from(user)))
				.orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
	}

	@PostMapping(SubPaths.LOGOUT)
	public ResponseEntity<Void> logout(final HttpServletRequest httpRequest)
	{
		final HttpSession session = httpRequest.getSession(false);
		if (session != null)
		{
			session.invalidate();
		}
		SecurityContextHolder.clearContext();

		return ResponseEntity.noContent().build();
	}

	/**
	 * Always answers 200 - the page uses it to decide whether to show "Sign in" or the account panel.
	 */
	@GetMapping(SubPaths.ME)
	public Map<String, Object> currentUser(final Authentication authentication)
	{
		if (!trustResolver.isAuthenticated(authentication))
		{
			return Map.of(ResponseKeys.AUTHENTICATED, false);
		}

		return appUserService.getByUsername(authentication.getName())
				.<Map<String, Object>> map(
						user -> Map.of(ResponseKeys.AUTHENTICATED, true, ResponseKeys.USER, UserDTO.from(user)))
				.orElseGet(() -> Map.of(ResponseKeys.AUTHENTICATED, false));
	}

	/**
	 * The one endpoint in the demo that is not public - it answers 401 without a session.
	 */
	@GetMapping(SubPaths.MEMBERS)
	public Map<String, String> membersOnly(final Authentication authentication)
	{
		return Map.of(ResponseKeys.MESSAGE, String.format(Messages.MEMBERS_ONLY, authentication.getName()),
				ResponseKeys.TIMESTAMP, String.valueOf(System.currentTimeMillis()));
	}

	private Authentication authenticate(final String username,
			final String password,
			final HttpServletRequest httpRequest,
			final HttpServletResponse httpResponse)
	{
		final Authentication authentication = authenticationManager
				.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));

		// Rotate the id of an existing session so a pre-login session cannot be reused after
		// sign-in. With no session yet, saveContext below creates a fresh one.
		if (httpRequest.getSession(false) != null)
		{
			httpRequest.changeSessionId();
		}

		final SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, httpRequest, httpResponse);

		logger.info("AuthController: '{}' signed in", authentication.getName());

		return authentication;
	}

	@ExceptionHandler(RegistrationException.class)
	public ResponseEntity<Map<String, String>> handleRegistrationException(final RegistrationException exception)
	{
		return ResponseEntity.badRequest().body(Map.of(ResponseKeys.ERROR, exception.getMessage()));
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<Map<String, String>> handleAuthenticationException(final AuthenticationException exception)
	{
		logger.info("AuthController: sign-in rejected - {}", exception.getMessage());

		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(Map.of(ResponseKeys.ERROR, Messages.INVALID_CREDENTIALS));
	}
}
