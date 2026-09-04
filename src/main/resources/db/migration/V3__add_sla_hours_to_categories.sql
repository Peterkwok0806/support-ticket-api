-- V3__add_sla_hours_to_categories.sql
-- 為 categories 表格新增 SLA 時數欄位

ALTER TABLE categories
    ADD COLUMN IF NOT EXISTS sla_hours_low INTEGER NOT NULL DEFAULT 72
        CONSTRAINT chk_sla_hours_low CHECK (sla_hours_low BETWEEN 1 AND 720);

ALTER TABLE categories
    ADD COLUMN IF NOT EXISTS sla_hours_medium INTEGER NOT NULL DEFAULT 48
        CONSTRAINT chk_sla_hours_medium CHECK (sla_hours_medium BETWEEN 1 AND 720);

ALTER TABLE categories
    ADD COLUMN IF NOT EXISTS sla_hours_high INTEGER NOT NULL DEFAULT 24
        CONSTRAINT chk_sla_hours_high CHECK (sla_hours_high BETWEEN 1 AND 720);

ALTER TABLE categories
    ADD COLUMN IF NOT EXISTS sla_hours_urgent INTEGER NOT NULL DEFAULT 4
        CONSTRAINT chk_sla_hours_urgent CHECK (sla_hours_urgent BETWEEN 1 AND 720);

COMMENT ON TABLE categories IS '工單分類表，SLA 時數內嵌於各分類中';
COMMENT ON COLUMN categories.sla_hours_low IS 'Low Priority SLA 小時數（預設 72 小時 = 3 天）';
COMMENT ON COLUMN categories.sla_hours_medium IS 'Medium Priority SLA 小時數（預設 48 小時 = 2 天）';
COMMENT ON COLUMN categories.sla_hours_high IS 'High Priority SLA 小時數（預設 24 小時 = 1 天）';
COMMENT ON COLUMN categories.sla_hours_urgent IS 'Urgent Priority SLA 小時數（預設 4 小時）';
