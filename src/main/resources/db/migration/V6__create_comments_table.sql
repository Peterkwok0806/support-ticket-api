-- Comment 留言功能
-- 用於 Customer、Agent、Admin 在同一張 Ticket 上溝通
-- 透過 internal 欄位區分「客戶看得到的回覆」與「內部討論」

CREATE TABLE comments (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id       UUID            NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    author_id       UUID            NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content         TEXT            NOT NULL,
    internal        BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE comments IS '工單留言（包含公開回覆與內部討論）';
COMMENT ON COLUMN comments.internal IS '是否為內部留言；true=內部討論（僅 Agent/Admin 可見），false=公開回覆（所有人可見）';

-- 索引設計：用於依 Ticket 查詢 Comment 列表
CREATE INDEX idx_comments_ticket_id ON comments(ticket_id);
CREATE INDEX idx_comments_ticket_internal ON comments(ticket_id, internal);
