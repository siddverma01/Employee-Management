-- ---------------------------------------------------------------------------
-- Extend attendance_type CHECK to support roster status codes
-- (PL, SL, FL, ATR) alongside the existing type set.
-- ---------------------------------------------------------------------------

ALTER TABLE attendance DROP CONSTRAINT attendance_attendance_type_check;

ALTER TABLE attendance ADD CONSTRAINT attendance_attendance_type_check CHECK (
    attendance_type IN (
        'WORK_FROM_OFFICE', 'WORK_FROM_HOME', 'LEAVE',
        'PRIVILEGE_LEAVE', 'SICK_LEAVE', 'COMP_OFF',
        'FURLOUGH', 'HOLIDAY', 'WEEK_OFF', 'ATTRITION'
    )
);