-- Team-based visibility for holidays & events.
-- scope: GLOBAL (visible to every team) or TEAM (visible only to the assigned team).

ALTER TABLE holidays
    ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'GLOBAL' CHECK (scope IN ('GLOBAL', 'TEAM')),
    ADD COLUMN team_id BIGINT;

ALTER TABLE events
    ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'GLOBAL' CHECK (scope IN ('GLOBAL', 'TEAM')),
    ADD COLUMN team_id BIGINT;

ALTER TABLE holidays
    ADD CONSTRAINT fk_holiday_team FOREIGN KEY (team_id) REFERENCES departments (id);

ALTER TABLE events
    ADD CONSTRAINT fk_event_team FOREIGN KEY (team_id) REFERENCES departments (id);

CREATE INDEX idx_holidays_team ON holidays (team_id);
CREATE INDEX idx_events_team ON events (team_id);