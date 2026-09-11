-- V7: 刪除舊表 + 建立新表
-- 由於 V1__create_initial_schema.sql 中已存在通用型 audit_logs 表格，
-- 與本計劃的專用型設計不相容，因此需先刪除舊表再建立新表

-- 刪除舊的通用型 audit_logs 表格
DROP TABLE IF EXISTS audit_logs CASCADE;

-- 建立專用型 audit_logs 表格
-- 用於記錄 Ticket 相關的所有重要操作，支援可追溯性與可稽核性
CREATE TABLE audit_logs (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id        UUID            NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    ticket_id       UUID            NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    action          VARCHAR(30)     NOT NULL,
    field_name      VARCHAR(30),
    old_value       VARCHAR(500),
    new_value       VARCHAR(500),
    internal        BOOLEAN,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- 註解
COMMENT ON TABLE audit_logs IS '操作稽核日誌，記錄所有重要操作';
COMMENT ON COLUMN audit_logs.actor_id IS '執行操作的使用者 ID';
COMMENT ON COLUMN audit_logs.ticket_id IS '操作發生的 Ticket ID';
COMMENT ON COLUMN audit_logs.action IS '操作類型（TICKET_CREATED/STATUS_CHANGED/PRIORITY_CHANGED/ASSIGNED/UNASSIGNED/COMMENT_ADDED/RESOLVED/CLOSED）';
COMMENT ON COLUMN audit_logs.field_name IS '變更的欄位名稱（STATUS/PRIORITY/ASSIGNEE），非欄位變更時可為空';
COMMENT ON COLUMN audit_logs.old_value IS '變更前的值';
COMMENT ON COLUMN audit_logs.new_value IS '變更後的值';
COMMENT ON COLUMN audit_logs.internal IS '是否為內部操作，用於 COMMENT_ADDED 事件';
COMMENT ON COLUMN audit_logs.created_at IS '操作發生的時間';

-- 索引設計
CREATE INDEX idx_audit_logs_ticket_id ON audit_logs(ticket_id);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_ticket_created ON audit_logs(ticket_id, created_at DESC);
