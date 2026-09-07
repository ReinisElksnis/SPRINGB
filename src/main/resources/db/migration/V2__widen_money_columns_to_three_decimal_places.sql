-- AccountOperationValidator.validateAmountScale now allows up to 3 decimal places, matching real
-- currencies whose ISO 4217 minor-unit count is 3 (KWD, BHD, OMR, JOD, TND) rather than assuming
-- every currency has 2 like EUR/USD. NUMERIC(19,2) would silently round a 3-decimal amount down to
-- 2 on write (verified: 15.500::numeric(19,2) becomes 15.50, no error) - exactly the class of
-- silent data-loss bug the validation fix was meant to prevent, just moved one layer down. Widening
-- scale 2 -> 3 is safe for existing data: every current value gains a trailing zero, nothing is
-- rounded or truncated.

ALTER TABLE accounts
    ALTER COLUMN balance TYPE NUMERIC(19, 3);

ALTER TABLE operations
    ALTER COLUMN amount TYPE NUMERIC(19, 3),
    ALTER COLUMN balance_after TYPE NUMERIC(19, 3);
