package lv.ray.springb.security;

import jakarta.annotation.Resource;
import lv.ray.springb.entity.AppUser;
import lv.ray.springb.repository.AppUserRepository;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Bridges the {@link AppUser} entity into Spring Security. Accepts either the username or the email
 * address as the login identifier.
 */
@Service
public class AppUserDetailsService implements UserDetailsService
{
	@Resource
	private AppUserRepository appUserRepository;

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(final String identifier) throws UsernameNotFoundException
	{
		final AppUser user = appUserRepository.findByUsername(identifier)
				.or(() -> appUserRepository.findByEmail(identifier.toLowerCase()))
				.orElseThrow(() -> new UsernameNotFoundException("No account found for '" + identifier + "'"));

		return User.withUsername(user.getUsername())
				.password(user.getPasswordHash())
				.authorities(user.getRole())
				.disabled(!user.isEnabled())
				.build();
	}
}
