package lv.ray.springb.web;

import lv.ray.springb.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real {@code SecurityFilterChain} (see {@code SecurityConfig}) through MockMvc exactly
 * the way {@code index.html}/{@code login.html}/{@code register.html} do from a browser: a GET
 * response sets the {@code XSRF-TOKEN} cookie, and that same cookie is sent back alongside an
 * {@code X-XSRF-TOKEN} header carrying its value on every mutating request - MockMvc, unlike a real
 * browser, does not resend cookies across separate {@code perform()} calls on its own, so both have
 * to be attached explicitly here. Every other test in this codebase either mocks the security layer
 * away entirely or never sends an HTTP request through it at all, so none of them would have caught
 * the regression this class exists to prevent: a security config change that leaves every mutating
 * endpoint - registration and login included - returning 403 for every real client, or one that
 * silently drops CSRF protection back to disabled.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityCsrfIntegrationTests
{

	@Autowired
	private MockMvc mockMvc;

	@Test
	void safeMethodsNeverNeedACsrfToken() throws Exception
	{
		mockMvc.perform(get("/api/hello"))
				.andExpect(status().isOk());
	}

	@Test
	void registeringWithTheCsrfTokenFromTheCookieSucceeds() throws Exception
	{
		final Cookie csrf = fetchCsrfCookie();

		mockMvc.perform(post("/api/auth/register")
						.cookie(csrf)
						.header("X-XSRF-TOKEN", csrf.getValue())
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("csrf-ok-" + UUID.randomUUID())))
				.andExpect(status().isCreated());
	}

	@Test
	void registeringWithTheCookieButNoHeaderIsRejected() throws Exception
	{
		final Cookie csrf = fetchCsrfCookie();

		mockMvc.perform(post("/api/auth/register")
						.cookie(csrf)
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("csrf-no-header-" + UUID.randomUUID())))
				.andExpect(status().isForbidden());
	}

	@Test
	void aHeaderThatDoesNotMatchTheCookieIsRejected() throws Exception
	{
		final Cookie csrf = fetchCsrfCookie();

		mockMvc.perform(post("/api/auth/register")
						.cookie(csrf)
						.header("X-XSRF-TOKEN", "not-the-real-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("csrf-mismatch-" + UUID.randomUUID())))
				.andExpect(status().isForbidden());
	}

	/**
	 * Covers the actual payments surface, and the interaction between the two independent checks:
	 * CSRF (does the request carry a valid token?) and authorization (is the caller signed in?)
	 * must each be enforced regardless of whether the other one passes.
	 */
	@Test
	void creatingAnAccountRequiresBothAnAuthenticatedSessionAndAValidCsrfToken() throws Exception
	{
		final String username = "csrf-account-" + UUID.randomUUID();
		final Cookie registerCsrf = fetchCsrfCookie();

		final MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
						.cookie(registerCsrf)
						.header("X-XSRF-TOKEN", registerCsrf.getValue())
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson(username)))
				.andExpect(status().isCreated())
				.andReturn();

		final Cookie session = registerResult.getResponse().getCookie("SESSION");
		assertThat(session).as("register() signs the caller in immediately, per its own Javadoc").isNotNull();

		// Authenticated session + a valid token → succeeds.
		final Cookie accountCsrf = fetchCsrfCookie();
		mockMvc.perform(post("/api/accounts")
						.cookie(session, accountCsrf)
						.header("X-XSRF-TOKEN", accountCsrf.getValue())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated());

		// Authenticated but no token → 403, not 201 or 500 - this is the exact regression that
		// shipped once already (see SecurityConfig's Javadoc on the csrf() call).
		mockMvc.perform(post("/api/accounts")
						.cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\"}"))
				.andExpect(status().isForbidden());

		// A valid token but no session → 401 - authorization is enforced independently of CSRF.
		final Cookie anonymousCsrf = fetchCsrfCookie();
		mockMvc.perform(post("/api/accounts")
						.cookie(anonymousCsrf)
						.header("X-XSRF-TOKEN", anonymousCsrf.getValue())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\"}"))
				.andExpect(status().isUnauthorized());
	}

	private Cookie fetchCsrfCookie() throws Exception
	{
		final MvcResult result = mockMvc.perform(get("/api/hello")).andReturn();
		final Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).as("CsrfCookieFilter should force the XSRF-TOKEN cookie onto every response").isNotNull();
		return cookie;
	}

	private static String registrationJson(final String username)
	{
		return "{\"username\":\"" + username + "\",\"email\":\"" + username
				+ "@example.com\",\"password\":\"Password123!\",\"displayName\":\"CSRF Test\"}";
	}
}
