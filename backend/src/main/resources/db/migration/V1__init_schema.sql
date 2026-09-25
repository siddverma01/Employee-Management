-- ============================================================================
-- Employee Management Application : Initial schema
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Departments
-- ---------------------------------------------------------------------------
CREATE TABLE departments (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- Companies / workspaces (future extensibility: multi-office/country)
-- ---------------------------------------------------------------------------
CREATE TABLE companies (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    country     VARCHAR(80),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- Users (authentication boundary - kept separate from employee business data)
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(30)  NOT NULL CHECK (role IN ('EMPLOYEE', 'ADMIN')),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- Employees
-- ---------------------------------------------------------------------------
CREATE TABLE employees (
    id                 BIGSERIAL PRIMARY KEY,
    employee_id        VARCHAR(30)  NOT NULL UNIQUE,
    user_id            BIGINT UNIQUE,
    department_id      BIGINT,
    manager_id         BIGINT,
    full_name          VARCHAR(150) NOT NULL,
    email              VARCHAR(255) NOT NULL UNIQUE,
    phone              VARCHAR(30),
    designation        VARCHAR(120),
    location           VARCHAR(150),
    date_of_joining    DATE,
    date_of_birth      DATE,
    employment_status  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (employment_status IN ('ACTIVE', 'INACTIVE')),
    profile_picture    VARCHAR(500),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_employee_user       FOREIGN KEY (user_id)        REFERENCES users (id),
    CONSTRAINT fk_employee_department FOREIGN KEY (department_id)   REFERENCES departments (id),
    CONSTRAINT fk_employee_manager    FOREIGN KEY (manager_id)      REFERENCES employees (id)
);

CREATE INDEX idx_employees_department   ON employees (department_id);
CREATE INDEX idx_employees_status       ON employees (employment_status);
CREATE INDEX idx_employees_date_joining ON employees (date_of_joining);

-- ---------------------------------------------------------------------------
-- Attendance (source-of-truth for WFH/WFO/leave/comp-off/holiday/week-off)
-- ---------------------------------------------------------------------------
CREATE TABLE attendance (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT      NOT NULL,
    attendance_date DATE        NOT NULL,
    attendance_type VARCHAR(30) NOT NULL CHECK (
        attendance_type IN ('WORK_FROM_OFFICE', 'WORK_FROM_HOME', 'LEAVE', 'COMP_OFF', 'HOLIDAY', 'WEEK_OFF')
    ),
    source          VARCHAR(20) NOT NULL DEFAULT 'SYSTEM' CHECK (source IN ('SYSTEM', 'MANUAL', 'EXCEL', 'IMPORT')),
    remarks         VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_attendance_employee_date UNIQUE (employee_id, attendance_date),
    CONSTRAINT fk_attendance_employee FOREIGN KEY (employee_id) REFERENCES employees (id)
);

CREATE INDEX idx_attendance_date          ON attendance (attendance_date);
CREATE INDEX idx_attendance_employee_date ON attendance (employee_id, attendance_date);

-- ---------------------------------------------------------------------------
-- Leave requests
-- ---------------------------------------------------------------------------
CREATE TABLE leave_requests (
    id               BIGSERIAL PRIMARY KEY,
    employee_id      BIGINT      NOT NULL,
    leave_type       VARCHAR(30) NOT NULL CHECK (leave_type IN ('PRIVILEGE_LEAVE', 'SICK_LEAVE', 'COMP_OFF')),
    start_date       DATE        NOT NULL,
    end_date         DATE        NOT NULL,
    days             NUMERIC(5,1) NOT NULL,
    reason           VARCHAR(1000),
    attachment       VARCHAR(500),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    rejection_reason VARCHAR(500),
    decided_by       BIGINT,
    decided_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_leave_employee    FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_leave_decided_by  FOREIGN KEY (decided_by)  REFERENCES users (id),
    CONSTRAINT chk_leave_dates      CHECK (end_date >= start_date)
);

CREATE INDEX idx_leave_employee_status ON leave_requests (employee_id, status);
CREATE INDEX idx_leave_status          ON leave_requests (status);
CREATE INDEX idx_leave_dates           ON leave_requests (start_date, end_date);
CREATE INDEX idx_leave_type            ON leave_requests (leave_type);

-- ---------------------------------------------------------------------------
-- Leave balances (allocated amounts; usage is derived from approved leaves)
-- ---------------------------------------------------------------------------
CREATE TABLE leave_balances (
    id          BIGSERIAL PRIMARY KEY,
    employee_id BIGINT       NOT NULL,
    leave_type  VARCHAR(30)  NOT NULL CHECK (leave_type IN ('PRIVILEGE_LEAVE', 'SICK_LEAVE', 'COMP_OFF')),
    year        INT          NOT NULL,
    allocated   NUMERIC(5,1) NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_leave_balance UNIQUE (employee_id, leave_type, year),
    CONSTRAINT fk_leave_balance_employee FOREIGN KEY (employee_id) REFERENCES employees (id)
);

CREATE INDEX idx_leave_balance_employee_year ON leave_balances (employee_id, year);

-- ---------------------------------------------------------------------------
-- Swap Off / Compensatory Off requests
-- ---------------------------------------------------------------------------
CREATE TABLE swap_off_requests (
    id                   BIGSERIAL PRIMARY KEY,
    employee_id          BIGINT      NOT NULL,
    worked_date          DATE        NOT NULL,
    requested_off_date   DATE        NOT NULL,
    reason               VARCHAR(1000),
    attachment           VARCHAR(500),
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    rejection_reason     VARCHAR(500),
    decided_by           BIGINT,
    decided_at           TIMESTAMPTZ,
    comp_off_credited    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_swap_employee    FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_swap_decided_by  FOREIGN KEY (decided_by)  REFERENCES users (id),
    CONSTRAINT chk_swap_dates_unique CHECK (worked_date <> requested_off_date)
);

CREATE INDEX idx_swap_employee_status ON swap_off_requests (employee_id, status);
CREATE INDEX idx_swap_status          ON swap_off_requests (status);

-- ---------------------------------------------------------------------------
-- Holidays
-- ---------------------------------------------------------------------------
CREATE TABLE holidays (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    holiday_date DATE       NOT NULL,
    country     VARCHAR(80) DEFAULT 'US',
    holiday_type VARCHAR(30) DEFAULT 'PUBLIC' CHECK (holiday_type IN ('PUBLIC', 'OPTIONAL', 'OBSERVED')),
    description VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_holiday_country_date UNIQUE (holiday_date, country, name)
);

CREATE INDEX idx_holiday_date ON holidays (holiday_date);

-- ---------------------------------------------------------------------------
-- Important company events
-- ---------------------------------------------------------------------------
CREATE TABLE events (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    event_date  DATE NOT NULL,
    event_type  VARCHAR(30) NOT NULL DEFAULT 'COMPANY_EVENT' CHECK (event_type IN ('COMPANY_EVENT', 'CONFERENCE', 'TEAM_MEETING', 'CUSTOM')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_event_date ON events (event_date);

-- ---------------------------------------------------------------------------
-- Notifications (one row per recipient = fan-out design, email/push later)
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT,
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(1000),
    type       VARCHAR(30) NOT NULL DEFAULT 'GENERAL' CHECK (type IN ('LEAVE', 'SWAP_OFF', 'HOLIDAY', 'BIRTHDAY', 'EVENT', 'SYSTEM', 'GENERAL')),
    link       VARCHAR(500),
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_notification_user_read ON notifications (user_id, read_at);

-- ---------------------------------------------------------------------------
-- Audit log
-- ---------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    action      VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id   VARCHAR(100),
    old_value   TEXT,
    new_value   TEXT,
    ip_address  VARCHAR(60),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_created ON audit_logs (created_at);

-- ---------------------------------------------------------------------------
-- Excel imports
-- ---------------------------------------------------------------------------
CREATE TABLE excel_imports (
    id                   BIGSERIAL PRIMARY KEY,
    file_name            VARCHAR(500) NOT NULL,
    original_file_name   VARCHAR(500) NOT NULL,
    uploaded_by          BIGINT,
    uploaded_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status               VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED', 'READY', 'IMPORTED', 'FAILED')),
    total_rows           INT NOT NULL DEFAULT 0,
    valid_rows           INT NOT NULL DEFAULT 0,
    invalid_rows         INT NOT NULL DEFAULT 0,
    duplicate_rows       INT NOT NULL DEFAULT 0,
    imported_rows        INT NOT NULL DEFAULT 0,
    mapping_json         TEXT,
    error_summary        TEXT,
    committed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_import_uploader FOREIGN KEY (uploaded_by) REFERENCES users (id)
);

CREATE INDEX idx_import_uploader ON excel_imports (uploaded_by);

-- ---------------------------------------------------------------------------
-- Excel import rows (parsed + validated preview before commit)
-- ---------------------------------------------------------------------------
CREATE TABLE excel_import_rows (
    id                 BIGSERIAL PRIMARY KEY,
    excel_import_id    BIGINT NOT NULL,
    row_number         INT NOT NULL,
    raw_data           TEXT,
    mapped_data        TEXT,
    validation_errors  TEXT,
    row_status         VARCHAR(20) NOT NULL DEFAULT 'VALID' CHECK (row_status IN ('VALID', 'INVALID', 'DUPLICATE', 'IMPORTED')),
    employee_id        BIGINT,
    attendance_date    DATE,
    attendance_type    VARCHAR(30),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_import_row_import   FOREIGN KEY (excel_import_id) REFERENCES excel_imports (id) ON DELETE CASCADE,
    CONSTRAINT fk_import_row_employee FOREIGN KEY (employee_id)     REFERENCES employees (id)
);

CREATE INDEX idx_import_row_import ON excel_import_rows (excel_import_id);