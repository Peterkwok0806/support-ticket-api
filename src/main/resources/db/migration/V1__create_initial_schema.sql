CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'AGENT'
                    CHECK (role IN ('ADMIN', 'AGENT', 'CUSTOMER')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN users.password_hash IS '已雜湊的密碼，嚴禁存放明文';

CREATE TABLE categories (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE tickets (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title         VARCHAR(255) NOT NULL,
    description   TEXT         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
                  CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_ON_CUSTOMER', 'RESOLVED', 'CLOSED')),
    priority      VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM'
                  CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    category_id   UUID         REFERENCES categories(id) ON DELETE RESTRICT,
    created_by    UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assigned_to   UUID         REFERENCES users(id) ON DELETE SET NULL,
    resolved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tickets_status        ON tickets(status);
CREATE INDEX idx_tickets_assigned_to   ON tickets(assigned_to);
CREATE INDEX idx_tickets_created_at     ON tickets(created_at DESC);

CREATE TABLE ticket_comments (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id   UUID         NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    author_id   UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content     TEXT         NOT NULL,
    is_internal BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type    VARCHAR(100) NOT NULL
                   CHECK (entity_type IN ('TICKET', 'COMMENT', 'USER', 'CATEGORY')),
    entity_id      UUID         NOT NULL,
    operation      VARCHAR(20)  NOT NULL
                   CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE')),
    actor_user_id  UUID         REFERENCES users(id) ON DELETE SET NULL,
    old_values     JSONB,
    new_values     JSONB,
    changed_fields JSONB,
    request_id     VARCHAR(100),
    ip_address     INET,
    user_agent     TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_entity        ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor_created ON audit_logs(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_logs_created_at    ON audit_logs(created_at DESC);