-- Stop session-start requests. Execute statements separately, with the intended schema selected.
SET lock_timeout = '3s';
DO $$ BEGIN
    IF current_schema() !~ '^soalku($|_)' THEN RAISE EXCEPTION 'Select the intended SoalKu schema first'; END IF;
    IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE version::numeric > 4) THEN RAISE EXCEPTION 'Roll back later migrations first'; END IF;
    IF EXISTS (SELECT 1 FROM sesi_latihan s WHERE to_jsonb(s)->>'request_key' IS NOT NULL) THEN
        RAISE EXCEPTION 'Practice snapshots are in use; preserve/export and restore with a maintenance plan before rollback';
    END IF;
END $$;
DROP INDEX CONCURRENTLY IF EXISTS uq_sesi_request;
ALTER TABLE sesi_latihan DROP CONSTRAINT IF EXISTS ck_sesi_snapshot;
ALTER TABLE sesi_latihan DROP COLUMN IF EXISTS request_key, DROP COLUMN IF EXISTS requested_count,
    DROP COLUMN IF EXISTS soal_snapshot, DROP COLUMN IF EXISTS berakhir_at;
DELETE FROM flyway_schema_history WHERE version='4';
RESET lock_timeout;
