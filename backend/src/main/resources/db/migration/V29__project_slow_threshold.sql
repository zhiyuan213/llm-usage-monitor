ALTER TABLE projects ADD COLUMN slow_call_threshold_ms integer NOT NULL DEFAULT 10000 CHECK (slow_call_threshold_ms BETWEEN 1000 AND 600000);
