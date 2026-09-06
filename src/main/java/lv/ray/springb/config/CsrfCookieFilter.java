package lv.ray.springb.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;


/**
 * {@code CsrfToken} deferred loading means the {@code XSRF-TOKEN} cookie is only written once
 * something actually calls {@link CsrfToken#getToken()} - which nothing does by default, since
 * this app has no server-rendered form reading it out of a request attribute. Without this filter
 * the cookie would never appear, and the frontend would have no token to echo back on the next
 * mutating request. Forcing that call on every request is the standard fix for SPA-style clients.
 */
public class CsrfCookieFilter extends OncePerRequestFilter
{

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain filterChain) throws ServletException, IOException
	{
		final CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		if (csrfToken != null)
		{
			csrfToken.getToken();
		}
		filterChain.doFilter(request, response);
	}
}
