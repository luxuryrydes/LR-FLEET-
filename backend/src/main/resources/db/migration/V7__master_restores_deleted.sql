-- V7: a vehicle master upload can bring back vehicles that were deleted (they are listed on the confirm step first).
ALTER TABLE import_batches
    ADD COLUMN restored_rows        INT NOT NULL DEFAULT 0,
    ADD COLUMN deleted_skipped_rows INT NOT NULL DEFAULT 0;
