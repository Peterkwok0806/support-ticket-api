-- V4__seed_categories.sql
-- 預設分類資料，包含不同 SLA 設定

INSERT INTO categories (id, name, description, sla_hours_low, sla_hours_medium, sla_hours_high, sla_hours_urgent, is_active, created_at, updated_at)
VALUES
  ('a0000000-0000-0000-0000-000000000001', '技術問題', '軟硬體技術相關問題', 72, 48, 24, 4, TRUE, NOW(), NOW()),

  ('a0000000-0000-0000-0000-000000000002', '帳務相關', '帳單、付款、發票等問題', 96, 72, 48, 8, TRUE, NOW(), NOW()),

  ('a0000000-0000-0000-0000-000000000003', '產品諮詢', '產品功能、使用方式諮詢', 120, 72, 24, 4, TRUE, NOW(), NOW()),

  ('a0000000-0000-0000-0000-000000000004', '功能建議', '新功能或改進建議', 168, 120, 72, 24, TRUE, NOW(), NOW()),

  ('a0000000-0000-0000-0000-000000000005', '其他', '無法分類的問題', 72, 48, 24, 4, TRUE, NOW(), NOW());
