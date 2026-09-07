package lv.ray.springb.service.validation;

import lv.ray.springb.constants.ApiConstants.Messages;
import lv.ray.springb.entity.Account;
import lv.ray.springb.service.AccountException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Currency;


@Component
public class AccountOperationValidator
{
	/**
	 * No live ISO 4217 currency has more minor units than this (KWD/BHD/OMR/JOD/TND use 3 - the
	 * most of any currency still in circulation). This is deliberately looser than any single
	 * currency's real precision: it is the cheap, currency-agnostic filter that runs before an
	 * account (and therefore a currency) is even known - {@link #validateAmountScale} is the
	 * precise, per-currency check that runs once it is.
	 */
	private static final int MAX_POSSIBLE_MINOR_UNITS = 3;

	public void validateCurrency(final String currency)
	{
		if (currency == null || !isRealIsoCurrency(currency))
		{
			throw new AccountException(HttpStatus.BAD_REQUEST, Messages.INVALID_CURRENCY);
		}
	}

	public void validateAmount(final BigDecimal amount)
	{
		if (amount == null || amount.scale() > MAX_POSSIBLE_MINOR_UNITS || amount.compareTo(BigDecimal.ZERO) <= 0)
		{
			throw new AccountException(HttpStatus.BAD_REQUEST, Messages.INVALID_AMOUNT);
		}
	}

	/**
	 * ISO 4217 defines a minor-unit count per currency, not a universal one - JPY has none (never
	 * a fractional yen), EUR/USD have 2, KWD/BHD/OMR have 3. A single account/transfer is always
	 * one specific currency by the time this runs (after the account is loaded), so this is the
	 * check that actually enforces the right precision, rather than {@link #validateAmount}'s
	 * looser upper bound that has to work for every currency at once.
	 */
	public void validateAmountScale(final BigDecimal amount, final String currency)
	{
		final int allowedScale = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
		if (amount.scale() > allowedScale)
		{
			throw new AccountException(HttpStatus.BAD_REQUEST,
					String.format(Messages.INVALID_AMOUNT_FOR_CURRENCY, currency, allowedScale));
		}
	}

	private static boolean isRealIsoCurrency(final String currency)
	{
		try
		{
			Currency.getInstance(currency);
			return true;
		}
		catch (final IllegalArgumentException notARealCurrency)
		{
			return false;
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
