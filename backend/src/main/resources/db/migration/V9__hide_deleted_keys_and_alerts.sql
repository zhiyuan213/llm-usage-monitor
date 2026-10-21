ALTER TABLE api_keys ADD COLUMN deleted_at timestamptz;
ALTER TABLE alert_rules ADD COLUMN deleted_at timestamptz;
