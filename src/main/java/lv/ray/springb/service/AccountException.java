package lv.ray.springb.service;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an account operation is rejected - carries the HTTP status the rejection maps to,
 * so a single {@code @ExceptionHandler} in {@code AccountController} covers every case (bad
 * input -> 400, unknown/foreign account -> 404, insufficient funds -> 409).
 */
public class AccountException extends RuntimeException
{
	private final HttpStatus status;

	public AccountException(final HttpStatus status, final String message)
	{
		super(message);
		this.status = status;
	}

	/**
	 * Keeps the underlying failure attached. The message a client sees is deliberately vague about
	 * causes ("retry the request"), so without the cause a contention 409 would be untraceable in
	 * the logs - you would know a transfer gave up but not whether it lost version checks, deadlocked,
	 * or was interrupted. The cause never reaches the response body; {@code AccountController}'s
	 * handler only reads {@link #getMessage()}.
	 */
	public AccountException(final HttpStatus status, final String message, final Throwable cause)
	{
		super(message, cause);
		this.status = status;
	}

	public HttpStatus getStatus()
	{
		return status;
	}
}
