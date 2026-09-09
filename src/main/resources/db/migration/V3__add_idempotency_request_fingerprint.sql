-- Records what each idempotency key was actually spent on (operation type, account(s), amount), so
-- a replay can be checked for shape and not just for caller. Without this column, one key reused
-- across two different requests by the same caller passed the owner check and returned the FIRST
-- request's result with HTTP 200 - silently dropping the second operation.
--
-- Nullable on purpose: rows written before this column existed have no fingerprint, and a replay of
-- one of those keys must not start failing. AccountServiceImpl reads null as "legacy row, shape
-- unknown" and skips the shape comparison; the owner check still applies to them.

ALTER TABLE idempotency_records
    ADD COLUMN request_fingerprint VARCHAR(200);
