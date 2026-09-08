-- Ticket 表格結構調整
-- 新增欄位：closed_at, first_response_at, sla_deadline, version
-- 調整 status CHECK：移除 WAITING_ON_CUSTOMER，保留 RESOLVED
-- 調整 title 長度：255 → 200
-- 調整 description：NOT NULL → 可為 NULL

DROP TABLE IF EXISTS tickets CASCADE;

CREATE TABLE tickets (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    priority        VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM'
                    CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    category_id     UUID         NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    created_by      UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assigned_to     UUID         REFERENCES users(id) ON DELETE SET NULL,
    resolved_at     TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    first_response_at TIMESTAMPTZ,
    sla_deadline    TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE tickets IS '客戶支援工單';
COMMENT ON COLUMN tickets.version IS '樂觀鎖版本，併發更新衝突時拋出 OptimisticLockException';
COMMENT ON COLUMN tickets.first_response_at IS '首次回應時間（Agent 回覆後設定）';
COMMENT ON COLUMN tickets.closed_at IS '關閉時間';
COMMENT ON COLUMN tickets.sla_deadline IS 'SLA 截止時間';

CREATE INDEX idx_tickets_status ON tickets(status);
CREATE INDEX idx_tickets_priority ON tickets(priority);
CREATE INDEX idx_tickets_category_id ON tickets(category_id);
CREATE INDEX idx_tickets_assigned_to ON tickets(assigned_to);
CREATE INDEX idx_tickets_created_by ON tickets(created_by);
CREATE INDEX idx_tickets_sla_deadline ON tickets(sla_deadline) WHERE sla_deadline IS NOT NULL;
