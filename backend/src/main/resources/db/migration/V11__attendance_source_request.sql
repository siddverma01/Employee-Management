-- ---------------------------------------------------------------------------
-- Attendance source-request linkage: when an approved Leave or Swap Off
-- request writes (or matches) a roster attendance_record, the record points
-- back to the originating request so the roster popup can show the original
-- reason and the actual approver without duplicating that data.
--
--   source_request_id     -> leave_requests.id OR swap_off_requests.id
--   source_request_type   -> 'LEAVE' | 'SWAP_OFF'
--
-- No FK is declared because the id may reference one of two tables; validity
-- is enforced logically by pairing the id with the type. Manual descriptions
-- (V10 columns) are independent and stay untouched by approval writes.
-- ---------------------------------------------------------------------------

ALTER TABLE attendance_records
    ADD COLUMN source_request_id   BIGINT,
    ADD COLUMN source_request_type VARCHAR(20);