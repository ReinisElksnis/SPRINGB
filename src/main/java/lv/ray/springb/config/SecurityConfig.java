package lv.ray.springb.config;

import lv.ray.springb.constants.ApiConstants.Endpoints;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;


@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig
{

	/**
	 * Two groups of endpoints require an authenticated session: everything under
	 * {@code /api/accounts} (the whole money API - balances, deposits, withdrawals, transfers,
	 * reconciliation) and {@code /api/auth/members}. Everything else - the greeting endpoint, the
	 * customer/order demo endpoints, and the auth endpoints needed to obtain a session in the
	 * first place - stays public, so the demo is still browsable without signing in.
	 */
	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception
	{
		http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(Endpoints.AUTH_MEMBERS).authenticated()
						.requestMatchers(Endpoints.ACCOUNTS, Endpoints.ACCOUNTS + "/**").authenticated()
						.anyRequest().permitAll()
				)
				// Session-cookie auth (see securityContextRepository()) is what makes this app CSRF-vulnerable
				// in the first place, so this stays enabled. CookieCsrfTokenRepository is the SPA-friendly
				// choice - static/index.html reads the XSRF-TOKEN cookie and echoes it back as a header on
				// every mutating request, instead of a server-rendered form field.
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
				)
				.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
				)
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.logout(logout -> logout.disable());

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder()
	{
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(final UserDetailsService userDetailsService,
			final PasswordEncoder passwordEncoder)
	{
		final DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);

		return new ProviderManager(provider);
	}

	/**
	 * Lets callers tell a real login apart from the anonymous authentication Spring Security
	 * supplies for guests, without testing the principal against a literal.
	 */
	@Bean
	public AuthenticationTrustResolver authenticationTrustResolver()
	{
		return new AuthenticationTrustResolverImpl();
	}

	/**
	 * Sessions are persisted to PostgreSQL by Spring Session JDBC, so a login survives a restart.
	 */
	@Bean
	public SecurityContextRepository securityContextRepository()
	{
		return new HttpSessionSecurityContextRepository();
	}
}
