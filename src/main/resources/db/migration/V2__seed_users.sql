-- V2__seed_users.sql
-- Initial demo accounts for testing

INSERT INTO users (id, email, password_hash, display_name, role, status, created_at, updated_at)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'admin@example.com',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'System Admin', 'ADMIN', 'ACTIVE', NOW(), NOW()),

  ('22222222-2222-2222-2222-222222222222', 'agent@example.com',
   '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
   'Support Agent', 'AGENT', 'ACTIVE', NOW(), NOW()),

  ('33333333-3333-3333-3333-333333333333', 'customer@example.com',
   '$2a$10$EqKcp1WFKVQISheBxkQYou.//N8HJxZQ9xO4HJyO3xq8x8Z8Z8Z8Z',
   'Happy Customer', 'CUSTOMER', 'ACTIVE', NOW(), NOW());
