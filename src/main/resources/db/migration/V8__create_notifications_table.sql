CREATE TABLE notifications (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ticket_id       UUID         NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    type            VARCHAR(30)  NOT NULL
                    CHECK (type IN (
                        'SLA_WARNING',
                        'SLA_BREACH',
                        'TICKET_ASSIGNED',
                        'TICKET_RESOLVED',
                        'TICKET_UPDATED'
                    )),
    title           VARCHAR(200) NOT NULL,
    message         TEXT         NOT NULL,
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 防止 SLA 通知重複發送
-- 業務語意：同一張 Ticket 的同一種 SLA 通知，只能發送一次
CREATE UNIQUE INDEX idx_sla_unique_notify
    ON notifications(ticket_id, type)
    WHERE type IN ('SLA_WARNING', 'SLA_BREACH');

-- 一般查詢索引
CREATE INDEX idx_notifications_recipient
    ON notifications(recipient_id, is_read, created_at DESC);

CREATE INDEX idx_notifications_ticket
    ON notifications(ticket_id);
