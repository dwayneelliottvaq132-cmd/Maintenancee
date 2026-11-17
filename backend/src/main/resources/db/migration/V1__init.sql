-- CMMS core schema

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(60)  NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    email           VARCHAR(160),
    role            VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN','SUPERVISOR','TECHNICIAN','REQUESTER')),
    hourly_rate     NUMERIC(10,2) NOT NULL DEFAULT 0,
    trade           VARCHAR(60),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE locations (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    parent_id   BIGINT REFERENCES locations(id) ON DELETE SET NULL,
    description TEXT
);

CREATE TABLE assets (
    id              BIGSERIAL PRIMARY KEY,
    tag             VARCHAR(40) NOT NULL UNIQUE,
    name            VARCHAR(160) NOT NULL,
    category        VARCHAR(80),
    location_id     BIGINT REFERENCES locations(id) ON DELETE SET NULL,
    parent_asset_id BIGINT REFERENCES assets(id) ON DELETE SET NULL,
    manufacturer    VARCHAR(120),
    model           VARCHAR(120),
    serial_number   VARCHAR(120),
    install_date    DATE,
    purchase_cost   NUMERIC(12,2),
    warranty_until  DATE,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DOWN','RETIRED')),
    criticality     VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (criticality IN ('LOW','MEDIUM','HIGH')),
    meter_reading   NUMERIC(14,2) NOT NULL DEFAULT 0,
    meter_unit      VARCHAR(20),
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset_documents (
    id           BIGSERIAL PRIMARY KEY,
    asset_id     BIGINT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    file_name    VARCHAR(255) NOT NULL,
    stored_path  VARCHAR(500) NOT NULL,
    content_type VARCHAR(120),
    size_bytes   BIGINT,
    uploaded_by  BIGINT REFERENCES users(id),
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE parts (
    id            BIGSERIAL PRIMARY KEY,
    part_no       VARCHAR(60) NOT NULL UNIQUE,
    name          VARCHAR(160) NOT NULL,
    unit          VARCHAR(20) NOT NULL DEFAULT 'ea',
    unit_cost     NUMERIC(12,2) NOT NULL DEFAULT 0,
    qty_on_hand   NUMERIC(12,2) NOT NULL DEFAULT 0,
    reorder_point NUMERIC(12,2) NOT NULL DEFAULT 0,
    bin_location  VARCHAR(60),
    supplier      VARCHAR(160),
    active        BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE stock_transactions (
    id          BIGSERIAL PRIMARY KEY,
    part_id     BIGINT NOT NULL REFERENCES parts(id) ON DELETE CASCADE,
    type        VARCHAR(10) NOT NULL CHECK (type IN ('RECEIVE','ISSUE','ADJUST','RETURN')),
    qty         NUMERIC(12,2) NOT NULL,
    unit_cost   NUMERIC(12,2),
    reference   VARCHAR(160),
    user_id     BIGINT REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE work_instructions (
    id                BIGSERIAL PRIMARY KEY,
    title             VARCHAR(200) NOT NULL,
    version           INT NOT NULL DEFAULT 1,
    description       TEXT,
    safety_notes      TEXT,
    required_ppe      VARCHAR(300),
    tools_required    VARCHAR(300),
    estimated_minutes INT,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE instruction_steps (
    id              BIGSERIAL PRIMARY KEY,
    instruction_id  BIGINT NOT NULL REFERENCES work_instructions(id) ON DELETE CASCADE,
    seq             INT NOT NULL,
    text            TEXT NOT NULL,
    requires_check  BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE instruction_parts (
    id              BIGSERIAL PRIMARY KEY,
    instruction_id  BIGINT NOT NULL REFERENCES work_instructions(id) ON DELETE CASCADE,
    part_id         BIGINT NOT NULL REFERENCES parts(id),
    qty             NUMERIC(12,2) NOT NULL
);

CREATE TABLE pm_schedules (
    id                  BIGSERIAL PRIMARY KEY,
    title               VARCHAR(200) NOT NULL,
    asset_id            BIGINT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    instruction_id      BIGINT REFERENCES work_instructions(id) ON DELETE SET NULL,
    frequency_type      VARCHAR(10) NOT NULL CHECK (frequency_type IN ('DAYS','WEEKS','MONTHS','METER')),
    interval_value      INT NOT NULL CHECK (interval_value > 0),
    next_due            DATE,
    next_meter          NUMERIC(14,2),
    lead_days           INT NOT NULL DEFAULT 0,
    priority            VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',
    default_assignee_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    last_generated_at   TIMESTAMPTZ,
    last_completed_at   TIMESTAMPTZ
);

CREATE TABLE maintenance_requests (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(200) NOT NULL,
    description      TEXT,
    requester_id     BIGINT NOT NULL REFERENCES users(id),
    asset_id         BIGINT REFERENCES assets(id) ON DELETE SET NULL,
    location_id      BIGINT REFERENCES locations(id) ON DELETE SET NULL,
    priority         VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH','URGENT')),
    status           VARCHAR(12) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','APPROVED','REJECTED')),
    reviewed_by      BIGINT REFERENCES users(id),
    reviewed_at      TIMESTAMPTZ,
    review_notes     TEXT,
    work_order_id    BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE SEQUENCE work_order_number_seq START 1000;

CREATE TABLE work_orders (
    id               BIGSERIAL PRIMARY KEY,
    number           VARCHAR(20) NOT NULL UNIQUE,
    title            VARCHAR(200) NOT NULL,
    description      TEXT,
    type             VARCHAR(12) NOT NULL CHECK (type IN ('PM','CORRECTIVE','REQUEST')),
    priority         VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH','URGENT')),
    status           VARCHAR(12) NOT NULL DEFAULT 'OPEN'
                     CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','COMPLETED','SIGNED_OFF','CANCELLED')),
    asset_id         BIGINT REFERENCES assets(id) ON DELETE SET NULL,
    location_id      BIGINT REFERENCES locations(id) ON DELETE SET NULL,
    pm_schedule_id   BIGINT REFERENCES pm_schedules(id) ON DELETE SET NULL,
    request_id       BIGINT REFERENCES maintenance_requests(id) ON DELETE SET NULL,
    instruction_id   BIGINT REFERENCES work_instructions(id) ON DELETE SET NULL,
    due_date         DATE,
    created_by       BIGINT REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    completed_by     BIGINT REFERENCES users(id),
    completion_notes TEXT,
    meter_at_completion NUMERIC(14,2),
    signed_off_by    BIGINT REFERENCES users(id),
    signed_off_at    TIMESTAMPTZ,
    signature_name   VARCHAR(120),
    sign_off_notes   TEXT
);
ALTER TABLE maintenance_requests ADD CONSTRAINT fk_request_wo FOREIGN KEY (work_order_id) REFERENCES work_orders(id) ON DELETE SET NULL;
CREATE INDEX idx_wo_status ON work_orders(status);
CREATE INDEX idx_wo_asset ON work_orders(asset_id);
CREATE UNIQUE INDEX uq_open_pm_wo ON work_orders(pm_schedule_id)
    WHERE pm_schedule_id IS NOT NULL AND status NOT IN ('SIGNED_OFF','CANCELLED');

CREATE TABLE wo_assignments (
    work_order_id BIGINT NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (work_order_id, user_id)
);

CREATE TABLE wo_step_results (
    id            BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    step_id       BIGINT NOT NULL REFERENCES instruction_steps(id) ON DELETE CASCADE,
    done          BOOLEAN NOT NULL DEFAULT TRUE,
    note          TEXT,
    done_by       BIGINT REFERENCES users(id),
    done_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (work_order_id, step_id)
);

CREATE TABLE labor_entries (
    id            BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    work_date     DATE NOT NULL DEFAULT CURRENT_DATE,
    hours         NUMERIC(8,2) NOT NULL CHECK (hours > 0),
    hourly_rate   NUMERIC(10,2) NOT NULL,
    note          VARCHAR(300)
);

CREATE TABLE part_usages (
    id            BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    part_id       BIGINT NOT NULL REFERENCES parts(id),
    qty           NUMERIC(12,2) NOT NULL CHECK (qty > 0),
    unit_cost     NUMERIC(12,2) NOT NULL,
    used_by       BIGINT REFERENCES users(id),
    used_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE other_costs (
    id            BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    description   VARCHAR(300) NOT NULL,
    vendor        VARCHAR(160),
    amount        NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    cost_date     DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    entity     VARCHAR(40) NOT NULL,
    entity_id  BIGINT,
    action     VARCHAR(40) NOT NULL,
    user_id    BIGINT,
    details    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notifications_sent (
    id         BIGSERIAL PRIMARY KEY,
    kind       VARCHAR(40) NOT NULL,
    ref_id     BIGINT NOT NULL,
    sent_on    DATE NOT NULL DEFAULT CURRENT_DATE,
    UNIQUE (kind, ref_id, sent_on)
);

-- Unified cost view used by API reports and the Python worker
CREATE VIEW v_cost_lines AS
SELECT wo.id AS work_order_id, wo.asset_id, 'LABOR'::text AS cost_type, l.user_id,
       l.work_date AS cost_date, (l.hours * l.hourly_rate) AS amount, l.hours AS hours
  FROM labor_entries l JOIN work_orders wo ON wo.id = l.work_order_id
UNION ALL
SELECT wo.id, wo.asset_id, 'PARTS', p.used_by, p.used_at::date, (p.qty * p.unit_cost), 0
  FROM part_usages p JOIN work_orders wo ON wo.id = p.work_order_id
UNION ALL
SELECT wo.id, wo.asset_id, 'OTHER', NULL, o.cost_date, o.amount, 0
  FROM other_costs o JOIN work_orders wo ON wo.id = o.work_order_id;
