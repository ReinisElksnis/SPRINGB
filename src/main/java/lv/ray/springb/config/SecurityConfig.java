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


@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig
{

	/**
	 * Signing in is optional in this demo: every existing endpoint stays public, and only
	 * {@code /api/auth/members} requires an authenticated session so the difference is visible.
	 */
	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception
	{
		http
				.csrf(csrf -> csrf.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(Endpoints.AUTH_MEMBERS).authenticated()
						.requestMatchers(Endpoints.ACCOUNTS, Endpoints.ACCOUNTS + "/**").authenticated()
						.anyRequest().permitAll()
				)
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
