-- ---------------------------------------------------------------------------
-- Attendance roster (Excel-imported, admin-editable monthly grid)
-- One row per employee per month (team + month + employee code is unique).
-- `days` holds a JSON map of "yyyy-MM-dd" -> status code
--   (WO | WFO | WFH | PL | SL | CO | FL | HPEH | ATR[0-9]* | "") 
-- ---------------------------------------------------------------------------
CREATE TABLE attendance_rosters (
    id             BIGSERIAL PRIMARY KEY,
    team_id        BIGINT       NOT NULL,
    month          VARCHAR(7)   NOT NULL,
    employee_code  VARCHAR(30)  NOT NULL,
    email          VARCHAR(255),
    employee_name  VARCHAR(150) NOT NULL,
    location       VARCHAR(150),
    shift          VARCHAR(60),
    week_off       VARCHAR(60),
    days           TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_roster_team_month_employee UNIQUE (team_id, month, employee_code),
    CONSTRAINT fk_roster_team FOREIGN KEY (team_id) REFERENCES departments (id)
);

CREATE INDEX idx_roster_team_month ON attendance_rosters (team_id, month);
CREATE INDEX idx_roster_month      ON attendance_rosters (month);