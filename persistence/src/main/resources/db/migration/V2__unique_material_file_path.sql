-- Non-transactional CONCURRENTLY avoids blocking reads/writes of a large materi table.
-- A failed concurrent build may leave an invalid index: drop it before retrying after Flyway repair.
DROP INDEX CONCURRENTLY IF EXISTS uq_materi_file_path;
CREATE UNIQUE INDEX CONCURRENTLY uq_materi_file_path ON materi(file_path);
