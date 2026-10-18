-- Nullable metadata preserves legacy sessions; new sessions store their immutable public question snapshot.
SET lock_timeout = '3s';
ALTER TABLE sesi_latihan ADD COLUMN IF NOT EXISTS request_key UUID;
ALTER TABLE sesi_latihan ADD COLUMN IF NOT EXISTS requested_count SMALLINT;
ALTER TABLE sesi_latihan ADD COLUMN IF NOT EXISTS soal_snapshot JSONB;
ALTER TABLE sesi_latihan ADD COLUMN IF NOT EXISTS berakhir_at TIMESTAMPTZ;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_sesi_snapshot' AND conrelid='sesi_latihan'::regclass) THEN
        ALTER TABLE sesi_latihan ADD CONSTRAINT ck_sesi_snapshot CHECK (
            (request_key IS NULL AND requested_count IS NULL AND soal_snapshot IS NULL AND berakhir_at IS NULL)
            OR (request_key IS NOT NULL AND requested_count IS NOT NULL AND requested_count BETWEEN 1 AND 50
                AND soal_snapshot IS NOT NULL
                AND CASE WHEN jsonb_typeof(soal_snapshot)='array' THEN jsonb_array_length(soal_snapshot) BETWEEN 1 AND requested_count ELSE false END
                AND ((mode='BERWAKTU' AND berakhir_at IS NOT NULL AND berakhir_at > mulai_at)
                    OR (mode<>'BERWAKTU' AND berakhir_at IS NULL)))
        ) NOT VALID;
    END IF;
END $$;
ALTER TABLE sesi_latihan VALIDATE CONSTRAINT ck_sesi_snapshot;
-- Rebuild safely after interrupted non-transactional index creation.
DROP INDEX CONCURRENTLY IF EXISTS uq_sesi_request;
CREATE UNIQUE INDEX CONCURRENTLY uq_sesi_request ON sesi_latihan(siswa_id,request_key) WHERE request_key IS NOT NULL;
RESET lock_timeout;
