ALTER TABLE projects ALTER COLUMN slow_call_threshold_ms SET DEFAULT 2000;
ALTER TABLE projects DROP CONSTRAINT projects_slow_call_threshold_ms_check;
ALTER TABLE projects ADD CONSTRAINT projects_slow_call_threshold_ms_check CHECK (slow_call_threshold_ms BETWEEN 1 AND 600000);
-- Adopt the new default only for projects whose threshold has never been configured.
UPDATE projects p SET slow_call_threshold_ms=2000
WHERE slow_call_threshold_ms=10000 AND NOT EXISTS (
  SELECT 1 FROM audit_logs a WHERE a.action='project.slow-threshold' AND a.target LIKE p.id::text || ':%'
);
