package lv.ray.springb.service.validation;

import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.entity.Account;
import lv.ray.springb.service.AccountException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Pattern;


@Component
public class AccountOperationValidator
{
	private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

	public void validateCurrency(final String currency)
	{
		if (currency == null || !CURRENCY_PATTERN.matcher(currency).matches())
		{
			throw new AccountException(HttpStatus.BAD_REQUEST, Messages.INVALID_CURRENCY);
		}
	}

	public void validateAmount(final BigDecimal amount)
	{
		if (amount == null || amount.scale() > 2 || amount.compareTo(BigDecimal.ZERO) <= 0)
		{
			throw new AccountException(HttpStatus.BAD_REQUEST, Messages.INVALID_AMOUNT);
		}
	}

	/**
	 * A caller acting on an account that is not theirs sees the same "not found" response as an
	 * unknown id - existence of someone else's account must not be observable.
	 */
	public void validateOwnership(final Account account, final String requestingUsername)
	{
		if (!account.getOwner().getUsername().equals(requestingUsername))
		{
			throw new AccountException(HttpStatus.NOT_FOUND,
					String.format(Messages.FUNDS_ACCOUNT_NOT_FOUND, account.getId()));
		}
	}

	public void validateSufficientFunds(final Account account, final BigDecimal amount)
	{
		if (account.getBalance().compareTo(amount) < 0)
		{
			throw new AccountException(HttpStatus.CONFLICT,
					String.format(Messages.INSUFFICIENT_FUNDS, account.getId()));

		}
	}

	public void validateSameCurrency(final Account first, final Account second)
	{
		if (!first.getCurrency().equals(second.getCurrency()))
		{
			throw new AccountException(HttpStatus.BAD_REQUEST,
					String.format(Messages.CURRENCY_MISMATCH, first.getId(), second.getId()));
		}
	}

	public void validateDistinctAccounts(final Long fromAccountId, final Long toAccountId)
	{
		if (fromAccountId.equals(toAccountId))
		{
			throw new AccountException(HttpStatus.BAD_REQUEST, Messages.SAME_ACCOUNT_TRANSFER);
		}
	}
}
