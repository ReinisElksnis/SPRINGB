package lv.ray.springb.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;


/**
 * Spring Security 6's default handler XORs the token it renders (BREACH protection for a
 * server-rendered {@code _csrf} form field), but this app has no such form - the frontend reads
 * the raw value {@link CookieCsrfTokenRepository} put in the {@code XSRF-TOKEN} cookie and echoes
 * it back verbatim as the {@code X-XSRF-TOKEN} header, so a submission that way must be compared
 * against the raw token, not decoded as if it were XOR-encoded. Delegating {@link #handle} to the
 * XOR handler keeps BREACH protection for the (unused here) parameter-based path; overriding
 * {@link #resolveCsrfTokenValue} to read the header directly, without XOR-decoding it, is what
 * makes the header-based path work at all.
 */
final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler
{

	private final CsrfTokenRequestHandler xorHandler = new XorCsrfTokenRequestAttributeHandler();

	@Override
	public void handle(final HttpServletRequest request, final HttpServletResponse response,
			final Supplier<CsrfToken> csrfToken)
	{
		this.xorHandler.handle(request, response, csrfToken);
	}

	@Override
	public String resolveCsrfTokenValue(final HttpServletRequest request, final CsrfToken csrfToken)
	{
		final String headerValue = request.getHeader(csrfToken.getHeaderName());
		return StringUtils.hasText(headerValue) ? headerValue : super.resolveCsrfTokenValue(request, csrfToken);
	}
}
