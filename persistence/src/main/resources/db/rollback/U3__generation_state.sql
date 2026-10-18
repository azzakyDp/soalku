-- Stop generate requests first. Execute each statement separately, without a transaction wrapper.
SET lock_timeout = '3s';
DO $$ BEGIN
    IF current_schema() !~ '^soalku($|_)' THEN RAISE EXCEPTION 'Select the intended SoalKu schema first'; END IF;
    IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE version::numeric > 3) THEN
        RAISE EXCEPTION 'Roll back later migrations first';
    END IF;
    IF EXISTS (SELECT 1 FROM bagian_materi b WHERE to_jsonb(b)->>'generated_at' IS NOT NULL OR to_jsonb(b)->>'generation_token' IS NOT NULL)
        OR EXISTS (SELECT 1 FROM soal s WHERE to_jsonb(s)->>'urutan_generasi' IS NOT NULL) THEN
        RAISE EXCEPTION 'Generation metadata is in use; preserve/export it before rollback';
    END IF;
END $$;
ALTER TABLE soal DROP CONSTRAINT IF EXISTS fk_soal_gambar_bagian;
DROP INDEX CONCURRENTLY IF EXISTS idx_soal_bagian_gambar;
DROP INDEX CONCURRENTLY IF EXISTS uq_gambar_bagian_id;
DROP INDEX CONCURRENTLY IF EXISTS uq_soal_generasi;
ALTER TABLE soal DROP CONSTRAINT IF EXISTS ck_soal_urutan_generasi;
ALTER TABLE soal DROP COLUMN IF EXISTS urutan_generasi;
ALTER TABLE bagian_materi DROP CONSTRAINT IF EXISTS ck_bagian_generation;
ALTER TABLE bagian_materi DROP COLUMN IF EXISTS generated_at, DROP COLUMN IF EXISTS generation_token, DROP COLUMN IF EXISTS generation_started_at;
DELETE FROM flyway_schema_history WHERE version='3';
RESET lock_timeout;
