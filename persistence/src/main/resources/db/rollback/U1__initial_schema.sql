-- Manual rollback for Flyway Community; NOT discovered by Flyway migrate.
-- Stop application writers and select the SoalKu schema before running.
-- Refuse data loss, later migrations, unrelated tables, and prolonged locks.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

DO $rollback$
DECLARE
    target_schema TEXT := current_schema();
    table_name TEXT;
    has_data BOOLEAN;
    has_later_migrations BOOLEAN;
    application_tables TEXT[] := ARRAY[
        'riwayat_jawaban', 'sesi_latihan', 'opsi_jawaban', 'soal',
        'gambar_materi', 'bagian_materi', 'materi', 'kode_undangan', 'pengguna'
    ];
BEGIN
    IF target_schema IS NULL OR target_schema NOT LIKE 'soalku%' THEN
        RAISE EXCEPTION 'Select the dedicated SoalKu schema before rollback';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables t
        WHERE t.table_schema = target_schema AND t.table_type = 'BASE TABLE'
            AND NOT (t.table_name = ANY(application_tables))
            AND t.table_name <> 'flyway_schema_history'
    ) THEN
        RAISE EXCEPTION 'Rollback refused: schema contains unrelated or later tables';
    END IF;

    IF to_regclass(format('%I.flyway_schema_history', target_schema)) IS NOT NULL THEN
        EXECUTE format('LOCK TABLE %I.flyway_schema_history IN ACCESS EXCLUSIVE MODE', target_schema);
        EXECUTE format(
            'SELECT EXISTS (SELECT 1 FROM %I.flyway_schema_history WHERE type <> ''SCHEMA'' AND version IS DISTINCT FROM ''1'')',
            target_schema) INTO has_later_migrations;
        IF has_later_migrations THEN
            RAISE EXCEPTION 'Rollback refused: revert later migrations first';
        END IF;
    END IF;

    -- Hold locks through the emptiness checks and drops, preventing concurrent inserts.
    FOREACH table_name IN ARRAY application_tables LOOP
        IF to_regclass(format('%I.%I', target_schema, table_name)) IS NOT NULL THEN
            EXECUTE format('LOCK TABLE %I.%I IN ACCESS EXCLUSIVE MODE', target_schema, table_name);
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I LIMIT 1)', target_schema, table_name) INTO has_data;
            IF has_data THEN
                RAISE EXCEPTION 'Rollback refused: table % contains data; use a verified backup/restore plan', table_name;
            END IF;
        END IF;
    END LOOP;

    FOREACH table_name IN ARRAY application_tables LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I.%I', target_schema, table_name);
    END LOOP;

    -- Keep Flyway history consistent so migrate can apply V1 again after rollback.
    IF to_regclass(format('%I.flyway_schema_history', target_schema)) IS NOT NULL THEN
        EXECUTE format('DELETE FROM %I.flyway_schema_history WHERE version = ''1''', target_schema);
    END IF;
END
$rollback$;
COMMIT;
