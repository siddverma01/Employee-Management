-- ---------------------------------------------------------------------------
-- Historical attendance import subsystem.
--
-- Fully separate from the live `employees` / `attendance` tables and from
-- the monthly `attendance_rosters` grid. This model is normalised:
--
--   attendance_status        canonical status codes (WO/WFO/WFH/PL/SL/CO/…)
--   import_employees         employee snapshot imported from workbooks
--                            (`employee_id` holds the employee code)
--   attendance_records       one row per employee + day, with traceability
--                            to the source sheet/row/file
--   attendance_import_history  one row per import run (preview / commit)
--   attendance_import_rows   staged preview rows shown to the admin before commit
--
-- Unknown / unstandard status codes are preserved in attendance_records
-- (status_code is a free VARCHAR; no FK to attendance_status) and flagged
-- with is_unknown so the admin can review and remap them later.
-- ---------------------------------------------------------------------------

CREATE TABLE attendance_status (
    code            VARCHAR(30) PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(255),
    display_color   VARCHAR(30)
);

INSERT INTO attendance_status (code, name, description, display_color) VALUES
    ('WO',    'Weekly Off',            'Regular weekly off',               'slate'),
    ('WFO',   'Work From Office',      'Present in office',                 'sky'),
    ('WFH',   'Work From Home',        'Working remotely',                  'violet'),
    ('PL',    'Privilege Leave',       'Planned / privilege leave',         'amber'),
    ('SL',    'Sick Leave',            'Medical leave',                     'orange'),
    ('CO',    'Compensatory Off',      'Comp off for extra hours worked',   'emerald'),
    ('HPEH',  'HPE Holiday',           'Company holiday',                   'pink'),
    ('FL',    'Furlough Leave',        'Furlough / involuntary leave',      'red'),
    ('SW OFF','Swap Off',              'Swap-off day',                      'indigo'),
    ('SW WK', 'Swap Working',          'Working on a swapped day',          'teal'),
    ('WK WRK','Weekend Working',       'Working on a weekend',              'cyan'),
    ('HD',    'Half Day',              'Half day present',                  'yellow'),
    ('WX',    'Wellness',              'Wellness / wellness off',           'lime'),
    ('TR',    'Training',              'On training',                       'purple'),
    ('ITS',   'IT Issues',             'Blocked by IT issues',              'rose'),
    ('WDT',   'Working for Different Team', 'On assignment to another team','fuchsia'),
    ('ATR',   'Attrition / Left Team', 'Left the team / organization',      'gray');

CREATE TABLE import_employees (
    employee_id     VARCHAR(40) PRIMARY KEY,          -- employee code
    employee_name   VARCHAR(200),
    email           VARCHAR(255),
    location        VARCHAR(150),
    manager         VARCHAR(150),
    default_shift   VARCHAR(60),
    week_off        VARCHAR(60),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE attendance_records (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     VARCHAR(40) NOT NULL REFERENCES import_employees (employee_id) ON DELETE CASCADE,
    attendance_date DATE NOT NULL,
    status_code     VARCHAR(30) NOT NULL,
    status_name     VARCHAR(120),
    shift           VARCHAR(60),
    location        VARCHAR(150),
    source_sheet    VARCHAR(200),
    source_row      INTEGER,
    source_file     VARCHAR(500),
    is_unknown      BOOLEAN NOT NULL DEFAULT FALSE,
    imported_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_attendance_records_employee_date UNIQUE (employee_id, attendance_date)
);

CREATE INDEX idx_attendance_records_date     ON attendance_records (attendance_date);
CREATE INDEX idx_attendance_records_employee ON attendance_records (employee_id);
CREATE INDEX idx_attendance_records_status   ON attendance_records (status_code);

CREATE TABLE attendance_import_history (
    id                 BIGSERIAL PRIMARY KEY,
    file_name          VARCHAR(500),
    original_file_name VARCHAR(500),
    imported_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    imported_by        VARCHAR(150),
    status             VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PREVIEWED','COMMITTED','FAILED')),
    total_sheets       INT NOT NULL DEFAULT 0,
    sheets_imported    INT NOT NULL DEFAULT 0,
    sheets_skipped     INT NOT NULL DEFAULT 0,
    employees_detected INT NOT NULL DEFAULT 0,
    records_detected   INT NOT NULL DEFAULT 0,
    inserted_records   INT NOT NULL DEFAULT 0,
    updated_records    INT NOT NULL DEFAULT 0,
    duplicate_records  INT NOT NULL DEFAULT 0,
    unknown_codes      INT NOT NULL DEFAULT 0,
    invalid_rows       INT NOT NULL DEFAULT 0,
    warnings           INT NOT NULL DEFAULT 0,
    errors             INT NOT NULL DEFAULT 0,
    error_summary      TEXT,
    summary_json       TEXT
);

CREATE INDEX idx_attendance_import_history_date ON attendance_import_history (imported_at);

CREATE TABLE attendance_import_rows (
    id               BIGSERIAL PRIMARY KEY,
    import_id        BIGINT NOT NULL REFERENCES attendance_import_history (id) ON DELETE CASCADE,
    sheet_name       VARCHAR(200),
    source_row       INTEGER,
    employee_id      VARCHAR(40),
    employee_name    VARCHAR(200),
    employee_email   VARCHAR(255),
    employee_location VARCHAR(150),
    employee_manager VARCHAR(150),
    employee_shift   VARCHAR(60),
    employee_week_off VARCHAR(60),
    attendance_date  DATE,
    existing_status  VARCHAR(30),
    incoming_status  VARCHAR(30),
    status_name      VARCHAR(120),
    action           VARCHAR(10) NOT NULL DEFAULT 'INSERT' CHECK (action IN ('INSERT','UPDATE','DUPLICATE','INVALID')),
    warning          VARCHAR(500),
    is_unknown       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_attendance_import_rows_import ON attendance_import_rows (import_id);