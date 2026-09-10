-- Adds the optimistic-locking version counter to accounts, for the alternative transfer path in
-- OptimisticAccountMutationExecutor. The existing pessimistic path is unaffected in behaviour: it
-- takes SELECT ... FOR UPDATE row locks before reading, so no second writer can ever have read the
-- same version, and the check below can never fail for it. It simply bumps the counter as it goes.
--
-- What the column buys is a way to detect a lost update *without* holding a lock for the duration
-- of the read-modify-write. Hibernate appends the version it read to every UPDATE:
--
--     UPDATE accounts SET balance = ?, version = 6 WHERE id = ? AND version = 5
--
-- If a concurrent transaction already moved the row to version 6, that statement matches zero rows,
-- Hibernate notices the mismatch between rows expected and rows affected, and throws instead of
-- silently overwriting. The conflict is detected at write time rather than prevented at read time -
-- which is the entire difference between the two strategies.
--
-- NOT NULL DEFAULT 0 so rows that already exist adopt a valid starting version rather than needing
-- a backfill; Hibernate takes over the value from the first update onwards.

ALTER TABLE accounts
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
