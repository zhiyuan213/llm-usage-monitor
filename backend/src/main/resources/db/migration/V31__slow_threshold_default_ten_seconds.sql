ALTER TABLE projects ALTER COLUMN slow_call_threshold_ms SET DEFAULT 10000;
-- Preserve thresholds explicitly saved by a user.
UPDATE projects p SET slow_call_threshold_ms=10000
WHERE slow_call_threshold_ms=2000 AND NOT EXISTS (
  SELECT 1 FROM audit_logs a WHERE a.action='project.slow-threshold' AND a.target LIKE p.id::text || ':%'
);
