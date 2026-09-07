package lv.ray.springb.service.validation;

import lv.ray.springb.service.AccountException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * No mocks needed - both methods under test are pure functions over {@link java.util.Currency},
 * which is why this never had its own test class before: everything about it was only ever
 * exercised indirectly, through a mocked {@code AccountOperationValidator} in
 * {@code AccountServiceImplTests}, which proves nothing about what the real implementation
 * actually accepts or rejects.
 */
class AccountOperationValidatorTests
{

	private final AccountOperationValidator validator = new AccountOperationValidator();

	@Test
	void validateCurrency_acceptsRealIsoCodesRegardlessOfFractionDigitCount()
	{
		assertThatCode(() -> validator.validateCurrency("EUR")).doesNotThrowAnyException();
		assertThatCode(() -> validator.validateCurrency("JPY")).doesNotThrowAnyException(); // 0 minor units
		assertThatCode(() -> validator.validateCurrency("KWD")).doesNotThrowAnyException(); // 3 minor units
	}

	@Test
	void validateCurrency_rejectsACodeThatLooksRightButIsntARealIsoCurrency()
	{
		// The old regex-only check ("^[A-Z]{3}$") would have let this straight through.
		assertThatThrownBy(() -> validator.validateCurrency("ZZZ")).isInstanceOf(AccountException.class);
	}

	@Test
	void validateCurrency_rejectsLowercaseAndNull()
	{
		assertThatThrownBy(() -> validator.validateCurrency("eur")).isInstanceOf(AccountException.class);
		assertThatThrownBy(() -> validator.validateCurrency(null)).isInstanceOf(AccountException.class);
	}

	@Test
	void validateAmount_rejectsNullNonPositiveAndImpossiblyPreciseAmounts()
	{
		assertThatThrownBy(() -> validator.validateAmount(null)).isInstanceOf(AccountException.class);
		assertThatThrownBy(() -> validator.validateAmount(BigDecimal.ZERO)).isInstanceOf(AccountException.class);
		assertThatThrownBy(() -> validator.validateAmount(new BigDecimal("-5.00"))).isInstanceOf(AccountException.class);
		// 4 decimal places - more than any real currency's minor units - is rejected before an
		// account (and therefore a currency) is even known.
		assertThatThrownBy(() -> validator.validateAmount(new BigDecimal("1.2345")))
				.isInstanceOf(AccountException.class);
	}

	@Test
	void validateAmount_acceptsAnythingUpToThreeDecimalPlaces()
	{
		// This coarse check can't know the currency yet, so it has to allow the most precise real
		// currency (3 decimal places, e.g. KWD) even for amounts that will later turn out to be
		// too precise for whatever currency they actually get validated against.
		assertThatCode(() -> validator.validateAmount(new BigDecimal("10.500"))).doesNotThrowAnyException();
	}

	@Test
	void validateAmountScale_acceptsTwoDecimalPlacesForEur()
	{
		assertThatCode(() -> validator.validateAmountScale(new BigDecimal("10.50"), "EUR"))
				.doesNotThrowAnyException();
	}

	@Test
	void validateAmountScale_rejectsAFractionalYen()
	{
		// This is the exact bug the old hardcoded "scale > 2" check had: it would have accepted
		// 100.5 JPY, a currency with no fractional minor unit at all.
		assertThatThrownBy(() -> validator.validateAmountScale(new BigDecimal("100.5"), "JPY"))
				.isInstanceOf(AccountException.class)
				.hasMessageContaining("JPY");
	}

	@Test
	void validateAmountScale_acceptsWholeYen()
	{
		assertThatCode(() -> validator.validateAmountScale(new BigDecimal("1500"), "JPY")).doesNotThrowAnyException();
	}

	@Test
	void validateAmountScale_acceptsThreeDecimalPlacesForKuwaitiDinar()
	{
		// This is the other direction of the same old bug: 15.500 KWD is perfectly valid currency
		// precision, but the old hardcoded "scale > 2" check would have rejected it outright.
		assertThatCode(() -> validator.validateAmountScale(new BigDecimal("15.500"), "KWD"))
				.doesNotThrowAnyException();
	}

	@Test
	void validateAmountScale_rejectsFourDecimalPlacesEvenForKuwaitiDinar()
	{
		assertThatThrownBy(() -> validator.validateAmountScale(new BigDecimal("15.5000"), "KWD"))
				.isInstanceOf(AccountException.class);
	}

	@Test
	void validateAmountScale_doesNotRejectEveryAmountForACurrencyWithNoDefinedFractionDigits()
	{
		// XXX ("no currency") is a real ISO 4217 code, but Currency.getDefaultFractionDigits()
		// returns -1 for it - without clamping that to 0, "scale > -1" would be true for every
		// amount with any scale at all, rejecting whole-number amounts too.
		assertThatCode(() -> validator.validateAmountScale(new BigDecimal("5"), "XXX")).doesNotThrowAnyException();
	}
}
