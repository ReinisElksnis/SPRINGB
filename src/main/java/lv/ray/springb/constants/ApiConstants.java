package lv.ray.springb.constants;

/**
 * Fixed values shared across the web layer: request paths, JSON field names and user-facing text.
 * <p>
 * Nothing here is externalised - these are contract details that a deployment must not be able to
 * change, unlike the tunable policy in {@link lv.ray.springb.config.AuthProperties}. Every value is
 * a compile-time constant, so it can be used inside annotations.
 */
public final class ApiConstants
{

	private ApiConstants()
	{
	}

	/** CORS origin used by the demo controllers. */
	public static final String ALL_ORIGINS = "*";

	/**
	 * Controller base paths.
	 */
	public static final class Endpoints
	{

		private Endpoints()
		{
		}

		public static final String API = "/api";

		public static final String CUSTOMERS = API + "/customers";

		public static final String ORDERS = API + "/orders";

		public static final String AUTH = API + "/auth";

		/**
		 * The full path of the members-only endpoint. The security filter chain and the handler
		 * both derive it from here so the two cannot drift apart.
		 */
		public static final String AUTH_MEMBERS = AUTH + SubPaths.MEMBERS;

		public static final String ACCOUNTS = API + "/accounts";
	}

	/**
	 * Handler paths, relative to the controller base they sit under.
	 */
	public static final class SubPaths
	{

		private SubPaths()
		{
		}

		public static final String BY_ID = "/{id}";

		public static final String HELLO = "/hello";

		public static final String REGISTER = "/register";

		public static final String LOGIN = "/login";

		public static final String LOGOUT = "/logout";

		public static final String ME = "/me";

		public static final String MEMBERS = "/members";

		public static final String OPERATIONS = "/operations";

		public static final String DEPOSITS = "/deposits";

		public static final String WITHDRAWALS = "/withdrawals";

		public static final String TRANSFERS = "/transfers";

		public static final String RECONCILE = "/reconcile";

		/** Alternative transfer path using optimistic locking - see OptimisticAccountMutationExecutor. */
		public static final String OPTIMISTIC = "/optimistic";
	}

	/**
	 * Field names in the hand-built {@code Map} responses.
	 */
	public static final class ResponseKeys
	{

		private ResponseKeys()
		{
		}

		public static final String MESSAGE = "message";

		public static final String TIMESTAMP = "timestamp";

		public static final String ERROR = "error";

		public static final String AUTHENTICATED = "authenticated";

		public static final String USER = "user";
	}

	/**
	 * User-facing text. Format strings are applied with {@link String#format}.
	 */
	public static final class Messages
	{

		private Messages()
		{
		}

		public static final String GREETING = "Hello, %s!";

		public static final String USERNAME_TOO_SHORT = "Username must be at least %d characters long";

		public static final String PASSWORD_TOO_SHORT = "Password must be at least %d characters long";

		public static final String INVALID_EMAIL = "A valid email address is required";

		public static final String USERNAME_TAKEN = "That username is already taken";

		public static final String EMAIL_TAKEN = "An account with that email already exists";

		/** Deliberately vague: it must not reveal whether the account exists. */
		public static final String INVALID_CREDENTIALS = "Invalid username or password";

		public static final String ACCOUNT_NOT_FOUND = "No account found for '%s'";

		public static final String MEMBERS_ONLY = "Members-only content for %s";

		/** Deliberately identical whether the id is unknown or simply belongs to someone else. */
		public static final String FUNDS_ACCOUNT_NOT_FOUND = "No account found with id %d";

		/** The coarse, currency-agnostic check - {@link #INVALID_AMOUNT_FOR_CURRENCY} is the precise one. */
		public static final String INVALID_AMOUNT = "Amount must be positive, with at most 3 decimal places";

		public static final String INVALID_AMOUNT_FOR_CURRENCY = "%s allows at most %d decimal place(s)";

		public static final String INVALID_CURRENCY = "Currency must be a real ISO 4217 code";

		public static final String INSUFFICIENT_FUNDS = "Account %d has insufficient funds for this operation";

		public static final String CURRENCY_MISMATCH = "Accounts %d and %d do not share a currency";

		public static final String SAME_ACCOUNT_TRANSFER = "Cannot transfer an account to itself";

		public static final String IDEMPOTENCY_KEY_REQUIRED = "Idempotency-Key header is required for this operation";

		/** Fallback when a request body is rejected but no field-level detail is available. */
		public static final String INVALID_REQUEST_BODY = "Request body is missing required fields";

		/** A key match alone never proves a replay is legitimate - see IdempotencyRecord's Javadoc. */
		public static final String IDEMPOTENCY_KEY_CONFLICT =
				"Idempotency-Key was already used for a different request";

		/**
		 * The optimistic path gave up. 409 rather than 500: nothing is broken and the request was
		 * valid - it simply lost every race it was given, and retrying later is the correct response.
		 */
		public static final String TRANSFER_CONTENTION_EXHAUSTED =
				"Transfer could not be completed after %d attempts because the accounts are under "
						+ "heavy concurrent modification; retry the request";
	}

	/**
	 * Fallbacks applied when request input is absent.
	 */
	public static final class Defaults
	{

		private Defaults()
		{
		}

		public static final String GREETING_NAME = "World";

		public static final String ACCOUNT_CURRENCY = "EUR";
	}
}
