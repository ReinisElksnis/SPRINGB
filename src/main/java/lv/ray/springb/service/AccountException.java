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

	public HttpStatus getStatus()
	{
		return status;
	}
}
