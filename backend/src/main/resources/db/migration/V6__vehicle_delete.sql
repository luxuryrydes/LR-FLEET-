-- V6: deleting vehicles. A deleted vehicle (status DELETED) is hidden from every list, dashboard, report, import match and
-- alert, but its history is kept and it can be restored. An admin can remove it permanently only when it has no records.

ALTER TABLE vehicles DROP CONSTRAINT vehicles_status_check;
ALTER TABLE vehicles ADD CONSTRAINT vehicles_status_check
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'IDLE', 'WORKSHOP', 'SOLD', 'SCRAPPED', 'DELETED'));

ALTER TABLE vehicles
    ADD COLUMN deleted_at            TIMESTAMPTZ,
    ADD COLUMN deleted_by            BIGINT REFERENCES users (id),
    ADD COLUMN delete_reason         VARCHAR(500),
    ADD COLUMN status_before_delete  VARCHAR(20);
