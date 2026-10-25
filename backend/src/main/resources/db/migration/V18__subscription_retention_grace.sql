ALTER TABLE workspaces ADD COLUMN retention_grace_until timestamptz;
-- Existing inactive subscriptions get a full transition window before deletion is possible.
UPDATE workspaces SET retention_grace_until=now()+interval '30 days'
 WHERE plan IN ('Core','Pro') AND (subscription_status IS DISTINCT FROM 'active' OR period_end<=now());
CREATE INDEX workspace_retention_grace ON workspaces(retention_grace_until) WHERE plan IN ('Core','Pro');
-- Freeze the paid cutoff throughout the renewal window. Free cleanup resumes after downgrade.
CREATE FUNCTION workspace_retention_cutoff(w workspaces) RETURNS timestamptz LANGUAGE sql STABLE AS $$
 SELECT (CASE WHEN w.plan IN ('Core','Pro') AND w.retention_grace_until IS NOT NULL
              THEN least(now(),w.retention_grace_until-interval '30 days') ELSE now() END)
        -make_interval(days=>CASE w.plan WHEN 'Pro' THEN 1096 WHEN 'Core' THEN 90 ELSE 30 END)
$$;
