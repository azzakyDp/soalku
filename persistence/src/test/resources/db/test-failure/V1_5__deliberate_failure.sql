-- Test-only fixture: verify PostgreSQL/Flyway rolls back DDL on failure.
CREATE TABLE partial_migration (id BIGINT PRIMARY KEY);
SELECT 1 / 0;
