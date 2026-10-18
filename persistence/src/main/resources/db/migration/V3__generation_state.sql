-- Metadata-only changes with short lock waits; validate separately after the metadata locks release.
SET lock_timeout = '3s';
ALTER TABLE bagian_materi ADD COLUMN IF NOT EXISTS generated_at TIMESTAMPTZ;
ALTER TABLE bagian_materi ADD COLUMN IF NOT EXISTS generation_token UUID;
ALTER TABLE bagian_materi ADD COLUMN IF NOT EXISTS generation_started_at TIMESTAMPTZ;
ALTER TABLE soal ADD COLUMN IF NOT EXISTS urutan_generasi INTEGER;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_bagian_generation' AND conrelid='bagian_materi'::regclass) THEN
        ALTER TABLE bagian_materi ADD CONSTRAINT ck_bagian_generation CHECK (
            (generation_token IS NULL) = (generation_started_at IS NULL)
            AND (generated_at IS NULL OR (teks_ringkasan IS NOT NULL AND btrim(teks_ringkasan) <> '' AND generation_token IS NULL))
        ) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_soal_urutan_generasi' AND conrelid='soal'::regclass) THEN
        ALTER TABLE soal ADD CONSTRAINT ck_soal_urutan_generasi CHECK (urutan_generasi BETWEEN 1 AND 10) NOT VALID;
    END IF;
END $$;
ALTER TABLE bagian_materi VALIDATE CONSTRAINT ck_bagian_generation;
ALTER TABLE soal VALIDATE CONSTRAINT ck_soal_urutan_generasi;
DROP INDEX CONCURRENTLY IF EXISTS uq_soal_generasi;
CREATE UNIQUE INDEX CONCURRENTLY uq_soal_generasi ON soal(bagian_materi_id,urutan_generasi) WHERE urutan_generasi IS NOT NULL;
-- On repair of a partially applied V3, release the new FK before rebuilding its supporting index.
ALTER TABLE soal DROP CONSTRAINT IF EXISTS fk_soal_gambar_bagian;
DROP INDEX CONCURRENTLY IF EXISTS uq_gambar_bagian_id;
CREATE UNIQUE INDEX CONCURRENTLY uq_gambar_bagian_id ON gambar_materi(bagian_materi_id,id);
DROP INDEX CONCURRENTLY IF EXISTS idx_soal_bagian_gambar;
CREATE INDEX CONCURRENTLY idx_soal_bagian_gambar ON soal(bagian_materi_id,gambar_id);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_soal_gambar_bagian' AND conrelid='soal'::regclass) THEN
        ALTER TABLE soal ADD CONSTRAINT fk_soal_gambar_bagian FOREIGN KEY (bagian_materi_id,gambar_id)
            REFERENCES gambar_materi(bagian_materi_id,id) NOT VALID;
    END IF;
END $$;
ALTER TABLE soal VALIDATE CONSTRAINT fk_soal_gambar_bagian;
RESET lock_timeout;
