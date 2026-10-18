-- Run manually with the intended soalku/soalku_it_* search_path; never inside a transaction.
-- Repeated execution is safe. Preserve all application data.
SET lock_timeout = '3s';
DO $$ BEGIN
    IF current_schema() !~ '^soalku($|_)' THEN
        RAISE EXCEPTION 'Select the intended SoalKu schema first';
    END IF;
    IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE version::numeric > 2) THEN
        RAISE EXCEPTION 'Roll back later migrations first';
    END IF;
END $$;
DROP INDEX CONCURRENTLY IF EXISTS uq_materi_file_path;
DELETE FROM flyway_schema_history WHERE version = '2';
RESET lock_timeout;
